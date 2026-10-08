package com.sliit.eventsphere.payment;

/**
 * What the gateway said.
 *
 * A record rather than a boolean, because a decline has a reason and the
 * attendee needs to be told it. "Your card was declined" and "the
 * payment provider is not responding" call for different actions from
 * the person reading the screen.
 *
 * @param success true when the money was taken
 * @param reason  why not, when it was not; null on success
 */
public record PaymentResult(boolean success, String reason) {

    /**
     * Named "approved" rather than "success" on purpose: a record cannot
     * declare a static method with the same name and arity as one of its
     * own accessors, and {@code success()} is already the accessor for
     * the component.
     */
    public static PaymentResult approved() {
        return new PaymentResult(true, null);
    }

    public static PaymentResult declined(String reason) {
        return new PaymentResult(false, reason);
    }
}
