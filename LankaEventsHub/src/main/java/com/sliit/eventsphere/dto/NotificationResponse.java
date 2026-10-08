package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Notification;
import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;

import java.time.LocalDateTime;

public class NotificationResponse {

    private Long id;
    private NotificationType type;
    private NotificationChannel channel;
    private String message;
    private boolean read;
    private LocalDateTime sentAt;

    public static NotificationResponse from(Notification n) {
        NotificationResponse dto = new NotificationResponse();
        dto.id = n.getId();
        dto.type = n.getType();
        dto.channel = n.getChannel();
        dto.message = n.getMessage();
        dto.read = n.isRead();
        dto.sentAt = n.getSentAt();
        return dto;
    }

    public Long getId() { return id; }
    public NotificationType getType() { return type; }
    public NotificationChannel getChannel() { return channel; }
    public String getMessage() { return message; }
    public boolean isRead() { return read; }
    public LocalDateTime getSentAt() { return sentAt; }
}
