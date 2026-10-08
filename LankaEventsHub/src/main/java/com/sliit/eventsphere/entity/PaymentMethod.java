package com.sliit.eventsphere.entity;

/**
 * How the attendee chose to settle.
 *
 * Both methods end in the same place - a COMPLETED payment and a
 * CONFIRMED booking - but they get there differently, and the difference
 * matters to three different people. The attendee needs to know whether
 * they still owe money. The organizer needs a door list that says who
 * does. And Payment &amp; Ticketing needs to know whether a gateway was
 * ever involved, because only one of these can be declined.
 *
 * Kept as an enum rather than a boolean because "is this a card payment"
 * is the wrong question to have to ask a column: a third method (bank
 * transfer, mobile wallet) would turn that boolean into a lie, whereas
 * it only adds a constant here.
 */
public enum PaymentMethod {

    /**
     * Settled through the payment gateway during checkout. Either it
     * clears immediately or it is declined, and the attendee finds out
     * before they leave the page.
     */
    CARD("Card"),

    /**
     * Settled in person when the attendee arrives.
     *
     * The booking is NOT confirmed by choosing this - that would confirm
     * a paid registration without payment, which is the one thing this
     * module exists to prevent. The seat is held until the event starts
     * instead of for the usual few minutes, and an organizer marks the
     * money received at the door, which is what completes the payment
     * and confirms the booking.
     */
    ON_ARRIVAL("Pay on arrival");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** True when this method has to go through the gateway to settle. */
    public boolean isSettledImmediately() {
        return this == CARD;
    }
}
