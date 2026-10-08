package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.EventCreateRequest;
import com.sliit.eventsphere.dto.EventResponse;
import com.sliit.eventsphere.dto.EventSearchCriteria;
import com.sliit.eventsphere.dto.EventUpdateRequest;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.events.EventPublishedEvent;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventServiceImplTest {

    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private ApplicationEventPublisher eventPublisher;

    // The real policy, not a mock: ownership is one of the things under
    // test, so stubbing it away would test nothing.
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(org.mockito.Mockito.mock(
            com.sliit.eventsphere.repository.RegistrationRepository.class));

    @InjectMocks private EventServiceImpl eventService;

    private User organizer;
    private User otherOrganizer;
    private User admin;
    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser adminActor;
    private ActingUser attendeeActor;

    @BeforeEach
    void setUp() {
        organizer = new User("Kasun Perera", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        otherOrganizer = new User("Ishara Fernando", "organizer2@test.com", "hash", UserRole.ORGANIZER);
        otherOrganizer.setId(7L);
        admin = new User("Admin", "admin@test.com", "hash", UserRole.ADMIN);
        admin.setId(99L);

        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = ActingUser.of(otherOrganizer);
        adminActor = ActingUser.of(admin);
        attendeeActor = new ActingUser(2L, UserRole.ATTENDEE);

        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        lenient().when(userRepository.findById(7L)).thenReturn(Optional.of(otherOrganizer));
        lenient().when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
    }

    // =================================================================
    // Create
    // =================================================================

    @Test
    void createEvent_usesTheAuthenticatedOrganizerNotTheRequestBody() {
        // The classic privilege-escalation attempt: post someone else's id.
        EventCreateRequest request = validRequest();
        request.setOrganizerId(7L);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.createEvent(request, organizerActor);

        verify(eventRepository).save(argThat(e -> e.getOrganizer().getId().equals(1L)));
        verify(userRepository, never()).findById(7L);
    }

    @Test
    void createEvent_letsAnAdminCreateOnBehalfOfAnotherOrganizer() {
        EventCreateRequest request = validRequest();
        request.setOrganizerId(7L);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.createEvent(request, adminActor);

        verify(eventRepository).save(argThat(e -> e.getOrganizer().getId().equals(7L)));
    }

    @Test
    void createEvent_throwsWhenOrganizerNotFound() {
        when(userRepository.findById(55L)).thenReturn(Optional.empty());
        ActingUser ghost = new ActingUser(55L, UserRole.ORGANIZER);

        assertThrows(ResourceNotFoundException.class,
                () -> eventService.createEvent(validRequest(), ghost));
    }

    @Test
    void createEvent_throwsWhenTheOwnerIsNotAnOrganizer() {
        User attendee = new User("Attendee", "a@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(2L);
        when(userRepository.findById(2L)).thenReturn(Optional.of(attendee));

        assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(validRequest(), attendeeActor));
    }

    @Test
    void createEvent_throwsWhenTheOrganizerAccountIsSuspended() {
        organizer.setActive(false);

        assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(validRequest(), organizerActor));
    }

    @Test
    void createEvent_detectsVenueDoubleBooking() {
        EventCreateRequest request = validRequest();
        request.setVenue("Main Hall");
        request.setStartDatetime(LocalDateTime.of(2027, 9, 15, 10, 0));
        request.setEndDatetime(LocalDateTime.of(2027, 9, 15, 13, 0));

        when(eventRepository.findOverlappingEvents(eq("Main Hall"), any(), any(),
                eq(EventStatus.CANCELLED), eq(-1L))).thenReturn(List.of(new Event()));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
        assertTrue(ex.getMessage().contains("already booked"));
    }

    @Test
    void createEvent_rejectsAnEndTimeBeforeTheStart() {
        EventCreateRequest request = validRequest();
        request.setStartDatetime(LocalDateTime.of(2027, 9, 15, 12, 0));
        request.setEndDatetime(LocalDateTime.of(2027, 9, 15, 9, 0));

        assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
    }

    @Test
    void createEvent_rejectsARegistrationDeadlineAfterTheEventStarts() {
        EventCreateRequest request = validRequest();
        request.setStartDatetime(LocalDateTime.of(2027, 9, 15, 9, 0));
        request.setEndDatetime(LocalDateTime.of(2027, 9, 15, 17, 0));
        request.setRegistrationOpenDate(LocalDateTime.of(2027, 8, 1, 0, 0));
        request.setRegistrationDeadline(LocalDateTime.of(2027, 9, 16, 0, 0));

        assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
    }

    @Test
    void createEvent_rejectsZeroCapacity() {
        EventCreateRequest request = validRequest();
        request.setCapacity(0);

        assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
    }

    @Test
    void createEvent_rejectsABannerUrlThatIsNotAUrl() {
        EventCreateRequest request = validRequest();
        request.setBannerUrl("javascript:alert(1)");

        assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
    }

    @Test
    void createEvent_startsAsADraft() {
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventResponse response = eventService.createEvent(validRequest(), organizerActor);

        assertEquals(EventStatus.DRAFT, response.getStatus());
    }

    @Test
    void createEvent_doesNotPutTheNewEventInTheApprovalQueue() {
        // The point of DRAFT: creating an event asks nothing of an
        // administrator. If this regresses, the queue fills with
        // half-finished events again.
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventResponse response = eventService.createEvent(validRequest(), organizerActor);

        assertNotEquals(EventStatus.PENDING_APPROVAL, response.getStatus());
    }

    // =================================================================
    // Submit for approval (DRAFT -> PENDING_APPROVAL)
    // =================================================================

    @Test
    void submitForApproval_movesACompleteDraftIntoTheQueue() {
        Event draft = submittableDraft(5L);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(draft));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventResponse response = eventService.submitForApproval(5L, organizerActor);

        assertEquals(EventStatus.PENDING_APPROVAL, response.getStatus());
    }

    @Test
    void submitForApproval_refusesSomeoneElsesDraft() {
        Event draft = submittableDraft(5L);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(draft));

        assertThrows(AccessDeniedException.class,
                () -> eventService.submitForApproval(5L, otherOrganizerActor));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void submitForApproval_refusesAnAttendee() {
        Event draft = submittableDraft(5L);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(draft));

        assertThrows(AccessDeniedException.class,
                () -> eventService.submitForApproval(5L, attendeeActor));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void submitForApproval_refusesAnythingThatIsNotADraft() {
        Event liveEvent = eventOwnedByOrganizer(5L, EventStatus.PUBLISHED);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(liveEvent));

        assertThrows(InvalidRequestException.class,
                () -> eventService.submitForApproval(5L, organizerActor));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void submitForApproval_cannotBeUsedTwiceToReSubmit() {
        Event pending = eventOwnedByOrganizer(5L, EventStatus.PENDING_APPROVAL);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(pending));

        assertThrows(InvalidRequestException.class,
                () -> eventService.submitForApproval(5L, organizerActor));
    }

    @Test
    void submitForApproval_namesEveryMissingFieldAtOnce() {
        // An empty draft is missing four things. The organizer should be
        // told all four now, not discover them one submission at a time.
        Event draft = new Event();
        draft.setId(5L);
        draft.setOrganizer(organizer);
        draft.setName("Untitled");
        draft.setStatus(EventStatus.DRAFT);
        draft.setCapacity(0);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(draft));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.submitForApproval(5L, organizerActor));

        String message = ex.getMessage();
        assertTrue(message.contains("venue"), message);
        assertTrue(message.contains("start date"), message);
        assertTrue(message.contains("capacity"), message);
        assertTrue(message.contains("deadline"), message);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void submitForApproval_refusesADraftWhoseDateHasAlreadyPassed() {
        Event draft = submittableDraft(5L);
        draft.setStartDatetime(LocalDateTime.now().minusDays(1));
        when(eventRepository.findById(5L)).thenReturn(Optional.of(draft));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.submitForApproval(5L, organizerActor));

        assertTrue(ex.getMessage().contains("passed"), ex.getMessage());
    }

    @Test
    void approveEvent_refusesToPublishADraftDirectly() {
        // A draft has not been reviewed by its own organizer yet, so it
        // must not be possible to skip the submission step - not even for
        // an administrator.
        Event draft = submittableDraft(5L);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(draft));

        assertThrows(InvalidRequestException.class, () -> eventService.approveEvent(5L, adminActor));
        verify(eventPublisher, never()).publishEvent(any(EventPublishedEvent.class));
    }

    // =================================================================
    // Approve / cancel / complete
    // =================================================================

    @Test
    void approveEvent_isAdminOnly() {
        assertThrows(AccessDeniedException.class,
                () -> eventService.approveEvent(5L, organizerActor));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void approveEvent_publishesEventAndChangesStatus() {
        Event event = eventOwnedByOrganizer(5L, EventStatus.PENDING_APPROVAL);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventResponse response = eventService.approveEvent(5L, adminActor);

        assertEquals(EventStatus.PUBLISHED, response.getStatus());
        verify(eventPublisher, times(1)).publishEvent(any(EventPublishedEvent.class));
    }

    @Test
    void approveEvent_refusesAnythingNotPendingApproval() {
        Event cancelled = eventOwnedByOrganizer(5L, EventStatus.CANCELLED);
        when(eventRepository.findById(5L)).thenReturn(Optional.of(cancelled));

        assertThrows(InvalidRequestException.class, () -> eventService.approveEvent(5L, adminActor));
    }

    // -----------------------------------------------------------------
    // Cancellation.
    //
    // Every case here stubs findByIdForUpdate, not findById: taking the row
    // lock is part of the behaviour under test. If someone ever "simplifies"
    // cancelEvent back to an unlocked read, these tests stop compiling
    // against a stub that is no longer used, rather than passing quietly
    // while the race comes back.
    // -----------------------------------------------------------------

    @Test
    void cancelEvent_refusesAnOrganizerWhoDoesNotOwnTheEvent() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));

        assertThrows(AccessDeniedException.class,
                () -> eventService.cancelEvent(10L, otherOrganizerActor));
        verify(eventPublisher, never()).publishEvent(any(EventCancelledEvent.class));
    }

    @Test
    void cancelEvent_takesTheRowLockRatherThanAPlainRead() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.cancelEvent(10L, organizerActor);

        verify(eventRepository).findByIdForUpdate(10L);
        verify(eventRepository, never()).findById(10L);
        // The registrations are snapshotted under a lock too, so the set of
        // people to notify and refund cannot change underneath the loop.
        verify(registrationRepository).findActiveByEventIdForUpdate(10L);
    }

    @Test
    void cancelEvent_publishesTheCancellationEventOnce() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.cancelEvent(10L, organizerActor);

        assertEquals(EventStatus.CANCELLED, event.getStatus());
        verify(eventPublisher, times(1)).publishEvent(any(EventCancelledEvent.class));
    }

    @Test
    void cancelEvent_refusesToCancelTwiceSoRefundsCannotBeDuplicated() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.CANCELLED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));

        assertThrows(InvalidRequestException.class, () -> eventService.cancelEvent(10L, organizerActor));
        verify(eventPublisher, never()).publishEvent(any(EventCancelledEvent.class));
    }

    @Test
    void cancelEvent_asecondCancellationHasNoObserverEffectAtAll() {
        // The concurrency case reduced to what a unit test can actually
        // assert: the first call flips the status, and the second - seeing
        // the state the first one left behind, exactly as the blocked
        // transaction would after the lock is released - is rejected before
        // anything is published, saved, or logged a second time.
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.cancelEvent(10L, organizerActor);
        assertThrows(InvalidRequestException.class, () -> eventService.cancelEvent(10L, organizerActor));

        verify(eventPublisher, times(1)).publishEvent(any(EventCancelledEvent.class));
        verify(eventRepository, times(1)).save(any(Event.class));
        verify(registrationRepository, times(1)).findActiveByEventIdForUpdate(10L);
    }

    @Test
    void cancelEvent_cancelsEveryRegistrationAndReleasesTheSeats() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        event.setCapacity(50);
        event.setSeatsTaken(2);
        Registration confirmed = registrationOn(event, RegistrationStatus.CONFIRMED);
        Registration waitlisted = registrationOn(event, RegistrationStatus.WAITLISTED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.findActiveByEventIdForUpdate(10L))
                .thenReturn(List.of(confirmed, waitlisted));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.cancelEvent(10L, organizerActor);

        // No live registration may survive a cancelled event: that state is
        // what made a cancelled event still show a valid-looking ticket.
        assertEquals(RegistrationStatus.CANCELLED, confirmed.getStatus());
        assertEquals(RegistrationStatus.CANCELLED, waitlisted.getStatus());
        assertNotNull(confirmed.getCancelledAt());
        assertEquals(0, event.getSeatsTaken());
    }

    @Test
    void cancelEvent_theEventItPublishesRemembersWhoHeldASeat() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        Registration confirmed = registrationOn(event, RegistrationStatus.CONFIRMED);
        Registration waitlisted = registrationOn(event, RegistrationStatus.WAITLISTED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.findActiveByEventIdForUpdate(10L))
                .thenReturn(List.of(confirmed, waitlisted));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        eventService.cancelEvent(10L, organizerActor);

        ArgumentCaptor<EventCancelledEvent> published =
                ArgumentCaptor.forClass(EventCancelledEvent.class);
        verify(eventPublisher).publishEvent(published.capture());
        List<EventCancelledEvent.AffectedRegistration> affected =
                published.getValue().getAffectedRegistrations();

        assertEquals(2, affected.size());
        // Both registrations are CANCELLED by now; the payload still knows
        // which of them was holding a paid seat. This is the whole reason
        // the status is captured rather than read back off the entity.
        assertTrue(affected.get(0).heldASeat());
        assertFalse(affected.get(1).heldASeat());
    }

    @Test
    void cancelEvent_refusesToCancelACompletedEvent() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.COMPLETED);
        when(eventRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(event));

        assertThrows(InvalidRequestException.class, () -> eventService.cancelEvent(10L, organizerActor));
    }

    @Test
    void completeEvent_onlyFromPublishedAndOnlyAfterItHasEnded() {
        Event notFinished = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        notFinished.setEndDatetime(LocalDateTime.now().plusDays(2));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(notFinished));

        assertThrows(InvalidRequestException.class, () -> eventService.completeEvent(10L, organizerActor));

        Event finished = eventOwnedByOrganizer(11L, EventStatus.PUBLISHED);
        finished.setEndDatetime(LocalDateTime.now().minusDays(1));
        when(eventRepository.findById(11L)).thenReturn(Optional.of(finished));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(EventStatus.COMPLETED,
                eventService.completeEvent(11L, organizerActor).getStatus());
    }

    @Test
    void completeEvent_fallsBackToTheStartDateWhenThereIsNoEndDate() {
        // end_datetime is nullable. The old check only refused when an end
        // date existed and was in the future, so an event with no end date
        // could be completed the moment it was published - before it had
        // even started.
        Event notStarted = eventOwnedByOrganizer(12L, EventStatus.PUBLISHED);
        notStarted.setStartDatetime(LocalDateTime.now().plusDays(3));
        notStarted.setEndDatetime(null);
        when(eventRepository.findById(12L)).thenReturn(Optional.of(notStarted));

        assertThrows(InvalidRequestException.class, () -> eventService.completeEvent(12L, organizerActor));

        Event started = eventOwnedByOrganizer(13L, EventStatus.PUBLISHED);
        started.setStartDatetime(LocalDateTime.now().minusDays(1));
        started.setEndDatetime(null);
        when(eventRepository.findById(13L)).thenReturn(Optional.of(started));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        assertEquals(EventStatus.COMPLETED,
                eventService.completeEvent(13L, organizerActor).getStatus());
    }

    @Test
    void completeEvent_refusesWhenThereIsNoScheduleToJudgeBy() {
        Event undated = eventOwnedByOrganizer(14L, EventStatus.PUBLISHED);
        undated.setStartDatetime(null);
        undated.setEndDatetime(null);
        when(eventRepository.findById(14L)).thenReturn(Optional.of(undated));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.completeEvent(14L, organizerActor));
        // Not a silent success, and not a 500 - a message the organizer can act on.
        assertTrue(ex.getMessage().contains("no start or end date"));
    }

    @Test
    void completeEvent_cannotResurrectACancelledEvent() {
        Event cancelled = eventOwnedByOrganizer(10L, EventStatus.CANCELLED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(cancelled));

        assertThrows(InvalidRequestException.class, () -> eventService.completeEvent(10L, organizerActor));
    }

    // =================================================================
    // Update
    // =================================================================

    @Test
    void updateEventDetails_refusesAnOrganizerWhoDoesNotOwnTheEvent() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setDescription("hijacked");

        assertThrows(AccessDeniedException.class,
                () -> eventService.updateEventDetails(10L, request, otherOrganizerActor));
    }

    @Test
    void updateEventDetails_blocksLockedFieldsWhenRegistrationsExist() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(1L);

        EventUpdateRequest request = new EventUpdateRequest();
        request.setVenue("A Different Hall");

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, request, organizerActor));
        assertTrue(ex.getMessage().contains("venue"));
        assertTrue(ex.getMessage().contains("Administrator"), ex.getMessage());
        // Nothing may be written when the update is refused.
        verify(eventRepository, never()).save(any(Event.class));
    }

    // =================================================================
    // BR-05: an Administrator can change what an organizer cannot
    //
    // Once people have registered, the terms they signed up to are fixed
    // FOR THE ORGANIZER. An administrator can still act - that is what
    // "requires Administrator approval" means when there is a real
    // administrator who can do it.
    //
    // An earlier pass deleted a message claiming this workflow existed
    // while nothing implemented it. These are what stop it becoming a
    // claim again: the capability is now real and it is audited.
    // =================================================================

    @Test
    void updateEventDetails_letsAnAdministratorChangeALockedField() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(5L);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setVenue("A Different Hall");

        EventResponse response = eventService.updateEventDetails(10L, request, adminActor);

        assertEquals("A Different Hall", response.getVenue());
    }

    @Test
    void updateEventDetails_anAdministratorOverrideIsAuditedByName() {
        // The one change here worth being able to find later: it alters
        // the terms under which people already registered. "Event details
        // updated" would bury it among ordinary description edits.
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(5L);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setVenue("A Different Hall");
        eventService.updateEventDetails(10L, request, adminActor);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), eq("UPDATE_EVENT"), eq("Event"), eq(10L),
                details.capture());
        assertTrue(details.getValue().contains("Administrator"), details.getValue());
        assertTrue(details.getValue().contains("venue"), details.getValue());
    }

    @Test
    void updateEventDetails_anOrdinaryAdminEditIsNotLoggedAsAnOverride() {
        // No registrations, so nothing was overridden. Calling it one
        // would make the audit trail cry wolf.
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(0L);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setDescription("Just a description change");
        eventService.updateEventDetails(10L, request, adminActor);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), eq("UPDATE_EVENT"), eq("Event"), eq(10L),
                details.capture());
        assertFalse(details.getValue().contains("Administrator"), details.getValue());
    }

    @Test
    void updateEventDetails_theVenueConflictCheckStillAppliesToAnAdministrator() {
        // The lock is about protecting attendees from changed terms. It
        // is not permission to double-book a room.
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        event.setStartDatetime(LocalDateTime.of(2027, 9, 15, 9, 0));
        event.setEndDatetime(LocalDateTime.of(2027, 9, 15, 12, 0));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(5L);
        when(eventRepository.findOverlappingEvents(eq("Booked Hall"), any(), any(),
                eq(EventStatus.CANCELLED), eq(10L))).thenReturn(List.of(new Event()));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setVenue("Booked Hall");

        assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, request, adminActor));
    }

    @Test
    void updateEventDetails_allowsFreeFieldsEvenWithRegistrations() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(3L);
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setDescription("Updated description only");

        EventResponse response = eventService.updateEventDetails(10L, request, organizerActor);
        assertEquals("Updated description only", response.getDescription());
    }

    @Test
    void updateEventDetails_checksVenueConflictsOnUpdateExcludingItself() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        event.setVenue("Main Hall");
        event.setStartDatetime(LocalDateTime.of(2027, 9, 15, 9, 0));
        event.setEndDatetime(LocalDateTime.of(2027, 9, 15, 12, 0));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(0L);
        when(eventRepository.findOverlappingEvents(eq("Other Hall"), any(), any(),
                eq(EventStatus.CANCELLED), eq(10L))).thenReturn(List.of(new Event()));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setVenue("Other Hall");

        assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, request, organizerActor));
        // The event itself must be excluded from the conflict query.
        verify(eventRepository).findOverlappingEvents(eq("Other Hall"), any(), any(),
                eq(EventStatus.CANCELLED), eq(10L));
    }

    @Test
    void updateEventDetails_refusesCapacityBelowSeatsAlreadyTaken() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        event.setCapacity(50);
        event.setSeatsTaken(20);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(registrationRepository.countActiveByEventId(10L)).thenReturn(0L);

        EventUpdateRequest request = new EventUpdateRequest();
        request.setCapacity(10);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, request, organizerActor));
        assertTrue(ex.getMessage().contains("already taken"));
    }

    @Test
    void updateEventDetails_refusesToEditACancelledEvent() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.CANCELLED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setDescription("anything");

        assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, request, organizerActor));
    }

    @Test
    void requireEventManager_allowsTheOwnerAndAdminAndNobodyElse() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertDoesNotThrow(() -> eventService.requireEventManager(10L, organizerActor));
        assertDoesNotThrow(() -> eventService.requireEventManager(10L, adminActor));
        assertThrows(AccessDeniedException.class,
                () -> eventService.requireEventManager(10L, otherOrganizerActor));
        assertThrows(AccessDeniedException.class,
                () -> eventService.requireEventManager(10L, attendeeActor));
    }

    // =================================================================
    // Search
    // =================================================================

    @Test
    void search_withNothingFilledInSkipsTheFilteredQueryEntirely() {
        // The listing page hits this controller on every visit, filters or
        // not. Running a match-everything Specification for the plain
        // "show me the events" case would be pure overhead.
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of());

        eventService.searchPublishedEvents(new EventSearchCriteria());

        verify(eventRepository).findByStatus(EventStatus.PUBLISHED);
        verify(eventRepository, never()).findAll(any(org.springframework.data.jpa.domain.Specification.class));
    }

    @Test
    void search_treatsBlankInputsAsNoFilterAtAll() {
        // Every text input on the form submits, empty or not.
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setKeyword("  ");
        criteria.setCategory("");
        criteria.setVenue(null);
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of());

        eventService.searchPublishedEvents(criteria);

        verify(eventRepository).findByStatus(EventStatus.PUBLISHED);
    }

    @Test
    void search_withAnyRealFilterRunsTheFilteredQuery() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setAvailableOnly(true);   // not a string, and still a filter
        when(eventRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of());

        eventService.searchPublishedEvents(criteria);

        verify(eventRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class));
        verify(eventRepository, never()).findByStatus(any());
    }

    @Test
    void search_aNullCriteriaIsTreatedAsNoFilter() {
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of());

        assertDoesNotThrow(() -> eventService.searchPublishedEvents(null));

        verify(eventRepository).findByStatus(EventStatus.PUBLISHED);
    }

    @Test
    void publishedCategoriesComeFromTheDataNotAHardCodedList() {
        // A hard-coded list would offer categories with nothing behind
        // them, so the filter would return an empty page.
        when(eventRepository.findDistinctCategoriesByStatus(EventStatus.PUBLISHED))
                .thenReturn(List.of("Conference", "Workshop"));

        assertEquals(List.of("Conference", "Workshop"), eventService.getPublishedCategories());
    }

    @Test
    void featuredCategoriesCarryTheirRealCountsAndKeepTheRepositoryOrder() {
        // The homepage grid links each tile to /events?category=NAME, so a
        // name that is not in the data is a tile that lands the visitor on
        // an empty listing. These come from a GROUP BY over published rows
        // for exactly that reason, busiest first.
        when(eventRepository.countPublishedGroupedByCategory(EventStatus.PUBLISHED))
                .thenReturn(List.of(
                        new Object[]{"Business", 3L},
                        new Object[]{"Workshop", 2L},
                        new Object[]{"Arts", 1L}));

        List<EventService.FeaturedCategory> featured = eventService.getFeaturedCategories(5);

        assertEquals(3, featured.size());
        assertEquals("Business", featured.get(0).name());
        assertEquals(3L, featured.get(0).count());
        assertEquals("Arts", featured.get(2).name());
        assertEquals(1L, featured.get(2).count());
    }

    @Test
    void featuredCategoriesNeverReturnMoreThanTheGridCanHold() {
        // The layout has span patterns for one through five tiles. A sixth
        // would index past the end of that table and take the homepage
        // down, so the limit is enforced here rather than trusted to the
        // caller.
        when(eventRepository.countPublishedGroupedByCategory(EventStatus.PUBLISHED))
                .thenReturn(List.of(
                        new Object[]{"A", 9L}, new Object[]{"B", 8L},
                        new Object[]{"C", 7L}, new Object[]{"D", 6L},
                        new Object[]{"E", 5L}, new Object[]{"F", 4L},
                        new Object[]{"G", 3L}));

        assertEquals(5, eventService.getFeaturedCategories(5).size());
    }

    @Test
    void featuredCategoriesAreEmptyWhenThereIsNothingToFeature() {
        // An empty list is the homepage's signal to leave the section out
        // altogether rather than draw an empty grid.
        when(eventRepository.countPublishedGroupedByCategory(EventStatus.PUBLISHED))
                .thenReturn(List.of());

        assertTrue(eventService.getFeaturedCategories(5).isEmpty());
    }

    @Test
    void featuredCategoriesRefuseANonsenseLimitWithoutTouchingTheDatabase() {
        assertTrue(eventService.getFeaturedCategories(0).isEmpty());

        verify(eventRepository, never()).countPublishedGroupedByCategory(any());
    }

    // =================================================================
    // Field validation
    //
    // These rules are checked in the SERVICE, not only as Bean Validation
    // annotations on the DTO. The annotations fire on the JSON path where
    // @Valid is applied; the Thymeleaf forms bind through the same service,
    // so a rule that lives only on the DTO is a rule the web forms do not
    // have. Each case below is asserted against the service directly, which
    // is the layer both routes share.
    // =================================================================

    @Test
    void createEvent_refusesADescriptionLongerThanTheAllowedLength() {
        // description is a TEXT column, so nothing downstream would have
        // stopped this either.
        EventCreateRequest request = validRequest();
        request.setDescription("x".repeat(5001));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
        assertTrue(ex.getMessage().contains("Description"));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_acceptsADescriptionAtExactlyTheLimit() {
        EventCreateRequest request = validRequest();
        request.setDescription("x".repeat(5000));
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> eventService.createEvent(request, organizerActor));
    }

    @Test
    void createEvent_refusesSpecialInstructionsLongerThanTheAllowedLength() {
        EventCreateRequest request = validRequest();
        request.setSpecialInstructions("x".repeat(2001));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.createEvent(request, organizerActor));
        assertTrue(ex.getMessage().contains("Special instructions"));
    }

    @Test
    void updateEventDetails_appliesTheSameLimitsAsCreation() {
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        EventUpdateRequest request = new EventUpdateRequest();
        request.setDescription("x".repeat(5001));

        assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, request, organizerActor));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void updateEventDetails_refusesAnEventTypeExplicitlyClearedToNothing() {
        // event_type is NOT NULL with no default on the update path, so
        // without this check the failure would surface as a constraint
        // violation at flush time rather than a message the organizer can
        // act on.
        Event event = eventOwnedByOrganizer(10L, EventStatus.PUBLISHED);
        event.setEventType(null);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> eventService.updateEventDetails(10L, new EventUpdateRequest(), organizerActor));
        assertTrue(ex.getMessage().contains("Event type is required"));
    }

    // =================================================================
    // helpers
    // =================================================================

    private EventCreateRequest validRequest() {
        EventCreateRequest request = new EventCreateRequest();
        request.setName("Cloud Computing Workshop");
        request.setEventType(EventType.PUBLIC);
        request.setCapacity(50);
        request.setFee(BigDecimal.ZERO);
        return request;
    }

    private Registration registrationOn(Event event, RegistrationStatus status) {
        Registration registration = new Registration();
        registration.setEvent(event);
        registration.setUser(organizer);
        registration.setStatus(status);
        return registration;
    }

    // =================================================================
    // Public visibility
    //
    // GET /events/{id} and GET /api/events/{id} are permitAll, and the
    // lookup behind them used to be getEventById - which loaded any event
    // in any state. An unapproved draft, with its venue, pricing and
    // special instructions, was one guessed id away from anybody.
    // =================================================================

    @Test
    void getEventForViewer_hidesADraftFromAnAnonymousVisitor() {
        Event draft = eventOwnedByOrganizer(40L, EventStatus.DRAFT);
        when(eventRepository.findById(40L)).thenReturn(Optional.of(draft));

        assertThrows(ResourceNotFoundException.class,
                () -> eventService.getEventForViewer(40L, null),
                "an unapproved draft must not be readable through the public route");
    }

    @Test
    void getEventForViewer_hidesAnEventAwaitingApprovalFromAnAttendee() {
        Event pending = eventOwnedByOrganizer(41L, EventStatus.PENDING_APPROVAL);
        when(eventRepository.findById(41L)).thenReturn(Optional.of(pending));

        assertThrows(ResourceNotFoundException.class,
                () -> eventService.getEventForViewer(41L, attendeeActor));
    }

    @Test
    void getEventForViewer_showsAPublishedEventToAnyone() {
        Event published = eventOwnedByOrganizer(42L, EventStatus.PUBLISHED);
        when(eventRepository.findById(42L)).thenReturn(Optional.of(published));

        assertEquals(42L, eventService.getEventForViewer(42L, null).getId());
    }

    @Test
    void getEventForViewer_letsTheOwningOrganizerPreviewTheirOwnDraft() {
        // The fix is a visibility rule, not a blanket "published only" -
        // previewing your own unpublished event has to keep working.
        Event draft = eventOwnedByOrganizer(43L, EventStatus.DRAFT);
        when(eventRepository.findById(43L)).thenReturn(Optional.of(draft));

        assertEquals(43L, eventService.getEventForViewer(43L, organizerActor).getId());
    }

    @Test
    void getEventForViewer_hidesADraftFromADifferentOrganizer() {
        Event draft = eventOwnedByOrganizer(44L, EventStatus.DRAFT);
        when(eventRepository.findById(44L)).thenReturn(Optional.of(draft));

        assertThrows(ResourceNotFoundException.class,
                () -> eventService.getEventForViewer(44L, otherOrganizerActor));
    }

    @Test
    void getEventForViewer_reportsAHiddenEventAsMissingNotForbidden() {
        // A 403 on a draft still confirms the event exists, which is enough
        // to walk the id range and count what an organization is planning.
        Event draft = eventOwnedByOrganizer(45L, EventStatus.DRAFT);
        when(eventRepository.findById(45L)).thenReturn(Optional.of(draft));

        ResourceNotFoundException hidden = assertThrows(ResourceNotFoundException.class,
                () -> eventService.getEventForViewer(45L, null));
        when(eventRepository.findById(46L)).thenReturn(Optional.empty());
        ResourceNotFoundException absent = assertThrows(ResourceNotFoundException.class,
                () -> eventService.getEventForViewer(46L, null));

        // Same exception type for "hidden" and "does not exist", so the two
        // cannot be told apart from outside.
        assertEquals(hidden.getClass(), absent.getClass());
    }

    /** A draft with everything submitForApproval insists on. */
    private Event submittableDraft(Long id) {
        Event draft = eventOwnedByOrganizer(id, EventStatus.DRAFT);
        draft.setStartDatetime(LocalDateTime.now().plusDays(30));
        draft.setEndDatetime(LocalDateTime.now().plusDays(30).plusHours(4));
        draft.setRegistrationDeadline(LocalDateTime.now().plusDays(28));
        return draft;
    }

    private Event eventOwnedByOrganizer(Long id, EventStatus status) {
        Event event = new Event();
        event.setId(id);
        event.setOrganizer(organizer);
        event.setName("Existing Event");
        event.setVenue("Main Hall");
        event.setCapacity(50);
        event.setSeatsTaken(0);
        event.setFee(BigDecimal.ZERO);
        event.setStatus(status);
        return event;
    }
}
