package com.tomcatmanager.deployment;

import com.tomcatmanager.infrastructure.TomcatInstance;
import com.tomcatmanager.release.Release;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * Everything a deployer needs for one target. {@code payload} is the WAR to install (a release, or
 * the restore point of a rollback); {@code previousRelease} is what is replaced, when known.
 * {@code cancelled} reflects the persisted cancel request.
 */
record TargetJob(long runId, long targetId, TomcatInstance instance, Payload payload, String filename, String contextPath,
                 Release previousRelease, boolean rollback, BooleanSupplier cancelled) {

    /** The bytes to install, with the checksum the uploaded copy must match. */
    record Payload(String label, String sha256, long size, Opener opener) {
        InputStream open() throws IOException { return opener.open(); }
    }

    interface Opener { InputStream open() throws IOException; }
}
