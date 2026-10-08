//represents the currently logged-in user.

package com.sliit.eventsphere.security;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import org.springframework.security.access.AccessDeniedException;

//Stores the logged-in user's ID and role
public record ActingUser(Long id, UserRole role) {

    //checks the user's role.
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public boolean isOrganizer() {
        return role == UserRole.ORGANIZER;
    }

    public boolean isAttendee() {
        return role == UserRole.ATTENDEE;
    }

    //Checks whether a given ID belongs to the currently logged-in user.
    public boolean is(Long userId) {
        return userId != null && userId.equals(id);
    }

    /** Builds an acting user from the authenticated principal. */
    public static ActingUser of(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null) {
            throw new AccessDeniedException("You must be signed in to perform this action.");
        }
        return new ActingUser(principal.getId(), principal.getUser().getRole());
    }

    /** Convenience for tests and for internal callers that already hold the entity. */
    public static ActingUser of(User user) {
        return new ActingUser(user.getId(), user.getRole());
    }
}
