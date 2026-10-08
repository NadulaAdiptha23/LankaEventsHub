package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.BookingRow;
import com.sliit.eventsphere.dto.BookingSearchCriteria;
import com.sliit.eventsphere.dto.RegistrationAvailability;
import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.events.RegistrationCancelledEvent;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.RegistrationSpecifications;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import com.sliit.eventsphere.service.registration.DuplicateRegistrationValidator;
import com.sliit.eventsphere.service.registration.EventStatusValidator;
import com.sliit.eventsphere.service.registration.InvitationEligibilityValidator;
import com.sliit.eventsphere.service.registration.RegistrationValidationContext;
import com.sliit.eventsphere.service.registration.RegistrationValidator;
import com.sliit.eventsphere.service.registration.RegistrationWindowValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Function 2: Registration & Booking.
 *
 * Design note: this class calls NotificationService directly rather than
 * going through the Observer-pattern event bus used between
 * EventServiceImpl and Notifications/Payment. That's a deliberate choice,
 * not an inconsistency: Observer earns its keep specifically at
 * boundaries between different function owners reacting to the same
 * trigger (see events/ and listener/ packages). Here, "a registration
 * happened, tell the user" is a single, tightly-scoped step within one
 * function's own flow, so a direct call is simpler and just as correct.
 *
 * Cancellation is the exception that proves the rule, and it does go
 * through the event bus. "A paid seat was given up" has to reach Payment
 * & Ticketing, which is a different function owner - exactly the boundary
 * above - so cancelRegistration publishes RegistrationCancelledEvent and
 * lets RefundTriggerListener decide whether money is owed. Registration &
 * Booking does not import RefundService and does not need to know that
 * refunds exist. It does read RefundPolicy - the commercial rule about
 * when money is owed back - because it has to warn an attendee what
 * cancelling will cost them before they press the button, and a warning
 * that is a separately maintained copy of that rule would eventually
 * tell them the wrong thing. The rule, not the machinery.
 *
 * Security note: every method takes the authenticated ActingUser. No
 * method accepts a caller-supplied user id, which is what stops one
 * attendee registering, cancelling, or reading on behalf of another.
 */
