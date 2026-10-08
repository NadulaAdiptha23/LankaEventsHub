package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.dto.EventSearchCriteria;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the event listing page's filter query.
 *
 * Why a Specification rather than one more `@Query` with
 * `:param is null or ...` branches: with eight optional filters that
 * string becomes unreadable, and - more practically - binding a null
 * LocalDate or a null enum into an `is null` comparison is exactly where
 * JPQL parameter-type inference gets fragile. Here each filter is only
 * added to the predicate list when it actually has a value, so a filter
 * the visitor left blank contributes no SQL at all.
 *
 * Filtering still happens in the database, not in Java: the whole point
 * is that an empty filter form does not pull every row into memory.
 */
public final class EventSpecifications {

    private EventSpecifications() {
    }

    public static Specification<Event> matching(EventStatus status, EventSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), status));

            String keyword = trimmed(criteria.getKeyword());
            if (keyword != null) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern),
                        cb.like(cb.lower(root.get("description")), pattern)));
            }

            String category = trimmed(criteria.getCategory());
            if (category != null) {
                predicates.add(cb.equal(cb.lower(root.get("category")), category.toLowerCase()));
            }

            String venue = trimmed(criteria.getVenue());
            if (venue != null) {
                predicates.add(cb.like(cb.lower(root.get("venue")),
                        "%" + venue.toLowerCase() + "%"));
            }

            // A date filter is inclusive of the whole day the visitor
            // picked: "from the 12th" means from 00:00 on the 12th, and
            // "to the 12th" means up to the last instant of the 12th.
            // Anything else quietly hides the events on the boundary day.
            LocalDate from = criteria.getDateFrom();
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("startDatetime"), from.atStartOfDay()));
            }
            LocalDate to = criteria.getDateTo();
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(
                        root.<LocalDateTime>get("startDatetime"), to.atTime(LocalTime.MAX)));
            }

            if (criteria.getMaxFee() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("fee"), criteria.getMaxFee()));
            }

            if (criteria.getEventType() != null) {
                predicates.add(cb.equal(root.get("eventType"), criteria.getEventType()));
            }

            if (criteria.isAvailableOnly()) {
                predicates.add(cb.lessThan(root.get("seatsTaken"), root.get("capacity")));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
