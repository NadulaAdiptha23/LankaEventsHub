package com.sliit.eventsphere.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What a client submits when previewing a price before paying.
 * discountId is optional - null means "no discount applied".
 * enteredCode is only used for PROMO_CODE discounts; harmless to leave
 * null otherwise. There is no groupSize here - GROUP eligibility is judged
 * on the registration's own seat count, never on anything the client
 * submits, so the preview cannot show a discount the real payment would
 * then refuse.
 *
 * The response is a PREVIEW only. PaymentServiceImpl recomputes the same
 * numbers from scratch when the payment is actually submitted, so a
 * tampered preview buys nothing.
 */
public class PriceQuoteRequest {

    @Positive(message = "discountId must be a positive id")
    private Long discountId;

    @Size(max = 40, message = "Promo code must be 40 characters or fewer")
    private String enteredCode;

    public Long getDiscountId() { return discountId; }
    public void setDiscountId(Long discountId) { this.discountId = discountId; }
    public String getEnteredCode() { return enteredCode; }
    public void setEnteredCode(String enteredCode) { this.enteredCode = enteredCode; }
}
