package com.sliit.eventsphere.listener;

import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.events.RegistrationCancelledEvent;
import com.sliit.eventsphere.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * OBSERVER PATTERN (Concrete Observer #2)
 * ---------------------------------------------------------------------
 * A second, completely independent subscriber to the same
 * EventCancelledEvent used by NotificationEventListener. This is the
 * clearest demonstration of why Observer is the right pattern here:
 * EventServiceImpl fires one event, and both Notifications and Payment &
 * Ticketing react to it without knowing about each other or about
 * EventServiceImpl's internals.
 *
 * Like the notification listener, this works from the registrations
 * carried in the event rather than re-reading them by status.
 */
@Component
public class RefundTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(RefundTriggerListener.class);

    private final RefundService refundService;

    public RefundTriggerListener(RefundService refundService) {
        this.refundService = refundService;
    }

    @EventListener
    public void onEventCancelled(EventCancelledEvent cancelledEvent) {
        var event = cancelledEvent.getEvent();
        int created = refundService.createRefundsForCancelledEvent(
                event, cancelledEvent.getAffectedRegistrations());
        log.info("Event {} cancelled - {} refund(s) created", event.getId(), created);
    }

    /**
     * The other way a paid place stops existing: one attendee cancels one
     * booking, or an Administrator cancels it for them. The event is not
     * called off and nobody else is affected.
     *
     * Whether money goes back depends on which of those two it was, and
     * that decision is not made here - this listener passes on who did it
     * and lets Payment &amp; Ticketing apply its own policy. A listener
     * that decided would be a second place where the refund rule lives.
     */
    @EventListener
    public void onRegistrationCancelled(RegistrationCancelledEvent cancelledEvent) {
        boolean created = refundService.createRefundForCancelledRegistration(
                cancelledEvent.getRegistration(),
                cancelledEvent.getPreviousStatus(),
                cancelledEvent.isCancelledByStaff());
        if (created) {
            log.info("Registration {} on event {} cancelled - refund created",
                    cancelledEvent.getRegistration().getId(),
                    cancelledEvent.getEvent().getId());
        }
    }
}
