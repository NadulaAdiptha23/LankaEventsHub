package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Refund;
import com.sliit.eventsphere.entity.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findByStatus(RefundStatus status);

    /**
     * One refund, locked for update.
     *
     * Advancing a refund is a read-then-write on a single row, and the
     * read was unlocked: two administrators pressing "Advance" at the same
     * moment could both read PROCESSED, both write COMPLETED, and both
     * reach the code that tells the attendee their money is on its way.
     * The status machine was already correct; it just had no way to see
     * the other transaction. Locking the row is what makes the switch a
     * decision rather than a guess.
     *
     * Same shape as findByIdForUpdate on registrations, deliberately - one
     * row, one lock, taken before the status is read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);

    /**
     * A payment can have at most one refund - refunds.payment_id is UNIQUE
     * in the schema, which is what actually prevents a second refund being
     * created if a cancellation is somehow processed twice.
     */
    Optional<Refund> findByPaymentId(Long paymentId);

    boolean existsByPaymentId(Long paymentId);

    /** Every refund for a page of payments, in one query - see findByRegistrationIdIn. */
    List<Refund> findByPaymentIdIn(Collection<Long> paymentIds);

    long countByStatus(RefundStatus status);

    /** Newest first, for the admin refund queue. */
    List<Refund> findAllByOrderByRequestedAtDesc();

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.status = "
            + "com.sliit.eventsphere.entity.RefundStatus.COMPLETED")
    java.math.BigDecimal sumCompletedRefunds();

    /**
     * The same sum, scoped the way a saved report needs it - one event,
     * one attendee, a date window, or everything when the relevant
     * parameter is null.
     *
     * Only COMPLETED refunds count, for the same reason only COMPLETED
     * payments count as revenue: a refund that is still PENDING has not
     * given anybody their money back yet, and reporting it as though it
     * had would make net revenue understate what the platform holds.
     *
     * from/to are scoped against processedAt (when the refund actually
     * completed), the same way completedCountAndSumScoped scopes payments
     * against paidAt - so a report's "Net" figure subtracts refunds that
     * happened in the same window as the revenue it is measured against,
     * rather than every refund ever issued regardless of when.
     */
    @Query("select coalesce(sum(r.amount), 0) from Refund r "
            + "where r.status = com.sliit.eventsphere.entity.RefundStatus.COMPLETED "
            + "and (:eventId is null or r.payment.registration.event.id = :eventId) "
            + "and (:userId is null or r.payment.registration.user.id = :userId) "
            + "and (:from is null or r.processedAt >= :from) "
            + "and (:to is null or r.processedAt < :to)")
    java.math.BigDecimal sumCompletedRefundsScoped(@Param("eventId") Long eventId,
                                                      @Param("userId") Long userId,
                                                      @Param("from") java.time.LocalDateTime from,
                                                      @Param("to") java.time.LocalDateTime to);
}
