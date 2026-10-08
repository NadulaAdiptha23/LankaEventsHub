package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.service.EventService;
import com.sliit.eventsphere.service.PublicStatsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The marketing pages: Services, About, Contact.
 *
 * These are new. The navigation the redesign calls for lists all three as
 * top-level destinations, and the application had routes for none of them
 * - so they are real pages with real routes rather than anchors that
 * quietly drop somebody half way down the homepage. A navigation item
 * that scrolls instead of navigating is the kind of thing that works in a
 * demo and confuses everybody else.
 *
 * Deliberately separate from PageController, which is already large and
 * owns the application's own screens. Nothing here reads or writes
 * anything: the only data any of them touch is the same public statistics
 * the homepage quotes, and they are all reachable signed out - see
 * SecurityConfig, where they are permitted alongside "/" and "/events".
 */
@Controller
public class PublicPageController {

    private final PublicStatsService publicStatsService;
    private final EventService eventService;

    public PublicPageController(PublicStatsService publicStatsService,
                                 EventService eventService) {
        this.publicStatsService = publicStatsService;
        this.eventService = eventService;
    }

    @GetMapping("/services")
    public String services(Model model) {
        model.addAttribute("stats", publicStatsService.get());
        return "services";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("stats", publicStatsService.get());
        return "about";
    }

    @GetMapping("/contact")
    public String contact(Model model) {
        // The categories are real, and they are what the enquiry form's
        // "what kind of event" field offers - so the form cannot suggest
        // a category this platform does not actually run.
        model.addAttribute("categories", eventService.getPublishedCategories());
        return "contact";
    }
}
