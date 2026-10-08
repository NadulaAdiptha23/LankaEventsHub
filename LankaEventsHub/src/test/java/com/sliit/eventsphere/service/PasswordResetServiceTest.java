package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.PasswordResetToken;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.PasswordResetTokenRepository;
import com.sliit.eventsphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private User user;

    @BeforeEach
    void setUp() {
        user = new User("Nadeesha", "attendee@test.com", passwordEncoder.encode("OldPass1"), UserRole.ATTENDEE);
        user.setId(3L);
        lenient().when(userRepository.findByEmail("attendee@test.com")).thenReturn(Optional.of(user));
        lenient().when(tokenRepository.save(any(PasswordResetToken.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenRepository.findByUserIdAndUsedFalse(3L)).thenReturn(List.of());
    }

    private PasswordResetService service(boolean devMode) {
        return new PasswordResetService(userRepository, tokenRepository, passwordEncoder, devMode);
    }

    // =================================================================
    // Requesting a reset
    // =================================================================

    @Test
    void requestReset_neverRevealsWhetherAnAccountExists() {
        PasswordResetService svc = service(false);
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        // Both answers are indistinguishable to the caller.
        assertTrue(svc.requestReset("nobody@test.com").isEmpty());
        assertTrue(svc.requestReset("attendee@test.com").isEmpty());
    }

    @Test
    void requestReset_withDevModeOffNeverHandsBackALink() {
        assertTrue(service(false).requestReset("attendee@test.com").isEmpty());
        // The token is still created - it just is not exposed anywhere.
        verify(tokenRepository).save(any(PasswordResetToken.class));
    }

    @Test
    void requestReset_withDevModeOnReturnsTheTokenForLocalTesting() {
        Optional<String> token = service(true).requestReset("attendee@test.com");

        assertTrue(token.isPresent());
        assertFalse(token.get().isBlank());
    }

    @Test
    void requestReset_normalisesTheEmailSoCaseDoesNotMatter() {
        service(true).requestReset("  Attendee@TEST.com ");

        verify(userRepository).findByEmail("attendee@test.com");
    }

    // =================================================================
    // Using a token
    // =================================================================

    @Test
    void resetPassword_withAValidTokenChangesThePassword() {
        PasswordResetToken token = validToken("good-token");
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("good-token"))).thenReturn(Optional.of(token));

        service(false).resetPassword("good-token", "BrandNew1");

        assertTrue(passwordEncoder.matches("BrandNew1", user.getPasswordHash()));
        assertTrue(token.isUsed(), "the token must be single-use");
    }

    @Test
    void resetPassword_rejectsAnUnknownToken() {
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("nope"))).thenReturn(Optional.empty());

        assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword("nope", "BrandNew1"));
    }

    @Test
    void resetPassword_rejectsAnExpiredToken() {
        PasswordResetToken expired = new PasswordResetToken(
                user, PasswordResetService.hash("old"), LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("old"))).thenReturn(Optional.of(expired));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword("old", "BrandNew1"));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    void resetPassword_rejectsAReusedToken() {
        PasswordResetToken used = validToken("used-token");
        used.setUsed(true);
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("used-token"))).thenReturn(Optional.of(used));

        assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword("used-token", "BrandNew1"));
    }

    @Test
    void resetPassword_enforcesThePasswordPolicy() {
        PasswordResetToken token = validToken("good-token");
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("good-token"))).thenReturn(Optional.of(token));

        assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword("good-token", "short"));
        assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword("good-token", "nodigitshere"));
    }

    @Test
    void resetPassword_retiresEveryOtherOutstandingLinkForThatAccount() {
        PasswordResetToken used = validToken("good-token");
        PasswordResetToken older = validToken("older-token");
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("good-token"))).thenReturn(Optional.of(used));
        when(tokenRepository.findByUserIdAndUsedFalse(3L)).thenReturn(List.of(older));

        service(false).resetPassword("good-token", "BrandNew1");

        assertTrue(older.isUsed(),
                "an older link must stop working once the password has been changed");
    }

    // =================================================================
    // The token is stored hashed
    // =================================================================

    @Test
    void requestReset_storesTheHashOfTheTokenAndNotTheTokenItself() {
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);

        String raw = service(true).requestReset("attendee@test.com").orElseThrow();

        verify(tokenRepository).save(saved.capture());
        String stored = saved.getValue().getTokenHash();
        assertNotEquals(raw, stored,
                "the raw token must never be what lands in the database");
        assertEquals(PasswordResetService.hash(raw), stored);
        assertEquals(64, stored.length(), "SHA-256 hex is 64 characters");
    }

    @Test
    void requestReset_issuesADifferentTokenEveryTime() {
        String first = service(true).requestReset("attendee@test.com").orElseThrow();
        String second = service(true).requestReset("attendee@test.com").orElseThrow();

        assertNotEquals(first, second);
        // 32 random bytes, base64url without padding.
        assertTrue(first.length() >= 42, "the token must carry real entropy, not a short id");
    }

    @Test
    void resetPassword_doesNotAcceptTheStoredHashInPlaceOfTheToken() {
        // If lookup were ever done on the raw value, someone who had read
        // the table could paste the stored string straight into the link.
        PasswordResetToken token = validToken("good-token");
        when(tokenRepository.findByTokenHashForUpdate(PasswordResetService.hash("good-token")))
                .thenReturn(Optional.of(token));

        assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword(token.getTokenHash(), "BrandNew1"));
    }

    private PasswordResetToken validToken(String value) {
        return new PasswordResetToken(user, PasswordResetService.hash(value), LocalDateTime.now().plusMinutes(30));
    }

    // =================================================================
    // Single-use has to hold under two simultaneous submissions
    //
    // Without a lock on the token row, both requests read it as unused,
    // both pass the validity check, and both set a password - which is
    // not a race anybody should be able to run against account recovery.
    // =================================================================

    @Test
    void resetPassword_readsTheTokenUnderALock() {
        // The locked lookup is the one the service must use. If somebody
        // switches it back to the plain finder, this fails.
        PasswordResetToken token = validToken("raw-token");
        when(tokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));
        when(tokenRepository.findByUserIdAndUsedFalse(3L)).thenReturn(List.of(token));

        service(false).resetPassword("raw-token", "Passw0rd!");

        verify(tokenRepository).findByTokenHashForUpdate(anyString());
        verify(tokenRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void resetPassword_refusesTheSameLinkASecondTime() {
        // What the lock makes possible: the second request sees the token
        // as used, because the first one committed before it was allowed
        // to read the row.
        PasswordResetToken token = validToken("raw-token");
        when(tokenRepository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(token));
        when(tokenRepository.findByUserIdAndUsedFalse(3L)).thenReturn(List.of(token));

        service(false).resetPassword("raw-token", "Passw0rd!");
        assertTrue(token.isUsed());

        assertThrows(InvalidRequestException.class,
                () -> service(false).resetPassword("raw-token", "Different1!"));
    }
}
