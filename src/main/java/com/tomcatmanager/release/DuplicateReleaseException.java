package com.tomcatmanager.release;

/** The same WAR bytes are already a release of the same application. */
public class DuplicateReleaseException extends RuntimeException {
    private final long existingReleaseId;

    public DuplicateReleaseException(String message, long existingReleaseId) {
        super(message);
        this.existingReleaseId = existingReleaseId;
    }

    public long getExistingReleaseId() { return existingReleaseId; }
}
