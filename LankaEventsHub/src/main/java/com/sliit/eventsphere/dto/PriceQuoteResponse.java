package com.sliit.eventsphere.dto;

import java.math.BigDecimal;

/**
 * Transparent price breakdown - shows original price, discount amount,
 * and final price clearly, exactly as the client (Mr. Chamara Fernando,
 * Finance Executive) requested during requirement gathering.
 */
public class PriceQuoteResponse {

    private final BigDecimal originalAmount;
    private final BigDecimal discountAmount;
    private final BigDecimal finalAmount;
    private final String discountApplied;

    public PriceQuoteResponse(BigDecimal originalAmount, BigDecimal discountAmount,
                               BigDecimal finalAmount, String discountApplied) {
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.finalAmount = finalAmount;
        this.discountApplied = discountApplied;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public String getDiscountApplied() {
        return discountApplied;
    }
}
