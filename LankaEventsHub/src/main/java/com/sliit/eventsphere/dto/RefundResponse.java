package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Refund;
import com.sliit.eventsphere.entity.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Flattened view of a refund for the admin queue.
 *
 * Built inside the service's transaction so the template never touches a
 * lazily-loaded entity association - that is what lets
 * spring.jpa.open-in-view be turned off, which in turn stops accidental
 * database queries firing while a page renders.
 */
public class RefundResponse {

    private Long id;
    private String eventName;
    private String attendeeName;
    private BigDecimal amount;
    private RefundStatus status;
    private LocalDateTime requestedAt;
    private LocalDateTime processedAt;

    public static RefundResponse from(Refund r) {
        RefundResponse dto = new RefundResponse();
        dto.id = r.getId();
        dto.eventName = r.getPayment().getRegistration().getEvent().getName();
        dto.attendeeName = r.getPayment().getRegistration().getUser().getFullName();
        dto.amount = r.getAmount();
        dto.status = r.getStatus();
        dto.requestedAt = r.getRequestedAt();
        dto.processedAt = r.getProcessedAt();
        return dto;
    }

    public Long getId() { return id; }
    public String getEventName() { return eventName; }
    public String getAttendeeName() { return attendeeName; }
    public BigDecimal getAmount() { return amount; }
    public RefundStatus getStatus() { return status; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
}
