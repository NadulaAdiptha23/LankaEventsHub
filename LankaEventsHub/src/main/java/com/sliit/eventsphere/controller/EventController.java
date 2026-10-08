package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.EventCreateRequest;
import com.sliit.eventsphere.dto.EventResponse;
import com.sliit.eventsphere.dto.EventUpdateRequest;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for Function 1: Event Creation & Management.
 *
 * Endpoints:
 *   POST   /api/events               - create a new event (DRAFT)
 *   POST   /api/events/{id}/submit   - organizer submits a draft (-> PENDING_APPROVAL)
 *   POST   /api/events/{id}/approve  - admin approves an event (-> PUBLISHED)
 *   POST   /api/events/{id}/complete - organizer/admin closes a finished event
 *   GET    /api/events               - list all events (admin view)
 *   GET    /api/events/mine          - the caller's own events (organizer view)
 *   GET    /api/events/published     - list PUBLISHED events (public)
 *   GET    /api/events/{id}          - get one event (public)
 *   PUT    /api/events/{id}          - update event details (owner/admin)
 *   DELETE /api/events/{id}          - cancel an event (owner/admin)
 *
 * Note the fixed-path mappings (/published, /mine) are declared with
 * literal paths while /{id} is constrained to digits, so Spring can never
 * mistake "published" for an id.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    //creates an EventService object and injects it into the controller
    //constructor dependency injection.
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")

    //Create Event
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody EventCreateRequest request,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        EventResponse created = eventService.createEvent(request, ActingUser.of(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    @PostMapping("/{id:[0-9]+}/submit")
    public ResponseEntity<EventResponse> submitForApproval(@PathVariable Long id,
                                                             @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(eventService.submitForApproval(id, ActingUser.of(principal)));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id:[0-9]+}/approve")
    public ResponseEntity<EventResponse> approveEvent(@PathVariable Long id,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(eventService.approveEvent(id, ActingUser.of(principal)));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    @PostMapping("/{id:[0-9]+}/complete")
    public ResponseEntity<EventResponse> completeEvent(@PathVariable Long id,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(eventService.completeEvent(id, ActingUser.of(principal)));
    }

    /** Every event in every status - an administrative view, not a public one. */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<List<EventResponse>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    @GetMapping("/mine")
    public ResponseEntity<List<EventResponse>> getMyEvents(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(eventService.getEventsForOrganizer(ActingUser.of(principal).id()));
    }

    @GetMapping("/published")
    public ResponseEntity<List<EventResponse>> getPublishedEvents() {
        return ResponseEntity.ok(eventService.getPublishedEvents());
    }

    /**
     * Public, so the principal may be null - which is exactly why this
     * goes through getEventForViewer rather than getEventById. The route
     * is permitAll in SecurityConfig, so the visibility check is the only
     * thing standing between a guessed id and an unapproved draft.
     */
    @GetMapping("/{id:[0-9]+}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable Long id,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        ActingUser actor = principal == null ? null : ActingUser.of(principal);
        return ResponseEntity.ok(eventService.getEventForViewer(id, actor));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    @PutMapping("/{id:[0-9]+}")
    public ResponseEntity<EventResponse> updateEvent(@PathVariable Long id,
                                                       @Valid @RequestBody EventUpdateRequest request,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(eventService.updateEventDetails(id, request, ActingUser.of(principal)));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    @DeleteMapping("/{id:[0-9]+}")
    public ResponseEntity<Void> cancelEvent(@PathVariable Long id,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        eventService.cancelEvent(id, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }
}
