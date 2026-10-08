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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The approval workflow for human-written announcements.
 *
 * Three things are worth knowing before reading the methods:
 *
 * 1. EVERY state change goes through a locked read. Approving is
 *    read-check-write plus a fan-out to every attendee, so an unlocked
 *    read would let two administrators clearing the same queue both
 *    publish - and the visible result is every attendee receiving the
 *    same message twice, which is exactly what this gate exists to stop.
 *
 * 2. Publishing and delivering happen in ONE transaction. There is no
 *    "approved, delivery pending" state, so an approved announcement
 *    nobody received cannot exist, and a rolled-back publish takes its
 *    notifications with it.
 *
 * 3. The recipient list is resolved at publish time, never at draft time.
 *    People register and cancel while an announcement sits in the queue;
 *    a list captured when it was written would already be wrong.
 */
@Service
public class AnnouncementServiceImpl implements AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementServiceImpl.class);

    /**
     * An announcement can only be sent to an event that has attendees to
     * send it to. A DRAFT or PENDING_APPROVAL event has none by
     * definition - nobody can have registered for something that was
     * never public - so submitting one would queue a message destined for
     * an empty room.
     */
    private static final List<EventStatus> ANNOUNCEABLE =
            List.of(EventStatus.PUBLISHED, EventStatus.COMPLETED, EventStatus.CANCELLED);

    private final AnnouncementRepository announcementRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final EventAccessPolicy accessPolicy;

    public AnnouncementServiceImpl(AnnouncementRepository announcementRepository,
                                    EventRepository eventRepository,
                                    RegistrationRepository registrationRepository,
                                    UserRepository userRepository,
                                    NotificationService notificationService,
                                    AuditLogService auditLogService,
                                    EventAccessPolicy accessPolicy) {
        this.announcementRepository = announcementRepository;
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.accessPolicy = accessPolicy;
    }

    // =================================================================
    // CREATE
    // =================================================================

    @Override
    @Transactional
    public AnnouncementResponse create(Long eventId, AnnouncementRequest request, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        accessPolicy.requireManager(event, actor,
                "You can only write announcements for your own events.");

        String title = requireText(request == null ? null : request.getTitle(),
                "Give the announcement a title.", 150, "title");
        String message = requireText(request == null ? null : request.getMessage(),
                "Write the message you want attendees to receive.", 1000, "message");

        Announcement announcement = new Announcement();
        announcement.setEvent(event);
        announcement.setAuthor(requireUser(actor));
        announcement.setTitle(title);
        announcement.setMessage(message);
        // Always a draft, whoever is asking. An administrator publishes
        // with a second, explicit action - so "this went out to 80 people"
        // is never something that happened as a side effect of saving.
        announcement.setStatus(AnnouncementStatus.DRAFT);

        Announcement saved = announcementRepository.save(announcement);
        auditLogService.log(saved.getAuthor(), "CREATE_ANNOUNCEMENT", "Announcement",
                saved.getId(), "Draft announcement on event " + eventId);
        return AnnouncementResponse.from(saved);
    }

    // =================================================================
    // READ
    // =================================================================

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> getForEvent(Long eventId, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        accessPolicy.requireManager(event, actor,
                "You are not authorized to view announcements for this event.");
        return announcementRepository.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public AnnouncementResponse getById(Long announcementId, ActingUser actor) {
        Announcement announcement = getOrThrow(announcementId);
        requireCanRead(announcement, actor);
        return AnnouncementResponse.from(announcement);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> getPendingApproval(ActingUser actor) {
        requireAdmin(actor, "Only an Administrator can review announcements.");
        return announcementRepository
                .findByStatusOrderBySubmittedAtAsc(AnnouncementStatus.PENDING_APPROVAL).stream()
                .map(AnnouncementResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countPendingApproval(ActingUser actor) {
        if (actor == null || !actor.isAdmin()) {
            return 0;
        }
        return announcementRepository.countByStatus(AnnouncementStatus.PENDING_APPROVAL);
    }

    // =================================================================
    // UPDATE - the text
    // =================================================================

    @Override
    @Transactional
    public AnnouncementResponse update(Long announcementId, AnnouncementRequest request,
                                        ActingUser actor) {
        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> notFound(announcementId));
        accessPolicy.requireManager(announcement.getEvent(), actor,
                "You can only edit announcements for your own events.");

        // Re-checked under the lock rather than before it: an
        // administrator could have approved this announcement in the
        // moment between the author opening the edit form and saving it,
        // and editing text that has already reached eighty inboxes would
        // leave the record disagreeing with the message people are
        // holding.
        if (!announcement.isEditable()) {
            throw new InvalidRequestException(switch (announcement.getStatus()) {
                case PENDING_APPROVAL -> "This announcement is waiting for approval and cannot be "
                        + "edited. Withdraw it first if you need to change the wording.";
                case PUBLISHED -> "This announcement has already been sent to attendees, so its "
                        + "wording cannot be changed. Write a new one instead.";
                default -> "This announcement can no longer be edited.";
            });
        }

        announcement.setTitle(requireText(request == null ? null : request.getTitle(),
                "Give the announcement a title.", 150, "title"));
        announcement.setMessage(requireText(request == null ? null : request.getMessage(),
                "Write the message you want attendees to receive.", 1000, "message"));

        // Editing a rejected announcement puts it back in the author's
        // hands: the old reason described text that no longer exists, and
        // leaving it on screen reads as though it were still the verdict.
        if (announcement.getStatus() == AnnouncementStatus.REJECTED) {
            announcement.setStatus(AnnouncementStatus.DRAFT);
            announcement.setRejectReason(null);
            announcement.setDecidedAt(null);
            announcement.setDecidedBy(null);
            announcement.setSubmittedAt(null);
        }

        Announcement saved = announcementRepository.save(announcement);
        auditLogService.log(loadActor(actor), "UPDATE_ANNOUNCEMENT", "Announcement",
                announcementId, "Announcement text edited");
        return AnnouncementResponse.from(saved);
    }

    // =================================================================
    // UPDATE - the lifecycle
    // =================================================================

    @Override
    @Transactional
    public AnnouncementResponse submitForApproval(Long announcementId, ActingUser actor) {
        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> notFound(announcementId));
        accessPolicy.requireManager(announcement.getEvent(), actor,
                "You can only submit announcements for your own events.");

        if (!announcement.isEditable()) {
            throw new InvalidRequestException(
                    announcement.isPublished()
                            ? "This announcement has already been sent."
                            : "This announcement is already waiting for approval.");
        }
        requireAnnounceableEvent(announcement.getEvent());

        announcement.setStatus(AnnouncementStatus.PENDING_APPROVAL);
        announcement.setSubmittedAt(LocalDateTime.now());
        // The previous verdict belongs to the previous submission.
        announcement.setRejectReason(null);
        announcement.setDecidedAt(null);
        announcement.setDecidedBy(null);

        Announcement saved = announcementRepository.save(announcement);
        auditLogService.log(loadActor(actor), "SUBMIT_ANNOUNCEMENT", "Announcement",
                announcementId, "Submitted for administrator approval");
        return AnnouncementResponse.from(saved);
    }

    @Override
    @Transactional
    public AnnouncementResponse approveAndPublish(Long announcementId, ActingUser actor) {
        requireAdmin(actor, "Only an Administrator can approve an announcement.");

        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> notFound(announcementId));

        // The re-check under the lock is what makes the approval gate
        // real. Two administrators working the same queue both used to be
        // able to read PENDING_APPROVAL; the second now blocks here and
        // sees PUBLISHED, so the attendees are not told twice.
        if (announcement.getStatus() != AnnouncementStatus.PENDING_APPROVAL) {
            throw new InvalidRequestException(
                    announcement.isPublished()
                            ? "This announcement has already been approved and sent."
                            : "Only an announcement waiting for approval can be approved.");
        }

        return publish(announcement, actor, "APPROVE_ANNOUNCEMENT");
    }

    @Override
    @Transactional
    public AnnouncementResponse reject(Long announcementId, String reason, ActingUser actor) {
        requireAdmin(actor, "Only an Administrator can reject an announcement.");

        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> notFound(announcementId));

        if (announcement.getStatus() != AnnouncementStatus.PENDING_APPROVAL) {
            throw new InvalidRequestException(
                    announcement.isPublished()
                            ? "This announcement has already been sent and cannot be rejected."
                            : "Only an announcement waiting for approval can be rejected.");
        }

        // A rejection with no reason is the thing that makes an author
        // resubmit the same text and get rejected again. The schema
        // refuses it too, so this is a clear message rather than the
        // database's.
        String trimmed = requireText(reason,
                "Say why you are rejecting it, so the author can fix it.", 500, "reason");

        announcement.setStatus(AnnouncementStatus.REJECTED);
        announcement.setRejectReason(trimmed);
        announcement.setDecidedAt(LocalDateTime.now());
        announcement.setDecidedBy(loadActor(actor));

        Announcement saved = announcementRepository.save(announcement);

        // The author is told, in app, by the same notification machinery
        // the announcement itself would have used.
        notificationService.notify(saved.getAuthor(), NotificationType.ANNOUNCEMENT,
                NotificationChannel.IN_APP,
                "Your announcement \"" + saved.getTitle() + "\" for \""
                        + saved.getEvent().getName() + "\" was not approved: " + trimmed);

        auditLogService.log(loadActor(actor), "REJECT_ANNOUNCEMENT", "Announcement",
                announcementId, "Rejected: " + trimmed);
        return AnnouncementResponse.from(saved);
    }

    @Override
    @Transactional
    public AnnouncementResponse publishDirectly(Long announcementId, ActingUser actor) {
        requireAdmin(actor, "Only an Administrator can publish an announcement directly.");

        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> notFound(announcementId));

        if (announcement.isPublished()) {
            throw new InvalidRequestException("This announcement has already been sent.");
        }

        return publish(announcement, actor, "PUBLISH_ANNOUNCEMENT");
    }

    // =================================================================
    // DELETE
    // =================================================================

    @Override
    @Transactional
    public void delete(Long announcementId, ActingUser actor) {
        Announcement announcement = announcementRepository.findByIdForUpdate(announcementId)
                .orElseThrow(() -> notFound(announcementId));
        accessPolicy.requireManager(announcement.getEvent(), actor,
                "You can only delete announcements for your own events.");

        // The one guard that makes this delete safe. A published
        // announcement put a notification in every attendee's inbox;
        // removing the record would leave those messages with nothing
        // behind them saying who wrote them or who approved them - the
        // same reasoning that refuses to delete a discount a payment used.
        if (announcement.isPublished()) {
            throw new InvalidRequestException(
                    "This announcement has already been sent to " + announcement.getRecipientCount()
                            + " attendee(s), so it cannot be deleted - it is the record of what "
                            + "they were told and who approved it.");
        }

        Long eventId = announcement.getEvent().getId();
        String title = announcement.getTitle();
        announcementRepository.delete(announcement);

        auditLogService.log(loadActor(actor), "DELETE_ANNOUNCEMENT", "Announcement",
                announcementId, "Unpublished announcement \"" + title + "\" removed from event " + eventId);
    }

    // =================================================================
    // Publication - the one place that actually sends anything
    // =================================================================

    /**
     * Marks the announcement published and delivers it, in this
     * transaction.
     *
     * Both paths in - an administrator approving somebody else's
     * submission, and an administrator publishing their own draft - land
     * here, so there is one definition of what publication means and one
     * place where the fan-out happens.
     */
    private AnnouncementResponse publish(Announcement announcement, ActingUser actor, String auditAction) {
        requireAnnounceableEvent(announcement.getEvent());

        // Resolved now, not when it was written: people register and
        // cancel while an announcement sits in the queue.
        List<Registration> recipients =
                registrationRepository.findPlaceHoldersWithUser(announcement.getEvent().getId());

        LocalDateTime now = LocalDateTime.now();
        announcement.setStatus(AnnouncementStatus.PUBLISHED);
        announcement.setPublishedAt(now);
        announcement.setRecipientCount(recipients.size());
        announcement.setDecidedAt(now);
        announcement.setDecidedBy(loadActor(actor));
        announcement.setRejectReason(null);

        Announcement saved = announcementRepository.save(announcement);

        String body = saved.getEvent().getName() + " - " + saved.getTitle() + ": " + saved.getMessage();
        for (Registration registration : recipients) {
            notificationService.notify(registration.getUser(), NotificationType.ANNOUNCEMENT,
                    NotificationChannel.IN_APP, body);
        }

        // Publishing to nobody is allowed - an event can legitimately have
        // no attendees yet - but it is worth a line in the log, because
        // "I sent it and nobody got it" is otherwise a mystery.
        log.info("Announcement {} published on event {} to {} recipient(s)",
                saved.getId(), saved.getEvent().getId(), recipients.size());

        auditLogService.log(loadActor(actor), auditAction, "Announcement", saved.getId(),
                "Published to " + recipients.size() + " attendee(s) of event "
                        + saved.getEvent().getId());
        return AnnouncementResponse.from(saved);
    }

    // =================================================================
    // Guards and helpers
    // =================================================================

    /**
     * Who may READ one announcement.
     *
     * Wider than who may manage it, on purpose: the author keeps access to
     * their own submission after a rejection even if they have since lost
     * the ability to manage the event, so they can still read why it was
     * turned down.
     */
    private void requireCanRead(Announcement announcement, ActingUser actor) {
        if (actor == null) {
            throw new AccessDeniedException("Sign in to view this announcement.");
        }
        if (accessPolicy.canManage(announcement.getEvent(), actor)) {
            return;
        }
        if (actor.is(announcement.getAuthor().getId())) {
            return;
        }
        throw new AccessDeniedException("You are not authorized to view this announcement.");
    }

    private void requireAdmin(ActingUser actor, String message) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException(message);
        }
    }

    /**
     * An announcement needs an audience. A DRAFT or PENDING_APPROVAL event
     * has never been public, so nobody can hold a place on it - sending to
     * one would be sending to an empty room, permanently.
     */
    private void requireAnnounceableEvent(Event event) {
        if (!ANNOUNCEABLE.contains(event.getStatus())) {
            throw new InvalidRequestException(
                    "This event has not been published yet, so it has no attendees to announce to. "
                            + "Get the event approved first.");
        }
    }

    /**
     * Trims, rejects blank, and enforces the length the column can hold.
     *
     * Repeated here rather than left to the DTO's bean validation, because
     * bean validation only runs on the web path - a service called from a
     * listener, a test, or a future scheduled job would otherwise write a
     * 2000-character message into a 1000-character column and fail at the
     * database with something nobody can read.
     */
    private String requireText(String value, String blankMessage, int max, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(blankMessage);
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new InvalidRequestException(
                    "Keep the " + field + " to " + max + " characters or fewer - that one is "
                            + trimmed.length() + ".");
        }
        return trimmed;
    }

    private Event getEventOrThrow(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
    }

    private Announcement getOrThrow(Long id) {
        return announcementRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private ResourceNotFoundException notFound(Long id) {
        return new ResourceNotFoundException("Announcement not found with id " + id);
    }

    private User requireUser(ActingUser actor) {
        if (actor == null) {
            throw new AccessDeniedException("Sign in to write an announcement.");
        }
        return userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + actor.id()));
    }

    private User loadActor(ActingUser actor) {
        return actor == null ? null : userRepository.findById(actor.id()).orElse(null);
    }
}
