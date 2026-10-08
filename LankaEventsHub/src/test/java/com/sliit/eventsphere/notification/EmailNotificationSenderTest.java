package com.sliit.eventsphere.notification;

import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules around email delivery, as opposed to the protocol itself
 * (that is SmtpClientTest's job).
 *
 * The cases that matter are the ones about NOT sending: off unless
 * configured, and never throwing, because a notification is a side
 * effect of something the user actually did and a mail server being
 * unreachable must not roll that back.
 */
class EmailNotificationSenderTest {

    private User attendee() {
        User user = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        user.setId(2L);
        return user;
    }

    private MailProperties configured(int port) {
        return new MailProperties("localhost", port, "", "",
                "no-reply@eventsphere.test", "EventSphere", false, 3000);
    }

    private MailProperties unconfigured() {
        return new MailProperties("", 587, "", "", "", "EventSphere", true, 3000);
    }

    // =================================================================
    // Off unless configured
    // =================================================================

    @Test
    void isDisabledWhenNoMailHostIsConfigured() {
        assertFalse(new EmailNotificationSender(unconfigured()).isEnabled());
    }

    @Test
    void isEnabledOnceAHostAndFromAddressExist() {
        assertTrue(new EmailNotificationSender(configured(2525)).isEnabled());
    }

    @Test
    void aHostWithNoFromAddressIsNotConsideredConfigured() {
        // Mail with no sender is mail no server will accept, so this is
        // "not configured" rather than "configured and broken".
        MailProperties halfConfigured =
                new MailProperties("localhost", 2525, "", "", "", "EventSphere", false, 3000);

        assertFalse(halfConfigured.isConfigured());
    }

    @Test
    void theFromAddressFallsBackToTheUsername() {
        // What makes a typical relay work with two variables, not three.
        MailProperties properties = new MailProperties("localhost", 2525,
                "no-reply@example.com", "secret", "", "EventSphere", false, 3000);

        assertEquals("no-reply@example.com", properties.getFrom());
        assertTrue(properties.isConfigured());
    }

    @Test
    void sendingWhileDisabledDoesNothingAtAll() {
        // Not "queues it for later" and not "throws": an installation
        // that never asked for email must behave exactly as it did
        // before email existed.
        EmailNotificationSender sender = new EmailNotificationSender(unconfigured());

        assertDoesNotThrow(() -> sender.send(attendee(),
                NotificationType.REGISTRATION_CONFIRMATION, "You're confirmed."));
    }

    @Test
    void theChannelIsEmail() {
        assertEquals(NotificationChannel.EMAIL,
                new EmailNotificationSender(unconfigured()).channel());
    }

    // =================================================================
    // Never throwing
    // =================================================================

    @Test
    void anUnreachableMailServerDoesNotThrowAtTheCaller() throws Exception {
        // Port 1: nothing listens. The send is queued, fails on the
        // background thread, and the caller is never told - which is the
        // entire point, because the caller is in the middle of
        // registering somebody for an event.
        EmailNotificationSender sender = new EmailNotificationSender(configured(1));

        assertDoesNotThrow(() -> sender.send(attendee(),
                NotificationType.REGISTRATION_CONFIRMATION, "You're confirmed."));

        // Give the background thread a moment to fail, and confirm that
        // failing did not take anything else with it.
        Thread.sleep(200);
        assertDoesNotThrow(() -> sender.send(attendee(),
                NotificationType.PAYMENT_CONFIRMATION, "Payment received."));
    }

    @Test
    void aUserWithNoEmailAddressIsSkippedRatherThanCrashing() {
        User noAddress = new User("Nobody", "placeholder@test.com", "hash", UserRole.ATTENDEE);
        noAddress.setId(3L);
        noAddress.setEmail(null);
        EmailNotificationSender sender = new EmailNotificationSender(configured(2525));

        assertDoesNotThrow(() -> sender.send(noAddress,
                NotificationType.EVENT_REMINDER, "Starts tomorrow."));
    }

    // =================================================================
    // What the message says
    // =================================================================

    @Test
    void theSubjectIsDerivedFromTheNotificationTypeSoInboxesAreScannable() {
        assertEquals("EventSphere: Registration confirmation",
                EmailNotificationSender.subjectFor(NotificationType.REGISTRATION_CONFIRMATION));
        assertEquals("EventSphere: Event reminder",
                EmailNotificationSender.subjectFor(NotificationType.EVENT_REMINDER));
    }

    @Test
    void everyNotificationTypeProducesAReadableSubject() {
        // Built from the enum rather than a lookup table, so a new value
        // cannot arrive without a subject line.
        for (NotificationType type : NotificationType.values()) {
            String subject = EmailNotificationSender.subjectFor(type);
            assertTrue(subject.startsWith("EventSphere: "), subject);
            assertFalse(subject.contains("_"), "subject still reads like a constant: " + subject);
        }
    }

    @Test
    void theBodyCarriesTheSameWordsTheApplicationShows() {
        // One message, written once. If the email paraphrased, the email
        // and the notifications page could disagree about the same event.
        String message = "A seat for \"AI Conference\" is held for you.";

        String body = EmailNotificationSender.bodyFor(attendee(), message);

        assertTrue(body.contains(message), body);
        assertTrue(body.contains("Nadeesha Silva"), body);
    }

    @Test
    void theBodyStillGreetsSomebodyWithNoNameRecorded() {
        User nameless = new User("x", "a@test.com", "hash", UserRole.ATTENDEE);
        nameless.setFullName(null);

        assertTrue(EmailNotificationSender.bodyFor(nameless, "Hello").contains("Hello there"));
    }

    @Test
    void mailPropertiesNeverPrintThePassword() {
        // toString ends up in logs, and logs are read, shipped and kept
        // far more casually than configuration is.
        MailProperties properties = new MailProperties("smtp.example.com", 587,
                "no-reply@example.com", "hunter2", "", "EventSphere", true, 3000);

        String printed = properties.toString();

        assertFalse(printed.contains("hunter2"), printed);
        assertTrue(printed.contains("smtp.example.com"), printed);
    }

    @Test
    void sendersAreOrderIndependentlyIdentifiable() {
        // NotificationServiceImpl picks senders by channel, not by
        // position in the injected list.
        List<NotificationSender> senders = List.of(
                new EmailNotificationSender(unconfigured()));

        assertEquals(1, senders.stream().filter(s -> s.channel() == NotificationChannel.EMAIL).count());
    }
}
