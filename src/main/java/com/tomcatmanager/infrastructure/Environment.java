package com.tomcatmanager.infrastructure;

/** Deployment environment of a Tomcat instance. Production has stricter deployment rules. */
public enum Environment {
    DEVELOPMENT("Development"), TESTING("Testing"), STAGING("Staging"), PRODUCTION("Production");

    private final String label;
    Environment(String label) { this.label = label; }
    public String label() { return label; }
    public boolean isProduction() { return this == PRODUCTION; }
}
