package com.sliit.eventsphere.listener;

import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.events.EventPublishedEvent;
import com.sliit.eventsphere.service.NotificationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OBSERVER PATTERN (Concrete Observer)
 * ---------------------------------------------------------------------
 * Subscribes to domain events published by EventServiceImpl. Neither
 * class knows the other exists - Spring's ApplicationEventPublisher
 * connects them at runtime via @EventListener.
 *
 * Why the pattern here: Event Creation & Management and Notifications are
 * separate concerns that must stay independently testable. Without it,
 * EventService would need a direct dependency on NotificationService.
 *
 * This listener reads the affected registrations from the event payload
 * rather than re-querying by status, because cancelling an event also
 * cancels its registrations - a status query would find nothing.
 */
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onEventPublished(EventPublishedEvent publishedEvent) {
        var event = publishedEvent.getEvent();
        notificationService.notify(
                event.getOrganizer(),
                NotificationType.EVENT_CHANGE,
                NotificationChannel.IN_APP,
                "Your event \"" + event.getName() + "\" has been approved and is now live."
        );
    }

    @EventListener
    public void onEventCancelled(EventCancelledEvent cancelledEvent) {
        var event = cancelledEvent.getEvent();

        for (EventCancelledEvent.AffectedRegistration affected : cancelledEvent.getAffectedRegistrations()) {
            notificationService.notify(
                    affected.user(),
                    NotificationType.EVENT_CANCELLATION,
                    NotificationChannel.IN_APP,
                    // Deliberately does not name who cancelled it.
                    // cancelEvent() is open to the owning organizer AND to
                    // an Administrator, so "by the organizer" was wrong
                    // half the time - and the attendee needs to know their
                    // place is gone, not whose decision it was.
                    "\"" + event.getName() + "\" has been cancelled. "
                            + outcomeFor(affected)
            );
        }
    }

    /**
     * What this particular person lost, in their own terms.
     *
     * Three outcomes, not two. The seat-or-waitlist split was written
     * before a paid registration could hold a seat without being confirmed,
     * and it told everybody mid-checkout that they had been "removed from
     * the waitlist" - which was both wrong and confusing, since they had a
     * seat and a payment screen open at the time.
     */
    private String outcomeFor(EventCancelledEvent.AffectedRegistration affected) {
        if (affected.wasAwaitingPayment()) {
            // Nothing was ever charged - PaymentServiceImpl only completes a
            // payment by moving the registration to CONFIRMED - so promising
            // a refund here would be a promise of money that never moved.
            return "The seat you were holding has been released, and there is nothing left to pay.";
        }
        if (affected.heldASeat()) {
            return "Your registration has been cancelled and your ticket is no longer "
                    + "valid for entry. If you paid, a full refund has been queued.";
        }
        return "You have been removed from the waitlist.";
    }
}
