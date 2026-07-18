package com.feibijiubi.backend.common;

public class NonRetryableMessageException extends RuntimeException {
    public NonRetryableMessageException(String message) {
        super(message);
    }

    public NonRetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}