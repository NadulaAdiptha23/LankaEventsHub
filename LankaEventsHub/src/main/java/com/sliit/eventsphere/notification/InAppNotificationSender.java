package com.sliit.eventsphere.notification;

import com.sliit.eventsphere.entity.Notification;
import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The channel of record.
 *
 * Writing the row IS the delivery: the recipient reads it on their
 * /notifications page, it persists, and it is what the reminder sweep
 * checks to avoid sending the same reminder twice. Always enabled,
 * because a notification that is not recorded anywhere is one nobody can
 * go back and find.
 */
@Component
public class InAppNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(InAppNotificationSender.class);

    private final NotificationRepository notificationRepository;

    public InAppNotificationSender(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void send(User user, NotificationType type, String message) {
        notificationRepository.save(
                new Notification(user, type, NotificationChannel.IN_APP, message));
        // The user id, not the email address: application logs are read,
        // shipped and kept far more casually than the users table, and an
        // email address is personal data that adds nothing to the trace.
        log.info("In-app notification stored for user {} ({})", user.getId(), type);
    }
}
