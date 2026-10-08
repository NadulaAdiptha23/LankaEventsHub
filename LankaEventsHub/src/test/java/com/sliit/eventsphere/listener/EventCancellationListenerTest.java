package com.sliit.eventsphere.listener;

import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.events.EventPublishedEvent;
import com.sliit.eventsphere.service.NotificationService;
import com.sliit.eventsphere.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The Observer half of event cancellation.
 *
 * EventServiceImpl publishes one EventCancelledEvent and knows nothing
 * about who reacts; these tests check that both independent subscribers
 * do their own job when that event arrives. Together with
 * EventServiceImplTest's "publishes exactly once" and "refuses to cancel
 * twice" cases, that covers the whole chain without coupling the two
 * sides in a single test.
 *
 * Note that neither listener touches a repository any more. They read the
 * affected registrations off the event itself, so these tests build the
 * payload the way the service does - including the case that used to be
 * broken, where the registrations have already been flipped to CANCELLED
 * by the time a listener runs.
 */
@ExtendWith(MockitoExtension.class)
class EventCancellationListenerTest {

    @Mock private NotificationService notificationService;
    @Mock private RefundService refundService;

    @InjectMocks private NotificationEventListener notificationListener;

    private Event cancelledEvent;
    private User organizer;
    private User attendeeOne;
    private User attendeeTwo;

    @BeforeEach
    void setUp() {
        organizer = new User("Kasun", "o@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        attendeeOne = new User("Nadeesha", "a1@test.com", "hash", UserRole.ATTENDEE);
        attendeeOne.setId(2L);
        attendeeTwo = new User("Ravindu", "a2@test.com", "hash", UserRole.ATTENDEE);
        attendeeTwo.setId(3L);

        cancelledEvent = new Event();
        cancelledEvent.setId(100L);
        cancelledEvent.setName("Photography Masterclass");
        cancelledEvent.setOrganizer(organizer);
        cancelledEvent.setStatus(EventStatus.CANCELLED);
    }

    @Test
    void cancellationNotifiesEveryoneWhoHeldAPlace() {
        var payload = affected(confirmed(attendeeOne), confirmed(attendeeTwo));

        notificationListener.onEventCancelled(cancellation(payload));

        verify(notificationService).notify(eq(attendeeOne),
                eq(NotificationType.EVENT_CANCELLATION), any(), any());
        verify(notificationService).notify(eq(attendeeTwo),
                eq(NotificationType.EVENT_CANCELLATION), any(), any());
    }

    @Test
    void cancellationStillNotifiesAfterTheRegistrationsHaveBeenCancelled() {
        // The regression: the service cancels the registrations before the
        // listener runs. A listener that re-queried for CONFIRMED rows would
        // find none and notify nobody, while appearing to work.
        Registration registration = confirmed(attendeeOne);
        var payload = affected(registration);
        registration.setStatus(RegistrationStatus.CANCELLED);

        notificationListener.onEventCancelled(cancellation(payload));

        verify(notificationService).notify(eq(attendeeOne),
                eq(NotificationType.EVENT_CANCELLATION), any(), any());
    }

    @Test
    void cancellationNotifiesNobodyWhenNobodyHeldAPlace() {
        notificationListener.onEventCancelled(cancellation(List.of()));

        verifyNoInteractions(notificationService);
    }

    @Test
    void aWaitlistedAttendeeIsToldSomethingDifferentFromATicketHolder() {
        Registration waitlisted = confirmed(attendeeTwo);
        waitlisted.setStatus(RegistrationStatus.WAITLISTED);
        var payload = affected(confirmed(attendeeOne), waitlisted);

        notificationListener.onEventCancelled(cancellation(payload));

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(2))
                .notify(any(), eq(NotificationType.EVENT_CANCELLATION), any(), messages.capture());

        String toTicketHolder = messages.getAllValues().get(0);
        String toWaitlisted = messages.getAllValues().get(1);
        assertTrue(toTicketHolder.contains("ticket"),
                "someone who held a seat must be told their ticket is void");
        assertTrue(toWaitlisted.contains("waitlist"),
                "someone on the waitlist must not be told about a refund they were never owed");
        assertFalse(toWaitlisted.contains("refund"));
    }

