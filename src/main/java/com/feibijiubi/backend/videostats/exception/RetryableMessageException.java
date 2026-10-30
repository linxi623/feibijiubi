package com.feibijiubi.backend.videostats.exception;

public class RetryableMessageException extends RuntimeException {
    public RetryableMessageException(String message) {
        super(message);
    }

    public RetryableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
