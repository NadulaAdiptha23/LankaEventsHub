package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.EventInvitation;
import com.sliit.eventsphere.entity.InvitationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventInvitationRepository extends JpaRepository<EventInvitation, Long> {
    List<EventInvitation> findByEventId(Long eventId);
    List<EventInvitation> findByEventIdAndStatus(Long eventId, InvitationStatus status);
    Optional<EventInvitation> findByEventIdAndUserId(Long eventId, Long userId);

    /**
     * One invitation, locked for update.
     *
     * Deciding an invitation is a read-then-write on one row guarded by a
     * status check, and the read was unlocked. An organizer and an
     * administrator working the same queue - or one organizer
     * double-clicking Approve - could both read PENDING and both apply a
     * decision, which sent the requester two notifications and, if they
     * clicked different buttons, left the outcome down to whichever
     * transaction committed last.
     *
     * The status check was never wrong; it simply could not see the other
     * transaction. Locking the row first is what makes "is this still
     * PENDING?" an answer rather than a snapshot.
     *
     * Note the bulk path locks the same way, one row at a time and in the
     * order the ids were given, so two overlapping bulk decisions serialize
     * on the first row they share instead of interleaving.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from EventInvitation i where i.id = :id")
    Optional<EventInvitation> findByIdForUpdate(@Param("id") Long id);

    /** Every invitation belonging to one person - for a permanent delete. */
    List<EventInvitation> findByUserId(Long userId);
}
