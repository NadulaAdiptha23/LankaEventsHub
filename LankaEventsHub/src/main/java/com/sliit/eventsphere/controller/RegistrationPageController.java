package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.service.UserRegistrationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Public account signup pages. Kept separate from AccountController
 * (which handles password reset) so each controller has one clear job.
 *
 * The role arrives as a plain request parameter and is passed straight
 * through to the service without being inspected here. That is
 * deliberate: one place decides which roles are self-serviceable, and
 * this is not it. A controller that also checked would be a second
 * implementation of the same rule, free to drift from the first.
 */
@Controller
public class RegistrationPageController {

    private final UserRegistrationService userRegistrationService;

    public RegistrationPageController(UserRegistrationService userRegistrationService) {
        this.userRegistrationService = userRegistrationService;
    }

    @GetMapping("/register")
    public String registerForm() {
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String fullName,
                            @RequestParam String email,
                            @RequestParam String password,
                            @RequestParam(required = false) String confirmPassword,
                            @RequestParam(required = false) String role,
                            Model model) {
        try {
            userRegistrationService.register(fullName, email, password, confirmPassword, role);
            return "redirect:/login?registered";
        } catch (InvalidRequestException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("fullName", fullName);
            model.addAttribute("email", email);
            // Put back what they chose so a password typo does not also
            // reset the role they had already picked.
            model.addAttribute("role", role);
            return "register";
        }
    }
}
