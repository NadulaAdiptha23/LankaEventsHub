package com.sliit.eventsphere.service.registration;

import com.sliit.eventsphere.service.EventInvitationService;
import org.springframework.stereotype.Component;

/**
 * A no-op for a public event - EventInvitationService.isEligibleToRegister
 * only actually checks anything for a RESTRICTED one. The single wording
 * here replaces two slightly different sentences that used to say the
 * same thing at the two call sites this validator now serves.
 *
 * A null userId passes with no check at all and, deliberately, without
 * even calling eventInvitationService - same reasoning as
 * DuplicateRegistrationValidator: this only happens for
 * checkAvailability()'s anonymous-visitor case, where "log in to
 * register" is the entire answer regardless of what this chain would
 * have said.
 */
@Component
public class InvitationEligibilityValidator extends RegistrationValidator {

    private final EventInvitationService eventInvitationService;

    public InvitationEligibilityValidator(EventInvitationService eventInvitationService) {
        this.eventInvitationService = eventInvitationService;
    }

    @Override
    protected String check(RegistrationValidationContext context) {
        if (context.userId() == null) {
            return null;
        }
        boolean eligible = eventInvitationService.isEligibleToRegister(
                context.event().getId(), context.userId());
        return eligible ? null
                : "This is a restricted event. You must request an invitation and be "
                        + "approved by the organizer before you can register.";
    }
}
