package com.sliit.eventsphere.events;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.entity.User;
import org.springframework.context.ApplicationEvent;

import java.util.List;

/**
 * OBSERVER PATTERN (Subject side)
 * ---------------------------------------------------------------------
 * Published by EventServiceImpl.cancelEvent(). Two independent modules
 * react to this without EventServiceImpl knowing either exists:
 *   - NotificationEventListener  -> notifies everyone who held a place
 *   - RefundTriggerListener      -> creates refunds (Payment module)
 *
 * Why the event carries the affected registrations rather than just the
 * event id: cancelling an event now also cancels the registrations on it,
 * so a listener that re-queried for CONFIRMED rows would find none and
 * silently notify and refund nobody. The subject therefore states *who was
 * affected* as part of the announcement — which is also the more honest
 * shape for a domain event: it reports what happened rather than leaving a
 * hint to go and look something up.
 *
 * Each entry also records the status the registration held at the moment
 * of cancellation. Carrying that explicitly (instead of reading it back
 * off the entity) keeps the payload true no matter what order the service
 * does its work in, and would survive the listeners later being made
 * asynchronous.
 */
public class EventCancelledEvent extends ApplicationEvent {

    private final Event event;
    private final List<AffectedRegistration> affectedRegistrations;

    public EventCancelledEvent(Object source, Event event,
                                List<AffectedRegistration> affectedRegistrations) {
        super(source);
        this.event = event;
        this.affectedRegistrations = List.copyOf(affectedRegistrations);
    }

    public Event getEvent() {
        return event;
    }

    /** Everyone who held a seat or a waitlist place when the event was cancelled. */
    public List<AffectedRegistration> getAffectedRegistrations() {
        return affectedRegistrations;
    }

    /**
     * One registration as it stood immediately before the cancellation
     * touched it.
     */
    public record AffectedRegistration(Registration registration,
                                        RegistrationStatus statusAtCancellation) {

        public static AffectedRegistration of(Registration registration) {
            return new AffectedRegistration(registration, registration.getStatus());
        }

        public User user() {
            return registration.getUser();
        }

        /**
         * True when this person actually held a seat, not a waitlist place.
         *
         * PENDING_PAYMENT counts, and used not to. A held seat is genuinely
         * unavailable to anybody else - that is what
         * {@link com.sliit.eventsphere.entity.Registration#holdsASeat()}
         * means everywhere seats are counted - so answering "no" here made
         * this payload disagree with the entity that produced it. The
         * visible symptom was an attendee halfway through checkout being
         * told they had been "removed from the waitlist".
         */
        public boolean heldASeat() {
            return statusAtCancellation == RegistrationStatus.CONFIRMED
                    || statusAtCancellation == RegistrationStatus.PENDING_PAYMENT;
        }

        /**
         * True when the seat was reserved but not yet paid for.
         *
         * Separate from {@link #heldASeat()} because the two need different
         * things said to them: a confirmed attendee loses a ticket they may
         * have paid for, while somebody mid-checkout loses a reservation and
         * owes nothing. Collapsing both into one boolean is what produced
         * the wrong message in the first place.
         */
        public boolean wasAwaitingPayment() {
            return statusAtCancellation == RegistrationStatus.PENDING_PAYMENT;
        }
    }
}
