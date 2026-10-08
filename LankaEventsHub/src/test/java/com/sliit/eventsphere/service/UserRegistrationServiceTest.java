package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserRegistrationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new UserRegistrationService(userRepository, passwordEncoder, auditLogService);
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(10L);
            return u;
        });
    }

    @Test
    void omittingTheRoleFallsBackToTheLeastPrivilegedOne() {
        // A client that posts no role at all still works, and gets
        // ATTENDEE rather than an error or a guess.
        User created = service.register("Nadeesha Silva", "new@test.com", "Passw0rd!", "Passw0rd!", null);

        assertEquals(UserRole.ATTENDEE, created.getRole());
    }

    @Test
    void blankRoleIsTreatedTheSameAsAnAbsentOne() {
        User created = service.register("Nadeesha Silva", "new@test.com", "Passw0rd!", "Passw0rd!", "  ");

        assertEquals(UserRole.ATTENDEE, created.getRole());
    }

    @Test
    void attendeeCanBeChosenExplicitly() {
        User created = service.register("Nadeesha Silva", "new@test.com", "Passw0rd!", "Passw0rd!", "ATTENDEE");

        assertEquals(UserRole.ATTENDEE, created.getRole());
    }

    @Test
    void organizerCanBeChosenAtSignup() {
        User created = service.register("Ishara Perera", "org@test.com", "Passw0rd!", "Passw0rd!", "ORGANIZER");

        assertEquals(UserRole.ORGANIZER, created.getRole());
    }

    @Test
    void roleIsMatchedWithoutRegardToCapitalisation() {
        User created = service.register("Ishara Perera", "org@test.com", "Passw0rd!", "Passw0rd!", " organizer ");

        assertEquals(UserRole.ORGANIZER, created.getRole());
    }

    /**
     * The one that matters. A forged role=ADMIN must not create an
     * administrator, and must not quietly create an attendee either -
     * a silent downgrade hides the attempt from everyone.
     */
    @Test
    void adminCannotBeSelfGranted() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.register("Attacker", "a@test.com", "Passw0rd!", "Passw0rd!", "ADMIN"));

        assertTrue(ex.getMessage().toLowerCase().contains("administrator"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void adminCannotBeSelfGrantedInAnyCasing() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("Attacker", "a@test.com", "Passw0rd!", "Passw0rd!", "admin"));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void anUnrecognisedRoleIsRejectedRatherThanDefaulted() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("Someone", "a@test.com", "Passw0rd!", "Passw0rd!", "SUPERUSER"));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void passwordIsStoredAsABcryptHashNeverPlaintext() {
        User created = service.register("Nadeesha Silva", "new@test.com", "Passw0rd!", "Passw0rd!", null);

        assertNotEquals("Passw0rd!", created.getPasswordHash());
        assertTrue(created.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches("Passw0rd!", created.getPasswordHash()));
    }

    @Test
    void emailIsNormalisedSoSignInIsCaseInsensitive() {
        User created = service.register("Nadeesha", "  New.User@Example.COM ", "Passw0rd!", "Passw0rd!", null);

        assertEquals("new.user@example.com", created.getEmail());
    }

    @Test
    void duplicateEmailIsAConflict() {
        when(userRepository.existsByEmail("taken@test.com")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.register("Someone", "taken@test.com", "Passw0rd!", "Passw0rd!", null));
    }

    @Test
    void mismatchedConfirmationIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("Someone", "a@test.com", "Passw0rd!", "Different1", null));
    }

    @Test
    void shortPasswordIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("Someone", "a@test.com", "abc12", "abc12", null));
    }

    @Test
    void passwordWithoutADigitIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("Someone", "a@test.com", "onlyletters", "onlyletters", null));
    }

    @Test
    void malformedEmailIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("Someone", "not-an-email", "Passw0rd!", "Passw0rd!", null));
    }

    @Test
    void blankNameIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.register("   ", "a@test.com", "Passw0rd!", "Passw0rd!", null));
    }
}
