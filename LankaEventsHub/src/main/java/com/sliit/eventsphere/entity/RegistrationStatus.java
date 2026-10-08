package com.sliit.eventsphere.entity;

/**
 * What a booking currently is.
 *
 *   PENDING_PAYMENT --(payment clears)--> CONFIRMED
 *         |                                   |
 *         +--(hold expires, attendee gives up, staff cancel)--> CANCELLED
 *                                             |
 *   WAITLISTED --(a seat is freed)------------+
 *         |
 *         +-------------------------------------------------> CANCELLED
 *
 * PENDING_PAYMENT is a held reservation on a paid event. It occupies a
 * seat - seats_taken counts it - but it is not a booking yet, because
 * nothing has been paid for it.
 *
 * It exists to settle a contradiction that used to be resolved the wrong
 * way. "Payment happens before the booking is confirmed" and "the seat is
 * secured when the attendee clicks" are both requirements, and with no
 * reservation state only one of them can be true: either the seat goes to
 * whoever pays first, in a race nobody can see, or bookings are confirmed
 * for people who never paid. That was the behaviour before this state
 * existed. A hold satisfies both readings - the seat is yours from the
 * moment you click, the booking is not confirmed until the money arrives
 * - and because the hold expires, an abandoned checkout returns the seat
 * to the pool instead of holding it for ever.
 *
 * A free event has nothing to pay, so it goes straight to CONFIRMED and
 * never passes through this state.
 *
 * Only a CONFIRMED booking can be checked in, and only a CONFIRMED
 * booking can have been paid for. That is what makes the attendance
 * figures and the refund rules well defined rather than approximate.
 */
public enum RegistrationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    WAITLISTED,
    CANCELLED
}
