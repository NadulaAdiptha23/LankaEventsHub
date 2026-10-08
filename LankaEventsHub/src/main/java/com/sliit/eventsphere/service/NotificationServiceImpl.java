package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.NotificationResponse;
import com.sliit.eventsphere.entity.Notification;
import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.notification.NotificationSender;
import com.sliit.eventsphere.repository.NotificationRepository;
import com.sliit.eventsphere.security.ActingUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private final NotificationRepository notificationRepository;

    /**
     * Every way this system can reach somebody, injected by Spring. One
     * always-on in-app sender, plus an email sender that is only enabled
     * when a mail server has been configured. Adding SMS later means
     * adding a class, not editing this one.
     */
    private final List<NotificationSender> senders;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                    List<NotificationSender> senders) {
        this.notificationRepository = notificationRepository;
        this.senders = senders;
    }

    @Override
    @Transactional
    public void notify(User user, NotificationType type, NotificationChannel channel, String message) {
        // The requested channel is delivered first, because it is the one
        // the caller was promised - in practice always IN_APP, which is
        // the durable record everything else is a copy of.
        boolean deliveredPrimary = false;
        for (NotificationSender sender : senders) {
            if (sender.channel() == channel && sender.isEnabled()) {
                sender.send(user, type, message);
                deliveredPrimary = true;
            }
        }
        if (!deliveredPrimary) {
            // Asked for a channel nothing can deliver on. Recording the
            // row anyway is the honest fallback: the recipient still sees
            // the notification, and the log says the requested channel
            // did not happen rather than pretending it did.
            notificationRepository.save(new Notification(user, type, channel, message));
            log.warn("No enabled sender for channel {}; notification ({}) for user {} was "
                    + "recorded but not delivered on that channel.", channel, type, user.getId());
        }

        // Then mirror it to every other channel that is switched on. With
        // no mail server configured there is nothing here and the
        // behaviour is exactly what it was before email existed.
        for (NotificationSender sender : senders) {
            if (sender.channel() != channel && sender.isEnabled()) {
                sender.send(user, type, message);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getMyNotifications(ActingUser actor) {
        return notificationRepository.findByUserIdOrderBySentAtDesc(actor.id()).stream()
                .map(NotificationResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countMyUnread(ActingUser actor) {
        return notificationRepository.countByUserIdAndReadFalse(actor.id());
    }

    @Override
    @Transactional
    public int markAllRead(ActingUser actor) {
        List<Notification> unread = notificationRepository.findByUserIdAndReadFalse(actor.id());
        unread.forEach(n -> n.setRead(true));
        notificationRepository.saveAll(unread);
        return unread.size();
    }

    @Override
    @Transactional
    public void markRead(Long notificationId, ActingUser actor) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found with id " + notificationId));
        if (!actor.is(notification.getUser().getId())) {
            throw new AccessDeniedException("That notification does not belong to you.");
        }
        notification.setRead(true);
        notificationRepository.save(notification);
    }
}
