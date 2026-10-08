package com.sliit.eventsphere.service.discount;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.DiscountRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * STRATEGY PATTERN (Context)
 * ---------------------------------------------------------------------
 * Spring automatically collects every DiscountStrategy @Component into
 * the constructor's List<DiscountStrategy> - this class turns that list
 * into a Map keyed by DiscountType so the correct strategy can be looked
 * up in O(1) at runtime, based on the type stored on the Discount entity.
 * Adding a fifth discount type later only means writing one new
 * @Component; nothing here or in the price-quote logic needs to change.
 *
 * This class also owns the checks that are the same for every discount
 * type, so no strategy can forget one:
 *   - the discount must belong to the event being paid for
 *   - it must be active and inside its validity window
 *   - its percentage must be a sane 0-100
 *   - the resulting amount is rounded to 2dp and can never exceed the
 *     original price (so the final amount can never go negative)
 */
@Service
public class DiscountCalculationService {

    private final Map<DiscountType, DiscountStrategy> strategiesByType;
    private final DiscountRepository discountRepository;

    public DiscountCalculationService(List<DiscountStrategy> strategies,
                                       DiscountRepository discountRepository) {
        this.strategiesByType = strategies.stream()
                .collect(Collectors.toMap(DiscountStrategy::getType, Function.identity()));
        this.discountRepository = discountRepository;
    }

    /**
     * Loads a discount and refuses it unless it belongs to the event being
     * paid for.
     *
     * This is the fix for a real hole: without it, an attendee paying for
     * a cheap event could pass the id of a 90%-off discount attached to a
     * completely different event and have it applied. Every caller goes
     * through here rather than calling discountRepository.findById()
     * directly, so the check cannot be skipped by accident.
     */
    public Discount resolveDiscountForEvent(Long discountId, Long eventId) {
        Discount discount = discountRepository.findById(discountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Discount not found with id " + discountId));
        if (discount.getEvent() == null || !discount.getEvent().getId().equals(eventId)) {
            throw new InvalidRequestException("That discount does not belong to this event");
        }
        return discount;
    }

    /**
     * Calculates the discount amount for a given discount + context.
     * Business rule: only ONE discount can be applied per registration (no
     * stacking), so this always resolves to a single strategy call - never
     * a sum across multiple discounts.
     */
    public BigDecimal calculateDiscount(BigDecimal originalAmount, Discount discount, DiscountContext context) {
        if (originalAmount == null || originalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO; // nothing to discount on a free event
        }

        if (!discount.isActive()) {
            throw new InvalidRequestException("That discount is no longer active");
        }
        if (!discount.isCurrentlyValid(context.getEvaluationTime())) {
            throw new InvalidRequestException("That discount is expired or not yet valid");
        }

        BigDecimal percent = discount.getPercentOff();
        if (percent == null) {
            throw new InvalidRequestException("That discount has no percentage configured");
        }
        if (percent.compareTo(BigDecimal.ZERO) < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new InvalidRequestException("That discount has an invalid percentage and cannot be applied");
        }

        DiscountStrategy strategy = strategiesByType.get(discount.getType());
        if (strategy == null) {
            // Only reachable if a DiscountType is added without a matching
            // @Component. Failing loudly beats silently charging full price.
            throw new InvalidRequestException(
                    "Discount type " + discount.getType() + " is not supported");
        }

        BigDecimal discountAmount = strategy.calculateDiscountAmount(originalAmount, discount, context);
        if (discountAmount == null || discountAmount.compareTo(BigDecimal.ZERO) < 0) {
            discountAmount = BigDecimal.ZERO;
        }
        // Never let a discount exceed the original amount, and always
        // settle on 2dp so the stored payment matches what was displayed.
        return discountAmount.min(originalAmount).setScale(2, RoundingMode.HALF_UP);
    }

    /** Shared percentage maths, so all four strategies round identically. */
    static BigDecimal percentageOf(BigDecimal originalAmount, BigDecimal percentOff) {
        return originalAmount
                .multiply(percentOff)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
