package com.tomcatmanager.infrastructure;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * A Tomcat Manager text-interface URL such as {@code https://tomcat01.example.com:8443/manager/text},
 * entered as one field and stored as protocol/host/port/path columns.
 */
public record ManagerUrl(TomcatInstance.ManagerProtocol protocol, String host, int port, String path) {
    public static final String DEFAULT_PATH = "/manager/text";

    /** Parses user input; a missing port defaults to 80/443 and a missing path to {@value #DEFAULT_PATH}. */
    public static ManagerUrl parse(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Enter the Manager URL, e.g. https://tomcat01.example.com:8443/manager/text.");
        URI uri;
        try { uri = new URI(input.trim()); } catch (URISyntaxException e) { throw new IllegalArgumentException("The Manager URL is not a valid URL."); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new IllegalArgumentException("The Manager URL must start with https:// or http://.");
        if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("Do not put credentials in the URL; use the username and password fields.");
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException("The Manager URL must not contain ? or # parts.");
        String host = uri.getHost();
        if (host == null || !InfrastructureValidation.isValidHostname(host.replace("[", "").replace("]", "")))
            throw new IllegalArgumentException("The Manager URL must contain a valid host name or IP address.");
        TomcatInstance.ManagerProtocol protocol = scheme.equals("https") ? TomcatInstance.ManagerProtocol.HTTPS : TomcatInstance.ManagerProtocol.HTTP;
        int port = uri.getPort() == -1 ? (protocol == TomcatInstance.ManagerProtocol.HTTPS ? 443 : 80) : uri.getPort();
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() || uri.getRawPath().equals("/") ? DEFAULT_PATH : uri.getRawPath();
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (!InfrastructureValidation.isValidUrlPath(path)) throw new IllegalArgumentException("The Manager URL path may contain only letters, digits and . _ ~ - /.");
        return new ManagerUrl(protocol, host.replace("[", "").replace("]", ""), port, path);
    }

    /** The stored URL of an instance; the Linux host's address is used when no Manager host is set. */
    public static ManagerUrl of(TomcatInstance i) {
        String host = i.getManagerHost() == null || i.getManagerHost().isBlank() ? i.getHost().getHostname() : i.getManagerHost();
        String path = i.getManagerEndpoint() == null || i.getManagerEndpoint().isBlank() ? DEFAULT_PATH : i.getManagerEndpoint();
        return new ManagerUrl(i.getManagerProtocol(), host, i.getManagerPort(), path);
    }

    @Override public String toString() {
        String h = host.contains(":") ? "[" + host + "]" : host;
        return protocol.name().toLowerCase(Locale.ROOT) + "://" + h + ":" + port + path;
    }
}
