package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Payment;
import com.sliit.eventsphere.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Completed payments and what they came to, scoped however a saved
     * report needs - one event, one attendee, a date window, or the whole
     * platform when every parameter is null.
     *
     * Returns a single row: [count, sum]. Only COMPLETED payments are
     * counted, which is BR-28 - a pending or failed attempt took no money
     * and must not appear as revenue.
     *
     * The window is on paidAt rather than createdAt, because the question
     * a revenue report asks is when the money arrived, not when somebody
     * opened a checkout page.
     */
    @Query("select count(p), coalesce(sum(p.finalAmount), 0) from Payment p "
            + "where p.status = com.sliit.eventsphere.entity.PaymentStatus.COMPLETED "
            + "and (:eventId is null or p.registration.event.id = :eventId) "
            + "and (:userId is null or p.registration.user.id = :userId) "
            + "and (:from is null or p.paidAt >= :from) "
            + "and (:to is null or p.paidAt < :to)")
    List<Object[]> completedCountAndSumScoped(@Param("eventId") Long eventId,
                                                 @Param("userId") Long userId,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * At most one payment per registration - payments.registration_id is
     * UNIQUE in the schema, so this can never legitimately match more
     * than one row.
     */
    Optional<Payment> findByRegistrationId(Long registrationId);

    /**
     * The same lookup, holding a write lock on the payment row.
     *
     * Used when somebody at a door marks an on-arrival payment received.
     * Two staff members tapping the same name at the same moment would
     * otherwise both read a PENDING payment, both write COMPLETED, and
     * both confirm the booking - which is harmless twice and is not
     * harmless the day a refund is raised for each of them.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.registration.id = :registrationId")
    Optional<Payment> findByRegistrationIdForUpdate(@Param("registrationId") Long registrationId);

    boolean existsByRegistrationId(Long registrationId);

    List<Payment> findByStatus(PaymentStatus status);

    /**
     * Everybody on one event who still owes money at the door, oldest
     * booking first - the organizer's collection list.
     *
     * The registration and its user are join-fetched because the list
     * renders a name per row; without it a door list of forty people is
     * forty-one queries, on a phone, at a door.
     */
    @Query("select p from Payment p "
            + "join fetch p.registration r "
            + "join fetch r.user "
            + "where r.event.id = :eventId "
            + "and p.method = com.sliit.eventsphere.entity.PaymentMethod.ON_ARRIVAL "
            + "and p.status = com.sliit.eventsphere.entity.PaymentStatus.PENDING "
            + "order by r.registeredAt asc")
    List<Payment> findOwedAtTheDoor(@Param("eventId") Long eventId);

    /**
     * Every payment for a page of registrations, in ONE query.
     *
     * The admin bookings screen shows a payment against each booking. Doing
     * that with findByRegistrationId per row is the N+1 select problem: one
     * query for the page plus one per line. This takes the whole page's
     * worth in a single round trip, and the caller indexes it by
     * registration id.
     */
    List<Payment> findByRegistrationIdIn(Collection<Long> registrationIds);

    /**
     * Has this discount ever produced a price?
     *
     * Asked before deleting a discount. Answering it with findAll() and a
     * stream would pull every payment on the platform into memory to
     * decide one boolean; this is a single indexed COUNT.
     */
    boolean existsByDiscountId(Long discountId);

    /** Used by Reporting & Analytics (Function 6) for the revenue summary. */
    @Query("select coalesce(sum(p.finalAmount), 0) from Payment p where p.status = "
            + "com.sliit.eventsphere.entity.PaymentStatus.COMPLETED")
    BigDecimal sumCompletedRevenue();
}
