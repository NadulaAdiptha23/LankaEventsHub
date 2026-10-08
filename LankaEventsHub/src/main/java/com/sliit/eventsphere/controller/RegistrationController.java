package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.RegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for Function 2: Registration & Booking.
 *
 * There is no request body on register and no userId parameter on cancel:
 * both act on the authenticated caller. That is the whole fix for the
 * previous version, where {"userId": 2} let user 5 register as user 2 and
 * ?userId=2 let them cancel user 2's place.
 */
@RestController
@RequestMapping("/api/events/{eventId:[0-9]+}/registrations")
public class RegistrationController {

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    /**
     * Books seats for the acting user. {@code seats} defaults to 1, so
     * every caller written before parties existed keeps working; the
     * service validates the range and the capacity under a lock, so this
     * parameter is a request, not a decision.
     */
    @PostMapping
    public ResponseEntity<RegistrationResponse> register(@PathVariable Long eventId,
                                                           @RequestParam(defaultValue = "1") int seats,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        RegistrationResponse response =
                registrationService.register(eventId, seats, ActingUser.of(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping
    public ResponseEntity<Void> cancel(@PathVariable Long eventId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        registrationService.cancelRegistration(eventId, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }

    /**
     * The attendee list for this event. Authorized inside the service to
     * the owning organizer and administrators only - an attendee asking
     * for it gets 403, not the roster of everyone else attending.
     */
    @GetMapping
    public ResponseEntity<List<RegistrationResponse>> getForEvent(@PathVariable Long eventId,
                                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(registrationService.getRegistrationsForEvent(eventId, ActingUser.of(principal)));
    }
}
