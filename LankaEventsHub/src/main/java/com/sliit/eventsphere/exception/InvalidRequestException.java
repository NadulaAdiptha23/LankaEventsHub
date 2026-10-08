package com.sliit.eventsphere.exception;

/** Thrown when a request violates a business rule (e.g. editing a locked field). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
