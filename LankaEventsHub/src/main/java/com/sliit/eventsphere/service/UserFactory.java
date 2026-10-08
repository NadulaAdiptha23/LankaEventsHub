package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;

/**
 * Centralizes how a User entity gets built - the Factory Pattern for
 * Function 5 (User &amp; Role Management). Two callers used to each write
 * their own {@code new User(...)} plus field-setting:
 * UserRegistrationService (public sign-up) and
 * UserManagementService.createUser (admin-initiated). Neither does
 * anymore; both ask this factory for a fully-formed User instead.
 *
 * There is one User entity with a UserRole enum, not a class per role, so
 * this is deliberately a Simple/Static Factory centralizing construction
 * and each role's defaults - not a classic GoF Factory Method with a
 * product subclass hierarchy. Introducing Attendee/Organizer/Admin
 * subclasses of User would be a bigger, unrequested redesign of the
 * entity model for no behavioural gain; the factory gives the same "one
 * place to change how a role is constructed" benefit without it.
 *
 * The factory is a construction boundary, not a security boundary: it
 * will happily build an ADMIN if asked. The rule that a self-registered
 * account can never be ADMIN still lives exactly where it always did -
 * in UserRegistrationService, which simply never calls createAdmin and
 * never passes UserRole.ADMIN to createByRole. Moving that check into
 * the factory would make it one shared gate both callers pass through,
 * which is backwards: the two callers have opposite rules about ADMIN on
 * purpose, and collapsing that behind one gate is exactly the
 * "same method, bypassable flag" shape this project's own history has
 * already had to fix once. The factory stays a plain builder; the
 * services stay the ones deciding what they're allowed to ask it for.
 */
public final class UserFactory {

    private UserFactory() {
    }

    /** Used by self-registration when the resolved role is ATTENDEE. */
    public static User createAttendee(String fullName, String email, String hashedPassword) {
        return build(fullName, email, hashedPassword, UserRole.ATTENDEE, true, false);
    }

    /** Used by self-registration when the resolved role is ORGANIZER. */
    public static User createOrganizer(String fullName, String email, String hashedPassword) {
        return build(fullName, email, hashedPassword, UserRole.ORGANIZER, true, false);
    }

    /**
     * Used only from an admin-authorized path - see
     * UserManagementService.createUser, which is the only caller allowed
     * to reach this with an active/inactive choice attached. The factory
     * itself does not check who is calling; that authorization already
     * happened before construction was ever reached.
     */
    public static User createAdmin(String fullName, String email, String hashedPassword, boolean active) {
        return build(fullName, email, hashedPassword, UserRole.ADMIN, active, false);
    }

    /**
     * The general entry point, for the one caller that genuinely does not
     * know the role until runtime: an administrator choosing it from a
     * form. Self-registration never calls this with UserRole.ADMIN - it
     * only ever resolves to ATTENDEE or ORGANIZER and uses the named
     * methods above instead.
     */
    public static User createByRole(String fullName, String email, String hashedPassword,
                                     UserRole role, boolean active, boolean verifiedStudent) {
        return build(fullName, email, hashedPassword, role, active, verifiedStudent);
    }

    private static User build(String fullName, String email, String hashedPassword,
                               UserRole role, boolean active, boolean verifiedStudent) {
        User user = new User(fullName, email, hashedPassword, role);
        user.setActive(active);
        user.setVerifiedStudent(verifiedStudent);
        return user;
    }
}
