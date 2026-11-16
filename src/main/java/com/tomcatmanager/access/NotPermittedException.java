package com.tomcatmanager.access;

import org.springframework.security.access.AccessDeniedException;

/** The item exists for the user, but the action needs more access than they have (HTTP 403). */
public class NotPermittedException extends AccessDeniedException {
    public NotPermittedException(String message) { super(message); }
}
