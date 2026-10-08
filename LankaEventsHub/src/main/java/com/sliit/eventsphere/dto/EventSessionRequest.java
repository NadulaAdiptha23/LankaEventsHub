package com.sliit.eventsphere.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public class EventSessionRequest {

    @NotBlank(message = "Session title is required")
    @Size(max = 150, message = "Session title must be 150 characters or fewer")
    private String title;

    @Size(max = 5000, message = "Session description must be 5000 characters or fewer")
    private String description;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Size(max = 150, message = "Speaker name must be 150 characters or fewer")
    private String speaker;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
}
