package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Notification;
import com.sliit.eventsphere.entity.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderBySentAtDesc(Long userId);

    List<Notification> findByUserIdAndReadFalse(Long userId);

    long countByUserIdAndReadFalse(Long userId);

    /**
     * The duplicate guard for the reminder sweep.
     *
     * The scheduler may run many times before an event starts, so it has
     * to know whether this user has already been reminded about it. That
     * is answered from the notifications themselves rather than from a
     * "reminded" flag on the registration, which keeps the check correct
     * across restarts without adding a column whose only job is to
     * remember something the data already records.
     */
    boolean existsByUserIdAndTypeAndMessage(Long userId, NotificationType type, String message);

    /** Everything in one person's inbox - for a permanent delete. */
    List<Notification> findByUserId(Long userId);
}
