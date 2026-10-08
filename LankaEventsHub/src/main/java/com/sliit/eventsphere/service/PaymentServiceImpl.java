package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.DiscountOptionResponse;
import com.sliit.eventsphere.dto.PaymentRequest;
import com.sliit.eventsphere.dto.PaymentResponse;
import com.sliit.eventsphere.dto.PriceQuoteRequest;
import com.sliit.eventsphere.dto.PriceQuoteResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.payment.CardDetails;
import com.sliit.eventsphere.payment.PaymentGateway;
import com.sliit.eventsphere.payment.PaymentResult;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.DiscountRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import com.sliit.eventsphere.service.discount.DiscountCalculationService;
import com.sliit.eventsphere.service.discount.DiscountContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Function 3: Payment & Ticketing.
 *
 * Everything in here runs against a registration the ACTING USER owns.
 * Ownership is resolved by RegistrationService.requireOwnRegistration,
 * not by trusting the registrationId in the request body - knowing
 * somebody else's registration id must not let you pay with it, read
 * their payment, or open their ticket.
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final RegistrationService registrationService;
    private final PaymentRepository paymentRepository;
    private final DiscountRepository discountRepository;
    private final DiscountCalculationService discountCalculationService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    /** Whatever actually takes the money - see the payment package. */
    private final PaymentGateway paymentGateway;
    private final TicketCodeGenerator ticketCodeGenerator;
    /**
     * Who may act on an event. Injected rather than re-implemented here,
     * so "the owning organizer or an administrator" means exactly the
     * same thing at a door as it does everywhere else.
     */
    private final EventAccessPolicy accessPolicy;
    /** Only to resolve the event a door list belongs to, and authorize it. */
    private final EventRepository eventRepository;
    /** Only to name the staff member on an audit row. */
    private final UserRepository userRepository;

    public PaymentServiceImpl(RegistrationService registrationService,
                               PaymentRepository paymentRepository,
                               DiscountRepository discountRepository,
                               DiscountCalculationService discountCalculationService,
                               NotificationService notificationService,
                               AuditLogService auditLogService,
                               PaymentGateway paymentGateway,
                               TicketCodeGenerator ticketCodeGenerator,
                               EventAccessPolicy accessPolicy,
                               EventRepository eventRepository,
                               UserRepository userRepository) {
        this.registrationService = registrationService;
        this.paymentRepository = paymentRepository;
        this.discountRepository = discountRepository;
        this.discountCalculationService = discountCalculationService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.paymentGateway = paymentGateway;
        this.ticketCodeGenerator = ticketCodeGenerator;
        this.accessPolicy = accessPolicy;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscountOptionResponse> getSelectableDiscounts(Long registrationId, ActingUser actor) {
        Registration registration = registrationService.requireOwnRegistration(registrationId, actor);
        LocalDateTime now = LocalDateTime.now();
        // Only offer what could actually be applied - showing an expired
        // discount and then rejecting it is a worse experience than not
        // showing it at all.
        return discountRepository.findByEventId(registration.getEvent().getId()).stream()
                .filter(d -> d.isCurrentlyValid(now))
                .map(DiscountOptionResponse::from)
                .toList();
    }

    /**
     * {@code noRollbackFor} is load-bearing, not tidiness.
     *
     * A declined card is recorded as a FAILED payment and then reported
     * by throwing, so the attendee sees why. The exception is unchecked,
     * so by default Spring rolls that write straight back out again -
     * which is exactly what used to happen, invisibly, for as long as
     * this method has existed. The test verified that {@code save} had
     * been CALLED, which it had, and passed while every decline in the
     * running application left no trace at all.
     *
     * A separate REQUIRES_NEW transaction was tried first and deadlocks:
     * this method holds a PESSIMISTIC_WRITE lock on the registration row,
     * and inserting a payment needs a shared lock on that same row for
     * the foreign key - so the inner transaction waits for the outer one,
     * which is waiting for it. The live checkout hung for fifty seconds
     * and then failed on a lock timeout.
     *
     * What makes this safe is the ordering below: every other throw of
     * InvalidRequestException in this method happens BEFORE anything is
     * written or any entity is mutated. The card is validated at the top
     * for that reason - moving it back down would put a throw after the
     * payment entity has been populated, and on a retry that entity is a
     * managed row, so its half-set fields would then be committed.
     */
    @Override
    @Transactional(noRollbackFor = InvalidRequestException.class)
    public PaymentResponse pay(PaymentRequest request, ActingUser actor) {
        // LOCKED read: everything below reads this registration, decides,
        // and then writes. Two payment requests for the same held seat
        // must not both get past the "no completed payment exists" check.
        Registration registration =
                registrationService.requireOwnRegistrationForUpdate(request.getRegistrationId(), actor);
        Event event = registration.getEvent();

        // The seat is secured first and paid for second - but paying is
        // what turns the reservation into a booking, so this method is
        // reached with a HELD seat, never a confirmed one. Confirming
        // first and taking the money afterwards (which is what this check
        // used to require) is exactly the ordering the requirements rule
        // out: it confirms bookings for people who never pay.
        // ONE rule, with no exception to remember:
        //
        //     free event  -> confirmed and ticketed at registration,
        //                    and never arrives here at all
        //     paid event  -> held, then paid, then confirmed and ticketed
        //
        // This used to carry a free-event exception, because free events
        // got their ticket by being walked through the checkout. They are
        // ticketed at registration now (see RegistrationServiceImpl), so
        // the exception is gone and the gate says exactly one thing.
        if (registration.getStatus() != RegistrationStatus.PENDING_PAYMENT) {
            throw new InvalidRequestException(
                    registration.getStatus() == RegistrationStatus.CONFIRMED
                            ? "This booking is already confirmed and paid for."
                            : "There is no held seat to pay for (current status: "
                                    + registration.getStatus() + ").");
        }
        // The hold is the promise that the seat is still there. Once it
        // has lapsed the seat may already belong to somebody else, so
        // taking the money would be selling something we do not have.
        if (registration.getPaymentDueAt() != null
                && registration.getPaymentDueAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRequestException(
                    "The hold on this seat has expired. Register again to get a new one "
                            + "if the event still has space.");
        }
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new InvalidRequestException("This event has been cancelled, so it cannot be paid for");
        }
        // If a caller supplies an eventId as well, it has to agree with the
        // registration - otherwise the discount and the seat could come
        // from two different events.
        if (request.getEventId() != null && !request.getEventId().equals(event.getId())) {
            throw new InvalidRequestException("That registration does not belong to the specified event");
        }
        // Service-level guard; payments.registration_id is also UNIQUE in
        // the schema, so two racing requests cannot both create a payment.
        //
        // A FAILED payment is the exception, and it has to be: a card
        // that was declined must not lock somebody out of the seat they
        // are still holding. Because the UNIQUE constraint allows only
        // one payment row per registration, the retry REUSES the failed
        // row rather than inserting beside it - the attempt is
        // overwritten by the one that worked, which is what the row is
        // for.
        PaymentMethod method = resolveMethod(request.getMethod());

        // The card is checked HERE, before anything is written or any
        // entity is touched - see the note on this method's transaction.
        // A card that was mistyped is also not a decline: the money was
        // never asked for, so there is nothing to record as a failed
        // attempt and nothing to tell the attendee about their bank.
        CardDetails card = method == PaymentMethod.CARD ? request.toCard() : null;
        if (card != null) {
            String cardProblem = card.validationError();
            if (cardProblem != null) {
                throw new InvalidRequestException(cardProblem);
            }
        }

        Optional<Payment> existingPayment = paymentRepository.findByRegistrationId(registration.getId());
        if (existingPayment.isPresent()
                && existingPayment.get().getStatus() != PaymentStatus.FAILED) {
            throw new ConflictException(
                    existingPayment.get().getStatus() == PaymentStatus.COMPLETED
                            ? "This registration has already been paid for"
                            : "A payment for this registration is already in progress");
        }
        Payment payment = existingPayment.orElseGet(Payment::new);

        BigDecimal originalAmount = amountDueFor(registration);
        BigDecimal discountAmount = BigDecimal.ZERO;
        Discount discount = null;

        if (request.getDiscountId() != null) {
            // resolveDiscountForEvent refuses a discount that belongs to a
            // different event, which is what stops an attendee applying
            // another event's 90%-off code to this one.
            discount = discountCalculationService.resolveDiscountForEvent(
                    request.getDiscountId(), event.getId());
            // GROUP eligibility is judged on the seats this registration
            // actually holds, never on a number typed into the checkout
            // form. request.getGroupSize() used to be passed straight
            // through here, which meant a single-seat booking could claim
            // any group size the attendee cared to type - "the browser's
            // discount amount" rule applies just as much to an eligibility
            // input as to a final price. registration.getSeats() is the
            // one figure that is both authoritative (it is what
            // amountDueFor actually charges for) and already known
            // server-side, so there is nothing left for the client to
            // supply here at all.
            DiscountContext context = DiscountContext.of(
                    request.getEnteredCode(), registration.getSeats(),
                    registration.getUser().isVerifiedStudent());
            discountAmount = discountCalculationService.calculateDiscount(originalAmount, discount, context);
            if (discountAmount.compareTo(BigDecimal.ZERO) == 0) {
                discount = null; // nothing was actually applied - don't record one
            }
        }

        BigDecimal finalAmount = originalAmount.subtract(discountAmount);
        if (finalAmount.compareTo(BigDecimal.ZERO) < 0) {
            // Unreachable: calculateDiscount clamps the discount to the
            // original amount. Kept as an explicit invariant so a future
            // change to the discount rules can never produce a negative
            // charge silently.
            throw new InvalidRequestException("Calculated payment amount is invalid");
        }

        payment.setRegistration(registration);
        payment.setDiscount(discount);
        payment.setOriginalAmount(originalAmount);
        payment.setDiscountAmount(discountAmount);
        payment.setFinalAmount(finalAmount);
        payment.setMethod(method);

        // Settling at the door is a promise, not a payment, so it stops
        // here: a PENDING payment, a seat held until the event rather
        // than for the usual few minutes, and a booking that is still
        // not confirmed. Confirming it would be the unpaid-confirmation
        // bug arriving through a side door.
        if (method == PaymentMethod.ON_ARRIVAL) {
            return reserveForPaymentOnArrival(payment, registration, event, finalAmount);
        }

        // The gateway decides; this method records what it decided. That
        // separation is what makes the declined path real code rather
        // than a comment about what a real provider would do.
        PaymentResult result = paymentGateway.charge(finalAmount, card);

        if (!result.success()) {
            // A decline is an answer, not an error. The attempt is
            // recorded as FAILED - which is what makes it retryable, per
            // the reuse rule above - the seat stays held until its own
            // deadline, and the attendee is told why.
            //
            // This survives the throw below because of noRollbackFor on
            // this method - see the note there. Without it the row is
            // written and immediately rolled back, and the decline leaves
            // no trace.
            payment.setStatus(PaymentStatus.FAILED);
            payment.setPaidAt(null);
            // A code on a failed payment would be a scannable string in
            // front of somebody who has not paid - and because the column
            // is UNIQUE, it would also stop the retry issuing a fresh one.
            payment.setTicketCode(null);
            paymentRepository.save(payment);

            log.info("Payment declined for registration {}: {}",
                    registration.getId(), result.reason());
            throw new InvalidRequestException(
                    "The payment was declined"
                            + (result.reason() == null ? "" : " (" + result.reason() + ")")
                            + ". Your seat is still held - you can try again.");
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setPaidAt(LocalDateTime.now());
        payment.setTicketCode(ticketCodeGenerator.generate(registration));

        Payment saved = paymentRepository.save(payment);

        // The money is in, so the reservation becomes a booking. Nothing
        // else in the system promotes PENDING_PAYMENT to CONFIRMED, which
        // is what makes "confirmed implies paid" a fact rather than a
        // convention.
        registration.setStatus(RegistrationStatus.CONFIRMED);
        registration.setPaymentDueAt(null);
        registrationService.confirmPaidRegistration(registration);

        log.info("Payment {} completed for registration {} - final amount {}; "
                        + "registration confirmed",
                saved.getId(), registration.getId(), finalAmount);

        User attendee = registration.getUser();
        notificationService.notify(attendee, NotificationType.PAYMENT_CONFIRMATION,
                NotificationChannel.IN_APP,
                "Payment of " + finalAmount + " confirmed for \"" + event.getName()
                        + "\" - your booking is now confirmed.");
        notificationService.notify(attendee, NotificationType.TICKET_ISSUED,
                NotificationChannel.IN_APP,
                "Your e-ticket for \"" + event.getName() + "\" is ready: " + saved.getTicketCode());
        auditLogService.log(attendee, "PAY", "Registration", registration.getId(),
                "Payment " + saved.getId() + " completed for " + finalAmount);

        return PaymentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PriceQuoteResponse quoteForRegistration(Long registrationId, PriceQuoteRequest request,
                                                     ActingUser actor) {
        Registration registration = registrationService.requireOwnRegistration(registrationId, actor);
        BigDecimal originalAmount = amountDueFor(registration);

        if (request.getDiscountId() == null) {
            return new PriceQuoteResponse(originalAmount, BigDecimal.ZERO, originalAmount, "None");
        }

        Discount discount = discountCalculationService.resolveDiscountForEvent(
                request.getDiscountId(), registration.getEvent().getId());
        // Same authority rule as pay(): the preview must agree with what
        // the charge will actually apply, so it uses the same
        // registration.getSeats() rather than the client-supplied group
        // size - see the note in pay().
        DiscountContext context = DiscountContext.of(request.getEnteredCode(), registration.getSeats(),
                registration.getUser().isVerifiedStudent());
        BigDecimal discountAmount =
                discountCalculationService.calculateDiscount(originalAmount, discount, context);

        return new PriceQuoteResponse(originalAmount, discountAmount,
                originalAmount.subtract(discountAmount),
                discountAmount.compareTo(BigDecimal.ZERO) > 0 ? discount.getType().name() : "None");
    }

    /**
     * Generates a unique ticket code bound to this specific registration.
     * It carries only the event id, the registration id and a random
     * suffix - deliberately nothing about the attendee, because this
     * string is rendered as a QR code that anyone standing next to the
     * ticket holder can photograph.
     */
    /**
     * Turns whatever the client sent into a payment method, or refuses.
     *
     * Nothing means CARD, which is what every caller written before there
     * was a choice meant, and what the column defaults to. An unknown
     * value is refused rather than quietly treated as CARD: a client that
     * asked for something this server does not do should be told so, not
     * charged.
     */
    private PaymentMethod resolveMethod(String requested) {
        if (requested == null || requested.isBlank()) {
            return PaymentMethod.CARD;
        }
        try {
            return PaymentMethod.valueOf(requested.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(
                    "Choose either a card payment or paying on arrival.");
        }
    }

    /**
     * Records the undertaking to pay at the door, and extends the hold to
     * cover it.
     *
     * The seat has to stop expiring, because the whole point of this
     * method is that the money arrives at the event rather than in the
     * next thirty minutes - the sweep would otherwise release the seat
     * that evening for want of a payment nobody expected yet. The new
     * deadline is the event's own start, so an unclaimed promise still
     * releases its seat rather than holding it forever.
     *
     * The booking stays PENDING_PAYMENT. It is confirmed by
     * {@link #settleOnArrival}, when somebody has actually taken the
     * money - which keeps "confirmed implies paid" true of every booking
     * on the platform rather than true of most of them.
     */
    private PaymentResponse reserveForPaymentOnArrival(Payment payment, Registration registration,
                                                        Event event, BigDecimal amount) {
        payment.setStatus(PaymentStatus.PENDING);
        payment.setPaidAt(null);
        // A ticket code now, even though nothing has been paid. The
        // attendee has to have something to present at the door, and the
        // door needs something to look up. The ticket page says in as
        // many words that it is not paid for - a code is an identifier,
        // not a receipt.
        if (payment.getTicketCode() == null) {
            payment.setTicketCode(ticketCodeGenerator.generate(registration));
        }
        Payment saved = paymentRepository.save(payment);

        // The event's own start, so an unclaimed promise still releases
        // its seat rather than holding it forever. An event with no start
        // time has nothing to expire against, and gets no deadline.
        registrationService.extendPaymentHold(registration, event.getStartDatetime());

        notificationService.notify(registration.getUser(),
                NotificationType.PAYMENT_CONFIRMATION, NotificationChannel.IN_APP,
                "Your seat" + (registration.getSeats() == 1 ? "" : "s") + " for \""
                        + event.getName() + "\" " + (registration.getSeats() == 1 ? "is" : "are")
                        + " reserved. Pay " + amount + " when you arrive and show "
                        + saved.getTicketCode() + " at the door.");
        auditLogService.log(registration.getUser(), "RESERVE_PAY_ON_ARRIVAL", "Registration",
                registration.getId(),
                "Payment " + saved.getId() + " of " + amount + " to be settled on arrival");

        log.info("Registration {} reserved for payment on arrival - {} due at the door",
                registration.getId(), amount);
        return PaymentResponse.from(saved);
    }

    private BigDecimal normalise(BigDecimal amount) {
        return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * What this booking costs before any discount: the event's fee times
     * the number of seats it holds.
     *
     * One method rather than the multiplication written at each call site,
     * because the quote and the charge have to agree exactly - a quote
     * that shows one price and a charge that takes another is the single
     * worst bug this module could have. Both call this.
     *
     * The discount is applied afterwards to the party total, which is what
     * makes a percentage discount scale with the booking and a fixed one
     * come off once. That is the standard reading of both, and doing it
     * the other way round would let a 500-off code applied per seat wipe
     * out a party booking entirely.
     */
    private BigDecimal amountDueFor(Registration registration) {
        BigDecimal fee = normalise(registration.getEvent().getFee());
        return normalise(fee.multiply(BigDecimal.valueOf(registration.getSeats())));
    }

    // =================================================================
    // Settling at the door
    // =================================================================

    @Override
    @Transactional
    public PaymentResponse settleOnArrival(Long registrationId, String amountNote, ActingUser actor) {
        // LOCKED. Two people at a door tapping the same name at the same
        // moment would otherwise both read PENDING and both complete it.
        Payment payment = paymentRepository.findByRegistrationIdForUpdate(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for registration " + registrationId));

        Registration registration = payment.getRegistration();
        Event event = registration.getEvent();
        accessPolicy.requireManager(event, actor,
                "You are not authorized to take payments for this event.");

        if (payment.getMethod() != PaymentMethod.ON_ARRIVAL) {
            // A card payment reached its answer at the checkout. Letting a
            // door turn a decline into a sale would make the gateway's
            // verdict advisory, which is the opposite of what it is.
            throw new InvalidRequestException(
                    "This booking was paid by card, not at the door.");
        }
        if (payment.getStatus() == PaymentStatus.COMPLETED) {
            // Not an error worth failing over - pressing the button twice
            // at a busy door is the likeliest thing to happen to this
            // method - but the first time stands, because that is when
            // the money actually arrived.
            return PaymentResponse.from(payment);
        }
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "This event has been cancelled, so no money should be taken for it.");
        }

        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setPaidAt(LocalDateTime.now());
        if (payment.getTicketCode() == null) {
            payment.setTicketCode(ticketCodeGenerator.generate(registration));
        }
        Payment saved = paymentRepository.save(payment);

        // The money is in, so the reservation becomes a booking - the same
        // final step the card path takes, through the same method, so
        // there is exactly one place where a registration becomes
        // CONFIRMED by being paid for.
        registration.setStatus(RegistrationStatus.CONFIRMED);
        registration.setPaymentDueAt(null);
        registrationService.confirmPaidRegistration(registration);

        User attendee = registration.getUser();
        notificationService.notify(attendee, NotificationType.PAYMENT_CONFIRMATION,
                NotificationChannel.IN_APP,
                "Payment of " + saved.getFinalAmount() + " received at the door for \""
                        + event.getName() + "\" - your booking is now confirmed.");
        auditLogService.log(loadActor(actor), "SETTLE_ON_ARRIVAL", "Registration",
                registration.getId(),
                "Payment " + saved.getId() + " of " + saved.getFinalAmount()
                        + " taken at the door"
                        + (amountNote == null || amountNote.isBlank()
                                ? "" : " (" + amountNote.trim() + ")"));

        log.info("Registration {} settled at the door - payment {} completed",
                registration.getId(), saved.getId());
        return PaymentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentResponse> getOwedAtTheDoor(Long eventId, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found with id " + eventId));
        accessPolicy.requireManager(event, actor,
                "You are not authorized to see who owes money for this event.");

        return paymentRepository.findOwedAtTheDoor(eventId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    /**
     * The staff member an audit row should name. Read here rather than
     * taken from the ActingUser, because an audit trail records a person,
     * and the acting user is only an id and a role.
     */
    private User loadActor(ActingUser actor) {
        return userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Acting user not found with id " + actor.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getByRegistrationId(Long registrationId, ActingUser actor) {
        registrationService.requireOwnRegistration(registrationId, actor);
        Payment payment = paymentRepository.findByRegistrationId(registrationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment found for registration " + registrationId));
        return PaymentResponse.from(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findByRegistrationId(Long registrationId, ActingUser actor) {
        registrationService.requireOwnRegistration(registrationId, actor);
        return paymentRepository.findByRegistrationId(registrationId).map(PaymentResponse::from);
    }
}
