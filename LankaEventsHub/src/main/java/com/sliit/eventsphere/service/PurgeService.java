package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.*;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.ActiveSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Permanent deletion. Administrator only.
 *
 * Everything else in this product cancels, suspends or archives, and that
 * is the right default: a cancelled booking is a fact about what happened,
 * and a suspended account is one an administrator can put back. This class
 * is the escape hatch for the cases where a record should never have
 * existed at all - a test event on a live platform, a duplicate account, a
 * booking created against the wrong person - and where leaving a cancelled
 * husk behind is worse than removing it.
 *
 * Three rules hold for every method here.
 *
 * ONE. The audit row is written BEFORE anything is deleted, and it
 * describes what is about to go: not "event 14 deleted" but the event's
 * name, its organizer, and how many registrations, payments and refunds
 * went with it. Written afterwards it could not say any of that, because
 * the rows it describes would no longer exist to be counted. This is what
 * keeps a permanent delete traceable: the thing itself is gone, the fact
 * that somebody removed it is not.
 *
 * TWO. The children are deleted EXPLICITLY, in dependency order, in one
 * transaction - not left to the database's own ON DELETE rules. Half the
 * foreign keys in this schema are RESTRICT, so relying on cascade would
 * fail on some paths and silently work on others; and doing it here is
 * what lets the audit row count what went. If any step fails the whole
 * thing rolls back, so a purge cannot half-happen and leave orphans.
 *
 * THREE. Seat accounting is settled before the rows go. Deleting a
 * registration that was holding seats without giving them back would
 * leave the event permanently unable to sell them, and nothing in the
 * system would ever explain why.
 */
@Service
public class PurgeService {

    private static final Logger log = LoggerFactory.getLogger(PurgeService.class);

    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final UserRepository userRepository;
    private final AnnouncementRepository announcementRepository;
    private final SavedReportRepository savedReportRepository;
    private final DiscountRepository discountRepository;
    private final EventSessionRepository eventSessionRepository;
    private final EventInvitationRepository eventInvitationRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;
    private final ActiveSessionService activeSessionService;

