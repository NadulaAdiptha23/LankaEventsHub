package com.sliit.eventsphere.service.discount;

import java.time.LocalDateTime;

/**
 * Extra input a discount strategy might need beyond the Discount entity
 * itself. Not every strategy uses every field - GroupDiscountStrategy
 * needs groupSize, PromoCodeDiscountStrategy needs the code the attendee
 * actually typed, StudentDiscountStrategy needs to know whether this
 * account has been verified as a student. Keeping this as one small
 * holder class avoids changing the DiscountStrategy interface every time
 * a new strategy needs a new kind of input.
 */
public class DiscountContext {

    private final LocalDateTime evaluationTime;
    private final String enteredCode;
    private final Integer groupSize;
    private final boolean verifiedStudent;

    public DiscountContext(LocalDateTime evaluationTime, String enteredCode,
                            Integer groupSize, boolean verifiedStudent) {
        this.evaluationTime = evaluationTime;
        this.enteredCode = enteredCode;
        this.groupSize = groupSize;
        this.verifiedStudent = verifiedStudent;
    }

    /** For an anonymous price preview, where no account is involved yet. */
    public static DiscountContext of(String enteredCode, Integer groupSize) {
        return new DiscountContext(LocalDateTime.now(), enteredCode, groupSize, false);
    }

    /** For a real payment, where the paying account's status is known. */
    public static DiscountContext of(String enteredCode, Integer groupSize, boolean verifiedStudent) {
        return new DiscountContext(LocalDateTime.now(), enteredCode, groupSize, verifiedStudent);
    }

    public LocalDateTime getEvaluationTime() {
        return evaluationTime;
    }

    public String getEnteredCode() {
        return enteredCode;
    }

    public Integer getGroupSize() {
        return groupSize;
    }

    public boolean isVerifiedStudent() {
        return verifiedStudent;
    }
}
