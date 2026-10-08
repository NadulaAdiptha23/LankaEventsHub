package com.sliit.eventsphere.service.discount;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;
import com.sliit.eventsphere.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * STRATEGY PATTERN (Concrete Strategy)
 * The only strategy whose eligibility depends on a *count* rather than a
 * date, a code, or the account - a genuinely different algorithm shape,
 * which is exactly the point of using Strategy instead of one method with
 * a switch statement and four sets of if-branches tangled together.
 */
@Component
public class GroupDiscountStrategy implements DiscountStrategy {

    /** Minimum number of attendees registering together for this to apply. */
    public static final int MINIMUM_GROUP_SIZE = 3;

    @Override
    public DiscountType getType() {
        return DiscountType.GROUP;
    }

    @Override
    public BigDecimal calculateDiscountAmount(BigDecimal originalAmount, Discount discount, DiscountContext context) {
        Integer groupSize = context.getGroupSize();
        if (groupSize == null) {
            throw new InvalidRequestException("Enter your group size to use the group discount");
        }
        if (groupSize < 1) {
            throw new InvalidRequestException("Group size must be a positive number");
        }
        if (groupSize < MINIMUM_GROUP_SIZE) {
            throw new InvalidRequestException(
                    "The group discount needs at least " + MINIMUM_GROUP_SIZE + " attendees");
        }
        return DiscountCalculationService.percentageOf(originalAmount, discount.getPercentOff());
    }
}
