package com.tomcatmanager.infrastructure;

import java.util.regex.Pattern;

/** Syntax rules for configuration that later reaches remote systems. */
public final class InfrastructureValidation {
    private static final Pattern DNS_NAME = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?$");
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern REMOTE_PATH = Pattern.compile("^[A-Za-z0-9._@+/-]+$");
    private static final Pattern URL_PATH = Pattern.compile("^/[A-Za-z0-9._~/-]*$");
    private static final Pattern POSIX_USER = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}\\$?$");

    private InfrastructureValidation() { }

    public static boolean isValidHostname(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (IPV4.matcher(v).matches()) return true;
        if (v.contains(":")) return IPV6.matcher(v).matches() && v.chars().filter(c -> c == ':').count() >= 2;
        return DNS_NAME.matcher(v).matches() && !v.matches("^[0-9.]+$");
    }

    /** Absolute POSIX path of safe characters without '..' segments. */
    public static boolean isSafeAbsolutePath(String value) {
        return value != null && value.startsWith("/") && isSafePath(value);
    }

    /** Absolute or relative POSIX path of safe characters without '..' segments. */
    public static boolean isSafePath(String value) {
        if (value == null || value.isBlank() || !REMOTE_PATH.matcher(value).matches()) return false;
        for (String segment : value.split("/")) if (segment.equals("..")) return false;
        return true;
    }

    public static boolean isValidUrlPath(String value) {
        if (value == null || !URL_PATH.matcher(value).matches()) return false;
        for (String segment : value.split("/")) if (segment.equals("..")) return false;
        return true;
    }

    public static boolean isValidPosixUsername(String value) {
        return value != null && POSIX_USER.matcher(value).matches();
    }
}
