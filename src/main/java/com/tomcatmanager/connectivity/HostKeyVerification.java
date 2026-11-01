package com.tomcatmanager.connectivity;

/**
 * Result of comparing the SSH server's host key with the pinned fingerprint.
 * UNKNOWN (nothing pinned yet) and MISMATCH (pinned key differs) are deliberately distinct:
 * the first may be resolved by an administrator trusting the key, the second never automatically.
 */
public enum HostKeyVerification { MATCHED, UNKNOWN, MISMATCH, NOT_CHECKED }
