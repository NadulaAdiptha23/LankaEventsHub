package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.PaymentStatus;

/**
 * Combines a registration with its payment status (if any) for the
 * "My Registrations" page - a read-model built purely for that one
 * view, not persisted or used anywhere else.
 */
public class MyRegistrationRow {

    private final RegistrationResponse registration;
    private final PaymentResponse payment; // null if no payment exists yet
    private final boolean requiresPayment;

    public MyRegistrationRow(RegistrationResponse registration, PaymentResponse payment, boolean requiresPayment) {
        this.registration = registration;
        this.payment = payment;
        this.requiresPayment = requiresPayment;
    }

    public RegistrationResponse getRegistration() { return registration; }
    public PaymentResponse getPayment() { return payment; }
    public boolean isRequiresPayment() { return requiresPayment; }
    /**
     * Paid means the money actually arrived, not that an attempt exists.
     *
     * This used to be {@code payment != null}, which made a DECLINED card
     * render as "Paid" and hid the "Pay now" link from the one person who
     * still needed it. A failed attempt is a row like any other; only its
     * status says whether anybody was charged.
     */
    public boolean isPaid() {
        return payment != null && payment.getStatus() == PaymentStatus.COMPLETED;
    }

    /** A previous attempt that did not go through, and can be retried. */
    public boolean isPaymentFailed() {
        return payment != null && payment.getStatus() == PaymentStatus.FAILED;
    }
}
