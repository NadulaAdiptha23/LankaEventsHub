package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.ReportSummaryResponse;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.ReportingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/summary")
    public ReportSummaryResponse getSummary(@AuthenticationPrincipal UserPrincipal principal) {
        return reportingService.getSummary(ActingUser.of(principal));
    }
}
