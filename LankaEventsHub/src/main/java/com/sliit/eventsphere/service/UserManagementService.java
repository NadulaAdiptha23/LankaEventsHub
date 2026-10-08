package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.UserResponse;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.ActiveSessionService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Function 5 (User & Role Management): the admin-facing counterpart to
 * CustomUserDetailsService. Implements the rule that organizer access is
 * "assigned directly by an Administrator" - there is deliberately no
 * self-service "become an organizer" request flow anywhere in the system.
 *
 * Every method re-checks that the caller is an ADMIN. The /admin/**
 * URL rule in SecurityConfig already blocks everyone else, but a URL rule
 * is one config edit away from being wrong, and these are the operations
 * where that would matter most.
 */
@Service
public class UserManagementService {

    /** Statuses an event is still "live" in - it still needs an owner. */
    private static final Set<EventStatus> LIVE_EVENT_STATUSES =
            Set.of(EventStatus.PENDING_APPROVAL, EventStatus.PUBLISHED);

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final AuditLogService auditLogService;
    private final ActiveSessionService activeSessionService;
    private final PasswordEncoder passwordEncoder;

    public UserManagementService(UserRepository userRepository,
                                  EventRepository eventRepository,
                                  AuditLogService auditLogService,
                                  ActiveSessionService activeSessionService,
                                  PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.auditLogService = auditLogService;
        this.activeSessionService = activeSessionService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Admin-initiated account creation - the counterpart to
     * UserRegistrationService.register(), and deliberately a different
     * method rather than a shared one, because the two enforce opposite
     * rules about ADMIN: self-service categorically refuses it,
     * this categorically allows it. An authenticated administrator
     * granting administrator access to a new account is an authorized
     * act; an anonymous visitor typing role=ADMIN into a public sign-up
     * form is the privilege-escalation bug UserRegistrationService exists
     * to prevent. Same shape of method, opposite rule, so they stay two
     * methods rather than one with a bypassable flag.
     */
    @Transactional
    public UserResponse createUser(String fullName, String email, String password,
                                    String confirmPassword, UserRole role,
                                    boolean active, boolean verifiedStudent,
                                    ActingUser actor) {
        requireAdmin(actor);

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
        if (role == null) {
            throw new InvalidRequestException("Choose a role.");
        }
        if (userRepository.existsByEmail(normalisedEmail)) {
            throw new ConflictException("An account with that email already exists.");
        }

        User user = UserFactory.createByRole(fullName.trim(), normalisedEmail,
                passwordEncoder.encode(password), role, active, verifiedStudent);
        User saved = userRepository.save(user);

        auditLogService.log(actingEntity(actor), "ADMIN_CREATE_USER", "User", saved.getId(),
                "Account created by an administrator with role " + role
                        + (active ? "" : " (created inactive)"));

        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers(ActingUser actor) {
        requireAdmin(actor);
        return userRepository.findAllByOrderByFullNameAsc().stream()
                .map(UserResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateRole(Long userId, UserRole newRole, ActingUser actor) {
        requireAdmin(actor);
        User user = getUserOrThrow(userId);

        if (newRole == null) {
            throw new InvalidRequestException("Choose a role.");
        }
        if (actor.is(user.getId()) && newRole != UserRole.ADMIN) {
            throw new InvalidRequestException("You cannot remove your own administrator access.");
        }
        if (user.getRole() == UserRole.ADMIN && newRole != UserRole.ADMIN && isLastActiveAdmin(user)) {
            throw new InvalidRequestException(
                    "This is the last active administrator account - promote another administrator first.");
        }
        if (user.getRole() == newRole) {
            return; // nothing to do, and nothing worth writing to the audit log
        }
        // An organizer who still owns live events cannot simply stop being
        // an organizer - the events would be left with an owner who can no
        // longer manage them, silently becoming admin-only. Make the
        // administrator deal with the events first rather than leaving the
        // outcome to chance.
        requireNoLiveEvents(user, newRole != UserRole.ORGANIZER && newRole != UserRole.ADMIN,
                "change the role of");

        UserRole previousRole = user.getRole();
        user.setRole(newRole);
        userRepository.save(user);

        auditLogService.log(actingEntity(actor), "CHANGE_ROLE", "User", userId,
                "Role changed from " + previousRole + " to " + newRole);

        // The user's signed-in sessions still hold the OLD role. End them,
        // or the change does not take effect until they happen to log out.
        activeSessionService.invalidateSessionsFor(userId);
    }

    /**
     * Admin editing an existing account's name/email - the missing half
     * of profile management. updateRole/toggleActive/setVerifiedStudent
     * already existed; changing what somebody is called or how to reach
     * them did not, which is a real gap for an admin fixing a typo or a
     * changed email, not merely a cosmetic one.
     */
    @Transactional
    public UserResponse updateProfile(Long userId, String fullName, String email, ActingUser actor) {
        requireAdmin(actor);
        User user = getUserOrThrow(userId);

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
        // Only a genuine change needs the uniqueness check - re-submitting
        // the account's own current email is not a duplicate of itself.
        if (!normalisedEmail.equalsIgnoreCase(user.getEmail())
                && userRepository.existsByEmail(normalisedEmail)) {
            throw new ConflictException("An account with that email already exists.");
        }

        user.setFullName(fullName.trim());
        user.setEmail(normalisedEmail);
        User saved = userRepository.save(user);

        auditLogService.log(actingEntity(actor), "UPDATE_PROFILE", "User", userId,
                "Name/email updated by an administrator");

        return UserResponse.from(saved);
    }

    @Transactional
    public void toggleActive(Long userId, ActingUser actor) {
        requireAdmin(actor);
        User user = getUserOrThrow(userId);

        if (actor.is(user.getId())) {
            throw new InvalidRequestException("You cannot suspend your own account.");
        }
        if (user.isActive() && user.getRole() == UserRole.ADMIN && isLastActiveAdmin(user)) {
            throw new InvalidRequestException(
                    "This is the last active administrator account and cannot be suspended.");
        }

        boolean suspending = user.isActive();
        // Same reasoning as a demotion: a suspended organizer cannot manage
        // the events they own.
        requireNoLiveEvents(user, suspending && user.getRole() == UserRole.ORGANIZER, "suspend");

        user.setActive(!user.isActive());
        userRepository.save(user);

        auditLogService.log(actingEntity(actor), user.isActive() ? "REACTIVATE_USER" : "SUSPEND_USER",
                "User", userId, "Account " + (user.isActive() ? "reactivated" : "suspended"));

        if (suspending) {
            // CustomUserDetailsService blocks the NEXT sign-in, but the
            // session they are using right now would otherwise keep working.
            activeSessionService.invalidateSessionsFor(userId);
        }
    }

    /**
     * Refuses the change when it would leave live events without an owner
     * who can still manage them.
     */
    private void requireNoLiveEvents(User user, boolean applies, String action) {
        if (!applies || user.getRole() != UserRole.ORGANIZER) {
            return;
        }
        long liveEvents = eventRepository.countByOrganizerIdAndStatusIn(user.getId(), LIVE_EVENT_STATUSES);
        if (liveEvents > 0) {
            throw new InvalidRequestException(
                    "You cannot " + action + " this organizer while they still own " + liveEvents
                            + " event(s) awaiting approval or published. Cancel or complete those "
                            + "events first, or have another organizer take them over.");
        }
    }

    /**
     * Marks an account as a verified student, which is the only thing that
     * makes the STUDENT discount available to it (see
     * StudentDiscountStrategy). Checking the actual student ID is a manual
     * step an administrator performs outside the system.
     */
    @Transactional
    public void setVerifiedStudent(Long userId, boolean verified, ActingUser actor) {
        requireAdmin(actor);
        User user = getUserOrThrow(userId);
        if (user.isVerifiedStudent() == verified) {
            return;
        }
        user.setVerifiedStudent(verified);
        userRepository.save(user);

        auditLogService.log(actingEntity(actor), verified ? "VERIFY_STUDENT" : "UNVERIFY_STUDENT",
                "User", userId, "Student verification " + (verified ? "granted" : "removed"));
    }

    /**
     * Whether removing this account's administrator access would leave the
     * platform with none.
     *
     * The read is LOCKED, and that is the whole point of the method. The
     * check used to be an unlocked count, which is safe only while one
     * administrator acts at a time: with two administrators and two
     * simultaneous requests - one demoting the other's colleague, one
     * suspending them - both transactions read "2", both decided they were
     * not touching the last one, and both were allowed through. The end
     * state was zero administrators and no way back in through the
     * application.
     *
     * Locking every active administrator row makes the second request wait
     * for the first to commit and then look again, at which point it sees
     * the truth and refuses. This is the only guard in the system whose
     * failure cannot be repaired from inside the product, which is why it
     * gets a lock rather than a count.
     */
    private boolean isLastActiveAdmin(User user) {
        return user.isActive()
                && userRepository.findActiveByRoleForUpdate(UserRole.ADMIN).size() <= 1;
    }

    private void requireAdmin(ActingUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException("Only an Administrator can manage user accounts.");
        }
    }

    private User actingEntity(ActingUser actor) {
        return userRepository.findById(actor.id()).orElse(null);
    }

    private User getUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + userId));
    }
}
