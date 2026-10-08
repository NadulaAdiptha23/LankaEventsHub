package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class RegistrationResponse {

    private Long id;
    private Long eventId;
    private String eventName;
    private String eventVenue;
    private EventStatus eventStatus;
    private LocalDateTime eventStartDatetime;
    private BigDecimal eventFee;
    private Long userId;
    private String userName;
    private RegistrationStatus status;
    private LocalDateTime registeredAt;
    private LocalDateTime checkedInAt;
    private LocalDateTime paymentDueAt;
    /** How many seats this one booking holds - a party books once. */
    private int seats;
    /** How many of the party arrived, or null before check-in. */
    private Integer seatsCheckedIn;

    public static RegistrationResponse from(Registration r) {
        RegistrationResponse dto = new RegistrationResponse();
        dto.id = r.getId();
        dto.eventId = r.getEvent().getId();
        dto.eventName = r.getEvent().getName();
        dto.eventVenue = r.getEvent().getVenue();
        dto.eventStatus = r.getEvent().getStatus();
        dto.eventStartDatetime = r.getEvent().getStartDatetime();
        dto.eventFee = r.getEvent().getFee();
        dto.userId = r.getUser().getId();
        dto.userName = r.getUser().getFullName();
        dto.status = r.getStatus();
        dto.registeredAt = r.getRegisteredAt();
        dto.checkedInAt = r.getCheckedInAt();
        dto.paymentDueAt = r.getPaymentDueAt();
        dto.seats = r.getSeats();
        dto.seatsCheckedIn = r.getSeatsCheckedIn();
        return dto;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public String getEventVenue() { return eventVenue; }
    public EventStatus getEventStatus() { return eventStatus; }
    /** True when the event itself was called off - the ticket is not valid for entry. */
    public boolean isEventCancelled() { return eventStatus == EventStatus.CANCELLED; }
    public LocalDateTime getEventStartDatetime() { return eventStartDatetime; }
    public BigDecimal getEventFee() { return eventFee; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public RegistrationStatus getStatus() { return status; }
    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public int getSeats() { return seats; }
    public Integer getSeatsCheckedIn() { return seatsCheckedIn; }

    /**
     * What this booking costs before any discount: the fee times the
     * seats. Shown on the attendee's own pages so the number on the
     * booking and the number at checkout are visibly the same one.
     */
    public BigDecimal getTotalFee() {
        BigDecimal fee = eventFee == null ? BigDecimal.ZERO : eventFee;
        return fee.multiply(BigDecimal.valueOf(Math.max(1, seats)));
    }

    /** True when this booking is for more than one person. */
    public boolean isParty() { return seats > 1; }

    /**
     * When a held seat lapses if it is still unpaid, or null when
     * nothing is being held. Shown to the attendee, because a deadline
     * nobody is told about is a deadline that only produces surprises.
     */
    public LocalDateTime getPaymentDueAt() { return paymentDueAt; }

    /** When this attendee was checked in, or null if they were not. */
    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public boolean isCheckedIn() { return checkedInAt != null; }

    /**
     * Whether a check-in button should be offered at all. Only a
     * confirmed place on an event that has not been called off can be
     * checked in - the server enforces the same rule, so the page cannot
     * offer an action that would then be refused.
     */
    public boolean isCheckInEligible() {
        return status == RegistrationStatus.CONFIRMED && eventStatus != EventStatus.CANCELLED;
    }
}
