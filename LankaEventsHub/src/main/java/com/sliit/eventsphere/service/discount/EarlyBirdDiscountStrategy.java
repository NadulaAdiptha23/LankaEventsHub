package com.sliit.eventsphere.service.discount;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * STRATEGY PATTERN (Concrete Strategy)
 * Applies purely on the date: no code, no group size, no account check.
 * The "is it still inside its validity window" test is done once for
 * every discount type by DiscountCalculationService, which is exactly the
 * whole of Early Bird's eligibility rule - so there is nothing left for
 * this strategy to reject.
 */
@Component
public class EarlyBirdDiscountStrategy implements DiscountStrategy {

    @Override
    public DiscountType getType() {
        return DiscountType.EARLY_BIRD;
    }

    @Override
    public BigDecimal calculateDiscountAmount(BigDecimal originalAmount, Discount discount, DiscountContext context) {
        return DiscountCalculationService.percentageOf(originalAmount, discount.getPercentOff());
    }
}
