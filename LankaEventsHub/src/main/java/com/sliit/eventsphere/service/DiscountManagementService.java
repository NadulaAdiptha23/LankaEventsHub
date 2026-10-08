package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.DiscountRequest;
import com.sliit.eventsphere.dto.DiscountResponse;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;

/**
 * Function 3 / Payment & Ticketing: managing the discounts an event
 * offers.
 *
 * Kept separate from {@code DiscountCalculationService}, which applies
 * discounts at checkout through the Strategy pattern. Creating a discount
 * and calculating one are different jobs with different callers and
 * different authorization: an organizer creates, an attendee's checkout
 * calculates.
 *
 * Until this existed, discounts could only be created by writing SQL
 * directly into the table - which meant the four strategies had no
 * supported way of ever being exercised on a new event.
 *
 * Every method takes the authenticated ActingUser and re-checks ownership
 * through EventAccessPolicy. An organizer may only touch discounts on
 * their own events; an Administrator may touch any.
 */
public interface DiscountManagementService {

    /** Every discount on one event, for its owner or an Administrator. */
    List<DiscountResponse> getDiscountsForEvent(Long eventId, ActingUser actor);

    /**
     * Every discount the acting user may manage: their own events' for an
     * organizer, the whole platform's for an Administrator.
     */
    List<DiscountResponse> getManageableDiscounts(ActingUser actor);

    DiscountResponse create(Long eventId, DiscountRequest request, ActingUser actor);

    DiscountResponse update(Long discountId, DiscountRequest request, ActingUser actor);

    /**
     * Switches a discount off without deleting it.
     *
     * This is the preferred way to retire one: {@code payments.discount_id}
     * points at it, so a completed payment records which discount produced
     * its price. Deleting the row sets that column to NULL (ON DELETE SET
     * NULL) and the payment history quietly loses the reason it was
     * cheaper.
     */
    DiscountResponse setActive(Long discountId, boolean active, ActingUser actor);

    /**
     * Deletes a discount outright. Refused once any payment has used it -
     * see {@link #setActive} for why, and use that instead.
     */
    void delete(Long discountId, ActingUser actor);
}
