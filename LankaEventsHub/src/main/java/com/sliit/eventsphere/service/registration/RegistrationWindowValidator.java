package com.sliit.eventsphere.service.registration;

import com.sliit.eventsphere.entity.Event;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Three date checks against the same event, all "is now within the
 * window" questions rather than three unrelated concerns - which is why
 * they stay one validator rather than three: registration must have
 * opened, must not have closed, and the event itself must not have
 * already started (a deadline a published event can outlive if nobody
 * ever set one).
 */
@Component
public class RegistrationWindowValidator extends RegistrationValidator {

    @Override
    protected String check(RegistrationValidationContext context) {
        Event event = context.event();
        LocalDateTime now = LocalDateTime.now();

        if (event.getRegistrationOpenDate() != null && now.isBefore(event.getRegistrationOpenDate())) {
            return "Registration for this event has not opened yet";
        }
        if (event.getRegistrationDeadline() != null && now.isAfter(event.getRegistrationDeadline())) {
            return "Registration for this event has closed";
        }
        if (event.getStartDatetime() != null && !now.isBefore(event.getStartDatetime())) {
            return "This event has already started";
        }
        return null;
    }
}
