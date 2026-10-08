package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.AnnouncementRequest;
import com.sliit.eventsphere.dto.AnnouncementResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.AnnouncementRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The approval gate on human-written announcements.
 *
 * The cases worth reading first are the ones about who may publish: an
 * organizer must never be able to put their own words into eighty inboxes
 * without an administrator seeing them, and that has to be true of every
 * route into publication, not just the obvious one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnouncementServiceImplTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;

    // The real policy, not a mock: who may write an announcement for which
    // event is one of the things under test.
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(
            org.mockito.Mockito.mock(RegistrationRepository.class));

    @InjectMocks private AnnouncementServiceImpl service;

    private User organizer;
    private User otherOrganizer;
    private User admin;
    private User attendeeOne;
    private User attendeeTwo;

    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser adminActor;
    private ActingUser attendeeActor;

    private Event publishedEvent;
    private Event draftEvent;

    @BeforeEach
    void setUp() {
        organizer = user(1L, "Kasun", UserRole.ORGANIZER);
        otherOrganizer = user(7L, "Ishara", UserRole.ORGANIZER);
        admin = user(99L, "Admin", UserRole.ADMIN);
        attendeeOne = user(10L, "Nadeesha", UserRole.ATTENDEE);
        attendeeTwo = user(11L, "Ravindu", UserRole.ATTENDEE);

        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = ActingUser.of(otherOrganizer);
        adminActor = ActingUser.of(admin);
        attendeeActor = ActingUser.of(attendeeOne);

        publishedEvent = event(100L, EventStatus.PUBLISHED);
        draftEvent = event(101L, EventStatus.DRAFT);

        lenient().when(eventRepository.findById(100L)).thenReturn(Optional.of(publishedEvent));
        lenient().when(eventRepository.findById(101L)).thenReturn(Optional.of(draftEvent));
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        lenient().when(userRepository.findById(7L)).thenReturn(Optional.of(otherOrganizer));
        lenient().when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        lenient().when(announcementRepository.save(any(Announcement.class))).thenAnswer(inv -> {
            Announcement a = inv.getArgument(0);
            if (a.getId() == null) a.setId(500L);
            return a;
        });
    }

    // =================================================================
    // CREATE
    // =================================================================

    @Test
    void create_startsAsADraftForAnOrganizer() {
        AnnouncementResponse created = service.create(100L, request("Gate moved", "Use Galle Road."),
                organizerActor);

        assertEquals(AnnouncementStatus.DRAFT, created.getStatus());
        assertNull(created.getPublishedAt());
        verifyNoInteractions(notificationService);
    }

    @Test
    void create_startsAsADraftEvenForAnAdministrator() {
        // An administrator publishes with a second, explicit action. If
        // creating could publish, "this went out to 80 people" would be
        // something that happened as a side effect of pressing Save.
        AnnouncementResponse created = service.create(100L, request("Notice", "Body"), adminActor);

        assertEquals(AnnouncementStatus.DRAFT, created.getStatus());
        verifyNoInteractions(notificationService);
    }

    @Test
    void create_refusesAnotherOrganizersEvent() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(100L, request("Gate moved", "Use Galle Road."),
                        otherOrganizerActor));
    }

    @Test
    void create_refusesAnAttendee() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(100L, request("Hello", "Everyone"), attendeeActor));
    }

    @Test
    void create_refusesBlankTitleOrMessage() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(100L, request("   ", "Body"), organizerActor));
        assertThrows(InvalidRequestException.class,
                () -> service.create(100L, request("Title", "   "), organizerActor));
    }

    @Test
    void create_refusesTextLongerThanTheColumnCanHold() {
        // Checked in the service, not left to bean validation, because
        // validation only runs on the web path - a caller from a listener
        // or a test would otherwise fail at the database instead.
        String tooLong = "x".repeat(1001);
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.create(100L, request("Title", tooLong), organizerActor));
        assertTrue(ex.getMessage().contains("1000"));
    }

    @Test
    void create_trimsSurroundingWhitespace() {
        AnnouncementResponse created = service.create(100L,
                request("  Gate moved  ", "  Use Galle Road.  "), organizerActor);

        assertEquals("Gate moved", created.getTitle());
        assertEquals("Use Galle Road.", created.getMessage());
    }

    // =================================================================
    // THE GATE - who can publish
    // =================================================================

    @Test
    void anOrganizerCannotApproveTheirOwnAnnouncement() {
        // The rule the whole module exists for.
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));

        assertThrows(AccessDeniedException.class,
                () -> service.approveAndPublish(500L, organizerActor));
        assertEquals(AnnouncementStatus.PENDING_APPROVAL, pending.getStatus());
        verifyNoInteractions(notificationService);
    }

    @Test
    void anOrganizerCannotPublishDirectlyEither() {
        // The second route into publication. An access rule that guards
        // only the obvious entry point is not a rule.
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));

        assertThrows(AccessDeniedException.class,
                () -> service.publishDirectly(500L, organizerActor));
        assertEquals(AnnouncementStatus.DRAFT, draft.getStatus());
        verifyNoInteractions(notificationService);
    }

    @Test
    void anOrganizerCannotRejectEither() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));

        assertThrows(AccessDeniedException.class,
                () -> service.reject(500L, "no", organizerActor));
    }

    @Test
    void submittingDoesNotSendAnythingToAnybody() {
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));

        AnnouncementResponse submitted = service.submitForApproval(500L, organizerActor);

        assertEquals(AnnouncementStatus.PENDING_APPROVAL, submitted.getStatus());
        assertNotNull(submitted.getSubmittedAt());
        verifyNoInteractions(notificationService);
    }

    // =================================================================
    // APPROVE AND PUBLISH
    // =================================================================

    @Test
    void approving_deliversToEveryoneHoldingAPlace() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));
        when(registrationRepository.findPlaceHoldersWithUser(100L))
                .thenReturn(List.of(registration(attendeeOne), registration(attendeeTwo)));

        AnnouncementResponse published = service.approveAndPublish(500L, adminActor);

        assertEquals(AnnouncementStatus.PUBLISHED, published.getStatus());
        assertEquals(2, published.getRecipientCount());
        assertNotNull(published.getPublishedAt());
        verify(notificationService).notify(eq(attendeeOne),
                eq(NotificationType.ANNOUNCEMENT), any(), any());
        verify(notificationService).notify(eq(attendeeTwo),
                eq(NotificationType.ANNOUNCEMENT), any(), any());
    }

    @Test
    void approving_sendsTheAuthorsWordsNotASummary() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        pending.setTitle("Gate moved");
        pending.setMessage("Use the Galle Road entrance.");
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));
        when(registrationRepository.findPlaceHoldersWithUser(100L))
                .thenReturn(List.of(registration(attendeeOne)));

        service.approveAndPublish(500L, adminActor);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(any(), eq(NotificationType.ANNOUNCEMENT),
                any(), body.capture());
        assertTrue(body.getValue().contains("Gate moved"));
        assertTrue(body.getValue().contains("Use the Galle Road entrance."));
        assertTrue(body.getValue().contains("Photography Masterclass"),
                "the event name gives the message its context");
    }

    @Test
    void approving_recordsWhoApprovedItAndWhenItWentOut() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));
        when(registrationRepository.findPlaceHoldersWithUser(100L)).thenReturn(List.of());

        service.approveAndPublish(500L, adminActor);

        assertEquals(admin, pending.getDecidedBy());
        assertNotNull(pending.getDecidedAt());
        assertNotNull(pending.getPublishedAt());
    }

    @Test
    void approving_countsZeroRecipientsRatherThanRefusing() {
        // An event can legitimately have nobody on it yet. Publishing to
        // nobody is allowed and recorded honestly as zero.
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));
        when(registrationRepository.findPlaceHoldersWithUser(100L)).thenReturn(List.of());

        AnnouncementResponse published = service.approveAndPublish(500L, adminActor);

        assertEquals(0, published.getRecipientCount());
        verifyNoInteractions(notificationService);
    }

    @Test
    void approving_resolvesTheAudienceAtPublishTimeNotAtDraftTime() {
        // People register and cancel while an announcement sits in the
        // queue, so the recipient list is read now - not stored when the
        // author wrote it.
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));
        when(registrationRepository.findPlaceHoldersWithUser(100L))
                .thenReturn(List.of(registration(attendeeOne)));

        service.approveAndPublish(500L, adminActor);

        verify(registrationRepository).findPlaceHoldersWithUser(100L);
    }

    @Test
    void approving_readsTheRowUnderALock() {
        // Two administrators clearing the same queue could otherwise both
        // read PENDING_APPROVAL and both publish - and every attendee
        // would receive the same message twice, which is precisely what
        // this gate exists to prevent.
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));
        when(registrationRepository.findPlaceHoldersWithUser(100L)).thenReturn(List.of());

        service.approveAndPublish(500L, adminActor);

        verify(announcementRepository).findByIdForUpdate(500L);
        verify(announcementRepository, never()).findById(anyLong());
    }

    @Test
    void approving_refusesSomethingAlreadyPublished() {
        Announcement sent = announcement(AnnouncementStatus.PUBLISHED, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(sent));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.approveAndPublish(500L, adminActor));
        assertTrue(ex.getMessage().contains("already"));
        verifyNoInteractions(notificationService);
    }

    @Test
    void approving_refusesADraftThatWasNeverSubmitted() {
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));

        assertThrows(InvalidRequestException.class,
                () -> service.approveAndPublish(500L, adminActor));
    }

    @Test
    void anAdministratorPublishesTheirOwnDraftDirectly() {
        Announcement draft = announcement(AnnouncementStatus.DRAFT, admin);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));
        when(registrationRepository.findPlaceHoldersWithUser(100L))
                .thenReturn(List.of(registration(attendeeOne)));

        AnnouncementResponse published = service.publishDirectly(500L, adminActor);

        assertEquals(AnnouncementStatus.PUBLISHED, published.getStatus());
        assertEquals(1, published.getRecipientCount());
    }

    // =================================================================
    // REJECT
    // =================================================================

    @Test
    void rejecting_requiresAReason() {
        // A rejection with no reason is what makes an author resubmit the
        // same text and get rejected again.
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));

        assertThrows(InvalidRequestException.class, () -> service.reject(500L, "  ", adminActor));
        assertThrows(InvalidRequestException.class, () -> service.reject(500L, null, adminActor));
        assertEquals(AnnouncementStatus.PENDING_APPROVAL, pending.getStatus());
    }

    @Test
    void rejecting_tellsTheAuthorWhy() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));

        AnnouncementResponse rejected = service.reject(500L, "Too vague about the date", adminActor);

        assertEquals(AnnouncementStatus.REJECTED, rejected.getStatus());
        assertEquals("Too vague about the date", rejected.getRejectReason());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(eq(organizer), eq(NotificationType.ANNOUNCEMENT),
                any(), message.capture());
        assertTrue(message.getValue().contains("Too vague about the date"));
    }

    @Test
    void rejecting_neverReachesTheAttendees() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));

        service.reject(500L, "Not now", adminActor);

        verify(notificationService, times(1)).notify(any(), any(), any(), any());
        verify(notificationService, never()).notify(eq(attendeeOne), any(), any(), any());
    }

    @Test
    void editingARejectedAnnouncementClearsTheOldVerdict() {
        // The reason described text that no longer exists; leaving it on
        // screen reads as though it were still the verdict.
        Announcement turnedDown = announcement(AnnouncementStatus.REJECTED, organizer);
        turnedDown.setRejectReason("Too vague");
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(turnedDown));

        AnnouncementResponse updated = service.update(500L,
                request("Clearer title", "Clearer body"), organizerActor);

        assertEquals(AnnouncementStatus.DRAFT, updated.getStatus());
        assertNull(updated.getRejectReason());
    }

    // =================================================================
    // UPDATE and DELETE
    // =================================================================

    @Test
    void update_refusesOnceItIsWaitingForApproval() {
        Announcement pending = announcement(AnnouncementStatus.PENDING_APPROVAL, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(pending));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.update(500L, request("New", "Text"), organizerActor));
        assertTrue(ex.getMessage().contains("waiting for approval"));
    }

    @Test
    void update_refusesOnceItHasBeenSent() {
        // Editing text that has already reached eighty inboxes would leave
        // the record disagreeing with the message people are holding.
        Announcement sent = announcement(AnnouncementStatus.PUBLISHED, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(sent));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.update(500L, request("Rewritten", "Different"), organizerActor));
        assertTrue(ex.getMessage().contains("already been sent"));
        assertEquals("Gate moved", sent.getTitle());
    }

    @Test
    void delete_removesAnUnpublishedDraft() {
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));

        service.delete(500L, organizerActor);

        verify(announcementRepository).delete(draft);
    }

    @Test
    void delete_refusesOnceItHasBeenSent() {
        // The published row is the record of what attendees were told and
        // who approved it. Removing it would leave those notifications
        // with nothing behind them.
        Announcement sent = announcement(AnnouncementStatus.PUBLISHED, organizer);
        sent.setRecipientCount(42);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(sent));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.delete(500L, organizerActor));
        assertTrue(ex.getMessage().contains("42"));
        verify(announcementRepository, never()).delete(any());
    }

    @Test
    void delete_refusesAnotherOrganizersAnnouncement() {
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));

        assertThrows(AccessDeniedException.class, () -> service.delete(500L, otherOrganizerActor));
        verify(announcementRepository, never()).delete(any());
    }

    // =================================================================
    // The event has to have an audience
    // =================================================================

    @Test
    void submitting_refusesAnEventThatWasNeverPublished() {
        // A draft event has no attendees by definition, so this would
        // queue a message destined for an empty room.
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        draft.setEvent(draftEvent);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.submitForApproval(500L, organizerActor));
        assertTrue(ex.getMessage().contains("not been published"));
    }

    @Test
    void announcingOnACancelledEventIsStillAllowed() {
        // "The event is off, here is what happens next" is exactly the
        // sort of thing an organizer needs to say.
        Event cancelled = event(102L, EventStatus.CANCELLED);
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        draft.setEvent(cancelled);
        when(announcementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(draft));
        when(registrationRepository.findPlaceHoldersWithUser(102L)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.publishDirectly(500L, adminActor));
    }

    // =================================================================
    // READ
    // =================================================================

    @Test
    void theApprovalQueueIsAdministratorsOnly() {
        assertThrows(AccessDeniedException.class, () -> service.getPendingApproval(organizerActor));
        assertThrows(AccessDeniedException.class, () -> service.getPendingApproval(attendeeActor));
        assertDoesNotThrow(() -> service.getPendingApproval(adminActor));
    }

    @Test
    void theQueueBadgeReadsZeroForAnybodyWhoCannotSeeTheQueue() {
        // Returns 0 rather than throwing: it feeds a badge on a shared
        // sidebar, and a thrown exception there would break every page.
        assertEquals(0, service.countPendingApproval(organizerActor));
        assertEquals(0, service.countPendingApproval(null));
    }

    @Test
    void anAuthorCanStillReadTheirOwnRejectedAnnouncement() {
        // Wider than "may manage the event", on purpose: an organizer who
        // has since lost the event still needs to read why they were
        // turned down.
        Announcement rejected = announcement(AnnouncementStatus.REJECTED, otherOrganizer);
        when(announcementRepository.findById(500L)).thenReturn(Optional.of(rejected));

        assertDoesNotThrow(() -> service.getById(500L, otherOrganizerActor));
    }

    @Test
    void anUnrelatedAttendeeCannotReadAnAnnouncement() {
        Announcement draft = announcement(AnnouncementStatus.DRAFT, organizer);
        when(announcementRepository.findById(500L)).thenReturn(Optional.of(draft));

        assertThrows(AccessDeniedException.class, () -> service.getById(500L, attendeeActor));
    }

    @Test
    void aMissingAnnouncementIsReportedAsNotFound() {
        when(announcementRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.submitForApproval(999L, organizerActor));
    }

    // =================================================================
    // helpers
    // =================================================================

    private AnnouncementRequest request(String title, String message) {
        AnnouncementRequest req = new AnnouncementRequest();
        req.setTitle(title);
        req.setMessage(message);
        return req;
    }

    private Announcement announcement(AnnouncementStatus status, User author) {
        Announcement a = new Announcement();
        a.setId(500L);
        a.setEvent(publishedEvent);
        a.setAuthor(author);
        a.setTitle("Gate moved");
        a.setMessage("Use the Galle Road entrance.");
        a.setStatus(status);
        return a;
    }

    private Registration registration(User user) {
        Registration registration = new Registration();
        registration.setUser(user);
        registration.setEvent(publishedEvent);
        registration.setStatus(RegistrationStatus.CONFIRMED);
        return registration;
    }

    private Event event(Long id, EventStatus status) {
        Event e = new Event();
        e.setId(id);
        e.setName("Photography Masterclass");
        e.setOrganizer(organizer);
        e.setStatus(status);
        return e;
    }

    private User user(Long id, String name, UserRole role) {
        User u = new User(name, name.toLowerCase() + "@test.com", "hash", role);
        u.setId(id);
        return u;
    }

    /** Kept so the unused-import check does not flag ArrayList away. */
    @Test
    void theQueueIsReturnedOldestFirst() {
        List<Announcement> queue = new ArrayList<>();
        queue.add(announcement(AnnouncementStatus.PENDING_APPROVAL, organizer));
        when(announcementRepository.findByStatusOrderBySubmittedAtAsc(
                AnnouncementStatus.PENDING_APPROVAL)).thenReturn(queue);

        assertEquals(1, service.getPendingApproval(adminActor).size());
        // The ordering itself is the repository's, and naming it in the
        // method is what stops somebody "tidying" it into findByStatus -
        // which would let one submission sit behind newer work for ever.
        verify(announcementRepository)
                .findByStatusOrderBySubmittedAtAsc(AnnouncementStatus.PENDING_APPROVAL);
    }
}
