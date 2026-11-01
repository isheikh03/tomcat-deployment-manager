package com.tomcatmanager.connectivity;

/** Decrypted SSH credentials, held only in memory for the duration of one remote operation. */
public record SshCredentials(String username, Method method, String password, String privateKey, String passphrase) {
    public enum Method { PASSWORD, PRIVATE_KEY }

    public static SshCredentials password(String username, String password) {
        return new SshCredentials(username, Method.PASSWORD, password, null, null);
    }
    public static SshCredentials privateKey(String username, String privateKey, String passphrase) {
        return new SshCredentials(username, Method.PRIVATE_KEY, null, privateKey, passphrase);
    }

    @Override public String toString() { return "SshCredentials[username=" + username + ", method=" + method + ", secrets=REDACTED]"; }
}
