package com.sliit.eventsphere.service.discount;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;

import java.math.BigDecimal;

/**
 * STRATEGY PATTERN (Strategy interface)
 * ---------------------------------------------------------------------
 * Why this pattern here: there are four discount types (Early Bird,
 * Student, Promo Code, Group) that are structurally similar (all reduce a
 * price) but each has genuinely different eligibility logic - not just a
 * different label on the same formula:
 *   - Early Bird only cares about the current date vs. a cutoff
 *   - Student depends on who the attendee is, not when they registered
 *   - Promo Code must validate the code the attendee actually typed
 *   - Group only applies past a minimum group size threshold
 * That "same interface, different algorithm" shape is exactly what
 * Strategy is for. Each implementation is a separate Spring @Component,
 * and DiscountCalculationService picks the right one at runtime by
 * DiscountType - see that class for how they're wired together.
 */
public interface DiscountStrategy {

    /** Which discount type this strategy implements. */
    DiscountType getType();

    /**
     * Calculates the discount amount (not the final price) to subtract
     * from originalAmount.
     *
     * Contract note: choosing a discount is a deliberate action by the
     * attendee, so an ineligible choice is an ERROR, not a silent
     * BigDecimal.ZERO. Implementations throw InvalidRequestException with
     * a message the attendee can act on ("that promo code is not valid for
     * this event", "group discounts need at least 3 attendees"). The
     * earlier behaviour - quietly charging full price when a promo code
     * was mistyped - looked like success and was the worst of both worlds.
     *
     * Validity of the discount window and of percentOff is checked once,
     * centrally, in DiscountCalculationService before any strategy runs.
     */
    BigDecimal calculateDiscountAmount(BigDecimal originalAmount, Discount discount, DiscountContext context);
}
