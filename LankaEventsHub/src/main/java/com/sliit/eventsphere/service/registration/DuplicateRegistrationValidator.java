package com.sliit.eventsphere.service.registration;

import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * One active registration per attendee per event - a cancelled prior
 * attempt does not count.
 *
 * A null userId passes with no check at all: it means the caller is
 * checkAvailability() looking at the event on behalf of an anonymous,
 * not-yet-logged-in visitor (see RegistrationServiceImpl.checkAvailability),
 * who by definition cannot already have a registration to duplicate.
 * That page tells them to log in rather than showing any reason from
 * this chain at all, so what this validator returns for that case is
 * never actually read - but returning null (rather than, say, querying
 * with a null id and hoping the repository does something sensible) is
 * the version of "not applicable" that says so.
 */
@Component
public class DuplicateRegistrationValidator extends RegistrationValidator {

    private final RegistrationRepository registrationRepository;

    public DuplicateRegistrationValidator(RegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    @Override
    protected String check(RegistrationValidationContext context) {
        if (context.userId() == null) {
            return null;
        }
        boolean alreadyRegistered = registrationRepository
                .findActiveByEventIdAndUserId(context.event().getId(), context.userId())
                .isPresent();
        return alreadyRegistered ? "You are already registered for this event" : null;
    }
}
