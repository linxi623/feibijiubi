package com.feibijiubi.backend.videostats.exception;

public class NonRetryableMessageException extends RuntimeException {
    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}