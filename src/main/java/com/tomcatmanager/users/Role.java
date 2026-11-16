package com.tomcatmanager.users;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Application roles. Each role also holds the authorities of the roles below it, so a super user can do everything
 * an administrator can, and an administrator everything an operator can.
 *
 * <p>Where a role applies: a super user everywhere; an administrator in the teams assigned to them; operators and
 * viewers on the Tomcat instances granted to them (see {@code com.tomcatmanager.access}).
 */
public enum Role {
    SUPER_ADMIN("Super user", "Manages teams, administrators, settings and the whole audit log, and has every permission in every team."),
    ADMIN("Administrator", "Manages the Linux hosts, Tomcat instances, applications, releases and users of the teams assigned to them; deploys and rolls back."),
    DEPLOYMENT_OPERATOR("Deployment operator", "Runs connection tests, uploads releases (if permitted), deploys, rolls back and checks health on the instances granted to them."),
    VIEWER("Viewer", "Reads dashboards, infrastructure, releases, health and deployment results of the instances granted to them. Cannot change anything.");

    private final String label;
    private final String description;

    Role(String label, String description) { this.label = label; this.description = description; }

    public String label() { return label; }
    public String description() { return description; }

    public List<GrantedAuthority> authorities() {
        return switch (this) {
            case SUPER_ADMIN -> List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"), new SimpleGrantedAuthority("ROLE_ADMIN"),
                    new SimpleGrantedAuthority("ROLE_DEPLOYMENT_OPERATOR"));
            case ADMIN -> List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_DEPLOYMENT_OPERATOR"));
            case DEPLOYMENT_OPERATOR -> List.of(new SimpleGrantedAuthority("ROLE_DEPLOYMENT_OPERATOR"));
            case VIEWER -> List.of(new SimpleGrantedAuthority("ROLE_VIEWER"));
        };
    }

    /** Whether this role administers (super users and administrators). */
    public boolean administers() { return this == SUPER_ADMIN || this == ADMIN; }
}
