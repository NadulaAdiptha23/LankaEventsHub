package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A discount as its owner manages it - the full picture, unlike
 * {@link DiscountOptionResponse}, which is the trimmed version an
 * attendee sees at checkout.
 *
 * The difference matters: the checkout DTO deliberately hides the
 * percentage and the validity window, because an attendee choosing
 * between options does not need to know the discount was 40% until it is
 * applied, and the promo code itself must never be rendered to the person
 * who is supposed to already have it.
 */
public class DiscountResponse {

    private Long id;
    private Long eventId;
    private String eventName;
    private DiscountType type;
    private String code;
    private BigDecimal percentOff;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private boolean active;
    private boolean currentlyValid;

    public static DiscountResponse from(Discount d) {
        DiscountResponse r = new DiscountResponse();
        r.id = d.getId();
        r.eventId = d.getEvent() == null ? null : d.getEvent().getId();
        r.eventName = d.getEvent() == null ? null : d.getEvent().getName();
        r.type = d.getType();
        r.code = d.getCode();
        r.percentOff = d.getPercentOff();
        r.validFrom = d.getValidFrom();
        r.validTo = d.getValidTo();
        r.active = d.isActive();
        // The same method Payment & Ticketing calls, so the management
        // screen cannot disagree with checkout about whether a discount is
        // live right now.
        r.currentlyValid = d.isCurrentlyValid(LocalDateTime.now());
        return r;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public DiscountType getType() { return type; }
    public String getCode() { return code; }
    public BigDecimal getPercentOff() { return percentOff; }
    public LocalDateTime getValidFrom() { return validFrom; }
    public LocalDateTime getValidTo() { return validTo; }
    public boolean isActive() { return active; }
    public boolean isCurrentlyValid() { return currentlyValid; }
}
