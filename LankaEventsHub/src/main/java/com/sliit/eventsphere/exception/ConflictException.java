package com.sliit.eventsphere.exception;

/**
 * A request that is well-formed and permitted, but conflicts with the
 * current state of the system - a duplicate registration, a second
 * payment for the same registration, a double-booked venue.
 *
 * Modelled as a subclass of InvalidRequestException so that the page
 * controllers which already catch InvalidRequestException keep showing
 * the message on the form, while the REST API can answer 409 Conflict
 * instead of a blanket 400 (see GlobalExceptionHandler).
 */
public class ConflictException extends InvalidRequestException {
    public ConflictException(String message) {
        super(message);
    }
}