@Service
public class RegistrationServiceImpl implements RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationServiceImpl.class);
    private static final long CANCELLATION_CUTOFF_HOURS = 48;

    /**
     * How long a seat on a paid event is held while the attendee pays.
     *
     * Long enough to find a card and get through a checkout, short enough
     * that an abandoned one does not keep a seat off the market for the
     * rest of the day. Configurable because the right answer depends on
     * the event: a free community talk and a conference with a waiting
     * list are not the same problem.
     */
    private final long paymentHoldMinutes;

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final RegistrationRepository registrationRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final EventInvitationService eventInvitationService;
    private final EventAccessPolicy accessPolicy;
    private final ApplicationEventPublisher eventPublisher;
    // Mostly read-only: the admin bookings view, and the "would
    // cancelling cost me anything" check. The one write is the
    // zero-value ticket for a free event, which has no payment to wait
    // for and therefore nothing for Payment & Ticketing to react to.
    // Everything involving actual money still belongs to that module.
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    // The rule about when money is owed back, NOT the machinery that
    // gives it back. Booking needs the rule so it can warn an attendee
    // what cancelling will cost them before they press the button; it
    // still knows nothing about refund rows or the refund service.
    private final RefundPolicy refundPolicy;
    private final TicketCodeGenerator ticketCodeGenerator;

    /**
     * The Chain of Responsibility for Function 2 - see
     * com.sliit.eventsphere.service.registration.RegistrationValidator.
     * This field holds the HEAD of the chain (EventStatusValidator); the
     * other three links are reachable only by following it, the same way
     * a caller of a linked list holds only the head. Built once, in the
     * constructor, from the four validator beans Spring injects below -
     * the chain's shape never changes at runtime, so there is nothing to
     * gain from rebuilding it per call.
     */
    private final RegistrationValidator registrationValidationChain;

    public RegistrationServiceImpl(EventRepository eventRepository,
                                    UserRepository userRepository,
                                    RegistrationRepository registrationRepository,
                                    NotificationService notificationService,
                                    AuditLogService auditLogService,
                                    EventInvitationService eventInvitationService,
                                    EventAccessPolicy accessPolicy,
                                    ApplicationEventPublisher eventPublisher,
                                    PaymentRepository paymentRepository,
                                    RefundRepository refundRepository,
                                    RefundPolicy refundPolicy,
                                    TicketCodeGenerator ticketCodeGenerator,
                                    EventStatusValidator eventStatusValidator,
                                    RegistrationWindowValidator registrationWindowValidator,
                                    DuplicateRegistrationValidator duplicateRegistrationValidator,
                                    InvitationEligibilityValidator invitationEligibilityValidator,
                                    @Value("${app.bookings.payment-hold-minutes:30}")
                                    long paymentHoldMinutes) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.registrationRepository = registrationRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.eventInvitationService = eventInvitationService;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.refundPolicy = refundPolicy;
        this.ticketCodeGenerator = ticketCodeGenerator;
        eventStatusValidator.linkTo(registrationWindowValidator)
                .linkTo(duplicateRegistrationValidator)
                .linkTo(invitationEligibilityValidator);
        this.registrationValidationChain = eventStatusValidator;
        if (paymentHoldMinutes <= 0) {
            throw new IllegalArgumentException(
                    "app.bookings.payment-hold-minutes must be greater than zero; a hold of "
                            + paymentHoldMinutes + " minutes would expire before the attendee "
                            + "could reach the payment page.");
        }
        this.paymentHoldMinutes = paymentHoldMinutes;
    }

    /**
     * Records the zero-value payment that carries a free event's ticket
     * code.
     *
     * It is a COMPLETED payment of 0.00 rather than no payment at all,
     * because "has this person got a ticket?" is answered by the payment
     * row everywhere in this system, and a free attendee has as much
     * right to a ticket as a paying one. The amount being zero is what
     * keeps it out of revenue and out of refunds - RefundServiceImpl
     * refuses to raise a refund for a zero-amount payment.
     */
    private void issueFreeTicket(Registration registration) {
        Payment ticket = new Payment();
        ticket.setRegistration(registration);
        ticket.setOriginalAmount(java.math.BigDecimal.ZERO);
        ticket.setDiscountAmount(java.math.BigDecimal.ZERO);
        ticket.setFinalAmount(java.math.BigDecimal.ZERO);
        ticket.setStatus(PaymentStatus.COMPLETED);
        ticket.setPaidAt(LocalDateTime.now());
        ticket.setTicketCode(ticketCodeGenerator.generate(registration));
        paymentRepository.save(ticket);

        log.info("Free event {} - ticket issued for registration {} at no charge",
                registration.getEvent().getId(), registration.getId());
    }

    /**
     * Whether this event costs anything. A free event has nothing to pay
     * for, so it never goes through a payment hold.
     */
    private boolean isFree(Event event) {
        return event.getFee() == null
                || event.getFee().compareTo(java.math.BigDecimal.ZERO) <= 0;
    }

    // =================================================================
    // Register
    // =================================================================

    /**
     * The largest party one booking may hold.
     *
     * Ten, matching the CHECK on registrations.seats exactly. Two places
     * enforce the same number because each protects against a different
     * failure: the service so the attendee gets a sentence instead of a
     * constraint violation, the database so a future caller that forgets
     * to ask cannot write a row that breaks every seat count on the
     * platform.
     */
    static final int MAX_SEATS_PER_BOOKING = 10;

    @Override
    @Transactional
    public RegistrationResponse register(Long eventId, ActingUser actor) {
        return register(eventId, 1, actor);
    }

    @Override
    @Transactional
    public RegistrationResponse register(Long eventId, int seats, ActingUser actor) {
        if (seats < 1) {
            throw new InvalidRequestException("Book at least one seat.");
        }
        if (seats > MAX_SEATS_PER_BOOKING) {
            throw new InvalidRequestException(
                    "One booking can hold at most " + MAX_SEATS_PER_BOOKING
                            + " seats. For a larger group, contact the organizer.");
        }

        // PESSIMISTIC_WRITE lock: no other transaction can read/modify this
        // row until this transaction commits or rolls back. This is what
        // makes "check seatsTaken, then increment it" atomic across two
        // concurrent requests for the same event - the second request
        // simply blocks until the first one finishes, then sees the
        // updated seatsTaken and correctly falls back to waitlisting.
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));

        Long userId = actor.id();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + userId));

        // CHAIN OF RESPONSIBILITY: event status, registration window,
        // duplicate registration, restricted-event invitation - in that
        // order, against the event this method already holds the lock
        // on. See RegistrationValidator for why capacity is deliberately
        // not one of these links.
        String blocked = registrationValidationChain.validate(
                new RegistrationValidationContext(event, userId));
        if (blocked != null) {
            throw new InvalidRequestException(blocked);
        }

        Registration registration = new Registration();
        registration.setEvent(event);
        registration.setUser(user);
        registration.setSeats(seats);

        // A party that cannot fit in what is left waits as a party. Note
        // this asks for the whole group, not for one seat: an event with
        // two seats left is full as far as a booking for four is
        // concerned, and seating two of them would be worse than saying
        // so.
        if (!event.hasAvailableSeats(seats)) {
            registration.setStatus(RegistrationStatus.WAITLISTED);
        } else if (isFree(event)) {
            // Nothing to pay, so there is nothing to wait for. A free
            // event is confirmed on the spot, exactly as before.
            registration.setStatus(RegistrationStatus.CONFIRMED);
            event.setSeatsTaken(event.getSeatsTaken() + seats);
            eventRepository.save(event);
        } else {
            // A paid event reserves the seat rather than confirming it.
            // The seat is taken from this moment - nobody else can have it
            // while the hold lasts - but the booking is not a booking
            // until the money arrives. This is the only way both
            // requirements can hold at once; see RegistrationStatus.
            registration.setStatus(RegistrationStatus.PENDING_PAYMENT);
            registration.setPaymentDueAt(LocalDateTime.now().plusMinutes(paymentHoldMinutes));
            event.setSeatsTaken(event.getSeatsTaken() + seats);
            eventRepository.save(event);
        }

        Registration saved = registrationRepository.save(registration);

        // A free event has nothing to pay for, so it is confirmed here -
        // and a confirmed booking that cannot produce a ticket is not a
        // booking anybody can use at a door. The ticket is issued now,
        // rather than by walking the attendee through a checkout for
        // 0.00 that exists only to hand them one.
        //
        // The zero-value payment row is what carries the ticket code, so
        // the free and paid paths produce the same shape of record and
        // every page that reads a ticket works the same way for both.
        if (saved.getStatus() == RegistrationStatus.CONFIRMED && isFree(event)) {
            issueFreeTicket(saved);
        }

        String party = seats == 1 ? "" : " for " + seats + " people";
        switch (saved.getStatus()) {
            case CONFIRMED -> notificationService.notify(user,
                    NotificationType.REGISTRATION_CONFIRMATION,
                    NotificationChannel.IN_APP,
                    "You're confirmed for \"" + event.getName() + "\"" + party + ".");
            case PENDING_PAYMENT -> notificationService.notify(user,
                    NotificationType.REGISTRATION_CONFIRMATION,
                    NotificationChannel.IN_APP,
                    (seats == 1 ? "A seat" : seats + " seats")
                            + " for \"" + event.getName() + "\" "
                            + (seats == 1 ? "is" : "are") + " held for you. Pay within "
                            + paymentHoldMinutes + " minutes to confirm "
                            + (seats == 1 ? "it" : "them") + ", or "
                            + (seats == 1 ? "it goes" : "they go") + " back to the pool.");
            default -> notificationService.notify(user, NotificationType.WAITLIST_UPDATE,
                    NotificationChannel.IN_APP,
                    seats == 1
                            ? "\"" + event.getName() + "\" is full - you've been added to the "
                                    + "waitlist and will be notified automatically if a seat "
                                    + "opens up."
                            // Deliberately a different sentence: the event may
                            // well have seats left, just not this many, and
                            // telling a party of four that it is "full" when
                            // the page shows two seats free reads as a bug.
                            : "\"" + event.getName() + "\" does not have " + seats
                                    + " seats free together - you've been added to the waitlist "
                                    + "and will be notified automatically if enough seats open "
                                    + "up at once.");
        }

        auditLogService.log(user, "REGISTER", "Event", eventId,
                "Registration " + saved.getId() + " created with status " + saved.getStatus()
                        + " for " + seats + " seat" + (seats == 1 ? "" : "s"));

        return RegistrationResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationAvailability checkAvailability(Long eventId, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));

        boolean invitationRequired = event.getEventType() == EventType.RESTRICTED;

        if (actor == null) {
            // Anonymous visitor: the page shows "log in to register", so the
            // only thing worth reporting is whether the event itself is
            // open - a null userId makes the chain's duplicate/invitation
            // links no-ops, so this is exactly the two event-level checks,
            // not four (see DuplicateRegistrationValidator/
            // InvitationEligibilityValidator's null-userId handling).
            String anonymousBlocked = registrationValidationChain.validate(
                    new RegistrationValidationContext(event, null));
            return new RegistrationAvailability(false, false, anonymousBlocked,
                    null, false, null, invitationRequired, null, false, null, null,
                    event.seatsRemaining(), MAX_SEATS_PER_BOOKING, null);
        }

        String invitationStatus = null;
        if (invitationRequired) {
            var mine = eventInvitationService.findMyInvitation(eventId, actor);
            invitationStatus = mine == null ? null : mine.getStatus().name();
        }

        Registration existing = registrationRepository
                .findActiveByEventIdAndUserId(eventId, actor.id()).orElse(null);
        RegistrationStatus myStatus = existing == null ? null : existing.getStatus();

        boolean canCancel = false;
        String cancelBlockedReason = null;
        if (existing != null) {
            if (existing.getStatus() != RegistrationStatus.CONFIRMED) {
                // Leaving a waitlist, or walking away from an unpaid hold,
                // is always allowed: neither costs the organizer a
                // confirmed head, and the hold gives its seat straight
                // back. The cutoff below exists to protect a headcount
                // that in these cases does not include this person.
                canCancel = true;
            } else if (event.getStartDatetime() == null) {
                canCancel = true;
            } else {
                long hours = Duration.between(LocalDateTime.now(), event.getStartDatetime()).toHours();
                canCancel = hours >= CANCELLATION_CUTOFF_HOURS;
                if (!canCancel) {
                    cancelBlockedReason = "Cancellations close " + CANCELLATION_CUTOFF_HOURS
                            + " hours before the event starts.";
                }
            }
        }

        // CHAIN OF RESPONSIBILITY: the same four-link chain register()
        // uses, in "return the reason" mode rather than "throw" mode. This
        // used to be three separate checks here (one shared helper call
        // plus two more re-implemented locally) that had quietly drifted
        // to word the restricted-event rejection differently from
        // register()'s own message for the identical situation.
        String blocked = registrationValidationChain.validate(
                new RegistrationValidationContext(event, actor.id()));

        boolean canRegister = blocked == null;
        boolean wouldWaitlist = canRegister && !event.hasAvailableSeats();

        return new RegistrationAvailability(canRegister, wouldWaitlist, blocked, myStatus,
                canCancel, cancelBlockedReason, invitationRequired, invitationStatus,
                cancelWouldForfeitPayment(existing),
                existing == null ? null : existing.getId(),
                existing == null ? null : existing.getPaymentDueAt(),
                event.seatsRemaining(), MAX_SEATS_PER_BOOKING,
                existing == null ? null : existing.getSeats());
    }

    /**
     * Would cancelling this booking cost the attendee what they paid?
     *
     * Asked of the same facts the refund service will use when the
     * button is actually pressed - a completed, non-zero payment on a
     * seat-holding registration, under a policy that does not refund
     * self-cancellation - so the warning and the outcome cannot
     * disagree. When the policy is generous, or the place was free, or
     * it was only ever a waitlist place, there is nothing to warn about
     * and this is false.
     */
    private boolean cancelWouldForfeitPayment(Registration existing) {
        if (existing == null || existing.getStatus() != RegistrationStatus.CONFIRMED) {
            return false;
        }
        if (refundPolicy.refundsOnAttendeeCancellation()) {
            return false;
        }
        return paymentRepository.findByRegistrationId(existing.getId())
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .filter(p -> p.getFinalAmount() != null
                        && p.getFinalAmount().compareTo(java.math.BigDecimal.ZERO) > 0)
                .isPresent();
    }

    // =================================================================
    // Cancel
    // =================================================================

    @Override
    @Transactional
    public void cancelRegistration(Long eventId, ActingUser actor) {
        // Order matters here. Lock the EVENT first, because that is the row
        // whose seat count is about to change and the same row every other
        // registration and cancellation for this event contends on. Taking
        // it first also gives every path through this feature one
        // consistent lock order, so two of them can never deadlock.
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));

        // Only now read the registration, and read it FOR UPDATE. The
        // previous version loaded it before the lock, so two concurrent
        // cancellations of the same registration could both see it as
        // active and both decrement seatsTaken. With the locked re-read the
        // second transaction blocks until the first commits, then finds no
        // active registration and stops here.
        Registration registration = registrationRepository
                .findActiveByEventIdAndUserIdForUpdate(eventId, actor.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "You do not have an active registration for this event"));

        RegistrationStatus previousStatus = registration.getStatus();
        boolean wasConfirmed = previousStatus == RegistrationStatus.CONFIRMED;
        boolean heldASeat = registration.holdsASeat();

        // The 48-hour cutoff protects the organizer's headcount, so it only
        // applies to someone giving up an actual, confirmed seat. Leaving a
        // waitlist costs the organizer nothing, and abandoning an unpaid
        // hold gives the seat straight back - blocking either would be the
        // rule doing the opposite of its job.
        if (wasConfirmed && event.getStartDatetime() != null) {
            long hoursUntilStart = Duration.between(LocalDateTime.now(), event.getStartDatetime()).toHours();
            if (hoursUntilStart < CANCELLATION_CUTOFF_HOURS) {
                throw new InvalidRequestException(
                        "Cancellations must be made at least " + CANCELLATION_CUTOFF_HOURS
                                + " hours before the event starts. "
                                + "Please contact event staff directly for late changes.");
            }
        }

        registration.setStatus(RegistrationStatus.CANCELLED);
        registration.setCancelledAt(LocalDateTime.now());
        registration.setPaymentDueAt(null);
        registrationRepository.save(registration);

        // A held seat is as unavailable as a confirmed one, so giving up
        // either releases them and lets the waitlist move. The whole party
        // comes back at once - a booking for four returns four seats.
        if (heldASeat) {
            event.setSeatsTaken(Math.max(0, event.getSeatsTaken() - registration.getSeats()));
            eventRepository.save(event);
            promoteNextWaitlisted(event);
        }

        auditLogService.log(registration.getUser(), "CANCEL_REGISTRATION", "Event", eventId,
                "Registration " + registration.getId() + " cancelled by attendee, "
                        + registration.getSeats() + " seat"
                        + (registration.getSeats() == 1 ? "" : "s") + " released");

        // Announce it. Payment & Ticketing subscribes and decides whether
        // money is owed; this method does not know and does not need to.
        // Published last, once the seat has been released and the waitlist
        // promoted, so a listener never observes a half-finished state.
        // The listener runs synchronously inside this transaction, so a
        // failure there rolls the cancellation back rather than leaving a
        // cancelled booking with no refund behind it.
        eventPublisher.publishEvent(new RegistrationCancelledEvent(
                this, event, registration, previousStatus, false));
    }

    // =================================================================
    // Update ("Edit booking") - change the number of seats on a booking
    // that has not been paid for yet
    // =================================================================

    @Override
    @Transactional
    public RegistrationResponse updateBooking(Long registrationId, int newSeats, ActingUser actor) {
        if (newSeats < 1) {
            throw new InvalidRequestException("Book at least one seat.");
        }
        if (newSeats > MAX_SEATS_PER_BOOKING) {
            throw new InvalidRequestException(
                    "One booking can hold at most " + MAX_SEATS_PER_BOOKING
                            + " seats. For a larger group, contact the organizer.");
        }

        // Read once, unlocked, purely to find out which event to lock and
        // to reject someone editing a booking that isn't theirs before
        // taking any lock at all - the same "probe" pattern
        // cancelRegistrationAsStaff uses.
        Registration probe = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));
        if (!actor.is(probe.getUser().getId())) {
            throw new AccessDeniedException("This registration does not belong to you.");
        }

        // Same lock order as every other path in this feature: event,
        // then registration. A seat-count change is exactly the kind of
        // write cancelRegistration's ordering comment warns about -
        // diverging here is how a deadlock gets introduced.
        Event event = eventRepository.findByIdForUpdate(probe.getEvent().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found with id " + probe.getEvent().getId()));
        Registration registration = registrationRepository.findByIdForUpdate(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));

        // Deliberately narrow scope: a booking can be resized only while
        // no money has changed hands for it yet. WAITLISTED has no seat
        // to resize; CANCELLED is over; and CONFIRMED-on-a-paid-event
        // means a payment already completed for a specific amount and
        // seat count - changing the seat count afterwards would leave
        // that payment wrong with no refund/re-charge flow to correct
        // it. Everything else (CONFIRMED-and-free, or still
        // PENDING_PAYMENT) has never had money move, so the seat count
        // is still just a number, not a number somebody was charged for.
        switch (registration.getStatus()) {
            case WAITLISTED -> throw new InvalidRequestException(
                    "A waitlisted booking has no seat yet to resize - cancel it and rejoin with "
                            + "the number of seats you need, or wait to be promoted.");
            case CANCELLED -> throw new InvalidRequestException("This booking is cancelled.");
            case CONFIRMED -> {
                if (!isFree(event)) {
                    throw new InvalidRequestException(
                            "This booking is already paid for and confirmed. Cancel it and make "
                                    + "a new booking if you need a different number of seats.");
                }
            }
            default -> { /* PENDING_PAYMENT: editable, nothing to check here */ }
        }

        int oldSeats = registration.getSeats();
        int delta = newSeats - oldSeats;
        if (delta == 0) {
            return RegistrationResponse.from(registration);
        }

        if (delta > 0) {
            // Growing the booking needs room for the EXTRA seats - the same
            // "whole party or none of it" rule register() applies, just
            // applied to the delta instead of the full new total.
            if (!event.hasAvailableSeats(delta)) {
                throw new InvalidRequestException(
                        "Only " + event.seatsRemaining() + " seat(s) left - not enough room to "
                                + "add " + delta + " more. Cancel and rejoin the waitlist instead.");
            }
            event.setSeatsTaken(event.getSeatsTaken() + delta);
        } else {
            // Shrinking it gives the difference back, exactly like a
            // partial cancellation - which is what it is.
            event.setSeatsTaken(Math.max(0, event.getSeatsTaken() + delta));
        }
        eventRepository.save(event);

        registration.setSeats(newSeats);
        // paymentDueAt is deliberately left untouched: resizing an unpaid
        // hold neither earns it more time nor loses it any. The next
        // payment attempt recalculates the amount from the new seat count
        // automatically, because the amount was never cached anywhere to
        // begin with - see PaymentServiceImpl.amountDueFor.
        Registration saved = registrationRepository.save(registration);

        // Shrinking a booking on a full event is exactly the situation
        // that should wake the waitlist up, the same as a cancellation
        // does.
        if (delta < 0) {
            promoteNextWaitlisted(event);
        }

        auditLogService.log(registration.getUser(), "UPDATE_BOOKING", "Registration", registration.getId(),
                "Seats changed from " + oldSeats + " to " + newSeats + " for event " + event.getId());

        return RegistrationResponse.from(saved);
    }

    // =================================================================
    // Staff cancellation - the admin bookings screen
    // =================================================================

    @Override
    @Transactional
    public void cancelRegistrationAsStaff(Long registrationId, ActingUser actor, String reason) {
        if (!actor.isAdmin()) {
            throw new AccessDeniedException(
                    "Only an Administrator can cancel a booking on an attendee's behalf.");
        }

        // Read once, unlocked, purely to find out which event to lock. The
        // authoritative read is the locked one below; this row is not
        // trusted for anything that gets written.
        Registration probe = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));

        // Same lock order as every other path in this feature: event, then
        // registration. Diverging here is how deadlocks get introduced.
        Event event = eventRepository.findByIdForUpdate(probe.getEvent().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found with id " + probe.getEvent().getId()));

        Registration registration = registrationRepository
                .findActiveByEventIdAndUserIdForUpdate(event.getId(), probe.getUser().getId())
                .orElseThrow(() -> new InvalidRequestException(
                        "That booking is already cancelled."));

        RegistrationStatus previous = registration.getStatus();
        boolean heldASeat = registration.holdsASeat();

        // Deliberately NO 48-hour cutoff here. That rule exists to protect
        // the organizer's headcount from late attendee changes; an
        // Administrator acting on a support request is the exception the
        // rule's own error message tells people to go and find.
        registration.setStatus(RegistrationStatus.CANCELLED);
        registration.setCancelledAt(LocalDateTime.now());
        registration.setPaymentDueAt(null);
        registrationRepository.save(registration);

        if (heldASeat) {
            event.setSeatsTaken(Math.max(0, event.getSeatsTaken() - registration.getSeats()));
            eventRepository.save(event);
            promoteNextWaitlisted(event);
        }

        String note = (reason == null || reason.isBlank())
                ? "no reason given"
                : reason.trim();
        // The audit row records the ADMINISTRATOR who acted, not the
        // attendee it was done to - "who did this" is the question an
        // audit trail exists to answer.
        User admin = userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Acting user not found with id " + actor.id()));
        auditLogService.log(admin, "ADMIN_CANCEL_REGISTRATION", "Registration",
                registration.getId(),
                "Cancelled for attendee " + registration.getUser().getId()
                        + " on event " + event.getId() + " - " + note);

        // The attendee did not press this button, so unlike a self-service
        // cancellation they have to be told it happened.
        notificationService.notify(registration.getUser(), NotificationType.EVENT_CHANGE,
                NotificationChannel.IN_APP,
                "Your booking for \"" + event.getName()
                        + "\" was cancelled by an administrator (" + note + ").");

        eventPublisher.publishEvent(new RegistrationCancelledEvent(
                this, event, registration, previous, true));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BookingRow> searchBookings(BookingSearchCriteria criteria,
                                            Pageable pageable,
                                            ActingUser actor) {
        if (!actor.isAdmin()) {
            throw new AccessDeniedException(
                    "Only an Administrator can view platform-wide bookings.");
        }

        // Query 1 - the page itself, filtered in the database.
        Page<Registration> page = registrationRepository.findAll(
                RegistrationSpecifications.matching(criteria), pageable);

        List<Registration> rows = page.getContent();
        if (rows.isEmpty()) {
            return page.map(r -> BookingRow.of(r, null, null));
        }

        // Query 2 - every payment for this page, in one round trip. Doing
        // it per row is the N+1 select problem: a 50-row page would issue
        // 51 queries, and the count grows with the page size rather than
        // staying fixed.
        List<Long> registrationIds = rows.stream().map(Registration::getId).toList();
        Map<Long, Payment> paymentsByRegistration = paymentRepository
                .findByRegistrationIdIn(registrationIds).stream()
                .collect(Collectors.toMap(p -> p.getRegistration().getId(), p -> p));

        // Query 3 - and every refund for those payments, the same way.
        Map<Long, Refund> refundsByPayment = paymentsByRegistration.isEmpty()
                ? Map.of()
                : refundRepository.findByPaymentIdIn(
                        paymentsByRegistration.values().stream().map(Payment::getId).toList())
                    .stream()
                    .collect(Collectors.toMap(rf -> rf.getPayment().getId(), rf -> rf));

        return page.map(r -> {
            Payment payment = paymentsByRegistration.get(r.getId());
            Refund refund = payment == null ? null : refundsByPayment.get(payment.getId());
            return BookingRow.of(r, payment, refund);
        });
    }

    /**
     * Fills the seats that were just freed from the waitlist, oldest
     * booking first. Runs inside the same locked transaction as the
     * cancellation that freed them, so it cannot race with a brand-new
     * registration attempt for the same seats.
     *
     * A cancellation can now free several seats at once, so this walks the
     * queue rather than promoting exactly one booking: four seats coming
     * back should be able to seat four people who have been waiting, not
     * one of them with three seats left standing empty.
     *
     * Where a party does not fit, it stays on the waitlist and the walk
     * continues past it. That is a deliberate exception to strict
     * first-come-first-served, and it cuts both ways: a party of eight
     * near the front will watch smaller bookings behind it be seated. The
     * alternative - stopping dead at the first booking that does not fit -
     * means a single large party at the head of the queue blocks every
     * seat on the event until it either cancels or the event happens,
     * which is a worse outcome for everybody including them. The order is
     * still honoured for every booking that can actually be seated.
     */
    private void promoteNextWaitlisted(Event event) {
        if (!event.hasAvailableSeats()) {
            // Defensive: capacity may have been reduced since these people
            // joined the waitlist. Never push seatsTaken past capacity.
            return;
        }
        List<Registration> waitlist = registrationRepository
                .findWaitlistOrderedForUpdate(event.getId());
        if (waitlist.isEmpty()) {
            return;
        }

        boolean anyPromoted = false;
        for (Registration nextInLine : waitlist) {
            int seats = nextInLine.getSeats();
            if (!event.hasAvailableSeats(seats)) {
                continue;
            }

            // Promotion cannot confirm a paid booking outright - that would
            // be the same unpaid-confirmation bug arriving through a side
            // door. The seats become theirs and the clock starts; if they
            // do not pay the seats move on to whoever is next.
            if (isFree(event)) {
                nextInLine.setStatus(RegistrationStatus.CONFIRMED);
                // Same rule as a direct free-event registration: CONFIRMED
                // implies a ticket exists. Promotion is just a later route
                // to the same CONFIRMED state register() reaches directly,
                // and skipping this call was the bug - it left a promoted
                // attendee CONFIRMED with no payment row, so their own
                // ticket page (getByRegistrationId) threw ResourceNotFoundException
                // instead of showing their ticket.
                issueFreeTicket(nextInLine);
                notificationService.notify(nextInLine.getUser(), NotificationType.WAITLIST_UPDATE,
                        NotificationChannel.IN_APP,
                        (seats == 1 ? "A seat" : seats + " seats")
                                + " opened up for \"" + event.getName() + "\" and you've been "
                                + "moved from the waitlist to a confirmed registration.");
            } else {
                nextInLine.setStatus(RegistrationStatus.PENDING_PAYMENT);
                nextInLine.setPaymentDueAt(LocalDateTime.now().plusMinutes(paymentHoldMinutes));
                notificationService.notify(nextInLine.getUser(), NotificationType.WAITLIST_UPDATE,
                        NotificationChannel.IN_APP,
                        (seats == 1 ? "A seat" : seats + " seats")
                                + " opened up for \"" + event.getName() + "\" and "
                                + (seats == 1 ? "it is" : "they are") + " held for you. Pay "
                                + "within " + paymentHoldMinutes + " minutes to confirm "
                                + (seats == 1 ? "it" : "them") + ", or "
                                + (seats == 1 ? "it passes" : "they pass")
                                + " to the next person waiting.");
            }
            registrationRepository.save(nextInLine);

            event.setSeatsTaken(event.getSeatsTaken() + seats);
            anyPromoted = true;

            log.info("Promoted registration {} ({} seat(s)) from waitlist for event {} to {}",
                    nextInLine.getId(), seats, event.getId(), nextInLine.getStatus());

            if (!event.hasAvailableSeats()) {
                break;
            }
        }

        if (anyPromoted) {
            eventRepository.save(event);
        }
    }

    // =================================================================
    // Attendance
    //
    // Booking a seat and turning up are different facts. Only the first
    // was ever recorded, which is why "how many people registered" could
    // be answered and "how many came" could not - and why no report in
    // this system could say anything about attendance.
    // =================================================================

    @Override
    @Transactional
    public void confirmPaidRegistration(Registration registration) {
        // No seat accounting here: the seat was already counted when the
        // hold was created. Confirming does not take a second one, and
        // adding one would double-count every paid booking on the
        // platform.
        registrationRepository.save(registration);
    }

    @Override
    @Transactional
    public int releaseExpiredPaymentHolds() {
        // Discovery pass: UNLOCKED on purpose. These are candidates, not
        // decisions. Locking them here would take the registration locks
        // before the event locks, which is the opposite of the order
        // cancelRegistration uses - and two transactions taking the same
        // two locks in opposite orders is the definition of a deadlock.
        List<Registration> candidates =
                registrationRepository.findExpiredHolds(LocalDateTime.now());
        if (candidates.isEmpty()) {
            return 0;
        }

        int released = 0;
        for (Registration candidate : candidates) {
            // EVENT FIRST, then the registration. Same order as every
            // other path that touches both.
            Event event = eventRepository.findByIdForUpdate(candidate.getEvent().getId())
                    .orElse(null);
            if (event == null) {
                continue;
            }

            Registration hold = registrationRepository.findByIdForUpdate(candidate.getId())
                    .orElse(null);

            // Re-check under the lock. Between the unlocked discovery
            // read and this point the attendee may have paid, or
            // cancelled, or an administrator may have cancelled for
            // them. Acting on what the candidate said instead of what
            // the locked row says would cancel a booking somebody has
            // just paid for.
            if (hold == null
                    || hold.getStatus() != RegistrationStatus.PENDING_PAYMENT
                    || hold.getPaymentDueAt() == null
                    || hold.getPaymentDueAt().isAfter(LocalDateTime.now())) {
                continue;
            }

            hold.setStatus(RegistrationStatus.CANCELLED);
            hold.setCancelledAt(LocalDateTime.now());
            hold.setPaymentDueAt(null);
            registrationRepository.save(hold);

            int heldSeats = hold.getSeats();
            event.setSeatsTaken(Math.max(0, event.getSeatsTaken() - heldSeats));
            eventRepository.save(event);

            // Told, rather than left to discover it. Somebody who walked
            // away from a checkout still needs to know the seat is gone
            // before they turn up at the door expecting it.
            notificationService.notify(hold.getUser(), NotificationType.EVENT_CHANGE,
                    NotificationChannel.IN_APP,
                    "The " + (heldSeats == 1 ? "seat" : heldSeats + " seats")
                            + " held for you on \"" + event.getName() + "\" "
                            + (heldSeats == 1 ? "was" : "were")
                            + " not paid for in time and " + (heldSeats == 1 ? "has" : "have")
                            + " been released.");

            auditLogService.log(hold.getUser(), "RELEASE_PAYMENT_HOLD", "Registration",
                    hold.getId(),
                    "Unpaid hold on event " + event.getId() + " expired and the seat was released");

            // After the seat is back, not before - otherwise the person
            // promoted would be taking a seat that is still occupied.
            promoteNextWaitlisted(event);
            released++;
        }

        log.info("Released {} expired payment hold(s)", released);
        return released;
    }

    @Override
    @Transactional
    public void extendPaymentHold(Registration registration, LocalDateTime newDeadline) {
        if (registration.getStatus() != RegistrationStatus.PENDING_PAYMENT) {
            // Extending a hold on a booking that is not holding anything
            // would write a deadline the sweep then acts on, cancelling a
            // confirmed booking for being unpaid.
            throw new InvalidRequestException(
                    "Only a held seat has a payment deadline to extend (this one is "
                            + registration.getStatus() + ").");
        }
        registration.setPaymentDueAt(newDeadline);
        registrationRepository.save(registration);
    }

    @Override
    @Transactional
    public RegistrationResponse checkIn(Long registrationId, ActingUser actor) {
        return checkIn(registrationId, null, actor);
    }

    @Override
    @Transactional
    public RegistrationResponse checkIn(Long registrationId, Integer seatsArrived, ActingUser actor) {
        Registration registration = loadOrThrow(registrationId);
        Event event = registration.getEvent();
        accessPolicy.requireManager(event, actor,
                "You are not authorized to check attendees in for this event.");

        if (registration.getStatus() != RegistrationStatus.CONFIRMED) {
            // A waitlisted place has no seat and a cancelled one gave its
            // seat back. Either way there is nobody to admit, and letting
            // it through would put attendance above the seats that exist.
            throw new InvalidRequestException(
                    "Only a confirmed booking can be checked in (this one is "
                            + registration.getStatus() + ").");
        }
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "This event has been cancelled, so nobody can be checked in for it.");
        }
        if (registration.isCheckedIn()) {
            // Not an error worth failing over - pressing the button twice
            // is the most likely thing to happen at a busy door - but the
            // first time stands, because that is when they arrived.
            return RegistrationResponse.from(registration);
        }

        // How many of the party actually walked in. Nothing said means all
        // of them, which is both the common case and the only safe default
        // for the one-argument call sites that predate multi-seat booking.
        int booked = registration.getSeats();
        int arrived = seatsArrived == null ? booked : seatsArrived;
        if (arrived < 1) {
            // Zero is not a check-in, it is an absence, and absence is
            // recorded by leaving the booking un-checked-in. Writing a
            // check-in row that means "nobody came" would make
            // checked_in_at lie about what it records.
            throw new InvalidRequestException(
                    "Check in at least one person, or leave the booking as a no-show.");
        }
        if (arrived > booked) {
            throw new InvalidRequestException(
                    "This booking is for " + booked + " seat" + (booked == 1 ? "" : "s")
                            + ", so " + arrived + " cannot be checked in against it.");
        }

        User staff = loadActor(actor);
        registration.setCheckedInAt(LocalDateTime.now());
        registration.setCheckedInBy(staff);
        registration.setSeatsCheckedIn(arrived);
        Registration saved = registrationRepository.save(registration);

        auditLogService.log(staff, "CHECK_IN", "Registration", registrationId,
                "Checked in " + arrived + " of " + booked + " for "
                        + registration.getUser().getFullName()
                        + " at \"" + event.getName() + "\"");

        log.info("Registration {} checked in for event {} by user {}",
                registrationId, event.getId(), actor.id());
        return RegistrationResponse.from(saved);
    }

    @Override
    @Transactional
    public RegistrationResponse undoCheckIn(Long registrationId, ActingUser actor) {
        Registration registration = loadOrThrow(registrationId);
        accessPolicy.requireManager(registration.getEvent(), actor,
                "You are not authorized to check attendees in for this event.");

        if (!registration.isCheckedIn()) {
            throw new InvalidRequestException("This attendee has not been checked in.");
        }

        // Undoing exists because check-in happens on a phone at a door and
        // the wrong name gets tapped. Leaving an organizer no way back
        // would make the attendance figures worse, not better - they would
        // simply stop using it.
        registration.setCheckedInAt(null);
        registration.setCheckedInBy(null);
        // Cleared together with the timestamp, because the CHECK on the
        // table says the two are either both set or both null - and
        // because a head count left behind by an undone check-in would go
        // on being counted by every attendance report.
        registration.setSeatsCheckedIn(null);
        Registration saved = registrationRepository.save(registration);

        auditLogService.log(loadActor(actor), "UNDO_CHECK_IN", "Registration", registrationId,
                "Check-in reversed for " + registration.getUser().getFullName());

        return RegistrationResponse.from(saved);
    }

    // =================================================================
    // Reads
    // =================================================================

    @Override
    @Transactional(readOnly = true)
    public List<RegistrationResponse> getRegistrationsForEvent(Long eventId, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        requireEventManager(event, actor);
        return registrationRepository.findByEventId(eventId).stream()
                .map(RegistrationResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegistrationResponse> getRegistrationsForUser(ActingUser actor) {
        return registrationRepository.findByUserIdOrderByRegisteredAtDesc(actor.id()).stream()
                .map(RegistrationResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public RegistrationResponse getRegistrationById(Long registrationId, ActingUser actor) {
        Registration registration = loadOrThrow(registrationId);
        boolean owner = actor.is(registration.getUser().getId());
        if (!owner && !actor.isAdmin()) {
            requireEventManager(registration.getEvent(), actor);
        }
        return RegistrationResponse.from(registration);
    }

    @Override
    @Transactional
    public Registration requireOwnRegistrationForUpdate(Long registrationId, ActingUser actor) {
        Registration registration = registrationRepository.findByIdForUpdate(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));

        // Identical rule to the unlocked version, deliberately repeated
        // rather than delegated: delegating would do a second, unlocked
        // read of the row we have just locked.
        if (!actor.is(registration.getUser().getId())) {
            throw new AccessDeniedException("This registration does not belong to you.");
        }
        return registration;
    }

    @Override
    @Transactional(readOnly = true)
    public Registration requireOwnRegistration(Long registrationId, ActingUser actor) {
        Registration registration = loadOrThrow(registrationId);
        if (!actor.is(registration.getUser().getId())) {
            // Deliberately owner-only, and deliberately NOT extended to the
            // organizer: an organizer has no business paying with, or
            // looking at, an attendee's ticket and payment record.
            throw new AccessDeniedException("This registration does not belong to you.");
        }
        return registration;
    }

    /**
     * The acting user as an entity, for the columns and audit rows that
     * record a person rather than an id. Never trusts a caller-supplied
     * id - ActingUser comes from the authenticated principal.
     */
    private User loadActor(ActingUser actor) {
        return userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Acting user not found with id " + actor.id()));
    }

    private Registration loadOrThrow(Long registrationId) {
        return registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Registration not found with id " + registrationId));
    }

    private void requireEventManager(Event event, ActingUser actor) {
        accessPolicy.requireManager(event, actor,
                "You are not authorized to view registrations for this event.");
    }
}
