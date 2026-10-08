package com.sliit.eventsphere.service.discount;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;
import com.sliit.eventsphere.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * STRATEGY PATTERN (Concrete Strategy)
 * The clearest example of why these can't share one method: this is the
 * only discount type where the attendee supplies an input (the code) that
 * must match exactly, on top of the date check every other strategy also
 * gets. This directly implements the requirement that the system "prevent
 * invalid or expired discount codes" - and prevent means tell the
 * attendee, not quietly charge them full price.
 */
@Component
public class PromoCodeDiscountStrategy implements DiscountStrategy {

    @Override
    public DiscountType getType() {
        return DiscountType.PROMO_CODE;
    }

    @Override
    public BigDecimal calculateDiscountAmount(BigDecimal originalAmount, Discount discount, DiscountContext context) {
        if (discount.getCode() == null || discount.getCode().isBlank()) {
            throw new InvalidRequestException("That promotional discount has no code configured");
        }
        String entered = context.getEnteredCode();
        if (entered == null || entered.isBlank()) {
            throw new InvalidRequestException("Enter the promo code to use this discount");
        }
        if (!discount.getCode().equalsIgnoreCase(entered.trim())) {
            throw new InvalidRequestException("That promo code is not valid for this event");
        }
        return DiscountCalculationService.percentageOf(originalAmount, discount.getPercentOff());
    }
}
