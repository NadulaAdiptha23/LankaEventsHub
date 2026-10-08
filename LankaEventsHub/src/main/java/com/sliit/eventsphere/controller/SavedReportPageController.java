package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.EventResponse;
import com.sliit.eventsphere.dto.SavedReportRequest;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventService;
import com.sliit.eventsphere.service.SavedReportService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * The browser screens for saved reports.
 *
 * Open to every signed-in role, unlike /admin/reports - an attendee saves
 * reports about their own activity, an organizer about events they own,
 * an administrator about the platform. Which types each may use is decided
 * by ReportType and re-checked in the service on every save AND every run,
 * so this controller never has to know.
 */
@Controller
public class SavedReportPageController {

    private final SavedReportService savedReportService;
    private final EventService eventService;

    public SavedReportPageController(SavedReportService savedReportService,
                                      EventService eventService) {
        this.savedReportService = savedReportService;
        this.eventService = eventService;
    }

    @GetMapping("/reports")
    public String myReports(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("reports", savedReportService.getMyReports(actor));
        model.addAttribute("types", savedReportService.getAvailableTypes(actor));
        model.addAttribute("manageableEvents", manageableEvents(actor));
        return "saved-reports";
    }

    @PostMapping("/reports")
    public String create(@RequestParam String title,
                          @RequestParam String reportType,
                          @RequestParam(required = false) Long eventId,
                          @RequestParam(required = false) String dateFrom,
                          @RequestParam(required = false) String dateTo,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            savedReportService.create(
                    build(title, reportType, eventId, dateFrom, dateTo), ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Report saved.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/reports";
    }

    /**
     * Opens one saved report and computes its figures.
     *
     * Everything on this page is calculated during this request and thrown
     * away with the response - there is nothing stored to render.
     */
    @GetMapping("/reports/{id:[0-9]+}")
    public String run(@PathVariable Long id,
                       @AuthenticationPrincipal UserPrincipal principal,
                       Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("report", savedReportService.getById(id, actor));
        model.addAttribute("result", savedReportService.run(id, actor));
        return "saved-report-view";
    }

    @PostMapping("/reports/{id:[0-9]+}/update")
    public String update(@PathVariable Long id,
                          @RequestParam String title,
                          @RequestParam String reportType,
                          @RequestParam(required = false) Long eventId,
                          @RequestParam(required = false) String dateFrom,
                          @RequestParam(required = false) String dateTo,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            savedReportService.update(id,
                    build(title, reportType, eventId, dateFrom, dateTo), ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Report updated.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/reports";
    }

    @PostMapping("/reports/{id:[0-9]+}/delete")
    public String delete(@PathVariable Long id,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            savedReportService.delete(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Report deleted. Nothing it measured was touched.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/reports";
    }

    // =================================================================
    // Helpers
    // =================================================================

    private SavedReportRequest build(String title, String reportType, Long eventId,
                                      String dateFrom, String dateTo) {
        SavedReportRequest request = new SavedReportRequest();
        request.setTitle(title);
        request.setReportType(reportType);
        request.setEventId(eventId);
        request.setDateFrom(parse(dateFrom));
        request.setDateTo(parse(dateTo));
        return request;
    }

    /**
     * The events this user could point an event-scoped report at.
     *
     * A convenience for the dropdown only. The service re-resolves and
     * re-authorizes whatever event id actually arrives, so an attendee
     * posting one by hand gets refused rather than served.
     */
    private List<EventResponse> manageableEvents(ActingUser actor) {
        if (actor.isAdmin()) {
            return eventService.getAllEvents();
        }
        if (actor.isOrganizer()) {
            return eventService.getEventsForOrganizer(actor.id());
        }
        return List.of();
    }

    /** An empty date field arrives as "", which is not a date and not an error. */
    private LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new InvalidRequestException("Enter dates as YYYY-MM-DD.");
        }
    }
}
