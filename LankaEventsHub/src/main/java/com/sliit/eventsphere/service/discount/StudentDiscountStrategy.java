package com.sliit.eventsphere.service.discount;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;
import com.sliit.eventsphere.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * STRATEGY PATTERN (Concrete Strategy)
 *
 * Eligibility rule (this is the whole rule - there is no hidden extra
 * step waiting to be built): a student discount applies only to an
 * account an Administrator has marked as a verified student, via the
 * users.is_verified_student flag set from the Admin user-management page.
 * Selecting STUDENT on an unverified account is rejected with an
 * explanation rather than quietly falling through to full price.
 *
 * Verification itself is deliberately a manual, admin-side decision -
 * uploading and checking student IDs is outside this system's scope. The
 * earlier version of this class deferred the eligibility check to a
 * later increment and meanwhile handed the discount to every account,
 * which looked finished and was not.
 */
@Component
public class StudentDiscountStrategy implements DiscountStrategy {

    @Override
    public DiscountType getType() {
        return DiscountType.STUDENT;
    }

    @Override
    public BigDecimal calculateDiscountAmount(BigDecimal originalAmount, Discount discount, DiscountContext context) {
        if (!context.isVerifiedStudent()) {
            throw new InvalidRequestException(
                    "The student discount is only available to accounts verified as students. "
                            + "Contact the event organizer or an administrator to get your account verified.");
        }
        return DiscountCalculationService.percentageOf(originalAmount, discount.getPercentOff());
    }
}
