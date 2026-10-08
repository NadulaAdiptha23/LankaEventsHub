package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.EventSession;
import java.time.LocalDateTime;

public class EventSessionResponse {

    private Long id;
    private Long eventId;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String speaker;

    public static EventSessionResponse from(EventSession s) {
        EventSessionResponse dto = new EventSessionResponse();
        dto.id = s.getId();
        dto.eventId = s.getEvent().getId();
        dto.title = s.getTitle();
        dto.description = s.getDescription();
        dto.startTime = s.getStartTime();
        dto.endTime = s.getEndTime();
        dto.speaker = s.getSpeaker();
        return dto;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public String getSpeaker() { return speaker; }
}
