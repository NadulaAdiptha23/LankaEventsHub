package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Announcement;
import com.sliit.eventsphere.entity.AnnouncementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** The announcements on one event, newest first - the organizer's list. */
    List<Announcement> findByEventIdOrderByCreatedAtDesc(Long eventId);

    /** Everything one person has written, across all of their events. */
    List<Announcement> findByAuthorIdOrderByCreatedAtDesc(Long authorId);

    /**
     * The administrator's approval queue: oldest submission first, so
     * nothing waits indefinitely behind newer work.
     */
    List<Announcement> findByStatusOrderBySubmittedAtAsc(AnnouncementStatus status);

    long countByStatus(AnnouncementStatus status);

    /**
     * One announcement, locked for update.
     *
     * Approving is read status, check it is PENDING_APPROVAL, then write
     * PUBLISHED and fan out a notification to every attendee. Unlocked,
     * two administrators clearing the same queue could both read
     * PENDING_APPROVAL and both publish - and the visible result would be
     * every attendee receiving the same announcement twice, which is
     * precisely the failure this whole approval step exists to prevent.
     *
     * Same shape as the invitation and refund locks: one row, one lock,
     * taken before the status is read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Announcement a where a.id = :id")
    Optional<Announcement> findByIdForUpdate(@Param("id") Long id);

    /** Every announcement one person wrote - for a permanent delete. */
    List<Announcement> findByAuthorId(Long authorId);

    /**
     * Announcements this person DECIDED on, rather than wrote.
     *
     * The column is ON DELETE SET NULL in the schema, but nulling it has
     * to happen here too: Hibernate holds these rows in its own cache and
     * would otherwise write a stale decided_by back over the database's
     * own change.
     */
    List<Announcement> findByDecidedById(Long decidedById);
}
