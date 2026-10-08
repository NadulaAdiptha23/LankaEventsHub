package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.UserManagementService;
import com.sliit.eventsphere.service.PurgeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Admin user management. Protected three ways on purpose: the /admin/**
 * URL rule, the @PreAuthorize below, and a role check inside
 * UserManagementService itself - these are the operations where a single
 * mis-edited config line would be most costly.
 */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserManagementService userManagementService;
    /** Permanent deletion lives apart from the reversible actions. */
    private final PurgeService purgeService;

    public AdminUserController(UserManagementService userManagementService,
                                PurgeService purgeService) {
        this.userManagementService = userManagementService;
        this.purgeService = purgeService;
    }

    @GetMapping
    public String listUsers(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        model.addAttribute("users", userManagementService.getAllUsers(ActingUser.of(principal)));
        model.addAttribute("roles", UserRole.values());
        return "admin-users";
    }

    @GetMapping("/new")
    public String newUserForm(Model model) {
        model.addAttribute("roles", UserRole.values());
        return "admin-user-form";
    }

    /**
     * Admin-initiated account creation - see UserManagementService.createUser
     * for why this is a separate method (and separate rule) from the
     * public sign-up flow rather than the same one with a flag.
     *
     * Re-renders the form with what they typed on error, the same way
     * register.html does - a redirect-with-flash would lose it. The
     * password fields are deliberately not among what is put back, the
     * same reasoning PaymentPageController uses for card fields.
     */
    @PostMapping
    public String createUser(@RequestParam String fullName,
                              @RequestParam String email,
                              @RequestParam String password,
                              @RequestParam(required = false) String confirmPassword,
                              @RequestParam UserRole role,
                              @RequestParam(defaultValue = "true") boolean active,
                              @RequestParam(defaultValue = "false") boolean verifiedStudent,
                              @AuthenticationPrincipal UserPrincipal principal,
                              Model model,
                              RedirectAttributes flash) {
        try {
            userManagementService.createUser(fullName, email, password, confirmPassword,
                    role, active, verifiedStudent, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Account created for " + fullName + ".");
            return "redirect:/admin/users";
        } catch (InvalidRequestException ex) {
            // Covers ConflictException too - it extends InvalidRequestException,
            // so catching both by name is what the compiler correctly refuses
            // as a redundant multi-catch (verified with javac, not assumed).
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("roles", UserRole.values());
            model.addAttribute("fullName", fullName);
            model.addAttribute("email", email);
            model.addAttribute("selectedRole", role);
            model.addAttribute("active", active);
            model.addAttribute("verifiedStudent", verifiedStudent);
            return "admin-user-form";
        }
    }

    @GetMapping("/{id:[0-9]+}/edit")
    public String editForm(@PathVariable Long id,
                            @AuthenticationPrincipal UserPrincipal principal,
                            Model model) {
        var user = userManagementService.getAllUsers(ActingUser.of(principal)).stream()
                .filter(u -> u.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + id));
        model.addAttribute("editUser", user);
        return "admin-user-edit";
    }

    /**
     * The missing half of account editing: updateRole/toggleActive/
     * setVerifiedStudent already existed for role and status, but nothing
     * let an admin fix a name or an email on an existing account -
     * UserManagementService.updateProfile is new this round.
     */
    @PostMapping("/{id:[0-9]+}/edit")
    public String updateProfile(@PathVariable Long id,
                                 @RequestParam String fullName,
                                 @RequestParam String email,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 Model model,
                                 RedirectAttributes flash) {
        try {
            userManagementService.updateProfile(id, fullName, email, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Profile updated.");
            return "redirect:/admin/users";
        } catch (InvalidRequestException ex) {
            model.addAttribute("error", ex.getMessage());
            var editUser = new java.util.HashMap<String, Object>();
            editUser.put("id", id);
            editUser.put("fullName", fullName);
            editUser.put("email", email);
            model.addAttribute("editUser", editUser);
            return "admin-user-edit";
        }
    }

    @PostMapping("/{id:[0-9]+}/role")
    public String updateRole(@PathVariable Long id, @RequestParam UserRole role,
                              @AuthenticationPrincipal UserPrincipal principal,
                              RedirectAttributes flash) {
        try {
            userManagementService.updateRole(id, role, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Role updated.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    @PostMapping("/{id:[0-9]+}/toggle-active")
    public String toggleActive(@PathVariable Long id,
                                @AuthenticationPrincipal UserPrincipal principal,
                                RedirectAttributes flash) {
        try {
            userManagementService.toggleActive(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Account status updated.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    /**
     * Permanently deletes an account and everything personal to it.
     *
     * Kept separate from suspension rather than folded into it, because
     * they are different acts: suspending is reversible and this is not.
     * Every rule about who may be removed lives in PurgeService, including
     * the refusals - the last administrator, your own account, and an
     * account that still owns events - so this method only turns the
     * outcome into a sentence.
     */
    @PostMapping("/{id:[0-9]+}/delete")
    public String deleteAccount(@PathVariable Long id,
                                 @RequestParam(required = false) String reason,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 RedirectAttributes flash) {
        try {
            purgeService.purgeUser(id, ActingUser.of(principal), reason);
            flash.addFlashAttribute("flashSuccess",
                    "Account permanently deleted. The audit log records what went with it.");
        } catch (InvalidRequestException | ResourceNotFoundException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/users";
    }

    /** Grants or removes the student verification the STUDENT discount requires. */
    @PostMapping("/{id:[0-9]+}/verify-student")
    public String toggleVerifiedStudent(@PathVariable Long id,
                                         @RequestParam boolean verified,
                                         @AuthenticationPrincipal UserPrincipal principal,
                                         RedirectAttributes flash) {
        try {
            userManagementService.setVerifiedStudent(id, verified, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    verified ? "Account marked as a verified student."
                             : "Student verification removed.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/users";
    }
}
