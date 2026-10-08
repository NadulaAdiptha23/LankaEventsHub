package com.sliit.eventsphere.service.registration;

import org.springframework.stereotype.Component;

/**
 * Only a PUBLISHED event is open for registration - a cancelled or
 * completed event is rejected with a reason specific to which, and
 * anything else (DRAFT, PENDING_APPROVAL) has simply never opened yet.
 */
@Component
public class EventStatusValidator extends RegistrationValidator {

    @Override
    protected String check(RegistrationValidationContext context) {
        return switch (context.event().getStatus()) {
            case CANCELLED -> "This event has been cancelled";
            case COMPLETED -> "This event has already taken place";
            case PUBLISHED -> null;
            default -> "This event is not published yet, so registration has not opened";
        };
    }
}
