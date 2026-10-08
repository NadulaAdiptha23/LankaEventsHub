package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * One line of the audit trail, mapped inside the service transaction so
 * the template never dereferences the lazy user association.
 *
 * actorName is deliberately nullable. audit_logs.user_id is ON DELETE SET
 * NULL, because an audit trail that disappears along with the account
 * that produced it is not an audit trail. A row whose actor has since
 * been deleted still records what was done and to what - it just cannot
 * name who any more, and the screen says so rather than showing a blank.
 */
public class AuditLogRow {

    private final Long id;
    private final Long actorId;
    private final String actorName;
    private final String actorEmail;
    private final String action;
    private final String entityType;
    private final Long entityId;
    private final String details;
    private final LocalDateTime createdAt;

    private AuditLogRow(AuditLog log) {
        this.id = log.getId();
        this.actorId = log.getUser() == null ? null : log.getUser().getId();
        this.actorName = log.getUser() == null ? null : log.getUser().getFullName();
        this.actorEmail = log.getUser() == null ? null : log.getUser().getEmail();
        this.action = log.getAction();
        this.entityType = log.getEntityType();
        this.entityId = log.getEntityId();
        this.details = log.getDetails();
        this.createdAt = log.getCreatedAt();
    }

    public static AuditLogRow from(AuditLog log) {
        return new AuditLogRow(log);
    }

    public Long getId() { return id; }
    public Long getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getDetails() { return details; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** True when the account that performed this action no longer exists. */
    public boolean isActorDeleted() { return actorId == null; }
}
