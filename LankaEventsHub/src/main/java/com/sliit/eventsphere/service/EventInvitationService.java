package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.InvitationResponse;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;

public interface EventInvitationService {

    /**
     * The ACTING USER requests access to a RESTRICTED event. There is no
     * userId parameter on purpose - an attendee can only ever ask for
     * themselves.
     */
    InvitationResponse requestInvitation(Long eventId, ActingUser actor);

    /**
     * Approves a pending request. eventId is part of the signature (and is
     * checked against the invitation) so that
     * /events/1/invitations/999/approve cannot decide an invitation that
     * actually belongs to event 2. Only the organizer who owns the event,
     * or an Administrator, may approve.
     */
    InvitationResponse approve(Long eventId, Long invitationId, ActingUser actor);

    /** Rejects a pending request, with the same ownership and consistency checks. */
    InvitationResponse reject(Long eventId, Long invitationId, ActingUser actor);

    /**
     * Approves or rejects several pending requests for one event in a
     * single transaction. Every id is checked against the event exactly as
     * the single-invitation methods do; ids that are not pending are
     * skipped rather than failing the whole batch.
     *
     * @return how many invitations were actually decided
     */
    int decideAll(Long eventId, List<Long> invitationIds, boolean approve, ActingUser actor);

    /** The request list for one event - organizer/admin only. */
    List<InvitationResponse> getInvitationsForEvent(Long eventId, ActingUser actor);

    /** The acting user's own invitation request for an event, if any. */
    InvitationResponse findMyInvitation(Long eventId, ActingUser actor);

    /**
     * Used by RegistrationServiceImpl to enforce: "For restricted events,
     * only invited or approved participants should be able to register."
     * Returns true for PUBLIC events automatically - the check only
     * matters for RESTRICTED ones.
     */
    boolean isEligibleToRegister(Long eventId, Long userId);
}
