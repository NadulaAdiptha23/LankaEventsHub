package com.sliit.eventsphere.exception;

/** Thrown when a requested entity (event, user, etc.) does not exist. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
