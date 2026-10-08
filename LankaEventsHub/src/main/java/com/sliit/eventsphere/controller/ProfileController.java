package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.ProfileService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The Profile page the sidebar used to link to with href="#".
 * Either the link works or it should not be there - this is the version
 * where it works.
 */
@Controller
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/profile")
    public String profile(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        model.addAttribute("profile", profileService.getProfile(ActingUser.of(principal)));
        return "profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@RequestParam String fullName,
                                 @RequestParam(required = false) String contactNumber,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 RedirectAttributes flash) {
        try {
            profileService.updateProfile(ActingUser.of(principal), fullName, contactNumber);
            flash.addFlashAttribute("flashSuccess", "Profile updated.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/profile";
    }

    @PostMapping("/profile/password")
    public String changePassword(@RequestParam String currentPassword,
                                  @RequestParam String newPassword,
                                  @RequestParam String confirmPassword,
                                  @AuthenticationPrincipal UserPrincipal principal,
                                  RedirectAttributes flash) {
        try {
            profileService.changePassword(ActingUser.of(principal),
                    currentPassword, newPassword, confirmPassword);
            flash.addFlashAttribute("flashSuccess", "Password changed.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/profile";
    }
}
