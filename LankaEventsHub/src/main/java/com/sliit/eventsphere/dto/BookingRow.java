package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Payment;
import com.sliit.eventsphere.entity.Refund;
import com.sliit.eventsphere.entity.Registration;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One line on the admin bookings screen: a registration, plus whatever
 * payment and refund exist behind it.
 *
 * A read model built for this one view. It is assembled in the service
 * from three bulk queries rather than by walking associations per row -
 * with 400+ bookings, one payment lookup and one refund lookup per line
 * is 800 extra round trips for a single page (the same N+1 mistake that
 * was fixed in Reporting).
 *
 * Everything is mapped inside the service's transaction, so the template
 * never touches a lazy association while rendering.
 */
public class BookingRow {

    private final Long registrationId;
    private final Long eventId;
    private final String eventName;
    private final String eventStatus;
    private final Long attendeeId;
    private final String attendeeName;
    private final String attendeeEmail;
    private final String status;
    private final LocalDateTime registeredAt;
    private final LocalDateTime cancelledAt;
    private final int seats;
    private final Integer seatsCheckedIn;

    private final String paymentStatus;     // null when nothing was ever paid
    private final BigDecimal paidAmount;
    private final String ticketCode;

    private final String refundStatus;      // null when no refund exists
    private final BigDecimal refundAmount;

    private BookingRow(Registration r, Payment p, Refund rf) {
        this.registrationId = r.getId();
        this.eventId = r.getEvent().getId();
        this.eventName = r.getEvent().getName();
        this.eventStatus = r.getEvent().getStatus() == null
                ? null : r.getEvent().getStatus().name();
        this.attendeeId = r.getUser().getId();
        this.attendeeName = r.getUser().getFullName();
        this.attendeeEmail = r.getUser().getEmail();
        this.status = r.getStatus().name();
        this.registeredAt = r.getRegisteredAt();
        this.cancelledAt = r.getCancelledAt();
        this.seats = r.getSeats();
        this.seatsCheckedIn = r.getSeatsCheckedIn();

        this.paymentStatus = p == null ? null : p.getStatus().name();
        this.paidAmount = p == null ? null : p.getFinalAmount();
        this.ticketCode = p == null ? null : p.getTicketCode();

        this.refundStatus = rf == null ? null : rf.getStatus().name();
        this.refundAmount = rf == null ? null : rf.getAmount();
    }

    public static BookingRow of(Registration r, Payment p, Refund rf) {
        return new BookingRow(r, p, rf);
    }

    public Long getRegistrationId() { return registrationId; }
    public Long getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public String getEventStatus() { return eventStatus; }
    public Long getAttendeeId() { return attendeeId; }
    public String getAttendeeName() { return attendeeName; }
    public String getAttendeeEmail() { return attendeeEmail; }
    public String getStatus() { return status; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public int getSeats() { return seats; }
    public Integer getSeatsCheckedIn() { return seatsCheckedIn; }
    public String getPaymentStatus() { return paymentStatus; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public String getTicketCode() { return ticketCode; }
    public String getRefundStatus() { return refundStatus; }
    public BigDecimal getRefundAmount() { return refundAmount; }

    /** True when this booking is still live and could be cancelled. */
    public boolean isCancellable() {
        return !"CANCELLED".equals(status);
    }

    /**
     * True when money actually changed hands and has not been given back
     * - the state an operator needs to see before cancelling, because
     * cancelling will raise a refund.
     */
    public boolean isRefundOwedOnCancel() {
        return "CONFIRMED".equals(status)
                && "COMPLETED".equals(paymentStatus)
                && paidAmount != null
                && paidAmount.compareTo(BigDecimal.ZERO) > 0
                && refundStatus == null;
    }
}
