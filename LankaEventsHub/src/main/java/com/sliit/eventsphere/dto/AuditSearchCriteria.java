package com.sliit.eventsphere.dto;

/** The filter form on the admin audit-log screen. Every field optional. */
public class AuditSearchCriteria {

    /** Free text matched against the acting user's name and e-mail. */
    private String actor;

    /** Exact action, e.g. ADMIN_CANCEL_REGISTRATION. */
    private String action;

    /** Exact entity type, e.g. Registration. */
    private String entityType;

    /** Narrows to one record once an entity type is chosen. */
    private Long entityId;

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public boolean isEmpty() {
        return blank(actor) && blank(action) && blank(entityType) && entityId == null;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
