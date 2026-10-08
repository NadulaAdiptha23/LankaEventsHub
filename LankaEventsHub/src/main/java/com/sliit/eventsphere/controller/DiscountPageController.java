package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.DiscountRequest;
import com.sliit.eventsphere.dto.DiscountResponse;
import com.sliit.eventsphere.dto.EventResponse;
import com.sliit.eventsphere.entity.DiscountType;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.DiscountManagementService;
import com.sliit.eventsphere.service.EventService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Function 3: the discount management screen.
 *
 * Reachable at /discounts for an organizer and /admin/discounts for an
 * Administrator - the same page, because the difference between them is
 * which events they may manage, and that decision belongs to
 * EventAccessPolicy inside the service rather than to two near-identical
 * controllers.
 *
 * Note what is NOT injected here: DiscountRepository. An earlier version
 * of this feature had a controller reaching straight into the repository,
 * and it was deleted for exactly that reason. ArchitectureSmokeTest fails
 * the build if a controller imports a repository.
 */
@Controller
@PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
public class DiscountPageController {

    private final DiscountManagementService discountService;
    private final EventService eventService;

    public DiscountPageController(DiscountManagementService discountService,
                                   EventService eventService) {
        this.discountService = discountService;
        this.eventService = eventService;
    }

    @GetMapping({"/discounts", "/admin/discounts"})
    public String discounts(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        ActingUser actor = ActingUser.of(principal);
        List<DiscountResponse> discounts = discountService.getManageableDiscounts(actor);
        // An Administrator may attach a discount to any event; an
        // organizer only to their own. The service re-checks this on
        // every write, so this list is a convenience, not the control.
        List<EventResponse> events = actor.isAdmin()
                ? eventService.getAllEvents()
                : eventService.getEventsForOrganizer(actor.id());

        model.addAttribute("discounts", discounts);
        model.addAttribute("manageableEvents", events);
        model.addAttribute("types", DiscountType.values());
        return "discounts";
    }

    @GetMapping("/discounts/{id:[0-9]+}/edit")
    public String editForm(@PathVariable Long id,
                            @AuthenticationPrincipal UserPrincipal principal,
                            Model model) {
        ActingUser actor = ActingUser.of(principal);
        // Reuses the same read path the list page already uses, rather
        // than adding a new single-item getter to the service: this list
        // is already scoped to what the actor may manage (their own
        // events, or - for an admin - all of them), so finding this one
        // discount in it is also the ownership check, for free.
        DiscountResponse discount = discountService.getManageableDiscounts(actor).stream()
                .filter(d -> d.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Discount not found with id " + id));
        model.addAttribute("discount", discount);
        model.addAttribute("types", DiscountType.values());
        return "discount-edit";
    }

    @PostMapping("/discounts")
    public String create(@RequestParam Long eventId,
                          @RequestParam DiscountType type,
                          @RequestParam(required = false) String code,
                          @RequestParam(required = false) BigDecimal percentOff,
                          @RequestParam(required = false) String validFrom,
                          @RequestParam(required = false) String validTo,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            DiscountRequest request = new DiscountRequest();
            request.setType(type);
            request.setCode(code);
            request.setPercentOff(percentOff);
            request.setValidFrom(parse(validFrom));
            request.setValidTo(parse(validTo));
            request.setActive(true);

            DiscountResponse created = discountService.create(eventId, request,
                    ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    created.getPercentOff() + "% discount added to \""
                            + created.getEventName() + "\".");
        } catch (InvalidRequestException | ResourceNotFoundException
                 | AccessDeniedException ex) {   // ConflictException extends the first
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/discounts";
    }

    @PostMapping("/discounts/{id:[0-9]+}/update")
    public String update(@PathVariable Long id,
                          @RequestParam DiscountType type,
                          @RequestParam(required = false) String code,
                          @RequestParam(required = false) BigDecimal percentOff,
                          @RequestParam(required = false) String validFrom,
                          @RequestParam(required = false) String validTo,
                          @RequestParam(defaultValue = "false") boolean active,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            DiscountRequest request = new DiscountRequest();
            request.setType(type);
            request.setCode(code);
            request.setPercentOff(percentOff);
            request.setValidFrom(parse(validFrom));
            request.setValidTo(parse(validTo));
            request.setActive(active);

            discountService.update(id, request, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Discount updated.");
        } catch (InvalidRequestException | ResourceNotFoundException
                 | AccessDeniedException ex) {   // ConflictException extends the first
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/discounts";
    }

    @PostMapping("/discounts/{id:[0-9]+}/toggle")
    public String toggle(@PathVariable Long id,
                          @RequestParam boolean active,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            discountService.setActive(id, active, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    active ? "Discount switched on." : "Discount switched off.");
        } catch (ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/discounts";
    }

    @PostMapping("/discounts/{id:[0-9]+}/delete")
    public String delete(@PathVariable Long id,
                          @AuthenticationPrincipal UserPrincipal principal,
                          RedirectAttributes flash) {
        try {
            discountService.delete(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Discount deleted.");
        } catch (ConflictException | ResourceNotFoundException | AccessDeniedException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/discounts";
    }

    /**
     * datetime-local posts "2026-09-20T09:00" and an untouched field posts
     * an empty string. Both mean "no bound", so both become null rather
     * than a parse failure the operator cannot act on.
     */
    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new InvalidRequestException(
                    "\"" + value + "\" is not a valid date and time.");
        }
    }
}
