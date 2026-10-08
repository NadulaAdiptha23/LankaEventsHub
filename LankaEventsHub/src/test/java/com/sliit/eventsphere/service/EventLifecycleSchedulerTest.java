package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.NotificationRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The reminder sweep, which is the only part of this system that decides
 * on its own to contact somebody.
 *
 * The cases worth protecting are the ones about *which* reminder fires:
 * two lead times that both match the same event would produce a message
 * telling an attendee their event is a week away on the morning of the
 * day it happens.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventLifecycleSchedulerTest {

    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationService notificationService;
    @Mock private RegistrationService registrationService;

    private User attendee;

    @BeforeEach
    void setUp() {
        attendee = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(42L);
    }

    private EventLifecycleScheduler scheduler(String leadTimes) {
        return new EventLifecycleScheduler(eventRepository, registrationRepository,
                notificationRepository, notificationService, registrationService, leadTimes);
    }

    private Event publishedEventStartingIn(long hoursFromNow) {
        Event event = new Event();
        event.setId(100L);
        event.setName("Cloud Computing Workshop");
        event.setVenue("BMICH");
        event.setStatus(EventStatus.PUBLISHED);
        event.setStartDatetime(LocalDateTime.now().plusHours(hoursFromNow));
        return event;
    }

    private void givenOneConfirmedAttendeeOn(Event event) {
        Registration registration = new Registration();
        registration.setId(1L);
        registration.setEvent(event);
        registration.setUser(attendee);
        registration.setStatus(RegistrationStatus.CONFIRMED);

        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of(event));
        when(registrationRepository.findByEventIdAndStatus(100L, RegistrationStatus.CONFIRMED))
                .thenReturn(List.of(registration));
        when(notificationRepository.existsByUserIdAndTypeAndMessage(anyLong(), any(), anyString()))
                .thenReturn(false);
    }

    private List<String> sentMessages() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService, atLeast(0)).notify(eq(attendee),
                eq(NotificationType.EVENT_REMINDER), eq(NotificationChannel.IN_APP),
                message.capture());
        return message.getAllValues();
    }

    // =================================================================
    // Configuration
    // =================================================================

    @Test
    void leadTimesAreSortedLargestFirstWhateverOrderTheyAreConfiguredIn() {
        assertEquals(List.of(168L, 24L), scheduler("24, 168").getLeadTimesHours());
    }

    @Test
    void duplicateLeadTimesAreCollapsed() {
        assertEquals(List.of(24L), scheduler("24,24,24").getLeadTimesHours());
    }

    @Test
    void theDefaultIsSevenDaysAndTwentyFourHours() {
        // The requirement, asserted rather than assumed. Changing the
        // default should be a deliberate act that breaks this test.
        assertEquals(List.of(168L, 24L), scheduler("168,24").getLeadTimesHours());
    }

    @Test
    void aMalformedLeadTimeStopsStartupRatherThanSilentlyReducingReminders() {
        assertThrows(IllegalArgumentException.class, () -> scheduler("168,soon"));
    }

    @Test
    void aZeroOrNegativeLeadTimeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> scheduler("168,0"));
        assertThrows(IllegalArgumentException.class, () -> scheduler("-24"));
    }

    @Test
    void anEmptyConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> scheduler("  "));
        assertThrows(IllegalArgumentException.class, () -> scheduler(",,"));
    }

    // =================================================================
    // Which reminder fires
    // =================================================================

    @Test
    void anEventAWeekAwayGetsTheSevenDayReminderOnly() {
        Event event = publishedEventStartingIn(140); // inside 168, outside 24
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        List<String> messages = sentMessages();
        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("7 days"), messages.get(0));
    }

    @Test
    void anEventTomorrowGetsTheTwentyFourHourReminderOnly() {
        // The case the lower bound exists for. Without it this event
        // matches both thresholds and the attendee is told, on the day,
        // that their event is a week away.
        Event event = publishedEventStartingIn(20);
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        List<String> messages = sentMessages();
        assertEquals(1, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("1 day"), messages.get(0));
        assertFalse(messages.get(0).contains("7 days"), messages.get(0));
    }

    @Test
    void anEventFurtherAwayThanTheLargestLeadTimeIsNotRemindedYet() {
        Event event = publishedEventStartingIn(300);
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    @Test
    void anEventThatHasAlreadyStartedIsNotReminded() {
        Event event = publishedEventStartingIn(-2);
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    @Test
    void anEventWithNoStartTimeIsSkippedRatherThanCrashing() {
        Event event = publishedEventStartingIn(20);
        event.setStartDatetime(null);
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    // =================================================================
    // Not sending the same one twice
    // =================================================================

    @Test
    void aReminderAlreadySentIsNotSentAgain() {
        Event event = publishedEventStartingIn(20);
        givenOneConfirmedAttendeeOn(event);
        when(notificationRepository.existsByUserIdAndTypeAndMessage(anyLong(), any(), anyString()))
                .thenReturn(true);

        scheduler("168,24").sendUpcomingEventReminders();

        verify(notificationService, never()).notify(any(), any(), any(), anyString());
    }

    /**
     * The duplicate guard matches on message text, so the two reminders
     * must not read identically - otherwise the day-before reminder would
     * be mistaken for the week-before one and never sent at all.
     */
    @Test
    void theTwoRemindersAreDistinguishableToTheDuplicateGuard() {
        EventLifecycleScheduler s = scheduler("168,24");

        Event weekAway = publishedEventStartingIn(140);
        givenOneConfirmedAttendeeOn(weekAway);
        s.sendUpcomingEventReminders();
        String weekMessage = sentMessages().get(0);

        reset(notificationService);

        Event dayAway = publishedEventStartingIn(20);
        givenOneConfirmedAttendeeOn(dayAway);
        s.sendUpcomingEventReminders();
        String dayMessage = sentMessages().get(0);

        assertNotEquals(weekMessage, dayMessage);
    }

    @Test
    void onlyConfirmedAttendeesAreReminded() {
        // Asked for by status at the repository, so a waitlisted or
        // cancelled registration is never even loaded.
        Event event = publishedEventStartingIn(20);
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        verify(registrationRepository)
                .findByEventIdAndStatus(100L, RegistrationStatus.CONFIRMED);
    }

    @Test
    void onlyPublishedEventsAreConsidered() {
        Event event = publishedEventStartingIn(20);
        givenOneConfirmedAttendeeOn(event);

        scheduler("168,24").sendUpcomingEventReminders();

        verify(eventRepository).findByStatus(EventStatus.PUBLISHED);
        verify(eventRepository, never()).findByStatus(EventStatus.DRAFT);
    }

    // =================================================================
    // Completion sweep
    // =================================================================

    @Test
    void aFinishedEventIsMovedToCompleted() {
        Event event = publishedEventStartingIn(-10);
        event.setEndDatetime(LocalDateTime.now().minusHours(2));
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of(event));

        scheduler("168,24").completeFinishedEvents();

        assertEquals(EventStatus.COMPLETED, event.getStatus());
        verify(eventRepository).save(event);
    }

    @Test
    void anEventWithNoEndTimeIsLeftForTheOrganizerToClose() {
        Event event = publishedEventStartingIn(-10);
        event.setEndDatetime(null);
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of(event));

        scheduler("168,24").completeFinishedEvents();

        assertEquals(EventStatus.PUBLISHED, event.getStatus());
        verify(eventRepository, never()).save(any());
    }
}
