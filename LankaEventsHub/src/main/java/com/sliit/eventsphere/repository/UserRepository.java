package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    List<User> findByRole(UserRole role);

    boolean existsByEmail(String email);

    /**
     * How many administrators can actually still sign in.
     *
     * Kept for reporting and for tests; the last-administrator guard uses
     * {@link #findActiveByRoleForUpdate} instead, because a count answers
     * a question about a moment that has already passed by the time the
     * caller acts on it.
     */
    long countByRoleAndActiveTrue(UserRole role);

    /**
     * Every user holding this role who can still sign in, with a write lock
     * on each row, ordered by id.
     *
     * This exists for one guard: the last active administrator may not be
     * demoted or suspended. Counting was not enough. With two
     * administrators and two simultaneous requests, both transactions read
     * "2", both concluded they were not touching the last one, and the
     * platform ended up with zero administrators and no way back in through
     * the application - the one failure here that cannot be undone from
     * inside the product.
     *
     * A count locks nothing, so there is no way to make it safe. Locking
     * the rows themselves is: the second transaction blocks until the first
     * commits, then re-reads and sees the administrator it was counting on
     * is gone. The ordering by id matters - it gives both transactions the
     * same lock order, so they queue instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.active = true order by u.id")
    List<User> findActiveByRoleForUpdate(@Param("role") UserRole role);

    List<User> findAllByOrderByFullNameAsc();
}
