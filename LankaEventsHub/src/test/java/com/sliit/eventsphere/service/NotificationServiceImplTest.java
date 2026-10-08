package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.NotificationResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.notification.InAppNotificationSender;
import com.sliit.eventsphere.notification.NotificationSender;
import com.sliit.eventsphere.repository.NotificationRepository;
import com.sliit.eventsphere.security.ActingUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    @Mock private NotificationRepository notificationRepository;

    /**
     * Built by hand so the set of senders is the thing under test.
     * The in-app sender is real - it is four lines and mocking it would
     * only restate the assertion.
     */
    private NotificationServiceImpl notificationService;

    private User owner;
    private ActingUser ownerActor;
    private ActingUser intruder;

    @BeforeEach
    void setUp() {
        notificationService = serviceWith(new InAppNotificationSender(notificationRepository));

        owner = new User("Nadeesha", "a@test.com", "hash", UserRole.ATTENDEE);
        owner.setId(2L);
        ownerActor = ActingUser.of(owner);
        intruder = new ActingUser(3L, UserRole.ATTENDEE);
    }

    @Test
    void notifyStoresAnInAppRow() {
        notificationService.notify(owner, NotificationType.TICKET_ISSUED,
                NotificationChannel.IN_APP, "Your ticket is ready");

        verify(notificationRepository).save(any(Notification.class));
    }

    // =================================================================
    // Which channels a notification actually reaches
    //
    // EMAIL was a legal enum value with nothing behind it: the database
    // accepted it, the enum listed it, and every row ever written said
    // IN_APP. These cases are what stop that being true again.
    // =================================================================

    @Test
    void withNoOtherChannelConfiguredOnlyTheInAppRowIsWritten() {
        // The default installation. Behaviour is exactly what it was
        // before email existed.
        notificationService.notify(owner, NotificationType.TICKET_ISSUED,
                NotificationChannel.IN_APP, "Your ticket is ready");

        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    void anEnabledSecondChannelAlsoReceivesTheNotification() {
        RecordingSender email = new RecordingSender(NotificationChannel.EMAIL, true);
        NotificationServiceImpl service =
                serviceWith(new InAppNotificationSender(notificationRepository), email);

        service.notify(owner, NotificationType.PAYMENT_CONFIRMATION,
                NotificationChannel.IN_APP, "Payment received");

        verify(notificationRepository).save(any(Notification.class));
        assertEquals(1, email.sent.size());
        assertEquals("Payment received", email.sent.get(0));
    }

    @Test
    void aDisabledSecondChannelIsSkippedEntirely() {
        RecordingSender email = new RecordingSender(NotificationChannel.EMAIL, false);
        NotificationServiceImpl service =
                serviceWith(new InAppNotificationSender(notificationRepository), email);

        service.notify(owner, NotificationType.PAYMENT_CONFIRMATION,
                NotificationChannel.IN_APP, "Payment received");

        assertTrue(email.sent.isEmpty(),
                "a sender that cannot deliver must not be handed messages to drop");
    }

    @Test
    void bothChannelsCarryTheSameWords() {
        // One message, written once. Two channels paraphrasing the same
        // event differently is how a support ticket starts.
        RecordingSender email = new RecordingSender(NotificationChannel.EMAIL, true);
        NotificationServiceImpl service =
                serviceWith(new InAppNotificationSender(notificationRepository), email);

        service.notify(owner, NotificationType.EVENT_CANCELLATION,
                NotificationChannel.IN_APP, "\"AI Conference\" has been cancelled.");

        ArgumentCaptor<Notification> stored = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(stored.capture());
        assertEquals(stored.getValue().getMessage(), email.sent.get(0));
    }

    @Test
    void aChannelNothingCanDeliverStillRecordsTheNotification() {
        // Asking for SMS, which has no sender. The recipient must still
        // see the notification, and the fact that the requested channel
        // did not happen must be visible rather than pretended away.
        NotificationServiceImpl service =
                serviceWith(new InAppNotificationSender(notificationRepository));

        service.notify(owner, NotificationType.EVENT_REMINDER,
                NotificationChannel.SMS, "Starts tomorrow");

        ArgumentCaptor<Notification> stored = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeastOnce()).save(stored.capture());
        assertEquals(NotificationChannel.SMS, stored.getAllValues().get(0).getChannel());
    }

    @Test
    void aFailingSenderDoesNotStopTheOthers() {
        // A sender must not throw, and if one ever does it must not take
        // the registration that produced the notification down with it.
        NotificationServiceImpl service = serviceWith(
                new InAppNotificationSender(notificationRepository),
                new RecordingSender(NotificationChannel.EMAIL, true));

        assertDoesNotThrow(() -> service.notify(owner, NotificationType.TICKET_ISSUED,
                NotificationChannel.IN_APP, "Your ticket is ready"));
        verify(notificationRepository).save(any(Notification.class));
    }

    private NotificationServiceImpl serviceWith(NotificationSender... senders) {
        return new NotificationServiceImpl(notificationRepository, List.of(senders));
    }

    /** A sender that records what it was given instead of delivering it. */
    private static final class RecordingSender implements NotificationSender {
        private final NotificationChannel channel;
        private final boolean enabled;
        private final List<String> sent = new java.util.ArrayList<>();

        RecordingSender(NotificationChannel channel, boolean enabled) {
            this.channel = channel;
            this.enabled = enabled;
        }

        @Override public NotificationChannel channel() { return channel; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void send(User user, NotificationType type, String message) {
            sent.add(message);
        }
    }

    @Test
    void aUserOnlySeesTheirOwnNotifications() {
        when(notificationRepository.findByUserIdOrderBySentAtDesc(2L))
                .thenReturn(List.of(notification(1L, false)));

        List<NotificationResponse> mine = notificationService.getMyNotifications(ownerActor);

        assertEquals(1, mine.size());
        // The query is scoped by the acting user's id, never by a parameter.
        verify(notificationRepository).findByUserIdOrderBySentAtDesc(2L);
    }

    @Test
    void markingSomebodyElsesNotificationReadIsForbidden() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(notification(1L, false)));

        assertThrows(AccessDeniedException.class,
                () -> notificationService.markRead(1L, intruder));
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void theOwnerCanMarkTheirOwnNotificationRead() {
        Notification n = notification(1L, false);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(n));

        notificationService.markRead(1L, ownerActor);

        assertTrue(n.isRead());
    }

    @Test
    void markAllReadOnlyTouchesTheActingUsersRows() {
        Notification a = notification(1L, false);
        Notification b = notification(2L, false);
        when(notificationRepository.findByUserIdAndReadFalse(2L)).thenReturn(List.of(a, b));

        assertEquals(2, notificationService.markAllRead(ownerActor));
        assertTrue(a.isRead());
        assertTrue(b.isRead());
    }

    private Notification notification(Long id, boolean read) {
        Notification n = new Notification(owner, NotificationType.TICKET_ISSUED,
                NotificationChannel.IN_APP, "message");
        n.setId(id);
        n.setRead(read);
        return n;
    }
}
