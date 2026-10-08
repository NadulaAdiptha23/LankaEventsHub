package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.NotificationResponse;
import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;

/**
 * Function 4: Notifications.
 *
 * Scope, stated plainly.
 *
 * IN_APP is the channel of record: notify() always writes a row the
 * recipient sees on their /notifications page, and that row is what
 * persists and what the reminder sweep checks against.
 *
 * EMAIL is delivered as well, when - and only when - a mail server has
 * been configured through MAIL_HOST and the rest (see MailProperties).
 * With nothing configured, email is off and this behaves exactly as it
 * did when in-app was the only channel; no message is queued, dropped or
 * silently lost. Mail is sent on a background thread and can never fail
 * the action that produced the notification.
 *
 * SMS remains an enum value with nothing behind it. The requirement list
 * names EMAIL, and that is what has been built; claiming SMS as well
 * would be the exact defect this change exists to fix. Adding it later
 * means writing one more NotificationSender, and no edit to this
 * interface or its implementation.
 */
public interface NotificationService {

    /**
     * Notifies one user.
     *
     * The notification is delivered on the requested channel and
     * mirrored to every other channel that is switched on, so a
     * configured mail server means the recipient gets an email as well
     * as the in-app row without any caller having to ask for it.
     */
    void notify(User user, NotificationType type, NotificationChannel channel, String message);

    /** The acting user's own notifications, newest first. */
    List<NotificationResponse> getMyNotifications(ActingUser actor);

    /** How many of the acting user's notifications are unread. */
    long countMyUnread(ActingUser actor);

    /** Marks all of the acting user's notifications read. */
    int markAllRead(ActingUser actor);

    /**
     * Marks one notification read. Throws AccessDeniedException if it
     * belongs to somebody else - a notification id is not a capability.
     */
    void markRead(Long notificationId, ActingUser actor);
}
