package com.tomcatmanager.connectivity;

import java.util.regex.Pattern;

/**
 * Makes remote and exception messages safe to store, log and display: strips control
 * characters (log injection), redacts anything that looks like a credential, and bounds length.
 * HTML escaping is left to the rendering layer (Thymeleaf th:text / DOM textContent).
 */
public final class DiagnosticSanitizer {
    public static final int DEFAULT_MAX_LENGTH = 500;
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|$)", Pattern.DOTALL);
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization|proxy-authorization)\\s*[:=]\\s*(basic|bearer|digest)?\\s*[^\\s,;]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile("(?i)\\b(password|passwd|pwd|passphrase|secret|token|api[_-]?key)\\b(\\s*[:=]\\s*)[^\\s,;&]+");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u2028\\u2029]+");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    private DiagnosticSanitizer() { }

    public static String clean(String raw) { return clean(raw, DEFAULT_MAX_LENGTH); }

    public static String clean(String raw, int maxLength) {
        if (raw == null) return null;
        String s = PRIVATE_KEY.matcher(raw).replaceAll("[REDACTED PRIVATE KEY]");
        s = AUTH_HEADER.matcher(s).replaceAll("$1: [REDACTED]");
        s = KEY_VALUE_SECRET.matcher(s).replaceAll("$1$2[REDACTED]");
        s = URL_USERINFO.matcher(s).replaceAll("$1[REDACTED]@");
        s = CONTROL.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        if (s.length() > maxLength) s = s.substring(0, maxLength - 1) + "…";
        return s.isEmpty() ? null : s;
    }

    /** A short, sanitized description of the most specific cause of a failure. */
    public static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        String text = (message == null || message.isBlank()) ? root.getClass().getSimpleName() : message;
        return clean(text);
    }
}
