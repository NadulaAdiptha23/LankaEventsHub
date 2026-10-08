package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.RefundResponse;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;

public interface RefundService {

    /**
     * Creates a PENDING refund for every COMPLETED, non-zero payment tied
     * to one of the given registrations.
     *
     * The registrations are passed in rather than re-queried: by the time
     * this runs, cancelling the event has already cancelled them, so a
     * status query would find nothing and quietly refund nobody. The caller
     * (via EventCancelledEvent) supplies the snapshot taken under a row
     * lock before anything was modified.
     *
     * Business rule: 100% refund only, no partial refunds or cancellation
     * fees. Idempotent - a payment that already has a refund is skipped, so
     * this can never produce two refunds for the same payment.
     *
     * @return how many refunds were created
     */
    int createRefundsForCancelledEvent(Event event,
                                        List<EventCancelledEvent.AffectedRegistration> affected);

    /**
     * Creates a PENDING refund for ONE cancelled registration - but only
     * where the policy says the attendee is owed one.
     *
     * This is the single-registration counterpart of
     * {@link #createRefundsForCancelledEvent}, and the two are separated
     * by who gave the seat up rather than by how many seats were
     * involved:
     *
     *   - an organizer calls off the event, or an Administrator cancels
     *     one booking: the place was taken away from somebody who still
     *     wanted it, so the money goes back;
     *   - the attendee cancels their own booking: they chose to give the
     *     place up, and by default that forfeits what they paid. The
     *     organizer is left with a seat they can no longer sell.
     *
     * The second case is governed by
     * {@code app.refunds.on-attendee-cancellation}, which is false unless
     * an operator deliberately sets it otherwise - see
     * {@link #isRefundOnAttendeeCancellation()}.
     *
     * Where a refund IS owed, the amount rules are identical in both
     * cases: 100% of what was actually paid, nothing for a waitlist place
     * that never consumed a seat, nothing for a zero-amount payment, and
     * never a second refund for a payment that already has one.
     *
     * @param registration     the registration that has just been cancelled
     * @param previousStatus   the status it held immediately before, since
     *                         the entity itself now reads CANCELLED
     * @param cancelledByStaff true when an Administrator cancelled it on
     *                         the attendee's behalf, false when the
     *                         attendee cancelled it themselves
     * @return true if a refund row was created
     */
    boolean createRefundForCancelledRegistration(Registration registration,
                                                  RegistrationStatus previousStatus,
                                                  boolean cancelledByStaff);

    /**
     * Whether cancelling your own confirmed booking currently gives your
     * money back. Read by the booking pages so the warning an attendee
     * sees matches what the server will actually do.
     */
    boolean isRefundOnAttendeeCancellation();

    /**
     * Staff action: advances a refund PENDING -> PROCESSED -> COMPLETED,
     * one step at a time. Allowed for an Administrator, or for the
     * organizer who owns the event the refund belongs to - not for every
     * organizer on the platform.
     */
    void advanceRefundStatus(Long refundId, ActingUser actor);

    /** The refund queue an Administrator works through. */
    List<RefundResponse> getAllRefunds(ActingUser actor);
}
