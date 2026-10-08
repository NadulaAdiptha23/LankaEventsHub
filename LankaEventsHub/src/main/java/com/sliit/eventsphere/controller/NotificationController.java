package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.NotificationResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Function 4: Notifications - read side.
 *
 * Every method is scoped to the authenticated caller, so a notification
 * id from somebody else's account is useless here.
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationResponse>> mine(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(notificationService.getMyNotifications(ActingUser.of(principal)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("unread",
                notificationService.countMyUnread(ActingUser.of(principal))));
    }

    @PostMapping("/{id:[0-9]+}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markRead(id, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Integer>> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(Map.of("marked",
                notificationService.markAllRead(ActingUser.of(principal))));
    }
}
