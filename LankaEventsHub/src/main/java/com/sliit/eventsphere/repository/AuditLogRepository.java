package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>,
        JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByEntityTypeAndEntityId(String entityType, Long entityId);

    /** The distinct action names actually present, to populate the filter. */
    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findDistinctActions();

    /** The distinct entity types actually present, to populate the filter. */
    @Query("select distinct a.entityType from AuditLog a order by a.entityType")
    List<String> findDistinctEntityTypes();

    /**
     * Every audit row naming one person as the actor.
     *
     * Used to NULL the reference rather than to delete the rows: an audit
     * trail that disappears when the account does is not an audit trail.
     * The details column keeps the name in prose, which is the point -
     * "who did this" survives the account being removed.
     */
    List<AuditLog> findByUserId(Long userId);
}
