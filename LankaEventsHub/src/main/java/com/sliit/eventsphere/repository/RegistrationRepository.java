package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RegistrationRepository extends JpaRepository<Registration, Long>,
        JpaSpecificationExecutor<Registration> {

    List<Registration> findByEventId(Long eventId);

    List<Registration> findByEventIdAndStatus(Long eventId, RegistrationStatus status);

    List<Registration> findByUserIdOrderByRegisteredAtDesc(Long userId);

    long countByEventIdAndStatus(Long eventId, RegistrationStatus status);

    long countByStatus(RegistrationStatus status);

    /**
     * Registration counts by status, scoped however a saved report needs.
     *
     * One query rather than one per status, and one method rather than
     * twelve near-identical ones. Any of the four narrowing parameters may
     * be null, which means "do not narrow on this" - so the same query
     * serves an attendee's own activity (userId set), an organizer's event
     * (eventId set) and the platform total (both null).
     *
     * The `:p is null or ...` shape is deliberate: it keeps the optional
     * filters in the database, where the indexes are, instead of pulling
     * every registration into Java and filtering there.
     */
    @Query("select r.status, count(r) from Registration r "
            + "where (:eventId is null or r.event.id = :eventId) "
            + "and (:userId is null or r.user.id = :userId) "
            + "and (:from is null or r.registeredAt >= :from) "
            + "and (:to is null or r.registeredAt < :to) "
            + "group by r.status")
    List<Object[]> countByStatusScoped(@Param("eventId") Long eventId,
                                         @Param("userId") Long userId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /**
     * How many PEOPLE actually turned up, same scoping.
     *
     * A sum of seats_checked_in rather than a count of rows, because one
     * booking can be a party of four and three of them may have arrived.
     * Counting rows would report that booking as one attendee, which
     * understates every attendance figure on the platform by however many
     * people book together.
     *
     * coalesce because SUM over no rows is null in SQL, and a null
     * flowing into a long would be a NullPointerException on an empty
     * report rather than a zero.
     */
    @Query("select coalesce(sum(r.seatsCheckedIn), 0) from Registration r "
            + "where r.checkedInAt is not null "
            + "and (:eventId is null or r.event.id = :eventId) "
            + "and (:userId is null or r.user.id = :userId) "
            + "and (:from is null or r.registeredAt >= :from) "
            + "and (:to is null or r.registeredAt < :to)")
    long sumSeatsAttendedScoped(@Param("eventId") Long eventId,
                                  @Param("userId") Long userId,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    /**
     * How many SEATS are held by confirmed bookings, same scoping.
     *
     * The companion to the sum above: an attendance rate is people who
     * came over people who were expected, so both halves have to be
     * counted in people. Comparing arrivals against a count of bookings
     * would show a party of four arriving in full as 400% attendance.
     */
    @Query("select coalesce(sum(r.seats), 0) from Registration r "
            + "where r.status = com.sliit.eventsphere.entity.RegistrationStatus.CONFIRMED "
            + "and (:eventId is null or r.event.id = :eventId) "
            + "and (:userId is null or r.user.id = :userId) "
            + "and (:from is null or r.registeredAt >= :from) "
            + "and (:to is null or r.registeredAt < :to)")
    long sumConfirmedSeatsScoped(@Param("eventId") Long eventId,
                                   @Param("userId") Long userId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /**
     * Everyone holding a place on this event, with their user row already
     * loaded - the audience for an announcement.
     *
     * Unlocked, because publishing an announcement changes nothing about
     * these registrations; it only reads who they belong to. The join
     * fetch is what stops a fan-out to eighty attendees becoming
     * eighty-one queries.
     *
     * PENDING_PAYMENT is included on purpose: somebody mid-checkout is
     * holding one of the event's seats and is exactly the sort of person
     * who needs to hear that the venue has moved.
     */
    @Query("select r from Registration r join fetch r.user "
            + "where r.event.id = :eventId and r.status in ("
            + "com.sliit.eventsphere.entity.RegistrationStatus.CONFIRMED, "
            + "com.sliit.eventsphere.entity.RegistrationStatus.PENDING_PAYMENT) "
            + "order by r.registeredAt asc")
    List<Registration> findPlaceHoldersWithUser(@Param("eventId") Long eventId);

    /**
     * Whether this attendee has ever registered for this event, in any
     * status including CANCELLED.
     *
     * Used only by EventAccessPolicy.canView, and the "including
     * CANCELLED" is the whole point: it is what keeps the link from "My
     * registrations" working after the organizer calls the event off.
     */
    boolean existsByEventIdAndUserId(Long eventId, Long userId);

    /**
     * The one registration for this attendee on this event that is still
     * live (CONFIRMED or WAITLISTED).
     *
     * Deliberately NOT findByEventIdAndUserId: a user who cancels and then
     * registers again leaves historical CANCELLED rows behind (they are
     * kept because payments and refunds hang off them), so an event/user
     * pair can legitimately match several rows. Only one of them can ever
     * be active - the uq_registration_active constraint in the schema
     * enforces that in the database, not just here.
     */
    @Query("select r from Registration r where r.event.id = :eventId and r.user.id = :userId "
            + "and r.status <> com.sliit.eventsphere.entity.RegistrationStatus.CANCELLED")
    Optional<Registration> findActiveByEventIdAndUserId(@Param("eventId") Long eventId,
                                                          @Param("userId") Long userId);

    /**
     * Same lookup, but taking a write lock on the matched row.
     *
     * cancelRegistration() uses this rather than the unlocked version: the
     * unlocked read let two concurrent cancellations of the SAME
     * registration both see it as active and both release a seat. Locking
     * the event first and then re-reading the registration under a lock
     * means the second transaction blocks, then sees status = CANCELLED
     * and finds nothing active to cancel.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Registration r where r.event.id = :eventId and r.user.id = :userId "
            + "and r.status <> com.sliit.eventsphere.entity.RegistrationStatus.CANCELLED")
    Optional<Registration> findActiveByEventIdAndUserIdForUpdate(@Param("eventId") Long eventId,
                                                                   @Param("userId") Long userId);

    /**
     * Every registration on this event that still holds a place, locked for
     * update. Used when an event is cancelled, so the set of people to
     * notify and refund is fixed before any of them is modified.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Registration r where r.event.id = :eventId "
            + "and r.status <> com.sliit.eventsphere.entity.RegistrationStatus.CANCELLED")
    List<Registration> findActiveByEventIdForUpdate(@Param("eventId") Long eventId);

    /**
     * One registration, locked.
     *
     * Used wherever a registration is about to be read and then written
     * in the same transaction - paying for a held seat, releasing an
     * expired hold - so the check and the write cannot be split by
     * another request doing the same thing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Registration r where r.id = :id")
    Optional<Registration> findByIdForUpdate(@Param("id") Long id);

    /**
     * Payment holds that have run out, oldest first.
     *
     * DELIBERATELY NOT LOCKED. This is a discovery query: it finds
     * candidates, and the sweep then locks each one individually in the
     * order event -> registration, which is the order every other path
     * in this system uses.
     *
     * It used to take the locks here, which inverted that order -
     * registrations first, then their event - against a cancellation
     * holding the event and waiting for the registration. Two
     * transactions could then wait on each other. Finding candidates
     * without locks and re-checking each one after taking the locks
     * costs one extra read and removes the deadlock entirely.
     *
     * Backed by idx_registrations_payment_due, so this stays a small
     * index range scan rather than a walk over every registration.
     */
    @Query("select r from Registration r "
            + "where r.status = com.sliit.eventsphere.entity.RegistrationStatus.PENDING_PAYMENT "
            + "and r.paymentDueAt < :cutoff "
            + "order by r.paymentDueAt asc")
    List<Registration> findExpiredHolds(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Confirmed headcount per event, in one grouped query (Reporting).
     *
     * Seats, not rows. This figure is shown next to the event's capacity,
     * and capacity is measured in seats - a count of bookings beside a
     * capacity of 200 would make a sold-out event look nearly empty.
     */
    @Query("select r.event.id, coalesce(sum(r.seats), 0) from Registration r "
            + "where r.status = com.sliit.eventsphere.entity.RegistrationStatus.CONFIRMED "
            + "group by r.event.id")
    List<Object[]> countConfirmedGroupedByEvent();

    /**
     * How many confirmed attendees actually turned up, per event. One
     * grouped query alongside the one above, for the same reason: a
     * per-event count inside the reporting loop would be an N+1 that
     * grows with the platform.
     *
     * Also in people rather than bookings, so it can be read against the
     * confirmed figure above without the two counting different things.
     */
    @Query("select r.event.id, coalesce(sum(r.seatsCheckedIn), 0) from Registration r "
            + "where r.checkedInAt is not null "
            + "group by r.event.id")
    List<Object[]> countCheckedInGroupedByEvent();

    /** Platform-wide attendance: how many people were checked in at all. */
    @Query("select coalesce(sum(r.seatsCheckedIn), 0) from Registration r "
            + "where r.checkedInAt is not null")
    long countByCheckedInAtIsNotNull();

    /** Attendance on one event, for the organizer's own attendee list. */
    long countByEventIdAndCheckedInAtIsNotNull(Long eventId);

    /** All registrations - active and cancelled - for one attendee on one event. */
    List<Registration> findByEventIdAndUserIdOrderByRegisteredAtDesc(Long eventId, Long userId);

    /** How many people currently hold a place (confirmed or waitlisted). */
    @Query("select count(r) from Registration r where r.event.id = :eventId "
            + "and r.status <> com.sliit.eventsphere.entity.RegistrationStatus.CANCELLED")
    long countActiveByEventId(@Param("eventId") Long eventId);

    /**
     * Locks the waitlisted registration rows for this event for the
     * duration of the transaction, so the seat freed by a cancellation can
     * only be handed to one person. See the "first-completed-wins" rule in
     * RegistrationServiceImpl.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Registration r where r.event.id = :eventId "
            + "and r.status = com.sliit.eventsphere.entity.RegistrationStatus.WAITLISTED "
            + "order by r.registeredAt asc")
    List<Registration> findWaitlistOrderedForUpdate(@Param("eventId") Long eventId);

    /**
     * Every registration belonging to one attendee, for a permanent
     * delete. Unordered and unfiltered on purpose: a purge takes the
     * cancelled history too, because leaving it behind would leave rows
     * pointing at a user row that no longer exists.
     */
    List<Registration> findByUserId(Long userId);

    /**
     * Every booking one staff member checked in.
     *
     * registrations.checked_in_by is ON DELETE SET NULL, but the rows have
     * to be nulled here as well: Hibernate holds them in its own cache and
     * would write a stale checked_in_by back over the database's change.
     */
    List<Registration> findByCheckedInById(Long checkedInById);
}
