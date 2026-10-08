package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.BookingRow;
import com.sliit.eventsphere.dto.BookingSearchCriteria;
import com.sliit.eventsphere.entity.PaymentStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.web.CsvWriter;
import com.sliit.eventsphere.service.RegistrationService;
import com.sliit.eventsphere.service.PurgeService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;

/**
 * Function 2 / administration: the bookings screen.
 *
 * This is the one place an Administrator can see every registration on
 * the platform together with what was paid and whether a refund is
 * outstanding, and cancel a booking on an attendee's behalf.
 *
 * Protected three ways, deliberately: SecurityConfig requires ROLE_ADMIN
 * for /admin/**, the class-level @PreAuthorize repeats it, and
 * RegistrationServiceImpl checks actor.isAdmin() before it queries
 * anything. Typing the URL by hand gets a 403 from the first of those,
 * long before any data is read.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AdminBookingPageController {

    /** Deliberately modest: a big page is a slow page and a slow query. */
    private static final int PAGE_SIZE = 25;

    /** A hard ceiling on an export, so one click cannot stream the whole table. */
    private static final int EXPORT_LIMIT = 5000;

    private final RegistrationService registrationService;
    /** Permanent deletion lives apart from cancellation. */
    private final PurgeService purgeService;

    public AdminBookingPageController(RegistrationService registrationService,
                                       PurgeService purgeService) {
        this.registrationService = registrationService;
        this.purgeService = purgeService;
    }

    @GetMapping("/admin/bookings")
    public String bookings(@ModelAttribute("criteria") BookingSearchCriteria criteria,
                            @RequestParam(defaultValue = "0") int page,
                            @AuthenticationPrincipal UserPrincipal principal,
                            Model model) {

        // Newest first: an operator opening this screen is almost always
        // looking at something that just happened.
        Page<BookingRow> result = registrationService.searchBookings(
                criteria,
                PageRequest.of(Math.max(0, page), PAGE_SIZE,
                        Sort.by(Sort.Direction.DESC, "registeredAt")),
                ActingUser.of(principal));

        model.addAttribute("bookings", result);
        model.addAttribute("statuses", RegistrationStatus.values());
        model.addAttribute("paymentStatuses", PaymentStatus.values());
        model.addAttribute("queryString", queryString(criteria));
        return "admin-bookings";
    }

    /**
     * Cancels a booking for an attendee. The seat is released, the
     * waitlist is promoted, the attendee is notified, and if the place had
     * been paid for a refund is raised - all inside the service, so this
     * method only has to translate the outcome into a message.
     */
    @PostMapping("/admin/bookings/{id:[0-9]+}/cancel")
    public String cancelBooking(@PathVariable Long id,
                                 @RequestParam(required = false) String reason,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 RedirectAttributes flash) {
        try {
            registrationService.cancelRegistrationAsStaff(id, ActingUser.of(principal), reason);
            flash.addFlashAttribute("flashSuccess",
                    "Booking cancelled. If it had been paid for, a refund is now in the queue.");
        } catch (InvalidRequestException | ResourceNotFoundException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/bookings";
    }

    /**
     * Permanently deletes a booking, its payment and its refund, and
     * gives the seats back.
     *
     * Beside "cancel" rather than instead of it. Cancelling is what
     * belongs on almost every booking: it keeps the record, raises the
     * refund and moves the waitlist. This is for the row that should
     * never have existed - a duplicate, or one created against the wrong
     * person - where a cancelled husk is worse than nothing.
     */
    @PostMapping("/admin/bookings/{id:[0-9]+}/delete")
    public String deleteBooking(@PathVariable Long id,
                                 @RequestParam(required = false) String reason,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 RedirectAttributes flash) {
        try {
            purgeService.purgeRegistration(id, ActingUser.of(principal), reason);
            flash.addFlashAttribute("flashSuccess",
                    "Booking permanently deleted and its seats returned to the event. "
                            + "The audit log records what went with it.");
        } catch (InvalidRequestException | ResourceNotFoundException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/bookings";
    }

    /**
     * The same filtered list as a CSV download.
     *
     * Written straight to the response writer rather than assembled into a
     * String first, so a large export does not have to fit in memory
     * twice. The row cap is the honest limit: this is a report, not a
     * database dump.
     */
    @GetMapping("/admin/bookings.csv")
    public void bookingsCsv(@ModelAttribute("criteria") BookingSearchCriteria criteria,
                             @AuthenticationPrincipal UserPrincipal principal,
                             HttpServletResponse response) throws IOException {

        Page<BookingRow> result = registrationService.searchBookings(
                criteria,
                PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "registeredAt")),
                ActingUser.of(principal));

        CsvWriter.prepare(response, "eventsphere-bookings-" + LocalDate.now() + ".csv");
        PrintWriter out = response.getWriter();
        CsvWriter.row(out, "registration_id", "event_id", "event_name", "event_status",
                "attendee_id", "attendee_name", "attendee_email", "registration_status",
                "registered_at", "cancelled_at",
                "payment_status", "amount_paid", "ticket_code",
                "refund_status", "refund_amount");

        for (BookingRow b : result.getContent()) {
            CsvWriter.row(out,
                    b.getRegistrationId(), b.getEventId(), b.getEventName(), b.getEventStatus(),
                    b.getAttendeeId(), b.getAttendeeName(), b.getAttendeeEmail(), b.getStatus(),
                    b.getRegisteredAt(), b.getCancelledAt(),
                    b.getPaymentStatus(), b.getPaidAmount(), b.getTicketCode(),
                    b.getRefundStatus(), b.getRefundAmount());
        }
        out.flush();
    }

    /** Rebuilds the filter part of the URL so paging keeps the filters. */
    private String queryString(BookingSearchCriteria c) {
        StringBuilder sb = new StringBuilder();
        append(sb, "attendee", c.getAttendee());
        append(sb, "event", c.getEvent());
        append(sb, "status", c.getStatus() == null ? null : c.getStatus().name());
        append(sb, "paymentStatus", c.getPaymentStatus() == null ? null : c.getPaymentStatus().name());
        if (c.isUnpaidOnly()) {
            append(sb, "unpaidOnly", "true");
        }
        return sb.toString();
    }

    private void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append('&').append(key).append('=')
          .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }
}