    @Test
    void someoneMidCheckoutIsNotToldTheyWereOnTheWaitlist() {
        // The regression this case exists for. heldASeat() asked only about
        // CONFIRMED, so a PENDING_PAYMENT registration - which IS holding
        // one of the event's seats, with a payment screen open - fell to the
        // else branch and was told "You have been removed from the
        // waitlist." Wrong, and baffling to receive.
        Registration holding = confirmed(attendeeOne);
        holding.setStatus(RegistrationStatus.PENDING_PAYMENT);

        notificationListener.onEventCancelled(cancellation(affected(holding)));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(attendeeOne),
                eq(NotificationType.EVENT_CANCELLATION), any(), message.capture());

        String text = message.getValue();
        assertFalse(text.contains("waitlist"),
                "somebody holding a seat must not be told they were on the waitlist");
        assertTrue(text.contains("released"),
                "they should be told the seat they were holding is gone");
        assertFalse(text.contains("refund"),
                "nothing was ever charged, so no refund may be promised");
    }

    @Test
    void aHeldSeatCountsAsASeatInTheCancellationPayload() {
        // The payload has to agree with Registration.holdsASeat(), which is
        // what every seat count in the system uses. It did not.
        Registration holding = confirmed(attendeeOne);
        holding.setStatus(RegistrationStatus.PENDING_PAYMENT);
        var entry = EventCancelledEvent.AffectedRegistration.of(holding);

        assertTrue(entry.heldASeat());
        assertTrue(entry.wasAwaitingPayment());
    }

    @Test
    void aConfirmedAttendeeIsNotMistakenForOneMidCheckout() {
        var entry = EventCancelledEvent.AffectedRegistration.of(confirmed(attendeeOne));

        assertTrue(entry.heldASeat());
        assertFalse(entry.wasAwaitingPayment());
    }

    @Test
    void aWaitlistedPlaceIsStillNotASeat() {
        Registration waitlisted = confirmed(attendeeTwo);
        waitlisted.setStatus(RegistrationStatus.WAITLISTED);
        var entry = EventCancelledEvent.AffectedRegistration.of(waitlisted);

        assertFalse(entry.heldASeat());
        assertFalse(entry.wasAwaitingPayment());
    }

    @Test
    void publishingAnEventNotifiesItsOrganizer() {
        notificationListener.onEventPublished(new EventPublishedEvent(this, cancelledEvent));

        verify(notificationService).notify(eq(organizer),
                eq(NotificationType.EVENT_CHANGE), any(), any());
    }

    @Test
    void cancellationTriggersRefundCreationIndependently() {
        RefundTriggerListener refundListener = new RefundTriggerListener(refundService);
        var payload = affected(confirmed(attendeeOne));

        refundListener.onEventCancelled(cancellation(payload));

        // The refund side is handed the same payload - it does not go
        // looking for the registrations itself.
        verify(refundService).createRefundsForCancelledEvent(cancelledEvent, payload);
        // ...and it must not depend on the notification side at all.
        verifyNoInteractions(notificationService);
    }

    // =================================================================
    // helpers
    // =================================================================

    private EventCancelledEvent cancellation(List<EventCancelledEvent.AffectedRegistration> affected) {
        return new EventCancelledEvent(this, cancelledEvent, affected);
    }

    private List<EventCancelledEvent.AffectedRegistration> affected(Registration... registrations) {
        return Arrays.stream(registrations)
                .map(EventCancelledEvent.AffectedRegistration::of)
                .toList();
    }

    private Registration confirmed(User user) {
        Registration registration = new Registration();
        registration.setEvent(cancelledEvent);
        registration.setUser(user);
        registration.setStatus(RegistrationStatus.CONFIRMED);
        return registration;
    }
}
