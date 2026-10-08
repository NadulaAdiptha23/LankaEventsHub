package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.RefundResponse;
import com.sliit.eventsphere.dto.ReportSummaryResponse;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.RefundService;
import com.sliit.eventsphere.service.ReportingService;
import com.sliit.eventsphere.web.CsvWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class ReportingPageController {

    private final ReportingService reportingService;
    private final RefundService refundService;

    public ReportingPageController(ReportingService reportingService, RefundService refundService) {
        this.reportingService = reportingService;
        this.refundService = refundService;
    }

    @GetMapping("/admin/reports")
    public String dashboard(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        model.addAttribute("summary", reportingService.getSummary(ActingUser.of(principal)));
        return "admin-reports";
    }

    /**
     * The refund queue. Refunds are created automatically when an event is
     * cancelled (see RefundTriggerListener); this is where an
     * administrator walks them through PENDING -> PROCESSED -> COMPLETED.
     */
    @GetMapping("/admin/refunds")
    public String refunds(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        List<RefundResponse> refunds = refundService.getAllRefunds(ActingUser.of(principal));
        model.addAttribute("refunds", refunds);
        return "admin-refunds";
    }

    /**
     * The dashboard as a CSV an administrator can hand to somebody who
     * does not have a login.
     *
     * Deliberately three sections in one file rather than three downloads:
     * the headline totals, the category breakdown, and the most popular
     * events. A spreadsheet handles a blank line between blocks perfectly
     * well, and one file is one thing to attach to an e-mail.
     */
    @GetMapping("/admin/reports.csv")
    public void reportsCsv(@AuthenticationPrincipal UserPrincipal principal,
                            HttpServletResponse response) throws IOException {
        ReportSummaryResponse s = reportingService.getSummary(ActingUser.of(principal));

        CsvWriter.prepare(response, "eventsphere-report-" + LocalDate.now() + ".csv");
        PrintWriter out = response.getWriter();

        CsvWriter.row(out, "metric", "value");
        CsvWriter.row(out, "Generated", java.time.LocalDateTime.now());
        CsvWriter.row(out, "Total events", s.getTotalEvents());
        CsvWriter.row(out, "Published events", s.getPublishedEvents());
        CsvWriter.row(out, "Pending approval", s.getPendingApprovalEvents());
        CsvWriter.row(out, "Completed events", s.getCompletedEvents());
        CsvWriter.row(out, "Cancelled events", s.getCancelledEvents());
        CsvWriter.row(out, "Confirmed registrations", s.getConfirmedRegistrations());
        CsvWriter.row(out, "Waitlisted registrations", s.getWaitlistedRegistrations());
        CsvWriter.row(out, "Cancelled registrations", s.getCancelledRegistrations());
        CsvWriter.row(out, "Attended (checked in)", s.getAttendedRegistrations());
        CsvWriter.row(out, "No-shows", s.getNoShows());
        CsvWriter.row(out, "Attendance rate (%)", s.getAttendanceRate());
        CsvWriter.row(out, "Total revenue", s.getTotalRevenue());
        CsvWriter.row(out, "Total refunded", s.getTotalRefunded());
        CsvWriter.row(out, "Net revenue", s.getNetRevenue());

        CsvWriter.row(out);
        CsvWriter.row(out, "category", "events");
        for (Map.Entry<String, Long> e : s.getEventsByCategory().entrySet()) {
            CsvWriter.row(out, e.getKey(), e.getValue());
        }

        CsvWriter.row(out);
        CsvWriter.row(out, "event", "category", "confirmed_registrations",
                "attended", "attendance_rate_percent", "capacity");
        s.getMostPopularEvents().forEach(e -> CsvWriter.row(out,
                e.getEventName(), e.getCategory(), e.getConfirmedRegistrations(),
                e.getAttended(), e.getAttendanceRate(), e.getCapacity()));

        out.flush();
    }

    @PostMapping("/admin/refunds/{id:[0-9]+}/advance")
    public String advanceRefund(@PathVariable Long id,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 RedirectAttributes flash) {
        try {
            refundService.advanceRefundStatus(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Refund moved to the next stage.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/refunds";
    }
}
