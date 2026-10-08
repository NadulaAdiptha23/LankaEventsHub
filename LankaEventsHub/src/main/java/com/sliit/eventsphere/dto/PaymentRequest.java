package com.sliit.eventsphere.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * What a client submits to pay for a registration.
 *
 * Note what is NOT here: no userId, no amount, and no groupSize. The paying
 * user is the authenticated principal, the amount is always recalculated on
 * the server from the event fee and the discount rules, and GROUP-discount
 * eligibility is judged on registration.getSeats() - the seat count the
 * system already knows and already charges for - never on a number a client
 * could submit. A client can choose WHICH discount to try, never how much
 * it is worth or who is eligible for it.
 */
public class PaymentRequest {

    @NotNull(message = "registrationId is required")
    @Positive(message = "registrationId must be a positive id")
    private Long registrationId;

    /**
     * Optional. When present it must match the registration's own event -
     * a mismatch is rejected rather than ignored, so a caller cannot mix a
     * seat on one event with a discount context from another.
     */
    @Positive(message = "eventId must be a positive id")
    private Long eventId;

    @Positive(message = "discountId must be a positive id")
    private Long discountId;

    @Size(max = 40, message = "Promo code must be 40 characters or fewer")
    private String enteredCode;

    /**
     * CARD or ON_ARRIVAL. Absent means CARD, which is what every caller
     * written before there was a choice meant.
     *
     * A string rather than the enum so that an unknown value is a
     * sentence the attendee can read instead of a 400 from the JSON
     * parser with no message in it.
     */
    @Size(max = 20, message = "Unknown payment method")
    private String method;

    /**
     * The card, when the method is CARD. Never stored - these fields are
     * read once to reach a decision and are gone with the request. There
     * is deliberately no getter on the entity, no column in the schema,
     * and nothing written to a log.
     */
    @Size(max = 25, message = "A card number is 25 characters or fewer")
    private String cardNumber;

    @Size(max = 100, message = "Card holder name is 100 characters or fewer")
    private String cardHolder;

    private Integer expiryMonth;

    private Integer expiryYear;

    @Size(max = 4, message = "A security code is 3 or 4 digits")
    private String securityCode;

    public Long getRegistrationId() { return registrationId; }
    public void setRegistrationId(Long registrationId) { this.registrationId = registrationId; }
    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public Long getDiscountId() { return discountId; }
    public void setDiscountId(Long discountId) { this.discountId = discountId; }
    public String getEnteredCode() { return enteredCode; }
    public void setEnteredCode(String enteredCode) { this.enteredCode = enteredCode; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
    public String getCardHolder() { return cardHolder; }
    public void setCardHolder(String cardHolder) { this.cardHolder = cardHolder; }
    public Integer getExpiryMonth() { return expiryMonth; }
    public void setExpiryMonth(Integer expiryMonth) { this.expiryMonth = expiryMonth; }
    public Integer getExpiryYear() { return expiryYear; }
    public void setExpiryYear(Integer expiryYear) { this.expiryYear = expiryYear; }
    public String getSecurityCode() { return securityCode; }
    public void setSecurityCode(String securityCode) { this.securityCode = securityCode; }

    /**
     * The card as the gateway wants it. Assembled here rather than in the
     * service so the service never handles the loose fields at all.
     */
    public com.sliit.eventsphere.payment.CardDetails toCard() {
        return new com.sliit.eventsphere.payment.CardDetails(
                cardNumber, cardHolder, expiryMonth, expiryYear, securityCode);
    }
}
