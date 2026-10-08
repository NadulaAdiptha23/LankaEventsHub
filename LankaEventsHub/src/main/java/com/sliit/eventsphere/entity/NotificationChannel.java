package com.sliit.eventsphere.entity;

/**
 * How a notification reaches somebody.
 *
 * IN_APP is the channel of record and is always written. EMAIL is
 * delivered by EmailNotificationSender when a mail server is configured.
 *
 * SMS is listed because the requirement documents list it and the
 * database accepts it, and NOTHING SENDS IT. That is stated here rather
 * than left to be discovered: an enum value with no implementation
 * behind it reads to anyone auditing this code like a feature that
 * exists. Implementing it means one new NotificationSender.
 */
public enum NotificationChannel {
    IN_APP,
    EMAIL,
    SMS
}
