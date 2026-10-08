package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Function 5 (User &amp; Role Management): public self-service account
 * creation.
 *
 * Someone signing up chooses to be an ATTENDEE or an ORGANIZER, because
 * the two are different jobs rather than different privilege levels: one
 * books a seat, the other runs the event. Neither can do anything to the
 * other's data.
 *
 * ADMIN is categorically different. It is the role that can suspend
 * accounts, read the audit trail and approve events, so it cannot be
 * self-granted at any price. The allowlist below is the only place that
 * decision is made, and it is made on the server - the form is a
 * convenience, not the control. A request carrying role=ADMIN is
 * rejected with an error rather than quietly downgraded, because
 * silently ignoring an attempt to escalate tells the caller nothing and
 * tells us nothing either.
 */
@Service
public class UserRegistrationService {

    /**
     * The complete set of roles obtainable without an Administrator.
     * ADMIN is absent by design; adding it here would be the whole
     * privilege-escalation bug in one line.
     */
    private static final Set<UserRole> SELF_SERVICE_ROLES =
            Set.of(UserRole.ATTENDEE, UserRole.ORGANIZER);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserRegistrationService(UserRepository userRepository,
                                    PasswordEncoder passwordEncoder,
                                    AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * @param requestedRole the role the visitor asked for, as free text
     *                      straight off the form. Never trusted: it is
     *                      parsed and checked here, and nowhere else.
     */
    @Transactional
    public User register(String fullName, String email, String password,
                          String confirmPassword, String requestedRole) {

        if (fullName == null || fullName.isBlank()) {
            throw new InvalidRequestException("Full name is required.");
        }
        if (fullName.trim().length() > 150) {
            throw new InvalidRequestException("Full name must be 150 characters or fewer.");
        }
        if (email == null || email.isBlank()) {
            throw new InvalidRequestException("Email is required.");
        }
        String normalisedEmail = PasswordPolicy.normaliseEmail(email);
        if (normalisedEmail.length() > 150 || !normalisedEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new InvalidRequestException("Enter a valid email address.");
        }
        PasswordPolicy.validate(password);
        if (!password.equals(confirmPassword)) {
            throw new InvalidRequestException("Passwords do not match.");
        }

        UserRole role = resolveRole(requestedRole);

        if (userRepository.existsByEmail(normalisedEmail)) {
            throw new ConflictException("An account with that email already exists.");
        }

        User user = role == UserRole.ORGANIZER
                ? UserFactory.createOrganizer(fullName.trim(), normalisedEmail, passwordEncoder.encode(password))
                : UserFactory.createAttendee(fullName.trim(), normalisedEmail, passwordEncoder.encode(password));
        User saved = userRepository.save(user);

        auditLogService.log(saved, "REGISTER_ACCOUNT", "User", saved.getId(),
                "New account created with role " + role);

        return saved;
    }

    /**
     * Turns whatever arrived in the form into a role, or refuses.
     *
     * Absent or blank means ATTENDEE, so an older client that posts no
     * role at all still works and still gets the least privileged
     * option. Everything else must match a self-service role exactly
     * once case is normalised; ADMIN and any unrecognised string are
     * both errors.
     */
    private UserRole resolveRole(String requestedRole) {
        if (requestedRole == null || requestedRole.isBlank()) {
            return UserRole.ATTENDEE;
        }

        UserRole parsed;
        try {
            parsed = UserRole.valueOf(requestedRole.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("Choose whether you are joining as an attendee or an organizer.");
        }

        if (!SELF_SERVICE_ROLES.contains(parsed)) {
            throw new InvalidRequestException(
                    "Administrator accounts cannot be created from the sign-up form.");
        }
        return parsed;
    }
}
