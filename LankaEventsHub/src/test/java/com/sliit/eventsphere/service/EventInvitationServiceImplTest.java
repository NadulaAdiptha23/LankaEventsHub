package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.InvitationResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.EventInvitationRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventInvitationServiceImplTest {

    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private EventInvitationRepository invitationRepository;
    @Mock private NotificationService notificationService;
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(org.mockito.Mockito.mock(
            com.sliit.eventsphere.repository.RegistrationRepository.class));

    @InjectMocks private EventInvitationServiceImpl invitationService;

    private Event publicEvent;
    private Event restrictedEvent;
    private Event otherOrganizersEvent;
    private User attendee;
    private User organizer;
    private ActingUser attendeeActor;
    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser adminActor;

    @BeforeEach
    void setUp() {
        organizer = new User("Kasun Perera", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        attendee = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(10L);

        attendeeActor = ActingUser.of(attendee);
        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = new ActingUser(77L, UserRole.ORGANIZER);
        adminActor = new ActingUser(99L, UserRole.ADMIN);

        publicEvent = new Event();
        publicEvent.setId(1L);
        publicEvent.setOrganizer(organizer);
        publicEvent.setEventType(EventType.PUBLIC);
        publicEvent.setStatus(EventStatus.PUBLISHED);

        restrictedEvent = new Event();
        restrictedEvent.setId(2L);
        restrictedEvent.setName("VIP Summit");
        restrictedEvent.setOrganizer(organizer);
        restrictedEvent.setEventType(EventType.RESTRICTED);
        restrictedEvent.setStatus(EventStatus.PUBLISHED);

        User someoneElse = new User("Ishara", "o2@test.com", "hash", UserRole.ORGANIZER);
        someoneElse.setId(77L);
        otherOrganizersEvent = new Event();
        otherOrganizersEvent.setId(3L);
        otherOrganizersEvent.setOrganizer(someoneElse);
        otherOrganizersEvent.setEventType(EventType.RESTRICTED);
        otherOrganizersEvent.setStatus(EventStatus.PUBLISHED);

        lenient().when(eventRepository.findById(1L)).thenReturn(Optional.of(publicEvent));
        lenient().when(eventRepository.findById(2L)).thenReturn(Optional.of(restrictedEvent));
        lenient().when(eventRepository.findById(3L)).thenReturn(Optional.of(otherOrganizersEvent));
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(attendee));
    }

    // =================================================================
    // Eligibility
    // =================================================================

    @Test
    void isEligibleToRegister_alwaysTrueForPublicEvents() {
        assertTrue(invitationService.isEligibleToRegister(1L, 10L));
    }

    @Test
    void isEligibleToRegister_falseWhenNoInvitationExistsForRestrictedEvent() {
        when(invitationRepository.findByEventIdAndUserId(2L, 10L)).thenReturn(Optional.empty());
        assertFalse(invitationService.isEligibleToRegister(2L, 10L));
    }

    @Test
    void isEligibleToRegister_falseWhenInvitationStillPending() {
        assertFalse(isEligibleWithStatus(2L, 10L, InvitationStatus.PENDING));
    }

    @Test
    void isEligibleToRegister_falseWhenInvitationRejected() {
        assertFalse(isEligibleWithStatus(2L, 10L, InvitationStatus.REJECTED));
    }

    @Test
    void isEligibleToRegister_trueWhenInvitationApproved() {
        assertTrue(isEligibleWithStatus(2L, 10L, InvitationStatus.APPROVED));
    }

    /** Small helper so the three status cases read as one line each. */
    private boolean isEligibleWithStatus(Long eventId, Long userId, InvitationStatus status) {
        EventInvitation invitation = new EventInvitation();
        invitation.setStatus(status);
        when(invitationRepository.findByEventIdAndUserId(eventId, userId))
                .thenReturn(Optional.of(invitation));
        return invitationService.isEligibleToRegister(eventId, userId);
    }

    // =================================================================
    // Requesting
    // =================================================================

    @Test
    void requestInvitation_usesTheActingUserNotACallerSuppliedId() {
        when(invitationRepository.findByEventIdAndUserId(2L, 10L)).thenReturn(Optional.empty());
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> {
            EventInvitation i = inv.getArgument(0);
            i.setId(50L);
            return i;
        });

        InvitationResponse response = invitationService.requestInvitation(2L, attendeeActor);

        assertEquals(InvitationStatus.PENDING, response.getStatus());
        assertEquals(10L, response.getUserId());
    }

    @Test
    void requestInvitation_rejectedForPublicEvents() {
        assertThrows(InvalidRequestException.class,
                () -> invitationService.requestInvitation(1L, attendeeActor));
    }

    @Test
    void requestInvitation_rejectedWhenARequestIsAlreadyPending() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> requestWithExistingStatus(InvitationStatus.PENDING));

        assertTrue(ex.getMessage().contains("not decided yet"),
                "a pending applicant should be told the organizer has not decided");
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void requestInvitation_tellsAnApprovedUserToJustRegister() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> requestWithExistingStatus(InvitationStatus.APPROVED));

        assertTrue(ex.getMessage().contains("already been approved"));
    }

    @Test
    void requestInvitation_saysPlainlyThatARejectionIsFinal() {
        // One request per person per event is the rule, so a rejected
        // applicant cannot re-apply. The point of this test is the message:
        // telling them they had "already requested" (the old wording) reads
        // as a duplicate-submission complaint and leaves them waiting for a
        // decision that has already been made.
        ConflictException ex = assertThrows(ConflictException.class,
                () -> requestWithExistingStatus(InvitationStatus.REJECTED));

        assertTrue(ex.getMessage().contains("declined"));
        assertTrue(ex.getMessage().contains("final"));
        assertTrue(ex.getMessage().contains("contact the organizer"),
                "the person must be told what they can actually do next");
        verify(invitationRepository, never()).save(any());
    }

    private void requestWithExistingStatus(InvitationStatus status) {
        EventInvitation existing = new EventInvitation();
        existing.setStatus(status);
        when(invitationRepository.findByEventIdAndUserId(2L, 10L)).thenReturn(Optional.of(existing));

        invitationService.requestInvitation(2L, attendeeActor);
    }

    @Test
    void requestInvitation_rejectedWhenTheEventIsNotPublished() {
        restrictedEvent.setStatus(EventStatus.CANCELLED);

        assertThrows(InvalidRequestException.class,
                () -> invitationService.requestInvitation(2L, attendeeActor));
    }

    // =================================================================
    // Deciding
    // =================================================================

    @Test
    void approve_refusesAnOrganizerWhoDoesNotOwnTheEvent() {
        assertThrows(AccessDeniedException.class,
                () -> invitationService.approve(2L, 50L, otherOrganizerActor));
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void approve_refusesAnAttendee() {
        assertThrows(AccessDeniedException.class,
                () -> invitationService.approve(2L, 50L, attendeeActor));
    }

    @Test
    void approve_refusesAnInvitationBelongingToAnotherEvent() {
        // /events/2/invitations/50/approve where invitation 50 is really on
        // event 3 - the ownership check on event 2 passes, so without the
        // consistency check this would decide another event's invitation.
        EventInvitation foreign = pendingInvitation(50L, otherOrganizersEvent);
        when(invitationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(foreign));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> invitationService.approve(2L, 50L, organizerActor));
        assertTrue(ex.getMessage().contains("does not belong"));
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void approve_rejectsWhenAlreadyDecided() {
        EventInvitation alreadyApproved = pendingInvitation(50L, restrictedEvent);
        alreadyApproved.setStatus(InvitationStatus.APPROVED);
        when(invitationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(alreadyApproved));

        assertThrows(InvalidRequestException.class,
                () -> invitationService.approve(2L, 50L, organizerActor));
    }

    @Test
    void approve_notifiesRequesterOnSuccess() {
        EventInvitation pending = pendingInvitation(50L, restrictedEvent);
        when(invitationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(pending));
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        InvitationResponse response = invitationService.approve(2L, 50L, organizerActor);

        assertEquals(InvitationStatus.APPROVED, response.getStatus());
        assertNotNull(response.getDecidedAt());
        verify(notificationService).notify(eq(attendee),
                eq(NotificationType.INVITATION_APPROVED), any(), any());
    }

    @Test
    void reject_marksRejectedAndNotifies() {
        EventInvitation pending = pendingInvitation(51L, restrictedEvent);
        when(invitationRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(pending));
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        InvitationResponse response = invitationService.reject(2L, 51L, organizerActor);

        assertEquals(InvitationStatus.REJECTED, response.getStatus());
        verify(notificationService).notify(eq(attendee),
                eq(NotificationType.INVITATION_REJECTED), any(), any());
    }

    @Test
    void decidingAnInvitationReadsItUnderARowLock() {
        // Deciding is read, check PENDING, write - on one row. Unlocked, an
        // organizer and an administrator working the same queue could both
        // read PENDING, both apply a decision, and send the requester two
        // notifications, with the final answer left to whichever
        // transaction committed last. The PENDING check was never wrong; it
        // simply could not see the other transaction.
        EventInvitation pending = pendingInvitation(50L, restrictedEvent);
        when(invitationRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(pending));
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        invitationService.approve(2L, 50L, organizerActor);

        verify(invitationRepository).findByIdForUpdate(50L);
        verify(invitationRepository, never()).findById(anyLong());
    }

    @Test
    void decideAllLocksEveryRowItTouches() {
        // The bulk path has the same race, once per row.
        EventInvitation one = pendingInvitation(60L, restrictedEvent);
        EventInvitation two = pendingInvitation(61L, restrictedEvent);
        when(invitationRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(one));
        when(invitationRepository.findByIdForUpdate(61L)).thenReturn(Optional.of(two));
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(2, invitationService.decideAll(2L, List.of(60L, 61L), true, organizerActor));

        verify(invitationRepository).findByIdForUpdate(60L);
        verify(invitationRepository).findByIdForUpdate(61L);
        verify(invitationRepository, never()).findById(anyLong());
    }

    @Test
    void adminCanDecideAnyEventsInvitations() {
        EventInvitation pending = pendingInvitation(52L, otherOrganizersEvent);
        when(invitationRepository.findByIdForUpdate(52L)).thenReturn(Optional.of(pending));
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(InvitationStatus.APPROVED,
                invitationService.approve(3L, 52L, adminActor).getStatus());
    }

    // =================================================================
    // Bulk decisions
    // =================================================================

    @Test
    void decideAll_skipsForeignAndAlreadyDecidedInvitations() {
        EventInvitation mine = pendingInvitation(60L, restrictedEvent);
        EventInvitation foreign = pendingInvitation(61L, otherOrganizersEvent);
        EventInvitation decided = pendingInvitation(62L, restrictedEvent);
        decided.setStatus(InvitationStatus.REJECTED);

        when(invitationRepository.findByIdForUpdate(60L)).thenReturn(Optional.of(mine));
        when(invitationRepository.findByIdForUpdate(61L)).thenReturn(Optional.of(foreign));
        when(invitationRepository.findByIdForUpdate(62L)).thenReturn(Optional.of(decided));
        when(invitationRepository.findByIdForUpdate(63L)).thenReturn(Optional.empty());
        when(invitationRepository.save(any(EventInvitation.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = invitationService.decideAll(2L, List.of(60L, 61L, 62L, 63L), true, organizerActor);

        assertEquals(1, count, "only the pending invitation on THIS event may be decided");
        assertEquals(InvitationStatus.APPROVED, mine.getStatus());
        assertEquals(InvitationStatus.PENDING, foreign.getStatus());
    }

    @Test
    void decideAll_refusesAnOrganizerWhoDoesNotOwnTheEvent() {
        assertThrows(AccessDeniedException.class,
                () -> invitationService.decideAll(2L, List.of(60L), true, otherOrganizerActor));
    }

    @Test
    void decideAll_rejectsAnEmptySelection() {
        assertThrows(InvalidRequestException.class,
                () -> invitationService.decideAll(2L, List.of(), true, organizerActor));
    }

    // =================================================================
    // Reading
    // =================================================================

    @Test
    void getInvitationsForEvent_refusesAnotherOrganizer() {
        assertThrows(AccessDeniedException.class,
                () -> invitationService.getInvitationsForEvent(2L, otherOrganizerActor));
    }

    @Test
    void getInvitationsForEvent_allowsTheOwner() {
        when(invitationRepository.findByEventId(2L)).thenReturn(List.of());
        assertDoesNotThrow(() -> invitationService.getInvitationsForEvent(2L, organizerActor));
    }

    // =================================================================
    // helpers
    // =================================================================

    private EventInvitation pendingInvitation(Long id, Event event) {
        EventInvitation invitation = new EventInvitation();
        invitation.setId(id);
        invitation.setEvent(event);
        invitation.setUser(attendee);
        invitation.setStatus(InvitationStatus.PENDING);
        return invitation;
    }
}
