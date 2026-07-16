package com.feibijiubi.backend.common;

public class RedisOperationException extends RuntimeException {

    public RedisOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
