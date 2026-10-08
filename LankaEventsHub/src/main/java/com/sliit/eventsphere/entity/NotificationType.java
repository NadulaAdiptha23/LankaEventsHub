package com.sliit.eventsphere.entity;

/**
 * What a notification is about.
 *
 * The first ten are produced by the system reacting to something it did,
 * and their wording lives in the code that produces them - which is what
 * bounds them. ANNOUNCEMENT is the exception: its text is written by a
 * person, which is why it is the only type that has to be approved before
 * it can reach anybody. See the announcements table and
 * AnnouncementService.
 *
 * Kept in the same order as the CHECK constraint on notifications.type,
 * so the two can be read side by side.
 */
public enum NotificationType {
    REGISTRATION_CONFIRMATION,
    PAYMENT_CONFIRMATION,
    TICKET_ISSUED,
    WAITLIST_UPDATE,
    EVENT_CHANGE,
    EVENT_CANCELLATION,
    REFUND_PROCESSED,
    EVENT_REMINDER,
    INVITATION_APPROVED,
    INVITATION_REJECTED,

    /** A message written by an Organizer or Administrator, not by the system. */
    ANNOUNCEMENT
}
