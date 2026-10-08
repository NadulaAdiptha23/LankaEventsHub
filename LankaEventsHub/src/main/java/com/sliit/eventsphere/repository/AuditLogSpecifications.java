package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.dto.AuditSearchCriteria;
import com.sliit.eventsphere.entity.AuditLog;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters for the audit-log screen, built the same way as
 * {@link EventSpecifications} and {@link RegistrationSpecifications}.
 *
 * The actor join is a LEFT join, not an inner one. audit_logs.user_id is
 * nullable by design - the trail outlives the account - and an inner join
 * would silently drop exactly the rows an investigator is most likely to
 * be looking for.
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> matching(AuditSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            String actor = trimmed(criteria.getActor());
            if (actor != null) {
                var user = root.join("user", JoinType.LEFT);
                String pattern = "%" + actor.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(user.get("fullName")), pattern),
                        cb.like(cb.lower(user.get("email")), pattern)));
            }

            String action = trimmed(criteria.getAction());
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }

            String entityType = trimmed(criteria.getEntityType());
            if (entityType != null) {
                predicates.add(cb.equal(root.get("entityType"), entityType));
            }

            if (criteria.getEntityId() != null) {
                predicates.add(cb.equal(root.get("entityId"), criteria.getEntityId()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
