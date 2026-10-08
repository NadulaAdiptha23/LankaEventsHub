package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.service.PasswordResetService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

@Controller
public class AccountController {

    private final PasswordResetService passwordResetService;

    public AccountController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String requestReset(@RequestParam String email, Model model) {
        Optional<String> token = passwordResetService.requestReset(email);
        // Always the same confirmation, whether or not the address has an
        // account - otherwise this page becomes a way to discover which
        // emails are registered.
        model.addAttribute("submitted", true);
        // Only ever populated when app.password-reset.dev-mode=true. With
        // dev-mode off (the production setting) requestReset returns empty
        // and no link is rendered anywhere.
        token.ifPresent(t -> model.addAttribute("devResetLink", "/reset-password?token=" + t));
        model.addAttribute("devMode", passwordResetService.isDevMode());
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token,
                                 @RequestParam String newPassword,
                                 @RequestParam(required = false) String confirmPassword,
                                 Model model) {
        try {
            // The confirmation is compared unconditionally. It used to be
            // guarded by "confirmPassword != null", and the parameter is
            // optional, so simply omitting the field from the POST body
            // skipped the check entirely - and PasswordResetService does
            // not re-compare it. Anyone posting the form by hand could
            // set a password they had only typed once. The signup path
            // (UserRegistrationService) always compared it; this one now
            // matches.
            if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
                throw new InvalidRequestException("Passwords do not match.");
            }
            passwordResetService.resetPassword(token, newPassword);
            return "redirect:/login?reset";
        } catch (InvalidRequestException ex) {
            model.addAttribute("token", token);
            model.addAttribute("error", ex.getMessage());
            return "reset-password";
        }
    }
}
