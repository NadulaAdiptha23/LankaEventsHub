package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.AnnouncementRequest;
import com.sliit.eventsphere.dto.AnnouncementResponse;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.AnnouncementService;
import com.sliit.eventsphere.service.EventService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The browser screens for announcements.
 *
 * Two of them: the organizer's compose-and-track page for one event, and
 * the administrator's approval queue. The queue lives under /admin, so the
 * URL rule covers it; everything else is organizer-or-admin by URL and
 * then by event ownership inside the service.
 *
 * Approve, reject and publish are POSTs from this page and admin-only in
 * the service. There is no route here that would let an organizer publish,
 * and if one were added by accident the service would still refuse.
 */
@Controller
@PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
public class AnnouncementPageController {

    private final AnnouncementService announcementService;
    private final EventService eventService;

    public AnnouncementPageController(AnnouncementService announcementService,
                                       EventService eventService) {
        this.announcementService = announcementService;
        this.eventService = eventService;
    }

    // =================================================================
    // The organizer's page for one event
    // =================================================================

    @GetMapping("/events/{eventId:[0-9]+}/announcements")
    public String forEvent(@PathVariable Long eventId,
                            @AuthenticationPrincipal UserPrincipal principal,
                            Model model) {
        ActingUser actor = ActingUser.of(principal);
        // requireEventManager first, so an organizer poking at another
        // organizer's event id gets a 403 before any of it is rendered.
        eventService.requireEventManager(eventId, actor);

        model.addAttribute("event", eventService.getEventById(eventId));
        model.addAttribute("eventId", eventId);
        model.addAttribute("announcements", announcementService.getForEvent(eventId, actor));
        model.addAttribute("isAdmin", actor.isAdmin());
        return "announcements";
    }

    @PostMapping("/events/{eventId:[0-9]+}/announcements")
    public String create(@PathVariable Long eventId,
                          @RequestParam String title,
                          @RequestParam String message,
                          @RequestParam(defaultValue = "false") boolean submitNow,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        ActingUser actor = ActingUser.of(principal);
        try {
            AnnouncementRequest request = new AnnouncementRequest();
            request.setTitle(title);
            request.setMessage(message);

            AnnouncementResponse created = announcementService.create(eventId, request, actor);

            // "Save and submit" is two service calls rather than a flag on
            // the first, so creating and submitting stay separately
            // auditable - and so a validation failure on submit cannot
            // silently lose the draft the author just typed.
            if (submitNow) {
                announcementService.submitForApproval(created.getId(), actor);
                flash.addFlashAttribute("flashSuccess",
                        "Announcement submitted for administrator approval.");
            } else {
                flash.addFlashAttribute("flashSuccess",
                        "Draft saved. Submit it when you are ready for an administrator to review it.");
            }
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/announcements";
    }

    @PostMapping("/announcements/{id:[0-9]+}/update")
    public String update(@PathVariable Long id,
                          @RequestParam Long eventId,
                          @RequestParam String title,
                          @RequestParam String message,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            AnnouncementRequest request = new AnnouncementRequest();
            request.setTitle(title);
            request.setMessage(message);
            announcementService.update(id, request, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Announcement updated.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/announcements";
    }

    @PostMapping("/announcements/{id:[0-9]+}/submit")
    public String submit(@PathVariable Long id,
                          @RequestParam Long eventId,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            announcementService.submitForApproval(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Sent for approval. An administrator will review it before attendees see it.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/announcements";
    }

    @PostMapping("/announcements/{id:[0-9]+}/delete")
    public String delete(@PathVariable Long id,
                          @RequestParam Long eventId,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            announcementService.delete(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Draft deleted.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/announcements";
    }

    /**
     * An administrator publishing their own draft, straight from the
     * event page - the shortcut that skips the queue.
     */
    @PostMapping("/announcements/{id:[0-9]+}/publish")
    public String publish(@PathVariable Long id,
                           @RequestParam Long eventId,
                           @AuthenticationPrincipal UserPrincipal principal,
                           RedirectAttributes flash) {
        try {
            AnnouncementResponse published =
                    announcementService.publishDirectly(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Sent to " + published.getRecipientCount() + " attendee(s).");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/announcements";
    }

    // =================================================================
    // The administrator's approval queue
    // =================================================================

    @GetMapping("/admin/announcements")
    public String queue(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("pending", announcementService.getPendingApproval(actor));
        // Feeds the count on the sidebar link, the same way the unread
        // notification badge is fed - set where it is known rather than
        // computed on every page in the application.
        model.addAttribute("pendingAnnouncements", announcementService.countPendingApproval(actor));
        return "admin-announcements";
    }

    @PostMapping("/admin/announcements/{id:[0-9]+}/approve")
    public String approve(@PathVariable Long id,
                           @AuthenticationPrincipal UserPrincipal principal,
                           RedirectAttributes flash) {
        try {
            AnnouncementResponse published =
                    announcementService.approveAndPublish(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Approved and sent to " + published.getRecipientCount() + " attendee(s).");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/announcements";
    }

    @PostMapping("/admin/announcements/{id:[0-9]+}/reject")
    public String reject(@PathVariable Long id,
                          @RequestParam(required = false) String reason,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            announcementService.reject(id, reason, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Rejected. The author has been told why, so they can revise it.");
        } catch (InvalidRequestException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/announcements";
    }
}
