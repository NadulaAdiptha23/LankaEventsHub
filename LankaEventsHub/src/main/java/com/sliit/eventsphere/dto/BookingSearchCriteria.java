package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.PaymentStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;

/**
 * The filter form on the admin bookings screen.
 *
 * Every field is optional. A blank filter contributes no SQL at all -
 * see RegistrationSpecifications - rather than being turned into an
 * `is null or` branch that the database still has to evaluate.
 */
public class BookingSearchCriteria {

    /** Free text matched against the attendee's name and e-mail. */
    private String attendee;

    /** Free text matched against the event name. */
    private String event;

    private RegistrationStatus status;

    /**
     * Filters on the state of the payment attached to the booking.
     * Deliberately NOT part of the Registration entity: it lives on a
     * different table, so the specification joins to it.
     */
    private PaymentStatus paymentStatus;

    /** True to show only bookings that have no payment row at all. */
    private boolean unpaidOnly;

    public String getAttendee() { return attendee; }
    public void setAttendee(String attendee) { this.attendee = attendee; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus status) { this.status = status; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public boolean isUnpaidOnly() { return unpaidOnly; }
    public void setUnpaidOnly(boolean unpaidOnly) { this.unpaidOnly = unpaidOnly; }

    /** True when the operator has not narrowed anything down. */
    public boolean isEmpty() {
        return isBlank(attendee) && isBlank(event)
                && status == null && paymentStatus == null && !unpaidOnly;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
