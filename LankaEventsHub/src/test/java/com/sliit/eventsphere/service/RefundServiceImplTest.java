package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.RefundResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefundServiceImplTest {

    /** Reads better than a bare boolean at every call site. */
    private static final boolean STAFF = true;
    private static final boolean ATTENDEE = false;

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private NotificationService notificationService;
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(org.mockito.Mockito.mock(
            com.sliit.eventsphere.repository.RegistrationRepository.class));

    /**
     * The policy: an attendee who cancels their own booking forfeits
     * what they paid. There is no other setting.
     */
    private RefundServiceImpl refundService;

    private User organizer;
    private User attendee;
    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser adminActor;
    private Event cancelledEvent;

    @BeforeEach
    void setUp() {
        // Built by hand rather than with @InjectMocks, because the policy
        // is the thing under test in half of these cases and a mock of it
        // would just be the assertion restated.
        refundService = new RefundServiceImpl(paymentRepository, refundRepository,
                notificationService, accessPolicy, new RefundPolicy());

        organizer = new User("Kasun", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        attendee = new User("Nadeesha", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(2L);

        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = new ActingUser(77L, UserRole.ORGANIZER);
        adminActor = new ActingUser(99L, UserRole.ADMIN);

        cancelledEvent = new Event();
        cancelledEvent.setId(100L);
        cancelledEvent.setName("Photography Masterclass");
        cancelledEvent.setOrganizer(organizer);
        cancelledEvent.setStatus(EventStatus.CANCELLED);

        lenient().when(refundRepository.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(900L);
            return r;
        });
    }

    // =================================================================
    // Creation
    // =================================================================

    @Test
    void createRefunds_oneForEachCompletedPaidRegistration() {
        Registration r1 = registration(10L);
        Registration r2 = registration(11L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1, r2);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r1, new BigDecimal("3000.00"), PaymentStatus.COMPLETED)));
        when(paymentRepository.findByRegistrationId(11L))
                .thenReturn(Optional.of(payment(2L, r2, new BigDecimal("2500.00"), PaymentStatus.COMPLETED)));

        assertEquals(2, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
        verify(refundRepository, times(2)).save(any(Refund.class));
    }

    @Test
    void createRefunds_refundsExactlyWhatWasPaid() {
        Registration r1 = registration(10L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r1, new BigDecimal("3825.00"), PaymentStatus.COMPLETED)));

        refundService.createRefundsForCancelledEvent(cancelledEvent, affected);

        verify(refundRepository).save(argThat(
                refund -> refund.getAmount().compareTo(new BigDecimal("3825.00")) == 0
                        && refund.getStatus() == RefundStatus.PENDING));
    }

    @Test
    void createRefunds_skipsRegistrationsThatNeverPaid() {
        Registration r1 = registration(10L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        when(paymentRepository.findByRegistrationId(10L)).thenReturn(Optional.empty());

        assertEquals(0, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
        verify(refundRepository, never()).save(any());
    }

    @Test
    void createRefunds_skipsFreeRegistrationsWithNothingToGiveBack() {
        Registration r1 = registration(10L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r1, BigDecimal.ZERO, PaymentStatus.COMPLETED)));

        assertEquals(0, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
    }

    @Test
    void createRefunds_skipsPaymentsThatNeverCompleted() {
        Registration r1 = registration(10L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r1, new BigDecimal("100.00"), PaymentStatus.FAILED)));

        assertEquals(0, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
    }

    @Test
    void createRefunds_isIdempotentSoASecondCancellationCannotDoubleRefund() {
        Registration r1 = registration(10L);
        Payment payment = payment(1L, r1, new BigDecimal("3000.00"), PaymentStatus.COMPLETED);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        when(paymentRepository.findByRegistrationId(10L)).thenReturn(Optional.of(payment));
        when(refundRepository.existsByPaymentId(1L)).thenReturn(true);

        assertEquals(0, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
        verify(refundRepository, never()).save(any());
    }

    @Test
    void createRefunds_stillRefundsAfterTheRegistrationsHaveBeenCancelled() {
        // This is the regression that motivated the payload. The listener
        // runs after EventServiceImpl has already flipped every affected
        // registration to CANCELLED; the refund must still be created,
        // because the entry remembers that the seat was CONFIRMED when the
        // organizer pulled the event.
        Registration r1 = registration(10L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        r1.setStatus(RegistrationStatus.CANCELLED);   // as the service leaves it

        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r1, new BigDecimal("3000.00"), PaymentStatus.COMPLETED)));

        assertEquals(1, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
    }

    @Test
    void createRefunds_skipsWaitlistedPlacesBecauseTheyWereNeverCharged() {
        Registration waitlisted = registration(12L);
        waitlisted.setStatus(RegistrationStatus.WAITLISTED);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(waitlisted);

        assertEquals(0, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
        verify(paymentRepository, never()).findByRegistrationId(any());
        verify(refundRepository, never()).save(any());
    }

    @Test
    void createRefunds_runTwiceCreatesTheRefundOnlyOnce() {
        // Cancelling an already-cancelled event should be refused upstream,
        // but if the listener ever fires twice the second pass must be a
        // no-op rather than a second payout.
        Registration r1 = registration(10L);
        List<EventCancelledEvent.AffectedRegistration> affected = affected(r1);
        Payment payment = payment(1L, r1, new BigDecimal("3000.00"), PaymentStatus.COMPLETED);
        when(paymentRepository.findByRegistrationId(10L)).thenReturn(Optional.of(payment));
        when(refundRepository.existsByPaymentId(1L)).thenReturn(false, true);

        assertEquals(1, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
        assertEquals(0, refundService.createRefundsForCancelledEvent(cancelledEvent, affected));
        verify(refundRepository, times(1)).save(any(Refund.class));
    }

    // =================================================================
    // Status transitions and authorization
    // =================================================================

    @Test
    void advance_movesPendingToProcessedThenCompleted() {
        Refund refund = pendingRefund();
        when(refundRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(refund));

        refundService.advanceRefundStatus(900L, adminActor);
        assertEquals(RefundStatus.PROCESSED, refund.getStatus());
        assertNotNull(refund.getProcessedAt());

        refundService.advanceRefundStatus(900L, adminActor);
        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        verify(notificationService).notify(eq(attendee),
                eq(NotificationType.REFUND_PROCESSED), any(), any());
    }

    @Test
    void advance_refusesToGoPastCompleted() {
        Refund refund = pendingRefund();
        refund.setStatus(RefundStatus.COMPLETED);
        when(refundRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(refund));

        assertThrows(InvalidRequestException.class,
                () -> refundService.advanceRefundStatus(900L, adminActor));
    }

    @Test
    void advance_allowsTheOwningOrganizerButNotAnyOtherOrganizer() {
        Refund refund = pendingRefund();
        when(refundRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(refund));

        assertThrows(AccessDeniedException.class,
                () -> refundService.advanceRefundStatus(900L, otherOrganizerActor));
        assertDoesNotThrow(() -> refundService.advanceRefundStatus(900L, organizerActor));
    }

    @Test
    void advance_readsTheRefundUnderARowLock() {
        // Advancing is read status -> decide -> write status on one row.
        // With an unlocked read, two administrators pressing "Advance" at
        // the same moment both saw PROCESSED, both wrote COMPLETED, and the
        // attendee got two "your refund is complete" notifications for one
        // refund. The switch was always right; it just could not see the
        // other transaction.
        Refund refund = pendingRefund();
        when(refundRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(refund));

        refundService.advanceRefundStatus(900L, adminActor);

        verify(refundRepository).findByIdForUpdate(900L);
        verify(refundRepository, never()).findById(anyLong());
    }

    @Test
    void getAllRefunds_isAdminOnly() {
        when(refundRepository.findAllByOrderByRequestedAtDesc()).thenReturn(List.of(pendingRefund()));

        assertThrows(AccessDeniedException.class, () -> refundService.getAllRefunds(organizerActor));

        List<RefundResponse> rows = refundService.getAllRefunds(adminActor);
        assertEquals(1, rows.size());
        assertEquals("Photography Masterclass", rows.get(0).getEventName());
    }

    // =================================================================
    // Single-registration refunds, cancelled by staff
    //
    // An Administrator cancelling one booking has taken the place away
    // from somebody who still wanted it - the same thing that happens
    // when an event is called off - so the amount rules must be
    // identical to the event-wide path. Each case here has a sibling
    // above it.
    // =================================================================

    @Test
    void cancelledRegistration_refundsAConfirmedPaidSeat() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, new BigDecimal("2500.00"),
                        PaymentStatus.COMPLETED)));

        assertTrue(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, STAFF));

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(captor.capture());
        assertEquals(new BigDecimal("2500.00"), captor.getValue().getAmount(),
                "a refund must return exactly what was paid, never more");
        assertEquals(RefundStatus.PENDING, captor.getValue().getStatus(),
                "a refund starts PENDING and is walked through by staff");
    }

    @Test
    void cancelledRegistration_refundsNothingForAWaitlistPlace() {
        Registration r = registration(10L);

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.WAITLISTED, STAFF));

        verify(refundRepository, never()).save(any(Refund.class));
        verify(paymentRepository, never()).findByRegistrationId(any());
    }

    @Test
    void cancelledRegistration_refundsNothingWhenNothingWasPaid() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L)).thenReturn(Optional.empty());

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, STAFF));
        verify(refundRepository, never()).save(any(Refund.class));
    }

    @Test
    void cancelledRegistration_refundsNothingForAPaymentThatNeverCompleted() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, new BigDecimal("2500.00"),
                        PaymentStatus.FAILED)));

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, STAFF));
        verify(refundRepository, never()).save(any(Refund.class));
    }

    @Test
    void cancelledRegistration_refundsNothingForAFreeEvent() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, BigDecimal.ZERO, PaymentStatus.COMPLETED)));

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, STAFF));
        verify(refundRepository, never()).save(any(Refund.class));
    }

    @Test
    void cancelledRegistration_neverCreatesASecondRefundForOnePayment() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, new BigDecimal("2500.00"),
                        PaymentStatus.COMPLETED)));
        when(refundRepository.existsByPaymentId(1L)).thenReturn(true);

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, STAFF));
        verify(refundRepository, never()).save(any(Refund.class));
    }

    // =================================================================
    // Who cancelled decides whether money goes back
    //
    // This is the rule the requirements are explicit about, and it is
    // the one most easily lost in a refactor: a refund is what the
    // platform owes when the platform takes the seat away. These cases
    // exist so that "everybody who cancels gets their money back" cannot
    // quietly come back.
    // =================================================================

    @Test
    void attendeeCancellingTheirOwnPaidBookingIsNotRefundedByDefault() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, new BigDecimal("2500.00"),
                        PaymentStatus.COMPLETED)));

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, ATTENDEE));

        verify(refundRepository, never()).save(any(Refund.class));
    }

    /**
     * The pair that proves the two paths genuinely differ. Identical
     * registration, identical completed payment, identical amount - the
     * only difference is who pressed cancel, and the outcomes are
     * opposite. If someone collapses these back into one path, exactly
     * one of these two assertions fails.
     */
    @Test
    void theSamePaidBookingRefundsForStaffAndNotForTheAttendee() {
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, new BigDecimal("2500.00"),
                        PaymentStatus.COMPLETED)));

        assertFalse(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, ATTENDEE),
                "an attendee who gives the place up themselves is not refunded");

        assertTrue(refundService.createRefundForCancelledRegistration(
                r, RegistrationStatus.CONFIRMED, STAFF),
                "an Administrator taking the place away does refund it");
    }

    @Test
    void anEventBeingCancelledStillRefundsRegardlessOfThePolicy() {
        // The policy governs self-cancellation only. An organizer calling
        // off the event must always refund, under either setting.
        Registration r = registration(10L);
        when(paymentRepository.findByRegistrationId(10L))
                .thenReturn(Optional.of(payment(1L, r, new BigDecimal("2500.00"),
                        PaymentStatus.COMPLETED)));

        assertEquals(1, refundService.createRefundsForCancelledEvent(cancelledEvent, affected(r)));
    }

    @Test
    void theRuleIsFixedRatherThanConfigurable() {
        // This was a property once. It should not have been: the
        // requirement states the rule flatly, and a property meant an
        // environment variable could quietly run a system that broke it.
        // If somebody makes it configurable again, this fails.
        assertFalse(refundService.isRefundOnAttendeeCancellation());
        assertFalse(new RefundPolicy().refundsOnAttendeeCancellation());
    }

    @Test
    void theRuleIsTheSameObjectTheBookingPagesRead() {
        // The warning an attendee sees before pressing cancel and the
        // decision made afterwards must come from one place, or they
        // eventually disagree about somebody's money.
        RefundPolicy policy = new RefundPolicy();

        assertFalse(policy.refundIsOwed(ATTENDEE));
        assertTrue(policy.refundIsOwed(STAFF));
    }

    // =================================================================
    // helpers
    // =================================================================

    /**
     * The payload the domain event now carries. Each entry records the
     * status the registration held AT THE MOMENT the event was cancelled,
     * which is the whole point: by the time a listener runs, every one of
     * those registrations has already been set to CANCELLED, so re-querying
     * for CONFIRMED rows (as this service used to do) finds nothing and
     * silently refunds no one.
     */
    private List<EventCancelledEvent.AffectedRegistration> affected(Registration... registrations) {
        return java.util.Arrays.stream(registrations)
                .map(EventCancelledEvent.AffectedRegistration::of)
                .toList();
    }

    private Registration registration(Long id) {
        Registration registration = new Registration();
        registration.setId(id);
        registration.setEvent(cancelledEvent);
        registration.setUser(attendee);
        registration.setStatus(RegistrationStatus.CONFIRMED);
        return registration;
    }

    private Payment payment(Long id, Registration registration, BigDecimal finalAmount, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(id);
        payment.setRegistration(registration);
        payment.setOriginalAmount(finalAmount);
        payment.setDiscountAmount(BigDecimal.ZERO);
        payment.setFinalAmount(finalAmount);
        payment.setStatus(status);
        return payment;
    }

    private Refund pendingRefund() {
        Refund refund = new Refund();
        refund.setId(900L);
        refund.setPayment(payment(1L, registration(10L), new BigDecimal("3000.00"), PaymentStatus.COMPLETED));
        refund.setStatus(RefundStatus.PENDING);
        refund.setAmount(new BigDecimal("3000.00"));
        return refund;
    }
}
