package com.tomcatmanager.connectivity;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import java.security.PublicKey;
import java.util.regex.Pattern;

/** OpenSSH-style SHA-256 host-key fingerprints, e.g. {@code SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8}. */
public final class HostKeyFingerprints {
    private static final Pattern SHA256 = Pattern.compile("^SHA256:[A-Za-z0-9+/]{43}$");

    private HostKeyFingerprints() { }

    public static String sha256(PublicKey key) {
        return normalize(KeyUtils.getFingerPrint(BuiltinDigests.sha256, key));
    }

    /** Canonical form: trimmed, {@code SHA256:} prefix, no Base64 padding. Returns null for blank input. */
    public static String normalize(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return null;
        String s = fingerprint.trim();
        if (s.regionMatches(true, 0, "SHA256:", 0, 7)) s = "SHA256:" + s.substring(7);
        while (s.endsWith("=")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static boolean isValid(String fingerprint) {
        String n = normalize(fingerprint);
        return n != null && SHA256.matcher(n).matches();
    }

    public static boolean matches(String expected, String actual) {
        String a = normalize(expected), b = normalize(actual);
        return a != null && a.equals(b);
    }
}
