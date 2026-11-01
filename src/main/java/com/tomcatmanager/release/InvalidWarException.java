package com.tomcatmanager.release;

/** The uploaded file is not an acceptable web application archive. */
public class InvalidWarException extends Exception {
    public InvalidWarException(String message) { super(message); }
}
