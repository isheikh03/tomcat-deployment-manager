package com.tomcatmanager.connectivity;

import java.io.IOException;

/** A remote operation failed with a sanitized, user-facing reason. */
public class RemoteOperationException extends IOException {
    public RemoteOperationException(String message) { super(DiagnosticSanitizer.clean(message)); }
    public RemoteOperationException(String message, Throwable cause) { super(DiagnosticSanitizer.clean(message), cause); }
}