    public PurgeService(EventRepository eventRepository,
                         RegistrationRepository registrationRepository,
                         PaymentRepository paymentRepository,
                         RefundRepository refundRepository,
                         UserRepository userRepository,
                         AnnouncementRepository announcementRepository,
                         SavedReportRepository savedReportRepository,
                         DiscountRepository discountRepository,
                         EventSessionRepository eventSessionRepository,
                         EventInvitationRepository eventInvitationRepository,
                         NotificationRepository notificationRepository,
                         PasswordResetTokenRepository passwordResetTokenRepository,
                         AuditLogRepository auditLogRepository,
                         AuditLogService auditLogService,
                         ActiveSessionService activeSessionService) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.userRepository = userRepository;
        this.announcementRepository = announcementRepository;
        this.savedReportRepository = savedReportRepository;
        this.discountRepository = discountRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.eventInvitationRepository = eventInvitationRepository;
        this.notificationRepository = notificationRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditLogService = auditLogService;
        this.activeSessionService = activeSessionService;
    }

    // =================================================================
    // Events
    // =================================================================

    /**
     * Deletes an event and everything that belongs to it: its
     * registrations, their payments, those payments' refunds, its
     * announcements, saved reports, discounts, sessions and invitations.
     *
     * Attendees are NOT notified, deliberately. Cancelling an event tells
     * everyone, because a cancelled event is a thing that happened to
     * them; purging one is an administrator saying it never existed, and
     * a message about an event nobody can look up is worse than silence.
     * That is also why cancelling is the right action almost every time
     * and this one is the exception.
     *
     * @param reason free text, recorded in the audit log; may be blank
     */
    @Transactional
    public void purgeEvent(Long eventId, ActingUser actor, String reason) {
        requireAdmin(actor, "delete an event permanently");

        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));

        List<Registration> registrations = registrationRepository.findByEventId(eventId);
        List<Payment> payments = paymentsFor(registrations);
        List<Refund> refunds = refundsFor(payments);
        List<Announcement> announcements =
                announcementRepository.findByEventIdOrderByCreatedAtDesc(eventId);
        List<Discount> discounts = discountRepository.findByEventId(eventId);
        List<EventSession> sessions = eventSessionRepository.findByEventId(eventId);
        List<EventInvitation> invitations = eventInvitationRepository.findByEventId(eventId);
        List<SavedReport> reports = savedReportRepository.findByEventId(eventId);

        // BEFORE the delete, while there is still something to describe.
        auditLogService.log(actingEntity(actor), "PURGE_EVENT", "Event", eventId,
                describe("Permanently deleted event \"" + event.getName() + "\""
                                + " (organizer " + organizerName(event) + ")",
                        reason,
                        registrations.size() + " registration(s)",
                        payments.size() + " payment(s)",
                        refunds.size() + " refund(s)",
                        announcements.size() + " announcement(s)",
                        discounts.size() + " discount(s)",
                        sessions.size() + " session(s)",
                        invitations.size() + " invitation(s)",
                        reports.size() + " saved report(s)"));

        // Deepest first. Every one of these is a real DELETE.
        refundRepository.deleteAll(refunds);
        paymentRepository.deleteAll(payments);
        registrationRepository.deleteAll(registrations);
        announcementRepository.deleteAll(announcements);
        savedReportRepository.deleteAll(reports);
        discountRepository.deleteAll(discounts);
        eventSessionRepository.deleteAll(sessions);
        eventInvitationRepository.deleteAll(invitations);
        // Flushed before the parent goes, so the deletes above are
        // definitely at the database when the foreign keys are checked
        // rather than still sitting in Hibernate's queue.
        registrationRepository.flush();
        eventRepository.delete(event);

        log.warn("Event {} permanently deleted by user {} - {} registration(s) went with it",
                eventId, actor.id(), registrations.size());
    }

    // =================================================================
    // Registrations
    // =================================================================

    /**
     * Deletes one booking and its payment and refund, and gives the seats
     * back.
     *
     * The seats matter more than they look. A booking that was holding
     * four of them and is deleted without returning them leaves the event
     * four seats short for the rest of its life, with nothing anywhere to
     * explain the discrepancy - the booking that accounted for them no
     * longer exists.
     *
     * No waitlist promotion, unlike a cancellation. Promoting somebody is
     * a thing that should happen when a real booking is given up; a purge
     * is an administrator saying this row should never have been here, and
     * moving the queue on the strength of it would be acting on a fiction.
     * The seats simply become available again.
     */
    @Transactional
    public void purgeRegistration(Long registrationId, ActingUser actor, String reason) {
        requireAdmin(actor, "delete a booking permanently");

        Registration probe = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));

        // EVENT FIRST, then the registration - the same lock order every
        // other path that touches both uses. Diverging here is how
        // deadlocks get introduced.
        Event event = eventRepository.findByIdForUpdate(probe.getEvent().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found with id " + probe.getEvent().getId()));
        Registration registration = registrationRepository.findByIdForUpdate(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));

        Payment payment = paymentRepository.findByRegistrationId(registrationId).orElse(null);
        Refund refund = payment == null
                ? null : refundRepository.findByPaymentId(payment.getId()).orElse(null);

        boolean heldSeats = registration.holdsASeat();
        int seats = registration.getSeats();

        auditLogService.log(actingEntity(actor), "PURGE_REGISTRATION", "Registration",
                registrationId,
                describe("Permanently deleted the booking held by "
                                + registration.getUser().getFullName()
                                + " on \"" + event.getName() + "\"",
                        reason,
                        seats + " seat(s), status " + registration.getStatus(),
                        payment == null
                                ? "no payment"
                                : "payment " + payment.getId() + " of " + payment.getFinalAmount()
                                        + " (" + payment.getStatus() + ")",
                        refund == null ? "no refund" : "refund " + refund.getId()
                                + " of " + refund.getAmount() + " (" + refund.getStatus() + ")",
                        heldSeats ? seats + " seat(s) returned to the event" : "held no seats"));

        if (refund != null) {
            refundRepository.delete(refund);
        }
        if (payment != null) {
            paymentRepository.delete(payment);
        }
        registrationRepository.delete(registration);
        registrationRepository.flush();

        if (heldSeats) {
            event.setSeatsTaken(Math.max(0, event.getSeatsTaken() - seats));
            eventRepository.save(event);
        }

        log.warn("Registration {} permanently deleted by user {}", registrationId, actor.id());
    }

    // =================================================================
    // Users
    // =================================================================

    /**
     * Deletes an account and everything personal to it: its bookings and
     * their payments and refunds, its notifications, saved reports,
     * invitations, announcements and reset tokens. Seats held by those
     * bookings go back to their events.
     *
     * Three things are refused rather than done.
     *
     * An administrator cannot purge THEMSELVES - the same rule that stops
     * them suspending their own account, for the same reason: a mistake
     * here cannot be undone from inside the product.
     *
     * The LAST active administrator cannot be purged. That guard exists
     * elsewhere for suspension and demotion; leaving it off the one
     * irreversible path would make it decorative.
     *
     * An account that still ORGANIZES events cannot be purged. Its events
     * carry other people's bookings and other people's money, and deleting
     * them as a side effect of removing one account would be the widest
     * blast radius in this system, reached through the narrowest button.
     * The administrator is told to deal with those events first.
     *
     * What does NOT go is the audit trail. Rows naming this person are
     * kept and their user reference nulled, because an audit trail that
     * disappears along with the account is not an audit trail - and the
     * details column names them in prose, so "who did this" survives.
     */
    @Transactional
    public void purgeUser(Long userId, ActingUser actor, String reason) {
        requireAdmin(actor, "delete an account permanently");

        if (actor.is(userId)) {
            throw new InvalidRequestException("You cannot delete your own account.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + userId));

        // LOCKED, like every other last-administrator check in this
        // system. An unlocked count lets two administrators each delete
        // the other's colleague and leave the platform with none.
        if (user.getRole() == UserRole.ADMIN && user.isActive()
                && userRepository.findActiveByRoleForUpdate(UserRole.ADMIN).size() <= 1) {
            throw new InvalidRequestException(
                    "This is the last active administrator account and cannot be deleted.");
        }

        long organized = eventRepository.countByOrganizerId(userId);
        if (organized > 0) {
            throw new InvalidRequestException(
                    "This account still owns " + organized + " event(s). Delete or reassign "
                            + "those events first - removing an account must not take other "
                            + "people's bookings with it.");
        }

        List<Registration> registrations = registrationRepository.findByUserId(userId);
        List<Payment> payments = paymentsFor(registrations);
        List<Refund> refunds = refundsFor(payments);
        List<Notification> notifications = notificationRepository.findByUserId(userId);
        List<SavedReport> reports = savedReportRepository.findByOwnerId(userId);
        List<EventInvitation> invitations = eventInvitationRepository.findByUserId(userId);
        List<Announcement> authored = announcementRepository.findByAuthorId(userId);
        List<Announcement> decided = announcementRepository.findByDecidedById(userId);
        List<PasswordResetToken> tokens = passwordResetTokenRepository.findByUserId(userId);
        List<AuditLog> audit = auditLogRepository.findByUserId(userId);
        List<Registration> checkedInByThem = registrationRepository.findByCheckedInById(userId);

        auditLogService.log(actingEntity(actor), "PURGE_USER", "User", userId,
                describe("Permanently deleted the account of " + user.getFullName()
                                + " <" + user.getEmail() + ">, role " + user.getRole(),
                        reason,
                        registrations.size() + " booking(s)",
                        payments.size() + " payment(s)",
                        refunds.size() + " refund(s)",
                        notifications.size() + " notification(s)",
                        reports.size() + " saved report(s)",
                        invitations.size() + " invitation(s)",
                        authored.size() + " announcement(s) written",
                        audit.size() + " audit row(s) kept, their user reference cleared"));

        // Seats first, while the bookings still say how many they held.
        returnSeats(registrations);

        // Kept, not deleted - the reference is cleared instead.
        audit.forEach(row -> row.setUser(null));
        auditLogRepository.saveAll(audit);
        decided.forEach(a -> a.setDecidedBy(null));
        announcementRepository.saveAll(decided);
        checkedInByThem.forEach(r -> r.setCheckedInBy(null));
        registrationRepository.saveAll(checkedInByThem);

        refundRepository.deleteAll(refunds);
        paymentRepository.deleteAll(payments);
        registrationRepository.deleteAll(registrations);
        announcementRepository.deleteAll(authored);
        savedReportRepository.deleteAll(reports);
        eventInvitationRepository.deleteAll(invitations);
        notificationRepository.deleteAll(notifications);
        passwordResetTokenRepository.deleteAll(tokens);
        registrationRepository.flush();
        userRepository.delete(user);

        // Their browser still holds a signed-in session for an account
        // that no longer exists. Ending it here is the difference between
        // a clean sign-out and a stream of errors on every page they open.
        activeSessionService.invalidateSessionsFor(userId);

        log.warn("User {} permanently deleted by user {} - {} booking(s) went with it",
                userId, actor.id(), registrations.size());
    }

    // =================================================================
    // Helpers
    // =================================================================

    /**
     * Gives back the seats every still-holding booking in this list was
     * occupying, event by event.
     *
     * Grouped rather than one save per booking: an attendee with six
     * bookings on the same event would otherwise have that event's row
     * read and written six times inside one transaction.
     */
    private void returnSeats(List<Registration> registrations) {
        java.util.Map<Long, Integer> seatsByEvent = new java.util.LinkedHashMap<>();
        for (Registration registration : registrations) {
            if (registration.holdsASeat()) {
                seatsByEvent.merge(registration.getEvent().getId(),
                        registration.getSeats(), Integer::sum);
            }
        }
        seatsByEvent.forEach((eventId, seats) -> {
            // Locked, like every other write to seats_taken: a purge must
            // not race a registration for the seats it is releasing.
            eventRepository.findByIdForUpdate(eventId).ifPresent(event -> {
                event.setSeatsTaken(Math.max(0, event.getSeatsTaken() - seats));
                eventRepository.save(event);
            });
        });
    }

    private List<Payment> paymentsFor(List<Registration> registrations) {
        if (registrations.isEmpty()) {
            return List.of();
        }
        return paymentRepository.findByRegistrationIdIn(
                registrations.stream().map(Registration::getId).toList());
    }

    private List<Refund> refundsFor(List<Payment> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        return refundRepository.findByPaymentIdIn(
                payments.stream().map(Payment::getId).toList());
    }

    /**
     * Builds the audit sentence: what was removed, why if a reason was
     * given, and then the counts.
     *
     * One string rather than a structured record because the audit table
     * stores prose, and because this has to remain readable years later
     * to somebody who no longer has any of the rows it describes.
     */
    private String describe(String headline, String reason, String... counts) {
        StringBuilder text = new StringBuilder(headline);
        if (reason != null && !reason.isBlank()) {
            text.append(". Reason: ").append(reason.trim());
        }
        List<String> parts = new ArrayList<>(List.of(counts));
        text.append(". Removed: ").append(String.join(", ", parts)).append('.');
        return text.toString();
    }

    private String organizerName(Event event) {
        return event.getOrganizer() == null ? "unknown" : event.getOrganizer().getFullName();
    }

    private void requireAdmin(ActingUser actor, String action) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException("Only an Administrator can " + action + ".");
        }
    }

    private User actingEntity(ActingUser actor) {
        return userRepository.findById(actor.id()).orElse(null);
    }
}
