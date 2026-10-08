package com.sliit.eventsphere.service;

import org.springframework.stereotype.Component;

/**
 * When the platform owes an attendee their money back.
 *
 * This is a commercial rule, not a mechanism, and two different parts of
 * the system need to agree on it:
 *
 *   - Payment &amp; Ticketing, which decides whether to raise a refund
 *     after a cancellation has happened;
 *   - Registration &amp; Booking, which has to warn the attendee what
 *     cancelling will cost them BEFORE they press the button.
 *
 * Putting the rule in its own tiny component is what lets both read the
 * same answer without Registration &amp; Booking importing RefundService
 * - which would undo the decoupling that the whole cancellation flow is
 * built around (see the design note at the top of
 * RegistrationServiceImpl). Booking asks what the policy is; it still
 * does not know that refunds exist as a module.
 *
 * The rule itself:
 *
 *   - the organizer calls off the event, or an Administrator cancels one
 *     booking: the place was taken away from somebody who still wanted
 *     it, so the money goes back, always;
 *   - the attendee cancels their own confirmed booking: they chose to
 *     give the place up. By default that forfeits what they paid,
 *     because the organizer is left holding a seat they can no longer
 *     sell.
 */
@Component
public class RefundPolicy {

    /**
     * Whether an attendee who cancels their own confirmed, paid booking
     * gets their money back. Always false.
     *
     * This was configurable, and should not have been. The requirement
     * states the rule flatly - the organizer cancelling refunds, the
     * attendee cancelling does not - and a property meant anybody could
     * set an environment variable and quietly run a system that broke it.
     * A required business rule is not an operator preference.
     *
     * The method stays rather than being inlined as a constant, because
     * Registration &amp; Booking asks this question to warn the attendee
     * BEFORE they press cancel, and both sides must keep reading the
     * same answer from the same place.
     */
    public boolean refundsOnAttendeeCancellation() {
        return false;
    }

    /**
     * Whether a refund is owed for a single cancelled booking, given who
     * cancelled it. Says nothing about whether anything was actually
     * paid - that is Payment &amp; Ticketing's question, asked next.
     */
    public boolean refundIsOwed(boolean cancelledByStaff) {
        return cancelledByStaff || refundsOnAttendeeCancellation();
    }
}
