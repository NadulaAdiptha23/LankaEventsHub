package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Payment;
import com.sliit.eventsphere.entity.PaymentMethod;
import com.sliit.eventsphere.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentResponse {

    private Long id;
    private Long registrationId;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private BigDecimal finalAmount;
    private PaymentStatus status;
    private String ticketCode;
    private LocalDateTime paidAt;
    private PaymentMethod method;
    /** Who this booking belongs to - for the organizer's door list. */
    private String attendeeName;
    private Integer seats;

    public static PaymentResponse from(Payment p) {
        PaymentResponse dto = new PaymentResponse();
        dto.id = p.getId();
        dto.registrationId = p.getRegistration().getId();
        dto.originalAmount = p.getOriginalAmount();
        dto.discountAmount = p.getDiscountAmount();
        dto.finalAmount = p.getFinalAmount();
        dto.status = p.getStatus();
        dto.ticketCode = p.getTicketCode();
        dto.paidAt = p.getPaidAt();
        dto.method = p.getMethod();
        dto.attendeeName = p.getRegistration().getUser() == null
                ? null : p.getRegistration().getUser().getFullName();
        dto.seats = p.getRegistration().getSeats();
        return dto;
    }

    public Long getId() { return id; }
    public Long getRegistrationId() { return registrationId; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public PaymentStatus getStatus() { return status; }
    public String getTicketCode() { return ticketCode; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public PaymentMethod getMethod() { return method; }
    public String getAttendeeName() { return attendeeName; }
    public Integer getSeats() { return seats; }

    /**
     * True when this money is still owed in person. What the checkout,
     * the ticket and the door list all key off, so none of them can
     * disagree about whether a booking has been paid for.
     */
    public boolean isOwedAtTheDoor() {
        return method == PaymentMethod.ON_ARRIVAL && status == PaymentStatus.PENDING;
    }

    public boolean isPaid() { return status == PaymentStatus.COMPLETED; }
}
