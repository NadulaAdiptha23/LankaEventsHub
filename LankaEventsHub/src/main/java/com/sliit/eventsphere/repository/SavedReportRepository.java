package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.SavedReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedReportRepository extends JpaRepository<SavedReport, Long> {

    /**
     * One person's saved reports, newest first.
     *
     * There is deliberately no findAll-style query anywhere above this
     * line. Saved reports are personal, and an administrator has no more
     * business reading somebody else's saved questions than their
     * notifications - so the repository does not offer a method that would
     * let a careless caller do it.
     */
    List<SavedReport> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /**
     * Backs the friendly duplicate-title message.
     *
     * The uq_saved_report_title constraint is what actually enforces
     * uniqueness; this exists so the user gets "you already have a report
     * called that" instead of a constraint violation page.
     */
    boolean existsByOwnerIdAndTitleIgnoreCase(Long ownerId, String title);

    /** The same check when editing, ignoring the row being edited. */
    boolean existsByOwnerIdAndTitleIgnoreCaseAndIdNot(Long ownerId, String title, Long id);

    long countByOwnerId(Long ownerId);

    /** Every report one person saved - for a permanent delete. */
    List<SavedReport> findByOwnerId(Long ownerId);

    /** Every report pointed at one event - for a permanent delete. */
    List<SavedReport> findByEventId(Long eventId);
}
