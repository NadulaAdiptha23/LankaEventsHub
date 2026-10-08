package com.sliit.eventsphere.notification;

import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.User;

/**
 * One way of getting a notification to somebody.
 *
 * This interface is the seam the EMAIL channel needed and did not have.
 * NotificationChannel has listed EMAIL and SMS since the first version
 * of this system, and the notifications table has accepted them, but
 * every row ever written said IN_APP because there was nothing behind
 * the other two - an enum value with no implementation, which reads to
 * anyone auditing the code like a feature that exists.
 *
 * Implementations say which channel they are and whether they are
 * switched on. NotificationServiceImpl asks; it does not know how any
 * of them work, so adding SMS later is a new class and no edit to the
 * service.
 */
public interface NotificationSender {

    /** The channel this sender delivers on. */
    NotificationChannel channel();

    /**
     * Whether this sender can actually deliver right now.
     *
     * A sender that is not configured reports false rather than
     * accepting messages and dropping them, which is the difference
     * between "email is off" and "email is broken and nobody noticed".
     */
    boolean isEnabled();

    /**
     * Delivers one notification.
     *
     * Implementations must not throw. A notification is a side effect of
     * something the user actually did - registering, paying, cancelling
     * - and a mail server being unreachable must never roll that back or
     * show them an error about it. Failures are logged and swallowed
     * here, deliberately, which is the one place in this codebase where
     * that is the right thing to do.
     */
    void send(User user, NotificationType type, String message);
}
