package com.feibijiubi.backend.common;

/**
 * @deprecated 使用拼写正确的 {@link RepairRequiredMessageException}。
 */
@Deprecated
public class RepairRequireMessageException
        extends RepairRequiredMessageException {

    public RepairRequireMessageException(String message) {
        super(message);
    }

    public RepairRequireMessageException(
            String message,
            boolean alertRequired
    ) {
        super(message, alertRequired);
    }
}
