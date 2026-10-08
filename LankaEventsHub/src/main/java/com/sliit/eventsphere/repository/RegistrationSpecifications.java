package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.dto.BookingSearchCriteria;
import com.sliit.eventsphere.entity.Payment;
import com.sliit.eventsphere.entity.Registration;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the admin bookings screen's filter query, the same way
 * {@link EventSpecifications} builds the event listing's.
 *
 * The interesting part is the payment filters. Payment is not a field on
 * Registration - it is a separate table pointing back at it, and the
 * mapping deliberately has no inverse association, because a
 * registration is not supposed to reach into Payment & Ticketing's data.
 * So the filter is expressed as a correlated EXISTS subquery instead of a
 * join, which also means the row count cannot be inflated by the join
 * (it could not be here, since payments.registration_id is UNIQUE, but
 * writing it as EXISTS makes that independent of the constraint).
 */
public final class RegistrationSpecifications {

    private RegistrationSpecifications() {
    }

    public static Specification<Registration> matching(BookingSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            String attendee = trimmed(criteria.getAttendee());
            if (attendee != null) {
                var user = root.join("user", JoinType.INNER);
                String pattern = "%" + attendee.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(user.get("fullName")), pattern),
                        cb.like(cb.lower(user.get("email")), pattern)));
            }

            String event = trimmed(criteria.getEvent());
            if (event != null) {
                var ev = root.join("event", JoinType.INNER);
                predicates.add(cb.like(cb.lower(ev.get("name")),
                        "%" + event.toLowerCase() + "%"));
            }

            if (criteria.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.getStatus()));
            }

            if (criteria.getPaymentStatus() != null) {
                predicates.add(cb.exists(paymentSubquery(root, query, cb, criteria)));
            } else if (criteria.isUnpaidOnly()) {
                predicates.add(cb.not(cb.exists(paymentSubquery(root, query, cb, null))));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * `exists (select 1 from Payment p where p.registration = thisRow
     *          [and p.status = :status])`
     *
     * @param criteria null to test merely for the existence of any payment
     */
    private static Subquery<Long> paymentSubquery(
            Root<Registration> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            BookingSearchCriteria criteria) {

        Subquery<Long> sub = query.subquery(Long.class);
        Root<Payment> payment = sub.from(Payment.class);
        sub.select(cb.literal(1L));

        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(payment.get("registration").get("id"), root.get("id")));
        if (criteria != null && criteria.getPaymentStatus() != null) {
            where.add(cb.equal(payment.get("status"), criteria.getPaymentStatus()));
        }
        sub.where(cb.and(where.toArray(new Predicate[0])));
        return sub;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
