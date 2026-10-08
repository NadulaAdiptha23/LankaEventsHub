package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.EventSessionRequest;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.EventSessionRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventSessionServiceImplTest {

    @Mock private EventRepository eventRepository;
    @Mock private EventSessionRepository eventSessionRepository;
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(org.mockito.Mockito.mock(
            com.sliit.eventsphere.repository.RegistrationRepository.class));

    @InjectMocks private EventSessionServiceImpl sessionService;

    private Event event;
    private Event otherEvent;
    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser attendeeActor;

    @BeforeEach
    void setUp() {
        User organizer = new User("Kasun", "o@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        User someoneElse = new User("Ishara", "o2@test.com", "hash", UserRole.ORGANIZER);
        someoneElse.setId(77L);

        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = ActingUser.of(someoneElse);
        attendeeActor = new ActingUser(2L, UserRole.ATTENDEE);

        event = new Event();
        event.setId(100L);
        event.setOrganizer(organizer);
        event.setStartDatetime(LocalDateTime.of(2027, 3, 15, 9, 0));
        event.setEndDatetime(LocalDateTime.of(2027, 3, 15, 17, 0));

        otherEvent = new Event();
        otherEvent.setId(200L);
        otherEvent.setOrganizer(someoneElse);

        lenient().when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        lenient().when(eventRepository.findById(200L)).thenReturn(Optional.of(otherEvent));
        lenient().when(eventSessionRepository.save(any(EventSession.class))).thenAnswer(inv -> {
            EventSession s = inv.getArgument(0);
            s.setId(7L);
            return s;
        });
    }

    @Test
    void addSession_refusesAnOrganizerWhoDoesNotOwnTheEvent() {
        assertThrows(AccessDeniedException.class,
                () -> sessionService.addSession(100L, request("Talk", null, null), otherOrganizerActor));
        verify(eventSessionRepository, never()).save(any());
    }

    @Test
    void addSession_refusesAnAttendee() {
        assertThrows(AccessDeniedException.class,
                () -> sessionService.addSession(100L, request("Talk", null, null), attendeeActor));
    }

    @Test
    void addSession_requiresATitle() {
        assertThrows(InvalidRequestException.class,
                () -> sessionService.addSession(100L, request("   ", null, null), organizerActor));
    }

    @Test
    void addSession_requiresTheEndAfterTheStart() {
        assertThrows(InvalidRequestException.class,
                () -> sessionService.addSession(100L,
                        request("Talk", LocalDateTime.of(2027, 3, 15, 14, 0),
                                LocalDateTime.of(2027, 3, 15, 12, 0)), organizerActor));
    }

    @Test
    void addSession_refusesASessionOutsideTheEventWindow() {
        assertThrows(InvalidRequestException.class,
                () -> sessionService.addSession(100L,
                        request("Too early", LocalDateTime.of(2027, 3, 15, 7, 0),
                                LocalDateTime.of(2027, 3, 15, 8, 0)), organizerActor));

        assertThrows(InvalidRequestException.class,
                () -> sessionService.addSession(100L,
                        request("Too late", LocalDateTime.of(2027, 3, 15, 16, 0),
                                LocalDateTime.of(2027, 3, 15, 20, 0)), organizerActor));
    }

    @Test
    void addSession_succeedsInsideTheEventWindow() {
        assertEquals("Keynote", sessionService.addSession(100L,
                request("Keynote", LocalDateTime.of(2027, 3, 15, 9, 30),
                        LocalDateTime.of(2027, 3, 15, 10, 30)), organizerActor).getTitle());
    }

    @Test
    void removeSession_refusesToDeleteASessionThroughAnotherEvent() {
        EventSession session = new EventSession();
        session.setId(7L);
        session.setEvent(otherEvent);
        when(eventSessionRepository.findById(7L)).thenReturn(Optional.of(session));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> sessionService.removeSession(100L, 7L, organizerActor));
        assertTrue(ex.getMessage().contains("does not belong"));
        verify(eventSessionRepository, never()).delete(any());
    }

    @Test
    void removeSession_deletesASessionOnTheOrganizersOwnEvent() {
        EventSession session = new EventSession();
        session.setId(7L);
        session.setEvent(event);
        when(eventSessionRepository.findById(7L)).thenReturn(Optional.of(session));

        sessionService.removeSession(100L, 7L, organizerActor);

        verify(eventSessionRepository).delete(session);
    }

    // =================================================================
    // The agenda follows the event's own visibility
    //
    // GET /api/events/{id}/sessions is permitAll, and this read used to
    // take an event id and nothing else - which made it the second way to
    // read an unapproved draft: the titles, times and speakers of a
    // programme nobody had approved yet.
    // =================================================================

    @Test
    void getSessions_hidesTheAgendaOfADraftFromAnAnonymousVisitor() {
        event.setStatus(EventStatus.DRAFT);

        assertThrows(com.sliit.eventsphere.exception.ResourceNotFoundException.class,
                () -> sessionService.getSessionsForEvent(100L, null),
                "an unapproved programme must not be readable by a stranger");
        verify(eventSessionRepository, never()).findByEventId(100L);
    }

    @Test
    void getSessions_hidesTheAgendaOfADraftFromAnAttendee() {
        event.setStatus(EventStatus.DRAFT);

        assertThrows(com.sliit.eventsphere.exception.ResourceNotFoundException.class,
                () -> sessionService.getSessionsForEvent(100L, attendeeActor));
    }

    @Test
    void getSessions_showsTheAgendaOfAPublishedEventToAnyone() {
        event.setStatus(EventStatus.PUBLISHED);
        when(eventSessionRepository.findByEventId(100L)).thenReturn(java.util.List.of());

        assertDoesNotThrow(() -> sessionService.getSessionsForEvent(100L, null));
        verify(eventSessionRepository).findByEventId(100L);
    }

    @Test
    void getSessions_letsTheOwningOrganizerSeeTheirOwnDraftAgenda() {
        event.setStatus(EventStatus.DRAFT);
        when(eventSessionRepository.findByEventId(100L)).thenReturn(java.util.List.of());

        assertDoesNotThrow(() -> sessionService.getSessionsForEvent(100L, organizerActor));
    }

    @Test
    void getSessions_hidesAnotherOrganizersDraftAgenda() {
        otherEvent.setStatus(EventStatus.DRAFT);

        assertThrows(com.sliit.eventsphere.exception.ResourceNotFoundException.class,
                () -> sessionService.getSessionsForEvent(200L, organizerActor));
    }

    private EventSessionRequest request(String title, LocalDateTime start, LocalDateTime end) {
        EventSessionRequest request = new EventSessionRequest();
        request.setTitle(title);
        request.setStartTime(start);
        request.setEndTime(end);
        return request;
    }
}
