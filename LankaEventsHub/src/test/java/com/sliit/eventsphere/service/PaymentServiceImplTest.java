package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.DiscountOptionResponse;
import com.sliit.eventsphere.dto.PaymentRequest;
import com.sliit.eventsphere.dto.PaymentResponse;
import com.sliit.eventsphere.dto.PriceQuoteRequest;
import com.sliit.eventsphere.dto.PriceQuoteResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.DiscountRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.EventAccessPolicy;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.service.discount.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Payment is where an IDOR would hurt most, so most of these tests are
 * about who is allowed to do what, not about arithmetic.
 *
 * The discount strategies are the real ones rather than mocks - the point
 * of several of these cases is that a wrong promo code or an unverified
 * student is refused, and stubbing the strategies would test nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceImplTest {

    /**
     * A gateway whose answer the test chooses. Deliberately a real
     * implementation rather than a Mockito mock: the point of the
     * PaymentGateway interface is that a decline is an ordinary answer,
     * and that reads more clearly as a class than as a stubbed call.
     */
    private static final class SwitchablePaymentGateway
            implements com.sliit.eventsphere.payment.PaymentGateway {
        private com.sliit.eventsphere.payment.PaymentResult next =
                com.sliit.eventsphere.payment.PaymentResult.approved();

        void willDecline(String reason) {
            next = com.sliit.eventsphere.payment.PaymentResult.declined(reason);
        }

        /** The card the last charge was given, so a test can assert on it. */
        com.sliit.eventsphere.payment.CardDetails lastCard;

        @Override
        public com.sliit.eventsphere.payment.PaymentResult charge(
                java.math.BigDecimal amount, com.sliit.eventsphere.payment.CardDetails card) {
            this.lastCard = card;
            return next;
        }
    }

    @Mock private RegistrationService registrationService;
    @Mock private PaymentRepository paymentRepository;
    @Mock private DiscountRepository discountRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;
    /** Only reached by the door-list and audit paths. */
    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;

    private DiscountCalculationService discountCalculationService;
    private PaymentServiceImpl paymentService;

    private User attendee;
    private ActingUser attendeeActor;
    /** The event's own organizer - the person who stands at the door. */
    private User organizer;
    private ActingUser organizerActor;
    private SwitchablePaymentGateway paymentGateway;
    private Event paidEvent;
    /**
     * A held seat on a paid event - the only state from which a paid
     * booking can be paid for. It was CONFIRMED before payment was
     * required to come first, which was the bug.
     */
    private Registration heldRegistration;

    @BeforeEach
    void setUp() {
        discountCalculationService = new DiscountCalculationService(
                List.of(new EarlyBirdDiscountStrategy(), new StudentDiscountStrategy(),
                        new PromoCodeDiscountStrategy(), new GroupDiscountStrategy()),
                discountRepository);

        // A real gateway stand-in, swapped per test: this is what makes
        // the declined-card path testable at all.
        paymentGateway = new SwitchablePaymentGateway();
        paymentService = new PaymentServiceImpl(registrationService, paymentRepository,
                discountRepository, discountCalculationService, notificationService, auditLogService,
                paymentGateway, new TicketCodeGenerator(),
                new EventAccessPolicy(mock(RegistrationRepository.class)),
                eventRepository, userRepository);

        attendee = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(2L);
        attendeeActor = ActingUser.of(attendee);
        organizer = new User("Kasun Perera", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        organizerActor = ActingUser.of(organizer);

        paidEvent = new Event();
        paidEvent.setId(100L);
        paidEvent.setName("AI Conference");
        paidEvent.setOrganizer(organizer);
        paidEvent.setStatus(EventStatus.PUBLISHED);
        paidEvent.setFee(new BigDecimal("1000.00"));

        heldRegistration = new Registration();
        heldRegistration.setId(55L);
        heldRegistration.setEvent(paidEvent);
        heldRegistration.setUser(attendee);
        heldRegistration.setStatus(RegistrationStatus.PENDING_PAYMENT);
        heldRegistration.setPaymentDueAt(LocalDateTime.now().plusMinutes(30));

        lenient().when(registrationService.requireOwnRegistration(55L, attendeeActor))
                .thenReturn(heldRegistration);
        // pay() takes the LOCKED lookup, so that is the one it must find.
        lenient().when(registrationService.requireOwnRegistrationForUpdate(55L, attendeeActor))
                .thenReturn(heldRegistration);
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(500L);
            return p;
        });
    }

    // =================================================================
    // Ownership
    // =================================================================

    @Test
    void pay_isRefusedForSomebodyElsesRegistration() {
        ActingUser intruder = new ActingUser(3L, UserRole.ATTENDEE);
        when(registrationService.requireOwnRegistrationForUpdate(55L, intruder))
                .thenThrow(new AccessDeniedException("This registration does not belong to you."));

        assertThrows(AccessDeniedException.class, () -> paymentService.pay(request(null), intruder));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void getByRegistrationId_goesThroughTheOwnershipCheck() {
        when(paymentRepository.findByRegistrationId(55L)).thenReturn(Optional.of(new Payment()));
        ActingUser intruder = new ActingUser(3L, UserRole.ATTENDEE);
        when(registrationService.requireOwnRegistration(55L, intruder))
                .thenThrow(new AccessDeniedException("nope"));

        assertThrows(AccessDeniedException.class,
                () -> paymentService.getByRegistrationId(55L, intruder));
    }

    // =================================================================
    // Pricing is registration-owned
    //
    // There is exactly one pricing flow, and it starts from a registration
    // the caller owns: registration -> authenticated owner -> event ->
    // discount. Nobody can price an arbitrary event id, and nobody can
    // apply another event's discount to their own booking.
    // =================================================================

    @Test
    void quote_isRefusedForSomebodyElsesRegistration() {
        ActingUser intruder = new ActingUser(3L, UserRole.ATTENDEE);
        when(registrationService.requireOwnRegistration(55L, intruder))
                .thenThrow(new AccessDeniedException("This registration does not belong to you."));

        PriceQuoteRequest quoteRequest = new PriceQuoteRequest();
        quoteRequest.setDiscountId(7L);

        assertThrows(AccessDeniedException.class,
                () -> paymentService.quoteForRegistration(55L, quoteRequest, intruder));
        // The price of someone else's booking must not leak either.
        verify(discountRepository, never()).findById(any());
    }

    @Test
    void quote_refusesADiscountThatBelongsToADifferentEvent() {
        Event otherEvent = new Event();
        otherEvent.setId(999L);
        Discount foreign = discount(7L, DiscountType.PROMO_CODE, new BigDecimal("10"), otherEvent);
        when(discountRepository.findById(7L)).thenReturn(Optional.of(foreign));

        PriceQuoteRequest quoteRequest = new PriceQuoteRequest();
        quoteRequest.setDiscountId(7L);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.quoteForRegistration(55L, quoteRequest, attendeeActor));
        assertTrue(ex.getMessage().contains("does not belong to this event"));
    }

    @Test
    void quote_withNoDiscountIsSimplyTheEventFee() {
        PriceQuoteResponse quote =
                paymentService.quoteForRegistration(55L, new PriceQuoteRequest(), attendeeActor);

        assertEquals(0, quote.getFinalAmount().compareTo(new BigDecimal("1000.00")));
        assertEquals(0, quote.getDiscountAmount().compareTo(BigDecimal.ZERO));
        assertEquals("None", quote.getDiscountApplied());
    }

    // =================================================================
    // The checkout page's discount list
    // =================================================================

    @Test
    void getSelectableDiscounts_isRefusedForSomebodyElsesRegistration() {
        ActingUser intruder = new ActingUser(3L, UserRole.ATTENDEE);
        when(registrationService.requireOwnRegistration(55L, intruder))
                .thenThrow(new AccessDeniedException("nope"));

        assertThrows(AccessDeniedException.class,
                () -> paymentService.getSelectableDiscounts(55L, intruder));
    }

    @Test
    void getSelectableDiscounts_offersOnlyTheOnesUsableRightNow() {
        Discount live = discount(1L, DiscountType.PROMO_CODE, new BigDecimal("10"), paidEvent);
        Discount expired = discount(2L, DiscountType.EARLY_BIRD, new BigDecimal("10"), paidEvent);
        expired.setValidTo(LocalDateTime.now().minusDays(1));
        when(discountRepository.findByEventId(100L)).thenReturn(List.of(live, expired));

        List<DiscountOptionResponse> options =
                paymentService.getSelectableDiscounts(55L, attendeeActor);

        // Offering a discount and then refusing it at checkout is worse than
        // not offering it.
        assertEquals(1, options.size());
        assertEquals(1L, options.get(0).getId());
    }

    @Test
    void getSelectableDiscounts_tellsThePageWhichExtraFieldEachOptionNeeds() {
        // The view used to re-derive this from the discount type with a
        // chain of conditionals; it is decided once, here.
        when(discountRepository.findByEventId(100L)).thenReturn(
                List.of(discount(1L, DiscountType.PROMO_CODE, new BigDecimal("10"), paidEvent),
                        discount(2L, DiscountType.GROUP, new BigDecimal("10"), paidEvent),
                        discount(3L, DiscountType.EARLY_BIRD, new BigDecimal("10"), paidEvent)));

        List<DiscountOptionResponse> options =
                paymentService.getSelectableDiscounts(55L, attendeeActor);

        assertTrue(options.get(0).isRequiresCode());
        assertFalse(options.get(0).isRequiresGroupSize());
        assertTrue(options.get(1).isRequiresGroupSize());
        assertFalse(options.get(2).isRequiresCode());
        assertFalse(options.get(2).isRequiresGroupSize());
        // Every option carries a human label, so the page never renders a
        // bare enum constant at a paying customer.
        assertTrue(options.stream().noneMatch(o -> o.getLabel() == null || o.getLabel().isBlank()));
    }

    // =================================================================
    // Registration state
    // =================================================================

    @Test
    void pay_rejectsAWaitlistedRegistration() {
        heldRegistration.setStatus(RegistrationStatus.WAITLISTED);
        heldRegistration.setPaymentDueAt(null);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));
        assertTrue(ex.getMessage().contains("WAITLISTED"), ex.getMessage());
    }

    // =================================================================
    // Payment comes before confirmation
    //
    // The ordering the requirements are explicit about. Before this, a
    // registration was CONFIRMED the moment it was created and payment
    // was checked against that - so an event could be sold out to people
    // who never paid, and these are the cases that stop it coming back.
    // =================================================================

    @Test
    void pay_confirmsTheHeldSeat() {
        paymentService.pay(request(null), attendeeActor);

        assertEquals(RegistrationStatus.CONFIRMED, heldRegistration.getStatus());
        verify(registrationService).confirmPaidRegistration(heldRegistration);
    }

    @Test
    void pay_clearsTheHoldDeadlineOnceTheBookingIsConfirmed() {
        // A confirmed booking with a deadline still on it would be swept
        // up and cancelled by the hold-release job.
        paymentService.pay(request(null), attendeeActor);

        assertNull(heldRegistration.getPaymentDueAt());
    }

    @Test
    void pay_refusesAnAlreadyConfirmedPaidBooking() {
        heldRegistration.setStatus(RegistrationStatus.CONFIRMED);
        heldRegistration.setPaymentDueAt(null);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));
        assertTrue(ex.getMessage().contains("already confirmed"), ex.getMessage());
    }

    @Test
    void pay_refusesOnceTheHoldHasExpired() {
        // The hold is the promise that the seat is still there. Once it
        // has lapsed the seat may already belong to somebody else, so
        // taking the money would be selling something we do not have.
        heldRegistration.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));

        assertTrue(ex.getMessage().contains("expired"), ex.getMessage());
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(registrationService, never()).confirmPaidRegistration(any());
    }

    @Test
    void pay_rejectsACancelledRegistration() {
        heldRegistration.setStatus(RegistrationStatus.CANCELLED);

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));
    }

    @Test
    void pay_rejectsAPaymentForACancelledEvent() {
        paidEvent.setStatus(EventStatus.CANCELLED);

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));
    }

    @Test
    void pay_rejectsADuplicatePaymentWith409() {
        Payment alreadyPaid = new Payment();
        alreadyPaid.setId(500L);
        alreadyPaid.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByRegistrationId(55L)).thenReturn(Optional.of(alreadyPaid));

        assertThrows(ConflictException.class, () -> paymentService.pay(request(null), attendeeActor));
    }

    @Test
    void pay_rejectsASecondAttemptWhileOneIsStillInProgress() {
        Payment inFlight = new Payment();
        inFlight.setId(500L);
        inFlight.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByRegistrationId(55L)).thenReturn(Optional.of(inFlight));

        assertThrows(ConflictException.class, () -> paymentService.pay(request(null), attendeeActor));
    }

    @Test
    void pay_letsTheAttendeeTryAgainAfterACardWasDeclined() {
        // A declined card must not lock somebody out of a seat they are
        // still holding. payments.registration_id is UNIQUE, so the retry
        // reuses the failed row rather than inserting beside it - which
        // is why this would otherwise be impossible rather than merely
        // awkward.
        Payment declined = new Payment();
        declined.setId(500L);
        declined.setStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByRegistrationId(55L)).thenReturn(Optional.of(declined));

        PaymentResponse response = paymentService.pay(request(null), attendeeActor);

        assertEquals(PaymentStatus.COMPLETED, response.getStatus());
        assertEquals(RegistrationStatus.CONFIRMED, heldRegistration.getStatus());
    }

    @Test
    void pay_retryingReusesTheFailedRowRatherThanCreatingASecond() {
        Payment declined = new Payment();
        declined.setId(500L);
        declined.setStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByRegistrationId(55L)).thenReturn(Optional.of(declined));

        paymentService.pay(request(null), attendeeActor);

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertEquals(500L, saved.getValue().getId(),
                "a second row would violate the UNIQUE constraint on registration_id");
    }

    @Test
    void pay_rejectsAnEventIdThatDoesNotMatchTheRegistration() {
        PaymentRequest request = request(null);
        request.setEventId(999L);

        assertThrows(InvalidRequestException.class, () -> paymentService.pay(request, attendeeActor));
    }

    // =================================================================
    // Discounts
    // =================================================================

    @Test
    void pay_refusesADiscountBelongingToAnotherEvent() {
        Event otherEvent = new Event();
        otherEvent.setId(777L);
        Discount foreign = discount(9L, DiscountType.EARLY_BIRD, new BigDecimal("90.00"), otherEvent);
        when(discountRepository.findById(9L)).thenReturn(Optional.of(foreign));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(9L), attendeeActor));
        assertTrue(ex.getMessage().contains("does not belong to this event"));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void pay_refusesAnExpiredDiscount() {
        Discount expired = discount(1L, DiscountType.EARLY_BIRD, new BigDecimal("15.00"), paidEvent);
        expired.setValidFrom(LocalDateTime.now().minusDays(30));
        expired.setValidTo(LocalDateTime.now().minusDays(1));
        when(discountRepository.findById(1L)).thenReturn(Optional.of(expired));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(1L), attendeeActor));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    void pay_refusesAnInactiveDiscount() {
        Discount inactive = discount(1L, DiscountType.EARLY_BIRD, new BigDecimal("15.00"), paidEvent);
        inactive.setActive(false);
        when(discountRepository.findById(1L)).thenReturn(Optional.of(inactive));

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(1L), attendeeActor));
    }

    // =================================================================
    // A party pays for its seats
    // =================================================================

    @Test
    void pay_chargesTheFeeOncePerSeat() {
        heldRegistration.setSeats(4);

        PaymentResponse response = paymentService.pay(request(null), attendeeActor);

        // Four seats at 1000 each. Charging 1000 for a party of four is
        // the platform giving away three tickets on every group booking.
        assertEquals(new BigDecimal("4000.00"), response.getOriginalAmount());
        assertEquals(new BigDecimal("4000.00"), response.getFinalAmount());
    }

    @Test
    void pay_chargesASingleSeatExactlyTheFee() {
        // The party arithmetic must not change what a single booking
        // costs - that is the case every existing attendee is in.
        assertEquals(new BigDecimal("1000.00"),
                paymentService.pay(request(null), attendeeActor).getFinalAmount());
    }

    @Test
    void quote_showsTheSameTotalTheChargeWillTake() {
        heldRegistration.setSeats(3);

        PriceQuoteResponse quote =
                paymentService.quoteForRegistration(55L, new PriceQuoteRequest(), attendeeActor);

        // A quote that shows one price and a charge that takes another is
        // the single worst bug this module could have, so both read the
        // seat count from the same place.
        assertEquals(new BigDecimal("3000.00"), quote.getFinalAmount());
    }

    @Test
    void pay_takesAPercentageDiscountOffThePartyTotal() {
        heldRegistration.setSeats(4);
        Discount earlyBird = discount(1L, DiscountType.EARLY_BIRD, new BigDecimal("15.00"), paidEvent);
        when(discountRepository.findById(1L)).thenReturn(Optional.of(earlyBird));

        PaymentResponse response = paymentService.pay(request(1L), attendeeActor);

        // 15% of 4000, not 15% of 1000. A percentage that scales with the
        // booking is what "15% off" means to the person reading it.
        assertEquals(new BigDecimal("600.00"), response.getDiscountAmount());
        assertEquals(new BigDecimal("3400.00"), response.getFinalAmount());
    }

    @Test
    void pay_appliesAnEarlyBirdDiscount() {
        Discount earlyBird = discount(1L, DiscountType.EARLY_BIRD, new BigDecimal("15.00"), paidEvent);
        when(discountRepository.findById(1L)).thenReturn(Optional.of(earlyBird));

        PaymentResponse response = paymentService.pay(request(1L), attendeeActor);

        assertEquals(new BigDecimal("150.00"), response.getDiscountAmount());
        assertEquals(new BigDecimal("850.00"), response.getFinalAmount());
        assertEquals(PaymentStatus.COMPLETED, response.getStatus());
        assertNotNull(response.getTicketCode());
    }

    @Test
    void pay_refusesAWrongPromoCodeInsteadOfSilentlyChargingFullPrice() {
        Discount promo = discount(2L, DiscountType.PROMO_CODE, new BigDecimal("10.00"), paidEvent);
        promo.setCode("AI2027");
        when(discountRepository.findById(2L)).thenReturn(Optional.of(promo));

        PaymentRequest request = request(2L);
        request.setEnteredCode("WRONG");

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request, attendeeActor));
        assertTrue(ex.getMessage().contains("not valid"));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void pay_acceptsTheCorrectPromoCodeCaseInsensitively() {
        Discount promo = discount(2L, DiscountType.PROMO_CODE, new BigDecimal("10.00"), paidEvent);
        promo.setCode("AI2027");
        when(discountRepository.findById(2L)).thenReturn(Optional.of(promo));

        PaymentRequest request = request(2L);
        request.setEnteredCode("  ai2027 ");

        assertEquals(new BigDecimal("900.00"), paymentService.pay(request, attendeeActor).getFinalAmount());
    }

    @Test
    void pay_refusesTheStudentDiscountForAnUnverifiedAccount() {
        attendee.setVerifiedStudent(false);
        Discount student = discount(3L, DiscountType.STUDENT, new BigDecimal("20.00"), paidEvent);
        when(discountRepository.findById(3L)).thenReturn(Optional.of(student));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(3L), attendeeActor));
        assertTrue(ex.getMessage().contains("verified as students"));
    }

    @Test
    void pay_appliesTheStudentDiscountForAVerifiedAccount() {
        attendee.setVerifiedStudent(true);
        Discount student = discount(3L, DiscountType.STUDENT, new BigDecimal("20.00"), paidEvent);
        when(discountRepository.findById(3L)).thenReturn(Optional.of(student));

        assertEquals(new BigDecimal("800.00"),
                paymentService.pay(request(3L), attendeeActor).getFinalAmount());
    }

    @Test
    void pay_refusesAGroupDiscountBelowTheMinimumGroupSize() {
        Discount group = discount(4L, DiscountType.GROUP, new BigDecimal("25.00"), paidEvent);
        when(discountRepository.findById(4L)).thenReturn(Optional.of(group));

        // Eligibility is judged on the registration's actual seats, not on
        // anything the client submits - see PaymentServiceImpl.pay().
        heldRegistration.setSeats(2);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(4L), attendeeActor));
        assertTrue(ex.getMessage().contains("at least 3"));
    }

    @Test
    void pay_appliesAGroupDiscountAtTheMinimumGroupSize() {
        Discount group = discount(4L, DiscountType.GROUP, new BigDecimal("25.00"), paidEvent);
        when(discountRepository.findById(4L)).thenReturn(Optional.of(group));

        // Same rule as above: a 3-seat booking is what makes the group
        // rate apply, not a groupSize the client used to be able to type.
        heldRegistration.setSeats(3);

        assertEquals(new BigDecimal("750.00"),
                paymentService.pay(request(4L), attendeeActor).getFinalAmount());
    }

    @Test
    void pay_neverProducesANegativeAmountEvenAtAHundredPercentOff() {
        Discount everything = discount(5L, DiscountType.EARLY_BIRD, new BigDecimal("100.00"), paidEvent);
        when(discountRepository.findById(5L)).thenReturn(Optional.of(everything));

        PaymentResponse response = paymentService.pay(request(5L), attendeeActor);

        assertEquals(0, response.getFinalAmount().compareTo(BigDecimal.ZERO));
        assertTrue(response.getDiscountAmount().compareTo(response.getOriginalAmount()) <= 0);
    }

    // =================================================================
    // The card is checked before the gateway is troubled
    // =================================================================

    @Test
    void pay_refusesAMistypedCardWithoutRecordingAFailedAttempt() {
        PaymentRequest request = request(null);
        request.setCardNumber("4111 1111 1111 1112");   // one digit wrong

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request, attendeeActor));

        assertFalse(ex.getMessage().toLowerCase().contains("declin"));
        // Nothing was written. A typo is not an attempt: recording it as
        // a FAILED payment would tell the attendee their card was
        // refused, and would put a failure in the record that no bank
        // ever produced.
        verify(paymentRepository, never()).save(any());
        assertNull(paymentGateway.lastCard);
    }

    @Test
    void pay_refusesAnExpiredCardWithoutCallingTheGateway() {
        PaymentRequest request = request(null);
        java.time.YearMonth lastMonth = java.time.YearMonth.now().minusMonths(1);
        request.setExpiryMonth(lastMonth.getMonthValue());
        request.setExpiryYear(lastMonth.getYear());

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request, attendeeActor));
        assertNull(paymentGateway.lastCard);
    }

    @Test
    void pay_passesTheTypedCardToTheGatewayAndNotSomeOtherOne() {
        // The whole point of collecting one. A checkout that validated a
        // card and then charged a different one would approve every
        // payment - which is the behaviour this module was rewritten to
        // remove, and which a test asserting on the DEFAULT test card
        // could not tell apart from the correct code.
        PaymentRequest request = request(null);
        request.setCardNumber("5555 5555 5555 4444");
        request.setCardHolder("Ravindu Bandara");

        paymentService.pay(request, attendeeActor);

        assertNotNull(paymentGateway.lastCard);
        assertEquals("5555555555554444", paymentGateway.lastCard.digits());
        assertEquals("Ravindu Bandara", paymentGateway.lastCard.holder());
    }

    @Test
    void pay_recordsThatItWasACardPayment() {
        assertEquals(PaymentMethod.CARD,
                paymentService.pay(request(null), attendeeActor).getMethod());
    }

    // =================================================================
    // Paying on arrival
    // =================================================================

    private PaymentRequest onArrival() {
        PaymentRequest request = new PaymentRequest();
        request.setRegistrationId(55L);
        request.setMethod("ON_ARRIVAL");
        return request;
    }

    @Test
    void onArrival_needsNoCardAtAll() {
        // There is nothing to collect: the money arrives in person. A
        // checkout that demanded a card here would make the method
        // pointless.
        PaymentResponse response = paymentService.pay(onArrival(), attendeeActor);

        assertEquals(PaymentMethod.ON_ARRIVAL, response.getMethod());
        assertNull(paymentGateway.lastCard);
    }

    @Test
    void onArrival_doesNotConfirmTheBooking() {
        paidEvent.setStartDatetime(LocalDateTime.now().plusDays(3));

        PaymentResponse response = paymentService.pay(onArrival(), attendeeActor);

        // The single most important assertion in this section. Promising
        // to pay is not paying, and confirming here would be the
        // unpaid-confirmation bug arriving through a side door.
        assertEquals(PaymentStatus.PENDING, response.getStatus());
        assertEquals(RegistrationStatus.PENDING_PAYMENT, heldRegistration.getStatus());
        assertNull(response.getPaidAt());
    }

    @Test
    void onArrival_holdsTheSeatUntilTheEventInsteadOfForThirtyMinutes() {
        LocalDateTime start = LocalDateTime.now().plusDays(3);
        paidEvent.setStartDatetime(start);

        paymentService.pay(onArrival(), attendeeActor);

        // Without this the release sweep takes the seat back that evening,
        // for want of a payment nobody expected yet.
        verify(registrationService).extendPaymentHold(heldRegistration, start);
    }

    @Test
    void onArrival_issuesACodeSoThereIsSomethingToPresent() {
        // A code is an identifier, not a receipt. The attendee needs one
        // to show and the door needs one to look up, and the ticket page
        // says in as many words that it has not been paid for.
        assertNotNull(paymentService.pay(onArrival(), attendeeActor).getTicketCode());
    }

    @Test
    void onArrival_stillChargesTheFullPartyAmount() {
        heldRegistration.setSeats(3);

        assertEquals(new BigDecimal("3000.00"),
                paymentService.pay(onArrival(), attendeeActor).getFinalAmount());
    }

    @Test
    void pay_refusesAPaymentMethodThisServerDoesNotDo() {
        // Built from a request carrying a PERFECTLY GOOD card, so the
        // unknown method is the only thing left that could refuse it. A
        // card-less request would throw either way, and the test would
        // pass whether or not the method was checked at all.
        PaymentRequest request = request(null);
        request.setMethod("BITCOIN");

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request, attendeeActor));

        // Refused rather than quietly treated as a card. A client that
        // asked for something this server does not do should be told so,
        // not charged.
        assertTrue(ex.getMessage().contains("card payment or paying on arrival"));
        assertNull(paymentGateway.lastCard);
    }

    @Test
    void pay_treatsAMissingMethodAsACardPayment() {
        // Every caller written before there was a choice meant CARD, and
        // the column defaults to it.
        PaymentRequest request = request(null);
        request.setMethod(null);

        assertEquals(PaymentMethod.CARD,
                paymentService.pay(request, attendeeActor).getMethod());
    }

    // =================================================================
    // Settling at the door
    // =================================================================

    private Payment reservedAtTheDoor() {
        Payment payment = new Payment();
        payment.setId(500L);
        payment.setRegistration(heldRegistration);
        payment.setOriginalAmount(new BigDecimal("1000.00"));
        payment.setDiscountAmount(BigDecimal.ZERO);
        payment.setFinalAmount(new BigDecimal("1000.00"));
        payment.setStatus(PaymentStatus.PENDING);
        payment.setMethod(PaymentMethod.ON_ARRIVAL);
        payment.setTicketCode("TICKET-100-55-ABCD");
        return payment;
    }

    @Test
    void settle_completesThePaymentAndConfirmsTheBooking() {
        Payment reserved = reservedAtTheDoor();
        when(paymentRepository.findByRegistrationIdForUpdate(55L)).thenReturn(Optional.of(reserved));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));

        PaymentResponse response = paymentService.settleOnArrival(55L, "cash", organizerActor);

        assertEquals(PaymentStatus.COMPLETED, response.getStatus());
        assertNotNull(response.getPaidAt());
        assertEquals(RegistrationStatus.CONFIRMED, heldRegistration.getStatus());
        // The hold is over - the seat is theirs now, not held.
        assertNull(heldRegistration.getPaymentDueAt());
    }

    @Test
    void settle_isRefusedForSomebodyWhoDoesNotRunTheEvent() {
        Payment reserved = reservedAtTheDoor();
        when(paymentRepository.findByRegistrationIdForUpdate(55L)).thenReturn(Optional.of(reserved));

        assertThrows(AccessDeniedException.class,
                () -> paymentService.settleOnArrival(55L, "cash", attendeeActor));

        // An attendee marking their own booking paid is the whole point
        // of the method defeated.
        assertEquals(PaymentStatus.PENDING, reserved.getStatus());
        assertEquals(RegistrationStatus.PENDING_PAYMENT, heldRegistration.getStatus());
    }

    @Test
    void settle_refusesToTurnADeclinedCardIntoASale() {
        Payment declined = reservedAtTheDoor();
        declined.setMethod(PaymentMethod.CARD);
        declined.setStatus(PaymentStatus.FAILED);
        when(paymentRepository.findByRegistrationIdForUpdate(55L)).thenReturn(Optional.of(declined));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.settleOnArrival(55L, "cash", organizerActor));

        // Otherwise the gateway's verdict is advisory, which is the
        // opposite of what it is.
        assertTrue(ex.getMessage().contains("card"));
        assertEquals(PaymentStatus.FAILED, declined.getStatus());
    }

    @Test
    void settle_twiceKeepsTheFirstTime() {
        Payment reserved = reservedAtTheDoor();
        when(paymentRepository.findByRegistrationIdForUpdate(55L)).thenReturn(Optional.of(reserved));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));

        LocalDateTime first = paymentService.settleOnArrival(55L, "cash", organizerActor).getPaidAt();
        LocalDateTime second = paymentService.settleOnArrival(55L, "cash", organizerActor).getPaidAt();

        // Pressing the button twice at a busy door is the likeliest thing
        // to happen to this method, and the first time is when the money
        // actually arrived.
        assertEquals(first, second);
    }

    @Test
    void settle_refusesOnACancelledEvent() {
        paidEvent.setStatus(EventStatus.CANCELLED);
        Payment reserved = reservedAtTheDoor();
        when(paymentRepository.findByRegistrationIdForUpdate(55L)).thenReturn(Optional.of(reserved));

        assertThrows(InvalidRequestException.class,
                () -> paymentService.settleOnArrival(55L, "cash", organizerActor));
        assertEquals(PaymentStatus.PENDING, reserved.getStatus());
    }

    @Test
    void doorList_isRefusedToAnAttendee() {
        when(eventRepository.findById(100L)).thenReturn(Optional.of(paidEvent));

        // It says who owes what, which is not an attendee's business.
        assertThrows(AccessDeniedException.class,
                () -> paymentService.getOwedAtTheDoor(100L, attendeeActor));
    }

    @Test
    void doorList_showsTheOrganizerWhoStillOwes() {
        when(eventRepository.findById(100L)).thenReturn(Optional.of(paidEvent));
        when(paymentRepository.findOwedAtTheDoor(100L)).thenReturn(List.of(reservedAtTheDoor()));

        List<PaymentResponse> owed = paymentService.getOwedAtTheDoor(100L, organizerActor);

        assertEquals(1, owed.size());
        assertTrue(owed.get(0).isOwedAtTheDoor());
        assertEquals("Nadeesha Silva", owed.get(0).getAttendeeName());
    }

    // =================================================================
    // What the gateway decides
    //
    // These are only possible because charging goes through the
    // PaymentGateway interface. With the status hard-coded to COMPLETED
    // the declined path was unreachable, and the requirement documents
    // describe decline handling.
    // =================================================================

    @Test
    void pay_recordsADeclineAsAFailedPaymentRatherThanThrowingItAway() {
        paymentGateway.willDecline("insufficient funds");

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertEquals(PaymentStatus.FAILED, saved.getValue().getStatus());
        assertEquals(new BigDecimal("1000.00"), saved.getValue().getFinalAmount());
    }

    @Test
    void pay_keepsTheDeclinedRowWhenItThrowsToReportTheDecline() throws Exception {
        // The one thing a mocked repository CANNOT show, asserted the
        // only way a unit test can: on the annotation itself.
        //
        // pay() is transactional and the exception it throws to report a
        // decline is unchecked, so by default Spring rolls the FAILED row
        // straight back out again. That is what used to happen, and the
        // test above passed throughout - it verified that save had been
        // CALLED, which it had, while no decline in the running
        // application ever left a trace. Removing noRollbackFor silently
        // restores that bug, so it is worth a test that says so out loud.
        org.springframework.transaction.annotation.Transactional annotation =
                PaymentServiceImpl.class
                        .getMethod("pay", PaymentRequest.class, ActingUser.class)
                        .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertNotNull(annotation, "pay() must be transactional");
        assertTrue(List.of(annotation.noRollbackFor()).contains(InvalidRequestException.class),
                "a declined payment must survive the exception that reports it");
    }

    @Test
    void pay_leavesTheSeatHeldWhenTheCardIsDeclined() {
        // The seat is not given up on a decline - the hold runs to its own
        // deadline, so the attendee can find another card.
        paymentGateway.willDecline("declined by issuer");

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));

        assertEquals(RegistrationStatus.PENDING_PAYMENT, heldRegistration.getStatus());
        verify(registrationService, never()).confirmPaidRegistration(any());
    }

    @Test
    void pay_issuesNoTicketForADeclinedPayment() {
        paymentGateway.willDecline("declined by issuer");

        assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        assertNull(saved.getValue().getTicketCode(),
                "a ticket code on a failed payment would be admissible at a door");
        assertNull(saved.getValue().getPaidAt());
    }

    @Test
    void pay_tellsTheAttendeeWhyTheCardWasDeclined() {
        paymentGateway.willDecline("insufficient funds");

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));

        assertTrue(ex.getMessage().contains("insufficient funds"), ex.getMessage());
        assertTrue(ex.getMessage().contains("still held"), ex.getMessage());
    }

    @Test
    void pay_locksTheRegistrationRatherThanReadingItUnlocked() {
        // Two simultaneous payments for one held seat must not both get
        // past the "no completed payment exists" check. The lock is what
        // stops that; this asserts the locked lookup is the one used.
        paymentService.pay(request(null), attendeeActor);

        verify(registrationService).requireOwnRegistrationForUpdate(55L, attendeeActor);
    }

    // =================================================================
    // Free events
    // =================================================================

    @Test
    void pay_refusesAFreeEventBecauseItIsAlreadyTicketed() {
        // Free events are confirmed AND ticketed at registration (see
        // RegistrationServiceImpl), so they never reach this method. That
        // removed the one exception this gate used to carry, and the rule
        // is now a single line: only a held seat can be paid for.
        paidEvent.setFee(BigDecimal.ZERO);
        heldRegistration.setStatus(RegistrationStatus.CONFIRMED);
        heldRegistration.setPaymentDueAt(null);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> paymentService.pay(request(null), attendeeActor));
        assertTrue(ex.getMessage().contains("already confirmed"), ex.getMessage());
    }

    @Test
    void pay_ticketCodeCarriesNoPersonalData() {
        PaymentResponse response = paymentService.pay(request(null), attendeeActor);

        String code = response.getTicketCode();
        assertTrue(code.startsWith("TICKET-"));
        assertFalse(code.toLowerCase().contains("nadeesha"));
        assertFalse(code.toLowerCase().contains("attendee@test.com"));
    }

    // =================================================================
    // helpers
    // =================================================================

    /**
     * A request carrying a card that passes validation, because every
     * test in this file is about what happens AFTER the card is
     * accepted - the amount, the discount, the decline, the ticket. The
     * card checks have their own file.
     *
     * The gateway here is the switchable stand-in, so this number's real
     * meaning to MockPaymentGateway is irrelevant: what matters is that
     * it reaches the gateway at all.
     */
    private PaymentRequest request(Long discountId) {
        PaymentRequest request = new PaymentRequest();
        request.setRegistrationId(55L);
        request.setDiscountId(discountId);
        request.setCardNumber("4111 1111 1111 1111");
        request.setCardHolder("Nadeesha Silva");
        request.setExpiryMonth(12);
        request.setExpiryYear(java.time.YearMonth.now().getYear() + 2);
        request.setSecurityCode("123");
        return request;
    }

    private Discount discount(Long id, DiscountType type, BigDecimal percentOff, Event event) {
        Discount discount = new Discount();
        discount.setId(id);
        discount.setEvent(event);
        discount.setType(type);
        discount.setPercentOff(percentOff);
        discount.setActive(true);
        discount.setValidFrom(LocalDateTime.now().minusDays(1));
        discount.setValidTo(LocalDateTime.now().plusDays(30));
        return discount;
    }
}
