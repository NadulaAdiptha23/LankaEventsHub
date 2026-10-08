package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.EventInvitation;
import com.sliit.eventsphere.entity.InvitationStatus;
import java.time.LocalDateTime;

public class InvitationResponse {

    private Long id;
    private Long eventId;
    private Long userId;
    private String userName;
    private InvitationStatus status;
    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;

    public static InvitationResponse from(EventInvitation i) {
        InvitationResponse dto = new InvitationResponse();
        dto.id = i.getId();
        dto.eventId = i.getEvent().getId();
        dto.userId = i.getUser().getId();
        dto.userName = i.getUser().getFullName();
        dto.status = i.getStatus();
        dto.requestedAt = i.getRequestedAt();
        dto.decidedAt = i.getDecidedAt();
        return dto;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public Long getUserId() { return userId; }
    public String getUserName() { return userName; }
    public InvitationStatus getStatus() { return status; }
    public LocalDateTime getRequestedAt() { return requestedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
}
