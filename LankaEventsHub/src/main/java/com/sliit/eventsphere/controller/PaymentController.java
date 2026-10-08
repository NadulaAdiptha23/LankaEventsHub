package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.PaymentRequest;
import com.sliit.eventsphere.dto.PaymentResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for Function 3: Payment & Ticketing.
 *
 * Both endpoints are owner-only. Knowing (or guessing) another
 * attendee's registrationId gets a 403, not their ticket code.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@Valid @RequestBody PaymentRequest request,
                                                 @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.pay(request, ActingUser.of(principal)));
    }

    @GetMapping("/registration/{registrationId:[0-9]+}")
    public ResponseEntity<PaymentResponse> getByRegistration(@PathVariable Long registrationId,
                                                               @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(paymentService.getByRegistrationId(registrationId, ActingUser.of(principal)));
    }
}
