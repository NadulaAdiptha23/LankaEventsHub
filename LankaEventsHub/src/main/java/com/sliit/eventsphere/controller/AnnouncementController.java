package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.AnnouncementRequest;
import com.sliit.eventsphere.dto.AnnouncementResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.AnnouncementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for announcements - the human-written half of Function 4.
 *
 * Endpoints:
 *   POST   /api/events/{id}/announcements    - start a draft (organizer/admin)
 *   GET    /api/events/{id}/announcements    - the event's announcements
 *   GET    /api/announcements/pending        - the approval queue (admin)
 *   GET    /api/announcements/{id}           - one announcement
 *   PUT    /api/announcements/{id}           - edit a draft
 *   POST   /api/announcements/{id}/submit    - send for approval
 *   POST   /api/announcements/{id}/approve   - approve and deliver (admin)
 *   POST   /api/announcements/{id}/reject    - turn down with a reason (admin)
 *   POST   /api/announcements/{id}/publish   - admin's own direct publish
 *   DELETE /api/announcements/{id}           - remove an unpublished draft
 *
 * Approve, reject and publish are admin-only, and that is enforced in the
 * service rather than by a URL rule: /submit and /approve are the same
 * shape to a matcher, and only one of them is restricted. As with every
 * controller here, nothing decides anything - each method binds its input
 * and hands an ActingUser to the service.
 */
@RestController
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @PostMapping("/api/events/{eventId:[0-9]+}/announcements")
    public ResponseEntity<AnnouncementResponse> create(
            @PathVariable Long eventId,
            @Valid @RequestBody AnnouncementRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(announcementService.create(eventId, request, ActingUser.of(principal)));
    }

    @GetMapping("/api/events/{eventId:[0-9]+}/announcements")
    public ResponseEntity<List<AnnouncementResponse>> forEvent(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.getForEvent(eventId, ActingUser.of(principal)));
    }

    /**
     * Declared before /{id} and with a literal path, so "pending" can
     * never be mistaken for an id - the same precaution /api/events uses
     * for /published and /mine.
     */
    @GetMapping("/api/announcements/pending")
    public ResponseEntity<List<AnnouncementResponse>> pending(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.getPendingApproval(ActingUser.of(principal)));
    }

    @GetMapping("/api/announcements/{id:[0-9]+}")
    public ResponseEntity<AnnouncementResponse> getOne(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.getById(id, ActingUser.of(principal)));
    }

    @PutMapping("/api/announcements/{id:[0-9]+}")
    public ResponseEntity<AnnouncementResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody AnnouncementRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.update(id, request, ActingUser.of(principal)));
    }

    @PostMapping("/api/announcements/{id:[0-9]+}/submit")
    public ResponseEntity<AnnouncementResponse> submit(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.submitForApproval(id, ActingUser.of(principal)));
    }

    @PostMapping("/api/announcements/{id:[0-9]+}/approve")
    public ResponseEntity<AnnouncementResponse> approve(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.approveAndPublish(id, ActingUser.of(principal)));
    }

    /**
     * The reason is required, and it comes in the body rather than as a
     * query parameter - a rejection reason is free text written about a
     * person's work, and it has no business in a URL or a server log.
     */
    @PostMapping("/api/announcements/{id:[0-9]+}/reject")
    public ResponseEntity<AnnouncementResponse> reject(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.reject(
                id, body == null ? null : body.get("reason"), ActingUser.of(principal)));
    }

    @PostMapping("/api/announcements/{id:[0-9]+}/publish")
    public ResponseEntity<AnnouncementResponse> publish(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(announcementService.publishDirectly(id, ActingUser.of(principal)));
    }

    @DeleteMapping("/api/announcements/{id:[0-9]+}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        announcementService.delete(id, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }
}
