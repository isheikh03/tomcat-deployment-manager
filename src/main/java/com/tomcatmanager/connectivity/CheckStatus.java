package com.tomcatmanager.connectivity;

/** Outcome of one validation step, and (restricted to SUCCESS/WARNING/FAILED) of a whole test. */
public enum CheckStatus {
    SUCCESS, WARNING, FAILED, NOT_APPLICABLE, SKIPPED
}
