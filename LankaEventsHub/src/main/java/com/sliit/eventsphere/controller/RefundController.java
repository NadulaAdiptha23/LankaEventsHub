package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.RefundService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /**
     * Moves a refund PENDING -> PROCESSED -> COMPLETED one step at a time.
     * The role check here is the coarse filter; RefundServiceImpl then
     * requires the caller to be an admin or the organizer who owns the
     * event the refund belongs to, so one organizer cannot process another
     * organizer's refunds.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'ORGANIZER')")
    @PostMapping("/{id:[0-9]+}/advance")
    public ResponseEntity<Void> advance(@PathVariable Long id,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        refundService.advanceRefundStatus(id, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }
}
