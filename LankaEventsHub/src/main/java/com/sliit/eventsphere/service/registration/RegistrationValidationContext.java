package com.sliit.eventsphere.service.registration;

import com.sliit.eventsphere.entity.Event;

/**
 * What every link in the registration validation chain needs: the
 * already-locked event (loaded and row-locked by register() before the
 * chain ever runs - see the note on RegistrationValidator for why lock
 * order matters here), and which user is trying to register for it.
 */
public record RegistrationValidationContext(Event event, Long userId) {
}
