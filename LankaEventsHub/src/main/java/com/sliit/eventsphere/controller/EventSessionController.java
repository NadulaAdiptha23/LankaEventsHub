package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.EventSessionRequest;
import com.sliit.eventsphere.dto.EventSessionResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events/{eventId:[0-9]+}/sessions")
public class EventSessionController {

    private final EventSessionService eventSessionService;

    public EventSessionController(EventSessionService eventSessionService) {
        this.eventSessionService = eventSessionService;
    }

    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PostMapping
    public ResponseEntity<EventSessionResponse> addSession(@PathVariable Long eventId,
                                                             @Valid @RequestBody EventSessionRequest request,
                                                             @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventSessionService.addSession(eventId, request, ActingUser.of(principal)));
    }

    /**
     * Public in the same sense the event's own detail page is public: the
     * agenda is visible to whoever may see the event, and to nobody else.
     * The principal is null for an anonymous visitor.
     */
    @GetMapping
    public ResponseEntity<List<EventSessionResponse>> getSessions(
            @PathVariable Long eventId,
            @AuthenticationPrincipal UserPrincipal principal) {
        ActingUser actor = principal == null ? null : ActingUser.of(principal);
        return ResponseEntity.ok(eventSessionService.getSessionsForEvent(eventId, actor));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PutMapping("/{sessionId:[0-9]+}")
    public ResponseEntity<EventSessionResponse> updateSession(@PathVariable Long eventId,
                                                                @PathVariable Long sessionId,
                                                                @Valid @RequestBody EventSessionRequest request,
                                                                @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(eventSessionService.updateSession(
                eventId, sessionId, request, ActingUser.of(principal)));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @DeleteMapping("/{sessionId:[0-9]+}")
    public ResponseEntity<Void> removeSession(@PathVariable Long eventId,
                                               @PathVariable Long sessionId,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        eventSessionService.removeSession(eventId, sessionId, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }
}
