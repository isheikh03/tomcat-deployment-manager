package com.tomcatmanager.users;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Application roles. An administrator also holds the operator authority, so every operator
 * permission applies to administrators too.
 */
public enum Role {
    ADMIN("Administrator", "Manages users, infrastructure, settings and releases; deploys, rolls back and reads the audit log."),
    DEPLOYMENT_OPERATOR("Deployment operator", "Runs connection tests, uploads releases (if permitted), deploys, rolls back and checks health."),
    VIEWER("Viewer", "Reads dashboards, infrastructure, releases, health and deployment results. Cannot change anything.");

    private final String label;
    private final String description;

    Role(String label, String description) { this.label = label; this.description = description; }

    public String label() { return label; }
    public String description() { return description; }

    public List<GrantedAuthority> authorities() {
        return switch (this) {
            case ADMIN -> List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_DEPLOYMENT_OPERATOR"));
            case DEPLOYMENT_OPERATOR -> List.of(new SimpleGrantedAuthority("ROLE_DEPLOYMENT_OPERATOR"));
            case VIEWER -> List.of(new SimpleGrantedAuthority("ROLE_VIEWER"));
        };
    }
}
