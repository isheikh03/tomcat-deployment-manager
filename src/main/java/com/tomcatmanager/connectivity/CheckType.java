package com.tomcatmanager.connectivity;

/** The individual validations a connection test reports on, in execution order. */
public enum CheckType {
    NETWORK_TARGET("Network target"),
    SSH_CONNECTION("SSH connection"),
    SSH_HOST_KEY("SSH host-key verification"),
    SSH_AUTHENTICATION("SSH authentication"),
    SFTP_ACCESS("SFTP session"),
    DEPLOYMENT_DIRECTORY("Deployment directory"),
    TOMCAT_RESTART("Tomcat restart"),
    MANAGER_ENDPOINT("Tomcat Manager endpoint"),
    MANAGER_AUTHENTICATION("Tomcat Manager authentication"),
    MANAGER_PERMISSIONS("Tomcat Manager permissions");

    private final String label;
    CheckType(String label) { this.label = label; }
    public String label() { return label; }
}
