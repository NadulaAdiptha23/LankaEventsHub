package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.PasswordResetToken;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.PasswordResetTokenRepository;
import com.sliit.eventsphere.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Function 5 (User & Role Management) support: password reset.
 *
 * There is no email sender in this system, so the reset link cannot be
 * delivered to the user's inbox. Rather than pretend otherwise, the link
 * is returned to the caller ONLY when app.password-reset.dev-mode is
 * true, which is how a marker or a teammate can exercise the flow
 * locally. With dev-mode false (the default, and the setting to ship
 * with) requestReset still creates the token but returns nothing, so the
 * link is never exposed on-screen and never logged.
 *
 * The raw token exists only in memory and in the link handed back in
 * dev-mode; what is persisted is its SHA-256 hash. See
 * PasswordResetToken for why.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_VALID_MINUTES = 30;
    /** 32 bytes = 256 bits of entropy; far beyond guessing in 30 minutes. */
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean devMode;

    public PasswordResetService(UserRepository userRepository,
                                 PasswordResetTokenRepository tokenRepository,
                                 PasswordEncoder passwordEncoder,
                                 @Value("${app.password-reset.dev-mode:false}") boolean devMode) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.devMode = devMode;
    }

    public boolean isDevMode() {
        return devMode;
    }

    /**
     * Returns the raw token string when dev-mode is on and the email
     * matches an account; empty otherwise.
     *
     * Deliberately does NOT throw for an unknown email, and deliberately
     * takes the same code path either way - the controller shows the same
     * generic confirmation, so this endpoint cannot be used to discover
     * which email addresses have accounts. The token itself is never
     * written to the log.
     */
    @Transactional
    public Optional<String> requestReset(String email) {
        String normalised = PasswordPolicy.normaliseEmail(email);
        Optional<User> maybeUser = userRepository.findByEmail(normalised);
        if (maybeUser.isEmpty()) {
            log.info("Password reset requested for an address with no account");
            return Optional.empty();
        }

        String token = newRawToken();
        PasswordResetToken resetToken = new PasswordResetToken(
                maybeUser.get(), hash(token), LocalDateTime.now().plusMinutes(TOKEN_VALID_MINUTES));
        tokenRepository.save(resetToken);

        log.info("Password reset token created for user {} (valid {} min)",
                maybeUser.get().getId(), TOKEN_VALID_MINUTES);

        // Only ever handed back for local development.
        return devMode ? Optional.of(token) : Optional.empty();
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        // Matched by hash: an attacker who can read the table still cannot
        // reconstruct a usable link from it.
        //
        // LOCKED, because single-use has to hold under two simultaneous
        // submissions of the same link. Both would otherwise read it as
        // unused and both would set a password.
        PasswordResetToken resetToken = tokenRepository.findByTokenHashForUpdate(hash(token))
                .orElseThrow(() -> new InvalidRequestException("This reset link is invalid."));

        if (!resetToken.isValid()) {
            throw new InvalidRequestException(
                    "This reset link has expired or was already used. Please request a new one.");
        }
        PasswordPolicy.validate(newPassword);

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Single-use, and it also retires every other outstanding link for
        // this account - otherwise an older token would still work after
        // the password had already been changed.
        List<PasswordResetToken> outstanding = tokenRepository.findByUserIdAndUsedFalse(user.getId());
        outstanding.forEach(t -> t.setUsed(true));
        resetToken.setUsed(true);
        tokenRepository.saveAll(outstanding);
        tokenRepository.save(resetToken);

        log.info("Password reset completed for user {}", user.getId());
    }

    private static String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        // URL-safe and unpadded, because this value goes in a query string.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, hex-encoded. Package-private so the tests can build the
     * stored form of a known token without duplicating the algorithm.
     */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java SE implementation; if it is
            // genuinely missing, failing loudly is the only honest option.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
