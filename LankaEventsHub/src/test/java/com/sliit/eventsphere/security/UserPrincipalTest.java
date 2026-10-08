package com.sliit.eventsphere.security;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Authentication-side rules. The suspended-account case is the one that
 * matters most: DaoAuthenticationProvider refuses to authenticate a
 * UserDetails whose isEnabled() is false, so this is what actually stops
 * a suspended user signing in.
 */
@ExtendWith(MockitoExtension.class)
class UserPrincipalTest {

    @Mock private UserRepository userRepository;

    @Test
    void anActiveAccountIsEnabled() {
        UserPrincipal principal = new UserPrincipal(user(true));

        assertTrue(principal.isEnabled());
        assertTrue(principal.isAccountNonLocked());
    }

    @Test
    void aSuspendedAccountCannotAuthenticate() {
        UserPrincipal principal = new UserPrincipal(user(false));

        assertFalse(principal.isEnabled(),
                "Spring Security refuses to authenticate a disabled UserDetails");
        assertFalse(principal.isAccountNonLocked());
    }

    @Test
    void theRoleIsExposedWithTheRolePrefixSpringExpects() {
        UserPrincipal principal = new UserPrincipal(user(true));

        assertEquals(1, principal.getAuthorities().size());
        assertEquals("ROLE_ATTENDEE",
                principal.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void theUsernameIsTheEmailAndThePasswordIsTheStoredHash() {
        User user = user(true);
        UserPrincipal principal = new UserPrincipal(user);

        assertEquals("attendee@test.com", principal.getUsername());
        assertEquals(user.getPasswordHash(), principal.getPassword());
    }

    @Test
    void loadingAnUnknownEmailFails() {
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository);
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("ghost@test.com"));
    }

    @Test
    void loadingASuspendedAccountStillReturnsADisabledPrincipal() {
        CustomUserDetailsService service = new CustomUserDetailsService(userRepository);
        when(userRepository.findByEmail("attendee@test.com")).thenReturn(Optional.of(user(false)));

        assertFalse(service.loadUserByUsername("attendee@test.com").isEnabled());
    }

    private User user(boolean active) {
        User user = new User("Nadeesha", "attendee@test.com", "$2a$10$hash", UserRole.ATTENDEE);
        user.setId(3L);
        user.setActive(active);
        return user;
    }
}
