package com.sliit.eventsphere.security;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The one definition of "may this user manage this event", and of "may
 * this user SEE it". Every service that guards an event-scoped action
 * delegates here, so these cases cover all of them at once.
 */
class EventAccessPolicyTest {

    private RegistrationRepository registrations;
    private EventAccessPolicy policy;

    private Event event;
    private ActingUser owner;
    private ActingUser otherOrganizer;
    private ActingUser admin;
    private ActingUser attendee;

    @BeforeEach
    void setUp() {
        registrations = mock(RegistrationRepository.class);
        policy = new EventAccessPolicy(registrations);

        User organizer = new User("Kasun", "o@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);

        event = new Event();
        event.setId(100L);
        event.setOrganizer(organizer);
        event.setStatus(EventStatus.DRAFT);

        owner = new ActingUser(1L, UserRole.ORGANIZER);
        otherOrganizer = new ActingUser(77L, UserRole.ORGANIZER);
        admin = new ActingUser(99L, UserRole.ADMIN);
        attendee = new ActingUser(2L, UserRole.ATTENDEE);
    }

    // =================================================================
    // canManage
    // =================================================================

    @Test
    void theOwningOrganizerMayManage() {
        assertTrue(policy.canManage(event, owner));
        assertDoesNotThrow(() -> policy.requireManager(event, owner));
    }

    @Test
    void anAdministratorMayManageAnyEvent() {
        assertTrue(policy.canManage(event, admin));
    }

    @Test
    void anotherOrganizerMayNot() {
        assertFalse(policy.canManage(event, otherOrganizer));
        assertThrows(AccessDeniedException.class, () -> policy.requireManager(event, otherOrganizer));
    }

    @Test
    void anAttendeeMayNotEvenIfTheirIdMatchesTheOrganizerId() {
        // Same numeric id, different role - the role check must not be
        // skipped just because the ids line up.
        ActingUser attendeeWithOwnerId = new ActingUser(1L, UserRole.ATTENDEE);
        assertFalse(policy.canManage(event, attendeeWithOwnerId));
        assertFalse(policy.canManage(event, attendee));
    }

    @Test
    void nullsAreNeverAllowed() {
        assertFalse(policy.canManage(null, admin));
        assertFalse(policy.canManage(event, null));
    }

    @Test
    void theRefusalMessageIsSpecificWhenOneIsGiven() {
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> policy.requireManager(event, attendee, "You cannot manage invitations here."));
        assertEquals("You cannot manage invitations here.", ex.getMessage());
    }

    // =================================================================
    // canView — the rule the public detail routes were missing entirely
    // =================================================================

    @Test
    void anAnonymousVisitorMayNotSeeADraft() {
        // The regression this half of the class exists for. GET /events/{id}
        // is permitAll, and the service behind it loaded any event by id -
        // so an unapproved draft, with its venue, pricing and internal
        // instructions, was one guessed id away from the public.
        assertFalse(policy.canView(event, null),
                "an unapproved draft must not be readable by a stranger");
    }

    @Test
    void anAnonymousVisitorMayNotSeeAnEventAwaitingApproval() {
        event.setStatus(EventStatus.PENDING_APPROVAL);
        assertFalse(policy.canView(event, null));
    }

    @Test
    void anAnonymousVisitorMaySeeAPublishedEvent() {
        event.setStatus(EventStatus.PUBLISHED);
        assertTrue(policy.canView(event, null));
        // Published is public: no registration lookup is needed to decide it.
        verifyNoInteractions(registrations);
    }

    @Test
    void anAttendeeWithNoRegistrationMayNotSeeAnUnpublishedEvent() {
        when(registrations.existsByEventIdAndUserId(100L, 2L)).thenReturn(false);
        assertFalse(policy.canView(event, attendee));
    }

    @Test
    void theOwningOrganizerMaySeeTheirOwnDraft() {
        // Previewing your own unpublished event has to keep working - the
        // fix is a visibility rule, not a blanket "published only".
        assertTrue(policy.canView(event, owner));
        verifyNoInteractions(registrations);
    }

    @Test
    void anAdministratorMaySeeAnything() {
        assertTrue(policy.canView(event, admin));
    }

    @Test
    void anotherOrganizerMayNotSeeSomebodyElsesDraft() {
        when(registrations.existsByEventIdAndUserId(100L, 77L)).thenReturn(false);
        assertFalse(policy.canView(event, otherOrganizer));
    }

    @Test
    void anAttendeeKeepsAccessToAnEventTheyRegisteredForAfterItIsCancelled() {
        // "My registrations" links every booking to its event page. Without
        // this, calling an event off would turn the attendee's own link
        // into a dead end - which is why the rule is not simply "published".
        event.setStatus(EventStatus.CANCELLED);
        when(registrations.existsByEventIdAndUserId(100L, 2L)).thenReturn(true);

        assertTrue(policy.canView(event, attendee));
    }

    @Test
    void anAttendeeKeepsAccessToACompletedEventTheyAttended() {
        event.setStatus(EventStatus.COMPLETED);
        when(registrations.existsByEventIdAndUserId(100L, 2L)).thenReturn(true);

        assertTrue(policy.canView(event, attendee));
    }

    @Test
    void aHiddenEventIsReportedAsMissingRatherThanForbidden() {
        // The status code is part of the fix. "403 Forbidden" on a draft
        // still answers the question the stranger was really asking - does
        // event 47 exist? - and lets them walk the id range to count what an
        // organization is planning.
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> policy.requireViewable(event, null, 100L));
        assertTrue(ex.getMessage().contains("100"));
    }

    @Test
    void aVisibleEventPassesRequireViewable() {
        event.setStatus(EventStatus.PUBLISHED);
        assertDoesNotThrow(() -> policy.requireViewable(event, null, 100L));
    }

    @Test
    void aNullEventIsNeverViewable() {
        assertFalse(policy.canView(null, admin));
    }

    @Test
    void anAnonymousVisitorIsNeverLookedUpInTheRegistrationTable() {
        // A null actor has no id to look up; asking anyway would be an NPE
        // on the most public route in the application.
        assertFalse(policy.canView(event, null));
        verify(registrations, never()).existsByEventIdAndUserId(anyLong(), anyLong());
    }
}
