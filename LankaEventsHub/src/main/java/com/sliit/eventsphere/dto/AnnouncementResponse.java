package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Announcement;
import com.sliit.eventsphere.entity.AnnouncementStatus;

import java.time.LocalDateTime;

/**
 * An announcement as a screen or an API client sees it.
 *
 * Built field by field from the entity inside the service transaction,
 * the same as every other response here - so a column added to
 * Announcement later cannot leak into a JSON response by accident.
 */
public class AnnouncementResponse {

    private Long id;
    private Long eventId;
    private String eventName;
    private Long authorId;
    private String authorName;
    private String title;
    private String message;
    private AnnouncementStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime submittedAt;
    private LocalDateTime decidedAt;
    private String decidedByName;
    private String rejectReason;
    private LocalDateTime publishedAt;
    private Integer recipientCount;

    public static AnnouncementResponse from(Announcement a) {
        AnnouncementResponse dto = new AnnouncementResponse();
        dto.id = a.getId();
        dto.eventId = a.getEvent().getId();
        dto.eventName = a.getEvent().getName();
        dto.authorId = a.getAuthor().getId();
        dto.authorName = a.getAuthor().getFullName();
        dto.title = a.getTitle();
        dto.message = a.getMessage();
        dto.status = a.getStatus();
        dto.createdAt = a.getCreatedAt();
        dto.submittedAt = a.getSubmittedAt();
        dto.decidedAt = a.getDecidedAt();
        dto.decidedByName = a.getDecidedBy() == null ? null : a.getDecidedBy().getFullName();
        dto.rejectReason = a.getRejectReason();
        dto.publishedAt = a.getPublishedAt();
        dto.recipientCount = a.getRecipientCount();
        return dto;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public Long getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public AnnouncementStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public String getDecidedByName() { return decidedByName; }
    public String getRejectReason() { return rejectReason; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public Integer getRecipientCount() { return recipientCount; }

    /** Whether the author may still change the text - drives the edit button. */
    public boolean isEditable() {
        return status == AnnouncementStatus.DRAFT || status == AnnouncementStatus.REJECTED;
    }

    /** Whether it is waiting on an administrator. */
    public boolean isAwaitingApproval() {
        return status == AnnouncementStatus.PENDING_APPROVAL;
    }

    public boolean isPublished() {
        return status == AnnouncementStatus.PUBLISHED;
    }
}
