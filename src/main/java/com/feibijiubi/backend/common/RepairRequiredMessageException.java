package com.feibijiubi.backend.common;

public class RepairRequiredMessageException extends RuntimeException {

    private final boolean alertRequired;

    public RepairRequiredMessageException(String message) {
        this(message, false);
    }

    public RepairRequiredMessageException(
            String message,
            boolean alertRequired
    ) {
        super(message);
        this.alertRequired = alertRequired;
    }

    public boolean isAlertRequired() {
        return alertRequired;
    }
}
