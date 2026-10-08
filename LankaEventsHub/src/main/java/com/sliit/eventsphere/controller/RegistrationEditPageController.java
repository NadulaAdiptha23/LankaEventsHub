package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.RegistrationService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "Edit booking" - changing the number of seats on an existing
 * registration. Kept apart from PaymentPageController (which owns the
 * rest of the /registrations/{id}/* namespace) because resizing a
 * booking is not a payment operation, even though it can change how much
 * one costs; each controller here has stayed to one clear job rather
 * than accumulating unrelated concerns under a shared URL prefix.
 */
@Controller
public class RegistrationEditPageController {

    private final RegistrationService registrationService;

    public RegistrationEditPageController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping("/registrations/{id:[0-9]+}/edit")
    public String editForm(@PathVariable Long id,
                            @AuthenticationPrincipal UserPrincipal principal,
                            Model model) {
        ActingUser actor = ActingUser.of(principal);
        RegistrationResponse registration = registrationService.getRegistrationById(id, actor);
        model.addAttribute("registration", registration);
        return "registration-edit";
    }

    @PostMapping("/registrations/{id:[0-9]+}/edit")
    public String update(@PathVariable Long id,
                          @RequestParam int seats,
                          @AuthenticationPrincipal UserPrincipal principal,
                          Model model,
                          RedirectAttributes flash) {
        ActingUser actor = ActingUser.of(principal);
        try {
            registrationService.updateBooking(id, seats, actor);
            flash.addFlashAttribute("flashSuccess", "Booking updated.");
            return "redirect:/my-registrations";
        } catch (InvalidRequestException | AccessDeniedException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("registration", registrationService.getRegistrationById(id, actor));
            return "registration-edit";
        }
    }
}
