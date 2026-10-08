package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.UserResponse;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service profile management: a user's own name, contact number and
 * password. Deliberately narrow - role, active status and student
 * verification are administrator decisions and are not settable here, so
 * this page can never become a privilege-escalation route.
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public ProfileService(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(ActingUser actor) {
        return UserResponse.from(load(actor));
    }

    @Transactional(readOnly = true)
    public String getContactNumber(ActingUser actor) {
        return load(actor).getContactNumber();
    }

    @Transactional
    public void updateProfile(ActingUser actor, String fullName, String contactNumber) {
        User user = load(actor);

        if (fullName == null || fullName.isBlank()) {
            throw new InvalidRequestException("Full name is required.");
        }
        if (fullName.trim().length() > 150) {
            throw new InvalidRequestException("Full name must be 150 characters or fewer.");
        }
        if (contactNumber != null && !contactNumber.isBlank()) {
            String trimmed = contactNumber.trim();
            if (trimmed.length() > 30) {
                throw new InvalidRequestException("Contact number must be 30 characters or fewer.");
            }
            if (!trimmed.matches("^[0-9+()\\-\\s]{6,30}$")) {
                throw new InvalidRequestException("Enter a valid contact number.");
            }
            user.setContactNumber(trimmed);
        } else {
            user.setContactNumber(null);
        }

        user.setFullName(fullName.trim());
        userRepository.save(user);
        auditLogService.log(user, "UPDATE_PROFILE", "User", user.getId(), "Profile details updated");
    }

    @Transactional
    public void changePassword(ActingUser actor, String currentPassword,
                                String newPassword, String confirmPassword) {
        User user = load(actor);

        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Your current password is not correct.");
        }
        PasswordPolicy.validate(newPassword);
        if (!newPassword.equals(confirmPassword)) {
            throw new InvalidRequestException("The new passwords do not match.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new InvalidRequestException("Choose a password different from your current one.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        auditLogService.log(user, "CHANGE_PASSWORD", "User", user.getId(), "Password changed by the account owner");
    }

    private User load(ActingUser actor) {
        return userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + actor.id()));
    }
}
