package com.sliit.eventsphere.service;

import com.sliit.eventsphere.exception.InvalidRequestException;

/**
 * One definition of "is this password acceptable", shared by account
 * creation and password reset. Before this existed the two flows each had
 * their own copy of the rule, which is exactly how they drift apart.
 */
public final class PasswordPolicy {

    public static final int MINIMUM_LENGTH = 8;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new InvalidRequestException("Password is required.");
        }
        if (password.length() < MINIMUM_LENGTH) {
            throw new InvalidRequestException(
                    "Password must be at least " + MINIMUM_LENGTH + " characters.");
        }
        if (password.length() > 72) {
            // BCrypt silently ignores anything past 72 bytes, so a longer
            // password would give a false sense of strength.
            throw new InvalidRequestException("Password must be 72 characters or fewer.");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new InvalidRequestException("Password must contain at least one letter and one number.");
        }
    }

    /** Emails are stored and compared in one canonical form everywhere. */
    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
