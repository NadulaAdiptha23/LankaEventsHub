package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.RegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The caller's own registrations. Split out from RegistrationController
 * because these are not scoped to one event.
 */
@RestController
@RequestMapping("/api/registrations")
public class MyRegistrationController {

    private final RegistrationService registrationService;

    public MyRegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/mine")
    public ResponseEntity<List<RegistrationResponse>> myRegistrations(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(registrationService.getRegistrationsForUser(ActingUser.of(principal)));
    }

    /** Readable by the owner, the organizer who owns the event, or an admin. */
    @GetMapping("/{id:[0-9]+}")
    public ResponseEntity<RegistrationResponse> one(@PathVariable Long id,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(registrationService.getRegistrationById(id, ActingUser.of(principal)));
    }
}
