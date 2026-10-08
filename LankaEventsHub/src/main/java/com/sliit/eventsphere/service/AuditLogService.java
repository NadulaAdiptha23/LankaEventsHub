package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.AuditLogRow;
import com.sliit.eventsphere.dto.AuditSearchCriteria;
import com.sliit.eventsphere.entity.AuditLog;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.repository.AuditLogRepository;
import com.sliit.eventsphere.repository.AuditLogSpecifications;
import com.sliit.eventsphere.security.ActingUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Function 5 (User & Role Management) support: records key actions for
 * accountability, per the client's requirement ("keep a record of
 * important actions such as event creation, updates, and registration
 * changes").
 *
 * This class used to be write-only. Rows went in on every state change
 * and no endpoint, page or query ever read one back - which meant the
 * requirement was satisfied on paper and not in practice, because an
 * audit trail nobody can look at answers no questions. The read side
 * below is what makes it a trail rather than a table.
 *
 * Reading is Administrator-only. The trail names who did what to whom
 * across the whole platform; an organizer seeing it would see other
 * organizers' activity.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void log(User actor, String action, String entityType, Long entityId, String details) {
        auditLogRepository.save(new AuditLog(actor, action, entityType, entityId, details));
    }

    @Transactional(readOnly = true)
    public Page<AuditLogRow> search(AuditSearchCriteria criteria, Pageable pageable,
                                     ActingUser actor) {
        requireAdmin(actor);
        return auditLogRepository
                .findAll(AuditLogSpecifications.matching(criteria), pageable)
                .map(AuditLogRow::from);
    }

    /**
     * The action names and entity types that actually occur, so the filter
     * offers what exists rather than a hard-coded list that drifts as new
     * actions are added.
     */
    @Transactional(readOnly = true)
    public List<String> getActions(ActingUser actor) {
        requireAdmin(actor);
        return auditLogRepository.findDistinctActions();
    }

    @Transactional(readOnly = true)
    public List<String> getEntityTypes(ActingUser actor) {
        requireAdmin(actor);
        return auditLogRepository.findDistinctEntityTypes();
    }

    /** Everything recorded against one record, oldest first - its history. */
    @Transactional(readOnly = true)
    public List<AuditLogRow> getHistoryFor(String entityType, Long entityId, ActingUser actor) {
        requireAdmin(actor);
        return auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(AuditLogRow::from)
                .toList();
    }

    private void requireAdmin(ActingUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException("Only an Administrator can read the audit log.");
        }
    }
}
