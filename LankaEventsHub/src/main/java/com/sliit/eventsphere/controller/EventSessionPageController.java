package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.EventSessionRequest;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventSessionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/**
 * The browser's route to the session agenda, alongside the JSON API in
 * {@link EventSessionController}.
 *
 * The API existed from the start; no page ever called it, so an organizer
 * using the web interface had no way to build an agenda at all. Both go
 * through the same service, so the rules are identical whichever one is
 * used - the page is not a second, laxer implementation.
 *
 * Everything redirects back to the edit screen, where the agenda lives.
 */
@Controller
@PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
public class EventSessionPageController {

    private final EventSessionService sessionService;

    public EventSessionPageController(EventSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/events/{eventId:[0-9]+}/sessions")
    public String add(@PathVariable Long eventId,
                       @RequestParam String title,
                       @RequestParam(required = false) String description,
                       @RequestParam(required = false) String speaker,
                       @RequestParam(required = false) String startTime,
                       @RequestParam(required = false) String endTime,
                       @AuthenticationPrincipal UserPrincipal principal,
                       RedirectAttributes flash) {
        try {
            sessionService.addSession(eventId,
                    request(title, description, speaker, startTime, endTime),
                    ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Session added to the agenda.");
        } catch (InvalidRequestException | ResourceNotFoundException
                 | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/edit";
    }

    @PostMapping("/events/{eventId:[0-9]+}/sessions/{sessionId:[0-9]+}/update")
    public String update(@PathVariable Long eventId,
                          @PathVariable Long sessionId,
                          @RequestParam String title,
                          @RequestParam(required = false) String description,
                          @RequestParam(required = false) String speaker,
                          @RequestParam(required = false) String startTime,
                          @RequestParam(required = false) String endTime,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            sessionService.updateSession(eventId, sessionId,
                    request(title, description, speaker, startTime, endTime),
                    ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Session updated.");
        } catch (InvalidRequestException | ResourceNotFoundException
                 | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/edit";
    }

    @PostMapping("/events/{eventId:[0-9]+}/sessions/{sessionId:[0-9]+}/delete")
    public String delete(@PathVariable Long eventId,
                          @PathVariable Long sessionId,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            sessionService.removeSession(eventId, sessionId, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Session removed from the agenda.");
        } catch (InvalidRequestException | ResourceNotFoundException
                 | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/edit";
    }

    private EventSessionRequest request(String title, String description, String speaker,
                                         String startTime, String endTime) {
        EventSessionRequest r = new EventSessionRequest();
        r.setTitle(title);
        r.setDescription(blankToNull(description));
        r.setSpeaker(blankToNull(speaker));
        r.setStartTime(parse(startTime));
        r.setEndTime(parse(endTime));
        return r;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * An untouched datetime-local field posts an empty string, which means
     * "not set" rather than "invalid". A genuinely malformed value still
     * produces a message the organizer can act on, instead of a 400 with
     * no explanation.
     */
    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new InvalidRequestException("\"" + value + "\" is not a valid date and time.");
        }
    }
}
