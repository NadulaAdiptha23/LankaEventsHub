package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.InvitationResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.EventInvitationRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Function 1 (Event Creation & Management) support: implements the
 * requirement that restricted events "only allow invited or approved
 * participants to register." See isEligibleToRegister(), which
 * RegistrationServiceImpl calls before confirming any registration on a
 * RESTRICTED event.
 *
 * Two authorization rules run through every method here:
 *   1. a request is always made for the acting user, never for a
 *      caller-supplied userId;
 *   2. a decision (approve/reject) is only allowed for the organizer who
 *      owns the event, or an Administrator - and the invitation must
 *      actually belong to the event named in the URL.
 */
@Service
public class EventInvitationServiceImpl implements EventInvitationService {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final EventInvitationRepository invitationRepository;
    private final NotificationService notificationService;
    private final EventAccessPolicy accessPolicy;

    public EventInvitationServiceImpl(EventRepository eventRepository,
                                       UserRepository userRepository,
                                       EventInvitationRepository invitationRepository,
                                       NotificationService notificationService,
                                       EventAccessPolicy accessPolicy) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.notificationService = notificationService;
        this.accessPolicy = accessPolicy;
    }

    @Override
    @Transactional
    public InvitationResponse requestInvitation(Long eventId, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        User user = userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + actor.id()));

        if (event.getEventType() != EventType.RESTRICTED) {
            throw new InvalidRequestException("This event is public - no invitation is required to register");
        }
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new InvalidRequestException("This event is not open for invitation requests");
        }
        // One request per person per event. The uq_invitation
        // (event_id, user_id) constraint in the schema is the real
        // guarantee; this check exists to turn the resulting database error
        // into a message that says what state the person is actually in.
        //
        // The REJECTED case is a deliberate rule, not a side effect of the
        // unique key: a rejection is final, and re-requesting is not a way
        // to appeal it. Saying so plainly is the point - the earlier
        // version told a rejected applicant they had "already requested",
        // which reads like a duplicate-submission complaint and leaves them
        // refreshing the page waiting for a decision that has already been
        // made. If the organizer changes their mind they approve the
        // existing rejected request; there is no second request to make.
        var existing = invitationRepository.findByEventIdAndUserId(eventId, actor.id());
        if (existing.isPresent()) {
            throw new ConflictException(switch (existing.get().getStatus()) {
                case PENDING -> "You have already requested an invitation for this event. "
                        + "The organizer has not decided yet.";
                case APPROVED -> "Your invitation for this event has already been approved - "
                        + "you can register now.";
                case REJECTED -> "Your invitation request for this event was declined by the organizer. "
                        + "That decision is final and a new request cannot be submitted; "
                        + "please contact the organizer directly if you think it was a mistake.";
            });
        }

        EventInvitation invitation = new EventInvitation();
        invitation.setEvent(event);
        invitation.setUser(user);
        invitation.setStatus(InvitationStatus.PENDING);

        return InvitationResponse.from(invitationRepository.save(invitation));
    }

    @Override
    @Transactional
    public InvitationResponse approve(Long eventId, Long invitationId, ActingUser actor) {
        return decide(eventId, invitationId, true, actor);
    }

    @Override
    @Transactional
    public InvitationResponse reject(Long eventId, Long invitationId, ActingUser actor) {
        return decide(eventId, invitationId, false, actor);
    }

    @Override
    @Transactional
    public int decideAll(Long eventId, List<Long> invitationIds, boolean approve, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage invitations for this event.");

        if (invitationIds == null || invitationIds.isEmpty()) {
            throw new InvalidRequestException("Select at least one request first");
        }

        List<EventInvitation> decided = new ArrayList<>();
        for (Long id : invitationIds) {
            // Locked per row, for the same reason decide() locks: the
            // PENDING check below is only meaningful if no other
            // transaction can decide this invitation between the read and
            // the write. Skipping a row that is already decided is normal
            // here rather than an error - a bulk action is allowed to race
            // with a single one and simply do less work.
            EventInvitation invitation = invitationRepository.findByIdForUpdate(id).orElse(null);
            if (invitation == null) continue;
            if (!invitation.getEvent().getId().equals(eventId)) continue; // belongs to another event
            if (invitation.getStatus() != InvitationStatus.PENDING) continue; // already decided
            applyDecision(invitation, approve);
            decided.add(invitation);
        }
        return decided.size();
    }

    private InvitationResponse decide(Long eventId, Long invitationId, boolean approve, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage invitations for this event.");

        // Locked, not findById. Deciding an invitation is a read, a status
        // check and a write on one row: unlocked, an organizer and an
        // administrator working the same queue could both read PENDING,
        // both apply a decision, and send the requester two notifications -
        // with the final answer decided by whichever transaction committed
        // last. The check below was never wrong; it just could not see the
        // other transaction.
        EventInvitation invitation = invitationRepository.findByIdForUpdate(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Invitation not found with id " + invitationId));

        // Without this, /events/1/invitations/999/approve would happily
        // decide invitation 999 even though it belongs to event 2 - the
        // ownership check above would have passed on event 1.
        if (!invitation.getEvent().getId().equals(eventId)) {
            throw new InvalidRequestException("That invitation does not belong to this event");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new InvalidRequestException("This invitation has already been decided");
        }

        applyDecision(invitation, approve);
        return InvitationResponse.from(invitation);
    }

    private void applyDecision(EventInvitation invitation, boolean approve) {
        invitation.setStatus(approve ? InvitationStatus.APPROVED : InvitationStatus.REJECTED);
        invitation.setDecidedAt(LocalDateTime.now());
        invitationRepository.save(invitation);

        notificationService.notify(invitation.getUser(),
                approve ? NotificationType.INVITATION_APPROVED : NotificationType.INVITATION_REJECTED,
                NotificationChannel.IN_APP,
                approve
                        ? "You've been approved to register for \"" + invitation.getEvent().getName() + "\"."
                        : "Your request to join \"" + invitation.getEvent().getName() + "\" was not approved.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvitationResponse> getInvitationsForEvent(Long eventId, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        accessPolicy.requireManager(event, actor,
                "You are not authorized to view invitation requests for this event.");
        return invitationRepository.findByEventId(eventId).stream()
                .map(InvitationResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationResponse findMyInvitation(Long eventId, ActingUser actor) {
        return invitationRepository.findByEventIdAndUserId(eventId, actor.id())
                .map(InvitationResponse::from)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEligibleToRegister(Long eventId, Long userId) {
        Event event = getEventOrThrow(eventId);

        if (event.getEventType() != EventType.RESTRICTED) {
            return true; // public events have no invitation gate
        }

        return invitationRepository.findByEventIdAndUserId(eventId, userId)
                .map(invitation -> invitation.getStatus() == InvitationStatus.APPROVED)
                .orElse(false);
    }

    private Event getEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
    }
}
