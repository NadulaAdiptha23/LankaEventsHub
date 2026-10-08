package com.sliit.eventsphere.events;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;
import org.springframework.context.ApplicationEvent;

/**
 * OBSERVER PATTERN (Subject side)
 * ---------------------------------------------------------------------
 * Published when a SINGLE registration is cancelled - either by the
 * attendee themselves or by an Administrator from the bookings screen.
 * Its sibling, {@link EventCancelledEvent}, covers the other case: a
 * whole event being called off, taking every registration with it.
 *
 * Why an event rather than a direct call. Registration & Booking
 * (Function 2) owns cancelling. Payment & Ticketing (Function 3) owns
 * giving the money back. Those are two different function owners
 * reacting to the same trigger, which is precisely the boundary the
 * Observer pattern is used at in this codebase - see the design note at
 * the top of RegistrationServiceImpl. Registration announces what
 * happened; it does not need to know that refunds exist.
 *
 * The status the registration held BEFORE cancellation is carried
 * explicitly, because by the time a listener runs the entity already
 * reads CANCELLED, and only a registration that actually held a seat can
 * ever have been paid for. Reading it back off the entity would give the
 * wrong answer; re-querying would give none at all.
 */
public class RegistrationCancelledEvent extends ApplicationEvent {

    private final Event event;
    private final Registration registration;
    private final RegistrationStatus previousStatus;
    private final boolean cancelledByStaff;

    public RegistrationCancelledEvent(Object source,
                                       Event event,
                                       Registration registration,
                                       RegistrationStatus previousStatus,
                                       boolean cancelledByStaff) {
        super(source);
        this.event = event;
        this.registration = registration;
        this.previousStatus = previousStatus;
        this.cancelledByStaff = cancelledByStaff;
    }

    public Event getEvent() {
        return event;
    }

    public Registration getRegistration() {
        return registration;
    }

    public RegistrationStatus getPreviousStatus() {
        return previousStatus;
    }

    /**
     * True when an Administrator cancelled on the attendee's behalf,
     * false when the attendee did it themselves. Listeners use this to
     * decide whether the attendee needs telling - somebody who pressed
     * the cancel button already knows.
     */
    public boolean isCancelledByStaff() {
        return cancelledByStaff;
    }

    /**
     * Only a seat that was actually held can have been paid for: payment
     * is refused for anything other than a CONFIRMED registration (see
     * PaymentServiceImpl), so a waitlist place never has a payment behind
     * it and never produces a refund.
     */
    public boolean heldASeat() {
        return previousStatus == RegistrationStatus.CONFIRMED;
    }
}
