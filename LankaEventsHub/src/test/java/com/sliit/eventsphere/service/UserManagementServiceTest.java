package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.ActiveSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserManagementServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EventRepository eventRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private ActiveSessionService activeSessionService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserManagementService userManagementService;

    private User admin;
    private User secondAdmin;
    private User attendee;
    private User organizer;
    private ActingUser adminActor;
    private ActingUser organizerActor;

    @BeforeEach
    void setUp() {
        admin = new User("Admin One", "admin@test.com", "hash", UserRole.ADMIN);
        admin.setId(1L);
        secondAdmin = new User("Admin Two", "admin2@test.com", "hash", UserRole.ADMIN);
        secondAdmin.setId(2L);
        attendee = new User("Nadeesha", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(3L);
        organizer = new User("Kasun", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(4L);

        adminActor = ActingUser.of(admin);
        organizerActor = new ActingUser(9L, UserRole.ORGANIZER);

        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(secondAdmin));
        lenient().when(userRepository.findById(3L)).thenReturn(Optional.of(attendee));
        lenient().when(userRepository.findById(4L)).thenReturn(Optional.of(organizer));
    }

    @Test
    void everyOperationIsAdminOnly() {
        assertThrows(AccessDeniedException.class,
                () -> userManagementService.getAllUsers(organizerActor));
        assertThrows(AccessDeniedException.class,
                () -> userManagementService.updateRole(3L, UserRole.ORGANIZER, organizerActor));
        assertThrows(AccessDeniedException.class,
                () -> userManagementService.toggleActive(3L, organizerActor));
        assertThrows(AccessDeniedException.class,
                () -> userManagementService.setVerifiedStudent(3L, true, organizerActor));
        assertThrows(AccessDeniedException.class,
                () -> userManagementService.createUser("New Person", "new@test.com",
                        "Password1!", "Password1!", UserRole.ATTENDEE, true, false, organizerActor));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_adminCanCreateAnyRoleIncludingAdmin() {
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(20L);
            return u;
        });

        var response = userManagementService.createUser("New Admin", "new@test.com",
                "Password1!", "Password1!", UserRole.ADMIN, true, false, adminActor);

        assertEquals(UserRole.ADMIN, response.getRole());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("hashed", captor.getValue().getPasswordHash());
        assertTrue(captor.getValue().isActive());
        verify(auditLogService).log(eq(admin), eq("ADMIN_CREATE_USER"), eq("User"), eq(20L), anyString());
    }

    @Test
    void createUser_canCreateAnInactiveAccount() {
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = userManagementService.createUser("New Person", "new@test.com",
                "Password1!", "Password1!", UserRole.ATTENDEE, false, false, adminActor);

        assertFalse(response.isActive());
    }

    @Test
    void createUser_rejectsADuplicateEmail() {
        when(userRepository.existsByEmail("attendee@test.com")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> userManagementService.createUser("Someone Else", "attendee@test.com",
                        "Password1!", "Password1!", UserRole.ATTENDEE, true, false, adminActor));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_rejectsMismatchedPasswords() {
        assertThrows(InvalidRequestException.class,
                () -> userManagementService.createUser("New Person", "new@test.com",
                        "Password1!", "Password2!", UserRole.ATTENDEE, true, false, adminActor));
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUser_rejectsAWeakPassword() {
        assertThrows(InvalidRequestException.class,
                () -> userManagementService.createUser("New Person", "new@test.com",
                        "short", "short", UserRole.ATTENDEE, true, false, adminActor));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfile_changesNameAndEmail() {
        when(userRepository.existsByEmail("nadeesha.new@test.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = userManagementService.updateProfile(3L, "Nadeesha Perera",
                "nadeesha.new@test.com", adminActor);

        assertEquals("Nadeesha Perera", response.getFullName());
        assertEquals("nadeesha.new@test.com", response.getEmail());
        verify(auditLogService).log(eq(admin), eq("UPDATE_PROFILE"), eq("User"), eq(3L), anyString());
    }

    @Test
    void updateProfile_resubmittingTheirOwnCurrentEmailIsNotADuplicate() {
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // attendee's own email is "attendee@test.com" - submitting it back
        // unchanged must not trip the "already exists" check against itself.
        userManagementService.updateProfile(3L, "Nadeesha", "attendee@test.com", adminActor);

        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void updateProfile_rejectsAnEmailAlreadyUsedByAnotherAccount() {
        when(userRepository.existsByEmail("organizer@test.com")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> userManagementService.updateProfile(3L, "Nadeesha",
                        "organizer@test.com", adminActor));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfile_rejectsABlankName() {
        assertThrows(InvalidRequestException.class,
                () -> userManagementService.updateProfile(3L, "  ",
                        "attendee@test.com", adminActor));
    }

    @Test
    void updateProfile_isAdminOnly() {
        assertThrows(AccessDeniedException.class,
                () -> userManagementService.updateProfile(3L, "Nadeesha",
                        "attendee@test.com", organizerActor));
    }

    @Test
    void updateRole_promotesAnAttendeeToOrganizer() {
        userManagementService.updateRole(3L, UserRole.ORGANIZER, adminActor);

        assertEquals(UserRole.ORGANIZER, attendee.getRole());
        verify(auditLogService).log(any(), eq("CHANGE_ROLE"), eq("User"), eq(3L), any());
    }

    @Test
    void updateRole_refusesSelfDemotion() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> userManagementService.updateRole(1L, UserRole.ATTENDEE, adminActor));
        assertTrue(ex.getMessage().contains("your own administrator access"));
        assertEquals(UserRole.ADMIN, admin.getRole());
    }

    @Test
    void updateRole_refusesToDemoteTheLastActiveAdministrator() {
        // Admin One demoting Admin Two, with Admin Two the only active one
        // left, would lock everybody out of user management.
        admin.setActive(false);
        when(userRepository.findActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(java.util.List.of(secondAdmin));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> userManagementService.updateRole(2L, UserRole.ATTENDEE, adminActor));
        assertTrue(ex.getMessage().contains("last active administrator"));
        assertEquals(UserRole.ADMIN, secondAdmin.getRole());
    }

    @Test
    void updateRole_allowsDemotingAnAdminWhenAnotherOneRemains() {
        when(userRepository.findActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(java.util.List.of(admin, secondAdmin));

        userManagementService.updateRole(2L, UserRole.ORGANIZER, adminActor);

        assertEquals(UserRole.ORGANIZER, secondAdmin.getRole());
    }

    @Test
    void theLastAdministratorGuardReadsLockedRowsRatherThanACount() {
        // A count locks nothing, so it answers a question about a moment
        // that has already passed. With two administrators and two
        // simultaneous requests - one demoting, one suspending - both
        // transactions read "2", both concluded they were not touching the
        // last one, and the platform could end up with zero administrators
        // and no way back in through the application. Locking the rows is
        // what makes the second request wait and then look again.
        when(userRepository.findActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(java.util.List.of(admin, secondAdmin));

        userManagementService.updateRole(2L, UserRole.ORGANIZER, adminActor);

        verify(userRepository).findActiveByRoleForUpdate(UserRole.ADMIN);
        verify(userRepository, never()).countByRoleAndActiveTrue(any());
    }

    @Test
    void updateRole_isANoOpWhenTheRoleIsUnchanged() {
        userManagementService.updateRole(3L, UserRole.ATTENDEE, adminActor);

        verify(userRepository, never()).save(any());
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void updateRole_throwsForAnUnknownUser() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> userManagementService.updateRole(404L, UserRole.ATTENDEE, adminActor));
    }

    @Test
    void toggleActive_suspendsAndReactivates() {
        userManagementService.toggleActive(3L, adminActor);
        assertFalse(attendee.isActive());

        userManagementService.toggleActive(3L, adminActor);
        assertTrue(attendee.isActive());
    }

    @Test
    void toggleActive_refusesSelfSuspension() {
        assertThrows(InvalidRequestException.class,
                () -> userManagementService.toggleActive(1L, adminActor));
        assertTrue(admin.isActive());
    }

    @Test
    void toggleActive_refusesToSuspendTheLastActiveAdministrator() {
        admin.setActive(false); // acting admin is already suspended in this scenario
        when(userRepository.findActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(java.util.List.of(secondAdmin));

        assertThrows(InvalidRequestException.class,
                () -> userManagementService.toggleActive(2L, adminActor));
        assertTrue(secondAdmin.isActive());
    }

    // =================================================================
    // A change that takes effect immediately, not at next sign-in
    // =================================================================

    @Test
    void updateRole_endsTheUsersLiveSessionsSoTheNewRoleTakesEffectAtOnce() {
        // Without this, an organizer demoted to attendee keeps their
        // organizer authorities for as long as their session cookie lives -
        // the change looks applied in the admin screen while the user is
        // still acting as an organizer.
        userManagementService.updateRole(3L, UserRole.ORGANIZER, adminActor);

        verify(activeSessionService).invalidateSessionsFor(3L);
    }

    @Test
    void updateRole_doesNotDisturbAnyoneWhenTheRoleDidNotActuallyChange() {
        userManagementService.updateRole(3L, UserRole.ATTENDEE, adminActor);

        verify(activeSessionService, never()).invalidateSessionsFor(anyLong());
    }

    @Test
    void toggleActive_endsTheSuspendedUsersSessionsButNotOnReactivation() {
        userManagementService.toggleActive(3L, adminActor);   // suspend
        verify(activeSessionService).invalidateSessionsFor(3L);

        clearInvocations(activeSessionService);
        userManagementService.toggleActive(3L, adminActor);   // reactivate
        verify(activeSessionService, never()).invalidateSessionsFor(anyLong());
    }

    // =================================================================
    // An organizer's events must never be left without an owner
    // =================================================================

    @Test
    void updateRole_refusesToDemoteAnOrganizerWhoStillOwnsLiveEvents() {
        when(eventRepository.countByOrganizerIdAndStatusIn(eq(4L), any())).thenReturn(2L);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> userManagementService.updateRole(4L, UserRole.ATTENDEE, adminActor));

        assertTrue(ex.getMessage().contains("2 event(s)"));
        // Refused outright, not applied-and-hoped: the role is untouched and
        // no session is cut.
        assertEquals(UserRole.ORGANIZER, organizer.getRole());
        verify(userRepository, never()).save(any());
        verify(activeSessionService, never()).invalidateSessionsFor(anyLong());
    }

    @Test
    void updateRole_allowsDemotingAnOrganizerWithNothingLiveLeft() {
        when(eventRepository.countByOrganizerIdAndStatusIn(eq(4L), any())).thenReturn(0L);

        userManagementService.updateRole(4L, UserRole.ATTENDEE, adminActor);

        assertEquals(UserRole.ATTENDEE, organizer.getRole());
    }

    @Test
    void updateRole_promotingAnOrganizerToAdminIsNotBlockedByTheirEvents() {
        // They keep the ability to manage those events, so there is nothing
        // to protect against here.
        when(eventRepository.countByOrganizerIdAndStatusIn(eq(4L), any())).thenReturn(5L);

        userManagementService.updateRole(4L, UserRole.ADMIN, adminActor);

        assertEquals(UserRole.ADMIN, organizer.getRole());
    }

    @Test
    void toggleActive_refusesToSuspendAnOrganizerWhoStillOwnsLiveEvents() {
        when(eventRepository.countByOrganizerIdAndStatusIn(eq(4L), any())).thenReturn(1L);

        assertThrows(InvalidRequestException.class,
                () -> userManagementService.toggleActive(4L, adminActor));
        assertTrue(organizer.isActive());
    }

    @Test
    void toggleActive_reactivatingAnOrganizerIsNeverBlockedByTheirEvents() {
        organizer.setActive(false);

        userManagementService.toggleActive(4L, adminActor);

        assertTrue(organizer.isActive());
        verify(eventRepository, never()).countByOrganizerIdAndStatusIn(any(), any());
    }

    @Test
    void onlyPendingAndPublishedEventsCountAsLive() {
        // A cancelled or completed event needs nobody to manage it, so it
        // must not block an administrator from tidying up an account.
        ArgumentCaptor<java.util.Collection<EventStatus>> statuses =
                ArgumentCaptor.forClass(java.util.Collection.class);
        when(eventRepository.countByOrganizerIdAndStatusIn(eq(4L), any())).thenReturn(0L);

        userManagementService.updateRole(4L, UserRole.ATTENDEE, adminActor);

        verify(eventRepository).countByOrganizerIdAndStatusIn(eq(4L), statuses.capture());
        assertEquals(Set.of(EventStatus.PENDING_APPROVAL, EventStatus.PUBLISHED),
                Set.copyOf(statuses.getValue()));
    }

    @Test
    void setVerifiedStudent_isTheOnlyWayToUnlockTheStudentDiscount() {
        assertFalse(attendee.isVerifiedStudent());

        userManagementService.setVerifiedStudent(3L, true, adminActor);

        assertTrue(attendee.isVerifiedStudent());
        verify(auditLogService).log(any(), eq("VERIFY_STUDENT"), eq("User"), eq(3L), any());
    }

    @Test
    void getAllUsers_returnsEveryAccountForAnAdmin() {
        when(userRepository.findAllByOrderByFullNameAsc()).thenReturn(List.of(admin, attendee));

        assertEquals(2, userManagementService.getAllUsers(adminActor).size());
    }
}
