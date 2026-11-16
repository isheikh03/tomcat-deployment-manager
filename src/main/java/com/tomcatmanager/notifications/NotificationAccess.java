package com.tomcatmanager.notifications;

import com.tomcatmanager.access.AccessScope;
import com.tomcatmanager.deployment.DeploymentRunRepository;
import com.tomcatmanager.deployment.DeploymentService;
import com.tomcatmanager.infrastructure.ManagedHostRepository;
import org.springframework.stereotype.Component;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which notifications a user sees. Every notification links to what it is about (a deployment, a Tomcat instance's health
 * or configuration, a Linux host); a user sees it when they can see that. Notifications without such a link are for
 * super users only.
 */
@Component
public class NotificationAccess {
    private static final Pattern LINK = Pattern.compile("^/(deployments|health/instances|infrastructure/instances|infrastructure/hosts)/(\\d+)$");

    private final DeploymentRunRepository runs;
    private final DeploymentService deployments;
    private final ManagedHostRepository hosts;

    public NotificationAccess(DeploymentRunRepository runs, DeploymentService deployments, ManagedHostRepository hosts) {
        this.runs = runs; this.deployments = deployments; this.hosts = hosts;
    }

    public Predicate<Notification> visibleTo(AccessScope s) {
        if (s.isEverything()) return n -> true;
        return n -> visible(n.getLink(), s);
    }

    boolean visible(String link, AccessScope s) {
        if (link == null) return false;
        Matcher m = LINK.matcher(link);
        if (!m.matches()) return false;
        long id = Long.parseLong(m.group(2));
        return switch (m.group(1)) {
            case "deployments" -> runs.findById(id).map(r -> deployments.visible(r, s)).orElse(false);
            case "health/instances", "infrastructure/instances" -> s.canViewInstance(id);
            case "infrastructure/hosts" -> hosts.findById(id).map(h -> s.canViewHost(h.getId(), h.getTeamId())).orElse(false);
            default -> false;
        };
    }
}
