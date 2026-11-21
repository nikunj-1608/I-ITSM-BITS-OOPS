package com.iitsm.domain.exception;

/**
 * OOP: Thrown when a request could not even start because the system is saturated (ADR-018): for example a report
 * waited longer than the configured limit for its turn in ticket intake. Mapped to HTTP 503; the client may retry.
 */
public class ServiceBusyException extends IitsmException {

    public static final String ERROR_CODE = "ERR_SERVICE_BUSY";
    public static final String USER_MESSAGE = "The system is busy handling other reports. Please try again in a moment.";

    public ServiceBusyException() {
        super(ERROR_CODE, USER_MESSAGE);
    }

    public ServiceBusyException(Throwable cause) {
        super(ERROR_CODE, USER_MESSAGE, cause);
    }
}
