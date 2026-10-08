package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.DiscountOptionResponse;
import com.sliit.eventsphere.dto.PaymentRequest;
import com.sliit.eventsphere.dto.PaymentResponse;
import com.sliit.eventsphere.dto.PriceQuoteRequest;
import com.sliit.eventsphere.dto.PriceQuoteResponse;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;
import java.util.Optional;

public interface PaymentService {

    /**
     * Processes payment for the acting user's own CONFIRMED registration
     * through a mock gateway (which always succeeds), applies at most one
     * discount via the Strategy-pattern DiscountCalculationService, and
     * issues a ticket code on success. Free events (fee = 0) still produce
     * a COMPLETED payment record and a ticket, with no gateway call.
     *
     * The amount is always recalculated here from the event's own fee and
     * the discount rules - the price the browser displayed is a preview
     * and is never trusted.
     */
    PaymentResponse pay(PaymentRequest request, ActingUser actor);

    /**
     * Price preview for the acting user's own registration: what the total
     * would be with the chosen discount applied. Owner-only, and lives in
     * the service (not the controller) so the whole calculation happens
     * inside one transaction and the controller stays free of business
     * logic.
     *
     * A PREVIEW only - pay() recalculates everything from scratch.
     */
    PriceQuoteResponse quoteForRegistration(Long registrationId, PriceQuoteRequest request, ActingUser actor);

    /**
     * The discounts the owner of this registration could actually use right
     * now - active, in their validity window, and belonging to this
     * registration's event. Owner-only, like everything else here.
     *
     * Lives in the service rather than in the page controller so that no
     * controller has to inject a repository, and so the "must belong to
     * this event" rule is applied in one place.
     */
    List<DiscountOptionResponse> getSelectableDiscounts(Long registrationId, ActingUser actor);

    /**
     * Records that an on-arrival payment was taken at the door, which
     * completes it and confirms the booking.
     *
     * Restricted to the event's own organizer and to Administrators: this
     * is the moment money is declared received, and an attendee marking
     * their own booking paid would be the whole point of the method
     * defeated.
     *
     * Only ever applies to a PENDING payment whose method is ON_ARRIVAL.
     * A card payment reached its answer at the checkout, and nothing at a
     * door should be able to turn a decline into a sale.
     *
     * @param amountNote free text for the audit row - "cash", "card
     *                   machine", a receipt number - recorded as given
     */
    PaymentResponse settleOnArrival(Long registrationId, String amountNote, ActingUser actor);

    /**
     * Every booking on this event that still owes money at the door,
     * oldest first. The organizer's collection list.
     *
     * Restricted the same way as {@link #settleOnArrival}: it says who
     * owes what, which is not an attendee's business.
     */
    List<PaymentResponse> getOwedAtTheDoor(Long eventId, ActingUser actor);

    /** Owner-only lookup; throws if there is no payment yet. */
    PaymentResponse getByRegistrationId(Long registrationId, ActingUser actor);

    /**
     * Same owner-only lookup, but returns empty instead of throwing when
     * no payment exists - for UI pages (like "My Registrations") that need
     * to check payment status without exception-driven control flow.
     */
    Optional<PaymentResponse> findByRegistrationId(Long registrationId, ActingUser actor);
}
