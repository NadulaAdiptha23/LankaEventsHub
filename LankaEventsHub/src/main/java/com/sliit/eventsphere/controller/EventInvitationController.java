package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.InvitationResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventInvitationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Restricted-event invitation requests.
 *
 * The role annotations below are the coarse filter; the fine-grained
 * "is this YOUR event, and does this invitation belong to it" checks live
 * in EventInvitationServiceImpl, because only the service can see the
 * loaded entities.
 */
@RestController
@RequestMapping("/api/events/{eventId:[0-9]+}/invitations")
public class EventInvitationController {

    private final EventInvitationService eventInvitationService;

    public EventInvitationController(EventInvitationService eventInvitationService) {
        this.eventInvitationService = eventInvitationService;
    }

    /** An attendee requests access for themselves - no userId is accepted. */
    @PostMapping
    public ResponseEntity<InvitationResponse> requestInvitation(@PathVariable Long eventId,
                                                                  @AuthenticationPrincipal UserPrincipal principal) {
        InvitationResponse response =
                eventInvitationService.requestInvitation(eventId, ActingUser.of(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @GetMapping
    public ResponseEntity<List<InvitationResponse>> getInvitations(@PathVariable Long eventId,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                eventInvitationService.getInvitationsForEvent(eventId, ActingUser.of(principal)));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PostMapping("/{invitationId:[0-9]+}/approve")
    public ResponseEntity<InvitationResponse> approve(@PathVariable Long eventId,
                                                        @PathVariable Long invitationId,
                                                        @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                eventInvitationService.approve(eventId, invitationId, ActingUser.of(principal)));
    }

    @PreAuthorize("hasAnyRole('ORGANIZER', 'ADMIN')")
    @PostMapping("/{invitationId:[0-9]+}/reject")
    public ResponseEntity<InvitationResponse> reject(@PathVariable Long eventId,
                                                       @PathVariable Long invitationId,
                                                       @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(
                eventInvitationService.reject(eventId, invitationId, ActingUser.of(principal)));
    }
}
