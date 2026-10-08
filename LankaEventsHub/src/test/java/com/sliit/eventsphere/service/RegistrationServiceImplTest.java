package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.InvitationResponse;
import com.sliit.eventsphere.dto.RegistrationAvailability;
import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.events.RegistrationCancelledEvent;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistrationServiceImplTest {

    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;
    @Mock private EventInvitationService eventInvitationService;
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(org.mockito.Mockito.mock(
            com.sliit.eventsphere.repository.RegistrationRepository.class));
    // Cancelling now announces itself so Payment & Ticketing can refund a
    // paid seat. The publisher has to be mocked or every cancellation test
    // dies on a null reference rather than on the behaviour it is testing.
    @Mock private ApplicationEventPublisher eventPublisher;
    // Read-only collaborators, used only by the admin bookings view.
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    // The real policy object, not a mock: it holds one boolean, and a
    // mock of it would only restate whatever the test already assumed.
    // false is the default - self-cancellation forfeits payment.
    @Spy private RefundPolicy refundPolicy = new RefundPolicy();

    /**
     * Built by hand rather than with @InjectMocks: the hold window is a
     * primitive constructor argument, which Mockito cannot supply, and a
     * 30-minute hold is worth stating explicitly in a file full of tests
     * about what happens when it runs out.
     */
    private RegistrationServiceImpl registrationService;

    private static final long HOLD_MINUTES = 30;

    private User attendee;
    private User organizer;
    private User admin;
    private ActingUser attendeeActor;
    private ActingUser organizerActor;
    private ActingUser otherAttendeeActor;
    private ActingUser adminActor;
    private Event publishedEvent;

    @BeforeEach
    void setUp() {
        // The Chain of Responsibility (F2) built the same way SavedReportServiceTest
        // builds its Template Method generators: real validator instances,
        // not mocks, so the actual chain logic (including the null-userId
        // short-circuit for the anonymous-visitor path) is exercised by
        // every test below rather than silently skipped. EventStatusValidator
        // and RegistrationWindowValidator have no dependencies to mock;
        // DuplicateRegistrationValidator and InvitationEligibilityValidator
        // are built on the same registrationRepository/eventInvitationService
        // mocks this file already stubs directly.
        com.sliit.eventsphere.service.registration.EventStatusValidator eventStatusValidator =
                new com.sliit.eventsphere.service.registration.EventStatusValidator();
        com.sliit.eventsphere.service.registration.RegistrationWindowValidator registrationWindowValidator =
                new com.sliit.eventsphere.service.registration.RegistrationWindowValidator();
        com.sliit.eventsphere.service.registration.DuplicateRegistrationValidator duplicateRegistrationValidator =
                new com.sliit.eventsphere.service.registration.DuplicateRegistrationValidator(registrationRepository);
        com.sliit.eventsphere.service.registration.InvitationEligibilityValidator invitationEligibilityValidator =
                new com.sliit.eventsphere.service.registration.InvitationEligibilityValidator(eventInvitationService);

        registrationService = new RegistrationServiceImpl(eventRepository, userRepository,
                registrationRepository, notificationService, auditLogService,
                eventInvitationService, accessPolicy, eventPublisher,
                paymentRepository, refundRepository, refundPolicy,
                new TicketCodeGenerator(),
                eventStatusValidator, registrationWindowValidator,
                duplicateRegistrationValidator, invitationEligibilityValidator,
                HOLD_MINUTES);

        attendee = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(2L);
        organizer = new User("Kasun Perera", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        admin = new User("Admin", "admin@test.com", "hash", UserRole.ADMIN);
        admin.setId(99L);

        attendeeActor = ActingUser.of(attendee);
        organizerActor = ActingUser.of(organizer);
        otherAttendeeActor = new ActingUser(3L, UserRole.ATTENDEE);
        adminActor = new ActingUser(99L, UserRole.ADMIN);

        publishedEvent = new Event();
        publishedEvent.setId(100L);
        publishedEvent.setName("Cloud Workshop");
        publishedEvent.setOrganizer(organizer);
        publishedEvent.setStatus(EventStatus.PUBLISHED);
        publishedEvent.setEventType(EventType.PUBLIC);
        publishedEvent.setCapacity(1);
        publishedEvent.setSeatsTaken(0);
        publishedEvent.setFee(BigDecimal.ZERO);
        publishedEvent.setStartDatetime(LocalDateTime.now().plusDays(10));

        lenient().when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        lenient().when(eventRepository.findById(100L)).thenReturn(Optional.of(publishedEvent));
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(attendee));
        lenient().when(eventInvitationService.isEligibleToRegister(100L, 2L)).thenReturn(true);
        lenient().when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> {
            Registration r = inv.getArgument(0);
            if (r.getId() == null) r.setId(1L);
            return r;
        });
    }

    // =================================================================
    // Register
    // =================================================================

    @Test
    void register_confirmsAFreeEventImmediately() {
        // The fixture event is free (fee 0.00). There is nothing to pay,
        // so there is nothing to wait for.
        RegistrationResponse response = registrationService.register(100L, attendeeActor);

        assertEquals(RegistrationStatus.CONFIRMED, response.getStatus());
        assertEquals(1, publishedEvent.getSeatsTaken());
    }

    // =================================================================
    // Payment comes before confirmation
    //
    // The ordering the requirements are explicit about, and the one this
    // system previously had backwards: a registration was CONFIRMED the
    // moment it was created, so an event could sell out to people who
    // never paid a thing.
    //
    // A held seat is the resolution. It is taken from the pool
    // immediately - so "the seat is secured on click" is still true - but
    // it is not a booking until the money arrives.
    // =================================================================

    @Test
    void register_onAPaidEventHoldsTheSeatRatherThanConfirmingIt() {
        publishedEvent.setFee(new BigDecimal("2500.00"));

        RegistrationResponse response = registrationService.register(100L, attendeeActor);

        assertEquals(RegistrationStatus.PENDING_PAYMENT, response.getStatus());
        assertNotEquals(RegistrationStatus.CONFIRMED, response.getStatus(),
                "a paid booking must not be confirmed before it is paid for");
    }

    @Test
    void register_aHeldSeatIsStillTakenFromThePool() {
        // The other half of the requirement. If a hold did not consume a
        // seat, two people could hold the same one and one of them would
        // pay for a seat that no longer exists.
        publishedEvent.setFee(new BigDecimal("2500.00"));

        registrationService.register(100L, attendeeActor);

        assertEquals(1, publishedEvent.getSeatsTaken());
    }

    @Test
    void register_aHeldSeatIsGivenADeadline() {
        publishedEvent.setFee(new BigDecimal("2500.00"));

        registrationService.register(100L, attendeeActor);

        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(registrationRepository).save(saved.capture());
        assertNotNull(saved.getValue().getPaymentDueAt(),
                "a hold with no deadline would keep the seat for ever");
        assertTrue(saved.getValue().getPaymentDueAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void register_aFullPaidEventStillWaitlistsRatherThanHolding() {
        publishedEvent.setFee(new BigDecimal("2500.00"));
        publishedEvent.setSeatsTaken(1); // capacity is 1

        RegistrationResponse response = registrationService.register(100L, attendeeActor);

        assertEquals(RegistrationStatus.WAITLISTED, response.getStatus());
        assertEquals(1, publishedEvent.getSeatsTaken());
    }

    @Test
    void abandoningAnUnpaidHoldGivesTheSeatBackWithNoCutoff() {
        // The 48-hour cutoff protects a confirmed headcount. Somebody who
        // has not paid is not in that headcount, and blocking them would
        // keep a seat locked up that nobody has bought.
        publishedEvent.setFee(new BigDecimal("2500.00"));
        publishedEvent.setSeatsTaken(1);
        publishedEvent.setStartDatetime(LocalDateTime.now().plusHours(2)); // inside the cutoff
        Registration hold = confirmedRegistration();
        hold.setId(7L);
        hold.setStatus(RegistrationStatus.PENDING_PAYMENT);
        hold.setPaymentDueAt(LocalDateTime.now().plusMinutes(20));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(hold));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        assertDoesNotThrow(() -> registrationService.cancelRegistration(100L, attendeeActor));

        assertEquals(RegistrationStatus.CANCELLED, hold.getStatus());
        assertEquals(0, publishedEvent.getSeatsTaken(), "the held seat goes back to the pool");
        assertNull(hold.getPaymentDueAt());
    }

    @Test
    void releasingAnExpiredHoldCancelsItAndFreesTheSeat() {
        publishedEvent.setSeatsTaken(1);
        Registration hold = confirmedRegistration();
        hold.setId(7L);
        hold.setStatus(RegistrationStatus.PENDING_PAYMENT);
        hold.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));
        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(hold));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        assertEquals(1, registrationService.releaseExpiredPaymentHolds());

        assertEquals(RegistrationStatus.CANCELLED, hold.getStatus());
        assertEquals(0, publishedEvent.getSeatsTaken());
    }

    @Test
    void releasingAnExpiredHoldTellsTheAttendeeTheSeatIsGone() {
        publishedEvent.setSeatsTaken(1);
        Registration hold = confirmedRegistration();
        hold.setId(7L);
        hold.setStatus(RegistrationStatus.PENDING_PAYMENT);
        hold.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));
        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(hold));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        registrationService.releaseExpiredPaymentHolds();

        verify(notificationService).notify(eq(attendee), eq(NotificationType.EVENT_CHANGE),
                eq(NotificationChannel.IN_APP), anyString());
    }

    @Test
    void releasingHolds_leavesAloneAHoldThatWasPaidBetweenDiscoveryAndTheLock() {
        // THE case the re-check under the lock exists for.
        //
        // Discovery is deliberately unlocked, so that the sweep takes the
        // event lock before the registration lock and cannot deadlock
        // against a cancellation. The cost of that is a window: between
        // the unlocked read and the locked one the attendee may have
        // paid. Acting on the stale row would cancel a booking somebody
        // has just been charged for.
        publishedEvent.setSeatsTaken(1);
        Registration stale = confirmedRegistration();
        stale.setId(7L);
        stale.setStatus(RegistrationStatus.PENDING_PAYMENT);
        stale.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));

        // What the locked re-read finds: they paid, so it is CONFIRMED
        // with no deadline left on it.
        Registration paidSince = confirmedRegistration();
        paidSince.setId(7L);
        paidSince.setStatus(RegistrationStatus.CONFIRMED);
        paidSince.setPaymentDueAt(null);

        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of(stale));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(paidSince));

        assertEquals(0, registrationService.releaseExpiredPaymentHolds());

        assertEquals(RegistrationStatus.CONFIRMED, paidSince.getStatus(),
                "a booking paid for since discovery must not be cancelled");
        assertEquals(1, publishedEvent.getSeatsTaken(), "its seat must not be given away");
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    void releasingHolds_leavesAloneAHoldWhoseDeadlineWasExtendedSinceDiscovery() {
        publishedEvent.setSeatsTaken(1);
        Registration stale = confirmedRegistration();
        stale.setId(7L);
        stale.setStatus(RegistrationStatus.PENDING_PAYMENT);
        stale.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));

        Registration stillHeld = confirmedRegistration();
        stillHeld.setId(7L);
        stillHeld.setStatus(RegistrationStatus.PENDING_PAYMENT);
        stillHeld.setPaymentDueAt(LocalDateTime.now().plusMinutes(20));

        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of(stale));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(stillHeld));

        assertEquals(0, registrationService.releaseExpiredPaymentHolds());
        assertEquals(RegistrationStatus.PENDING_PAYMENT, stillHeld.getStatus());
    }

    @Test
    void releasingHolds_takesTheEventLockBeforeTheRegistrationLock() {
        // Lock ORDER, not merely lock presence. cancelRegistration takes
        // event then registration; this sweep used to take them the other
        // way round, and two transactions taking the same pair of locks
        // in opposite orders is the definition of a deadlock.
        publishedEvent.setSeatsTaken(1);
        Registration hold = confirmedRegistration();
        hold.setId(7L);
        hold.setStatus(RegistrationStatus.PENDING_PAYMENT);
        hold.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));
        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(hold));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        registrationService.releaseExpiredPaymentHolds();

        InOrder locks = inOrder(eventRepository, registrationRepository);
        locks.verify(eventRepository).findByIdForUpdate(100L);
        locks.verify(registrationRepository).findByIdForUpdate(7L);
    }

    @Test
    void releasingHolds_discoversWithoutLocking() {
        // The discovery query must stay unlocked. A locked discovery is
        // what created the inverted order in the first place.
        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of());

        registrationService.releaseExpiredPaymentHolds();

        verify(registrationRepository).findExpiredHolds(any());
    }

    @Test
    void releasingHoldsDoesNothingWhenThereAreNone() {
        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of());

        assertEquals(0, registrationService.releaseExpiredPaymentHolds());
        verify(eventRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void promotionFromTheWaitlistOnAPaidEventHoldsRatherThanConfirms() {
        // The side door. Promoting straight to CONFIRMED would confirm an
        // unpaid booking just as surely as registering did.
        publishedEvent.setFee(new BigDecimal("2500.00"));
        publishedEvent.setCapacity(2);
        publishedEvent.setSeatsTaken(1);
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        Registration waiting = confirmedRegistration();
        waiting.setId(8L);
        waiting.setStatus(RegistrationStatus.WAITLISTED);
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(mine));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of(waiting));

        registrationService.cancelRegistration(100L, attendeeActor);

        assertEquals(RegistrationStatus.PENDING_PAYMENT, waiting.getStatus());
        assertNotNull(waiting.getPaymentDueAt());
    }

    @Test
    void promotionFromTheWaitlistOnAFreeEventConfirmsImmediately() {
        publishedEvent.setFee(BigDecimal.ZERO);
        publishedEvent.setCapacity(2);
        publishedEvent.setSeatsTaken(1);
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        Registration waiting = confirmedRegistration();
        waiting.setId(8L);
        waiting.setStatus(RegistrationStatus.WAITLISTED);
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(mine));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of(waiting));

        registrationService.cancelRegistration(100L, attendeeActor);

        assertEquals(RegistrationStatus.CONFIRMED, waiting.getStatus());
        assertNull(waiting.getPaymentDueAt());
    }

    @Test
    void register_waitlistsWhenEventIsFull() {
        publishedEvent.setSeatsTaken(1); // capacity is 1

        RegistrationResponse response = registrationService.register(100L, attendeeActor);

        assertEquals(RegistrationStatus.WAITLISTED, response.getStatus());
        assertEquals(1, publishedEvent.getSeatsTaken(), "a waitlisted place must not consume a seat");
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void register_rejectsASecondActiveRegistration() {
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(new Registration()));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
    }

    @Test
    void register_isAllowedAgainAfterCancelling() {
        // Only CANCELLED rows exist for this attendee, so findActive... is
        // empty and registering again must succeed. Under the old
        // UNIQUE(event_id, user_id) this was impossible.
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L)).thenReturn(Optional.empty());

        RegistrationResponse response = registrationService.register(100L, attendeeActor);

        assertEquals(RegistrationStatus.CONFIRMED, response.getStatus());
    }

    @Test
    void register_rejectsWhenNotEligibleForRestrictedEvent() {
        when(eventInvitationService.isEligibleToRegister(100L, 2L)).thenReturn(false);

        assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    void register_rejectsBeforeTheRegistrationWindowOpens() {
        publishedEvent.setRegistrationOpenDate(LocalDateTime.now().plusDays(1));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
        assertTrue(ex.getMessage().contains("not opened"));
    }

    @Test
    void register_rejectsAfterTheRegistrationDeadline() {
        publishedEvent.setRegistrationDeadline(LocalDateTime.now().minusHours(1));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
        assertTrue(ex.getMessage().contains("closed"));
    }

    @Test
    void register_rejectsOnceTheEventHasStarted() {
        publishedEvent.setStartDatetime(LocalDateTime.now().minusMinutes(1));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
        assertTrue(ex.getMessage().contains("already started"));
    }

    @Test
    void register_rejectsACancelledEvent() {
        publishedEvent.setStatus(EventStatus.CANCELLED);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
        assertTrue(ex.getMessage().contains("cancelled"));
    }

    @Test
    void register_rejectsAnEventThatIsNotPublishedYet() {
        publishedEvent.setStatus(EventStatus.PENDING_APPROVAL);

        assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
    }

    @Test
    void register_rejectsADraft() {
        // A draft is the organizer's private working copy. Nobody books a
        // seat on it, even if they somehow reach the URL.
        publishedEvent.setStatus(EventStatus.DRAFT);

        assertThrows(InvalidRequestException.class,
                () -> registrationService.register(100L, attendeeActor));
    }

    // =================================================================
    // Cancel
    // =================================================================

    @Test
    void cancelRegistration_blockedWithin48HourCutoffForAConfirmedSeat() {
        publishedEvent.setStartDatetime(LocalDateTime.now().plusHours(10));
        Registration registration = confirmedRegistration();
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.cancelRegistration(100L, attendeeActor));
    }

    @Test
    void cancelRegistration_waitlistedPlaceCanBeGivenUpAtAnyTime() {
        // Leaving a waitlist costs the organizer nothing, so the 48-hour
        // rule does not apply to it.
        publishedEvent.setStartDatetime(LocalDateTime.now().plusHours(2));
        Registration waitlisted = confirmedRegistration();
        waitlisted.setStatus(RegistrationStatus.WAITLISTED);
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(waitlisted));

        assertDoesNotThrow(() -> registrationService.cancelRegistration(100L, attendeeActor));
        assertEquals(RegistrationStatus.CANCELLED, waitlisted.getStatus());
    }

    @Test
    void cancelRegistration_throwsWhenTheUserHasNoActiveRegistration() {
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> registrationService.cancelRegistration(100L, attendeeActor));
    }

    @Test
    void cancelRegistration_freesExactlyOneSeatAndPromotesTheNextWaitlistedAttendee() {
        publishedEvent.setCapacity(1);
        publishedEvent.setSeatsTaken(1);
        Registration cancelling = confirmedRegistration();

        User waitlistedUser = new User("Waitlisted Person", "wl@test.com", "hash", UserRole.ATTENDEE);
        waitlistedUser.setId(3L);
        Registration waitlisted = new Registration();
        waitlisted.setId(9L);
        waitlisted.setEvent(publishedEvent);
        waitlisted.setUser(waitlistedUser);
        waitlisted.setStatus(RegistrationStatus.WAITLISTED);

        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(cancelling));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of(waitlisted));

        registrationService.cancelRegistration(100L, attendeeActor);

        assertEquals(RegistrationStatus.CANCELLED, cancelling.getStatus());
        assertNotNull(cancelling.getCancelledAt());
        assertEquals(RegistrationStatus.CONFIRMED, waitlisted.getStatus());
        // One seat freed, one seat re-taken by the promotion.
        assertEquals(1, publishedEvent.getSeatsTaken());
        verify(notificationService, atLeastOnce()).notify(eq(waitlistedUser), any(), any(), any());
    }

    @Test
    void cancelRegistration_neverPushesSeatsTakenNegative() {
        publishedEvent.setSeatsTaken(0); // already inconsistent
        Registration cancelling = confirmedRegistration();
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(cancelling));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        registrationService.cancelRegistration(100L, attendeeActor);

        assertEquals(0, publishedEvent.getSeatsTaken());
    }

    @Test
    void cancelRegistration_locksTheEventBeforeItReadsTheRegistration() {
        // Consistent lock ordering - event first, then registration - is
        // what keeps a cancellation and a concurrent registration for the
        // same event from deadlocking against each other. Both the lock and
        // its order are part of the contract, so both are asserted.
        Registration cancelling = confirmedRegistration();
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(cancelling));

        registrationService.cancelRegistration(100L, attendeeActor);

        InOrder order = inOrder(eventRepository, registrationRepository);
        order.verify(eventRepository).findByIdForUpdate(100L);
        order.verify(registrationRepository).findActiveByEventIdAndUserIdForUpdate(100L, 2L);
        // The unlocked read must not be what a cancellation decides on.
        verify(registrationRepository, never()).findActiveByEventIdAndUserId(any(), any());
    }

    @Test
    void cancelRegistration_asecondCancellationReleasesNoFurtherSeat() {
        // The race, reduced to what a unit test can assert: the second
        // attempt sees the state the first one committed - no active
        // registration - and stops, so the seat is released exactly once
        // rather than twice.
        publishedEvent.setCapacity(10);
        publishedEvent.setSeatsTaken(3);
        Registration cancelling = confirmedRegistration();
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(cancelling))   // first caller
                .thenReturn(Optional.empty());          // second caller, after the lock is released

        registrationService.cancelRegistration(100L, attendeeActor);
        assertThrows(ResourceNotFoundException.class,
                () -> registrationService.cancelRegistration(100L, attendeeActor));

        assertEquals(2, publishedEvent.getSeatsTaken(),
                "the seat must be given back once, not once per attempt");
    }

    @Test
    void promotion_doesNotOverfillWhenCapacityWasReduced() {
        publishedEvent.setCapacity(1);
        publishedEvent.setSeatsTaken(1);
        Registration cancelling = confirmedRegistration();

        Registration waitlisted = new Registration();
        waitlisted.setId(9L);
        waitlisted.setEvent(publishedEvent);
        waitlisted.setUser(attendee);
        waitlisted.setStatus(RegistrationStatus.WAITLISTED);

        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(cancelling));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of(waitlisted));

        registrationService.cancelRegistration(100L, attendeeActor);

        assertTrue(publishedEvent.getSeatsTaken() <= publishedEvent.getCapacity());
    }

    // =================================================================
    // Authorization on reads
    // =================================================================

    @Test
    void getRegistrationsForEvent_refusesAnAttendee() {
        assertThrows(AccessDeniedException.class,
                () -> registrationService.getRegistrationsForEvent(100L, attendeeActor));
    }

    @Test
    void getRegistrationsForEvent_refusesAnotherOrganizer() {
        ActingUser otherOrganizer = new ActingUser(77L, UserRole.ORGANIZER);
        assertThrows(AccessDeniedException.class,
                () -> registrationService.getRegistrationsForEvent(100L, otherOrganizer));
    }

    @Test
    void getRegistrationsForEvent_allowsTheOwningOrganizerAndAdmin() {
        when(registrationRepository.findByEventId(100L)).thenReturn(List.of());

        assertDoesNotThrow(() -> registrationService.getRegistrationsForEvent(100L, organizerActor));
        assertDoesNotThrow(() -> registrationService.getRegistrationsForEvent(100L, adminActor));
    }

    @Test
    void requireOwnRegistration_refusesEveryoneButTheOwner() {
        Registration registration = confirmedRegistration();
        registration.setId(55L);
        when(registrationRepository.findById(55L)).thenReturn(Optional.of(registration));

        assertSame(registration, registrationService.requireOwnRegistration(55L, attendeeActor));
        assertThrows(AccessDeniedException.class,
                () -> registrationService.requireOwnRegistration(55L, otherAttendeeActor));
        // Deliberately not even the organizer of the event, nor an admin:
        // a ticket and its payment belong to the attendee alone.
        assertThrows(AccessDeniedException.class,
                () -> registrationService.requireOwnRegistration(55L, organizerActor));
        assertThrows(AccessDeniedException.class,
                () -> registrationService.requireOwnRegistration(55L, adminActor));
    }

    @Test
    void getRegistrationById_readableByOwnerOrganizerAndAdminOnly() {
        Registration registration = confirmedRegistration();
        registration.setId(55L);
        when(registrationRepository.findById(55L)).thenReturn(Optional.of(registration));

        assertDoesNotThrow(() -> registrationService.getRegistrationById(55L, attendeeActor));
        assertDoesNotThrow(() -> registrationService.getRegistrationById(55L, organizerActor));
        assertDoesNotThrow(() -> registrationService.getRegistrationById(55L, adminActor));
        assertThrows(AccessDeniedException.class,
                () -> registrationService.getRegistrationById(55L, otherAttendeeActor));
    }

    @Test
    void aRegistrationOnACancelledEventSaysSoInTheDto() {
        // The ticket page and "My registrations" render from this DTO. If
        // it did not carry the event's status, a cancelled event would
        // still show a scannable ticket and a "valid" badge - the page had
        // no way to know otherwise. The rendering is the visible half of
        // the fix; this is the half the page depends on.
        Registration registration = confirmedRegistration();
        registration.setId(55L);
        registration.setStatus(RegistrationStatus.CANCELLED);
        publishedEvent.setStatus(EventStatus.CANCELLED);
        when(registrationRepository.findById(55L)).thenReturn(Optional.of(registration));

        RegistrationResponse response = registrationService.getRegistrationById(55L, attendeeActor);

        assertEquals(EventStatus.CANCELLED, response.getEventStatus());
        assertTrue(response.isEventCancelled());
    }

    @Test
    void aRegistrationOnALiveEventIsNotMarkedCancelled() {
        Registration registration = confirmedRegistration();
        registration.setId(55L);
        when(registrationRepository.findById(55L)).thenReturn(Optional.of(registration));

        RegistrationResponse response = registrationService.getRegistrationById(55L, attendeeActor);

        assertEquals(EventStatus.PUBLISHED, response.getEventStatus());
        assertFalse(response.isEventCancelled());
    }

    // =================================================================
    // Availability (what the event page renders from)
    // =================================================================

    @Test
    void checkAvailability_reportsTheSameReasonRegistrationWouldThrow() {
        publishedEvent.setRegistrationDeadline(LocalDateTime.now().minusHours(1));

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertFalse(availability.isCanRegister());
        assertTrue(availability.getBlockedReason().contains("closed"));
    }

    @Test
    void checkAvailability_flagsThatRegisteringWouldOnlyGetAWaitlistPlace() {
        publishedEvent.setSeatsTaken(1); // capacity 1

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertTrue(availability.isCanRegister());
        assertTrue(availability.isWouldWaitlist());
    }

    @Test
    void checkAvailability_showsAPendingInvitationForARestrictedEvent() {
        publishedEvent.setEventType(EventType.RESTRICTED);
        when(eventInvitationService.isEligibleToRegister(100L, 2L)).thenReturn(false);
        InvitationResponse pending = mock(InvitationResponse.class);
        when(pending.getStatus()).thenReturn(InvitationStatus.PENDING);
        when(eventInvitationService.findMyInvitation(100L, attendeeActor)).thenReturn(pending);

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertTrue(availability.isInvitationRequired());
        assertTrue(availability.isAwaitingInvitationDecision());
        assertFalse(availability.isCanRegister());
        assertFalse(availability.isCanRequestInvitation());
    }

    // -----------------------------------------------------------------
    // What cancelling will cost.
    //
    // The page has to be able to say this BEFORE the button is pressed,
    // and it has to match what the refund service will actually do. That
    // is why both read the same RefundPolicy rather than each carrying
    // their own copy of the rule.
    // -----------------------------------------------------------------

    @Test
    void checkAvailability_warnsThatCancellingAPaidPlaceForfeitsTheMoney() {
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(mine));
        when(paymentRepository.findByRegistrationId(7L))
                .thenReturn(Optional.of(completedPaymentOf(new BigDecimal("2500.00"))));

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertTrue(availability.isCancelForfeitsPayment());
    }

    @Test
    void checkAvailability_doesNotWarnWhenNothingWasPaid() {
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(mine));
        when(paymentRepository.findByRegistrationId(7L)).thenReturn(Optional.empty());

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertFalse(availability.isCancelForfeitsPayment());
    }

    @Test
    void checkAvailability_doesNotWarnAboutAFreePlace() {
        // A free event produces a COMPLETED payment of 0.00. There is
        // nothing to forfeit, so warning about it would be noise.
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(mine));
        when(paymentRepository.findByRegistrationId(7L))
                .thenReturn(Optional.of(completedPaymentOf(BigDecimal.ZERO)));

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertFalse(availability.isCancelForfeitsPayment());
    }

    @Test
    void checkAvailability_doesNotWarnAboutAWaitlistPlace() {
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        mine.setStatus(RegistrationStatus.WAITLISTED);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(mine));

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertFalse(availability.isCancelForfeitsPayment());
        verify(paymentRepository, never()).findByRegistrationId(any());
    }

    @Test
    void checkAvailability_doesNotWarnWhenThePolicyRefundsSelfCancellation() {
        // Same paid booking, opposite policy: nothing is forfeited, so
        // the page must not claim otherwise.
        doReturn(true).when(refundPolicy).refundsOnAttendeeCancellation();
        Registration mine = confirmedRegistration();
        mine.setId(7L);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.of(mine));
        when(paymentRepository.findByRegistrationId(7L))
                .thenReturn(Optional.of(completedPaymentOf(new BigDecimal("2500.00"))));

        RegistrationAvailability availability =
                registrationService.checkAvailability(100L, attendeeActor);

        assertFalse(availability.isCancelForfeitsPayment());
    }

    @Test
    void checkAvailability_forAnAnonymousVisitorReportsOnlyTheEventState() {
        RegistrationAvailability availability = registrationService.checkAvailability(100L, null);

        assertFalse(availability.isCanRegister());
        assertNull(availability.getMyStatus());
    }

    // =================================================================
    // Free events are ticketed at registration
    //
    // A confirmed booking that cannot produce a ticket is not a booking
    // anybody can use at a door. Free events used to get one only if the
    // attendee happened to walk through a checkout for 0.00, which the
    // UI never offered them - so in practice they got none at all.
    // =================================================================

    @Test
    void register_issuesATicketImmediatelyForAFreeEvent() {
        // The fixture event is free.
        registrationService.register(100L, attendeeActor);

        ArgumentCaptor<Payment> ticket = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(ticket.capture());
        assertEquals(PaymentStatus.COMPLETED, ticket.getValue().getStatus());
        assertNotNull(ticket.getValue().getTicketCode());
        assertTrue(ticket.getValue().getTicketCode().startsWith("TICKET-"));
    }

    @Test
    void register_theFreeTicketIsWorthZeroSoItNeverBecomesRevenueOrARefund() {
        registrationService.register(100L, attendeeActor);

        ArgumentCaptor<Payment> ticket = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(ticket.capture());
        assertEquals(0, ticket.getValue().getFinalAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, ticket.getValue().getOriginalAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    void register_issuesNoTicketForAPaidEventUntilItIsPaidFor() {
        // The whole point of the hold: a held seat is not a booking, so
        // it does not get a ticket.
        publishedEvent.setFee(new BigDecimal("2500.00"));

        registrationService.register(100L, attendeeActor);

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void register_issuesNoTicketForAWaitlistedPlace() {
        publishedEvent.setSeatsTaken(1); // capacity is 1

        registrationService.register(100L, attendeeActor);

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    // =================================================================
    // Attendance
    //
    // The capability the reporting requirement depended on and the system
    // did not have. These cases are about the two things that make an
    // attendance figure trustworthy: only people who actually held a seat
    // can be counted, and only the organizer of that event can count them.
    // =================================================================

    @Test
    void checkIn_recordsWhenAndByWhom() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        RegistrationResponse response = registrationService.checkIn(7L, organizerActor);

        assertTrue(response.isCheckedIn());
        assertNotNull(registration.getCheckedInAt());
        assertEquals(organizer, registration.getCheckedInBy());
    }

    @Test
    void checkIn_isWrittenToTheAuditTrail() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        registrationService.checkIn(7L, organizerActor);

        verify(auditLogService).log(eq(organizer), eq("CHECK_IN"), eq("Registration"),
                eq(7L), anyString());
    }

    @Test
    void checkIn_isRefusedForSomeoneElsesEvent() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(AccessDeniedException.class,
                () -> registrationService.checkIn(7L, new ActingUser(77L, UserRole.ORGANIZER)));
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    void checkIn_isRefusedForAnAttendee() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(AccessDeniedException.class,
                () -> registrationService.checkIn(7L, attendeeActor));
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    void checkIn_refusesAWaitlistedPlace() {
        // Somebody on the waitlist has no seat. Counting them as attending
        // would put attendance above the seats that exist.
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setStatus(RegistrationStatus.WAITLISTED);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.checkIn(7L, organizerActor));
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    void checkIn_refusesACancelledBooking() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setStatus(RegistrationStatus.CANCELLED);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.checkIn(7L, organizerActor));
    }

    @Test
    void checkIn_refusesWhenTheEventItselfWasCancelled() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        publishedEvent.setStatus(EventStatus.CANCELLED);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.checkIn(7L, organizerActor));
    }

    @Test
    void checkIn_twiceKeepsTheFirstArrivalTime() {
        // Pressing the button again at a busy door is the likeliest thing
        // that will happen to this method. It must not fail, and it must
        // not overwrite when they actually arrived.
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        LocalDateTime firstTime = LocalDateTime.now().minusHours(2);
        registration.setCheckedInAt(firstTime);
        registration.setCheckedInBy(organizer);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        RegistrationResponse response = registrationService.checkIn(7L, organizerActor);

        assertEquals(firstTime, registration.getCheckedInAt());
        assertTrue(response.isCheckedIn());
        verify(registrationRepository, never()).save(any(Registration.class));
    }

    @Test
    void undoCheckIn_clearsBothAttendanceColumns() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setCheckedInAt(LocalDateTime.now());
        registration.setCheckedInBy(organizer);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        registrationService.undoCheckIn(7L, organizerActor);

        assertNull(registration.getCheckedInAt());
        assertNull(registration.getCheckedInBy());
    }

    @Test
    void undoCheckIn_refusesWhenNobodyWasCheckedIn() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.undoCheckIn(7L, organizerActor));
    }

    @Test
    void undoCheckIn_isRefusedForSomeoneElsesEvent() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setCheckedInAt(LocalDateTime.now());
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(AccessDeniedException.class,
                () -> registrationService.undoCheckIn(7L, new ActingUser(77L, UserRole.ORGANIZER)));
    }

    @Test
    void anAdministratorCanCheckInForAnyEvent() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(registrationService.checkIn(7L, adminActor).isCheckedIn());
    }

    // =================================================================
    // helpers
    // =================================================================

    private Payment completedPaymentOf(BigDecimal amount) {
        Payment payment = new Payment();
        payment.setId(500L);
        payment.setOriginalAmount(amount);
        payment.setDiscountAmount(BigDecimal.ZERO);
        payment.setFinalAmount(amount);
        payment.setStatus(PaymentStatus.COMPLETED);
        return payment;
    }

    private Registration confirmedRegistration() {
        Registration registration = new Registration();
        registration.setEvent(publishedEvent);
        registration.setUser(attendee);
        registration.setStatus(RegistrationStatus.CONFIRMED);
        return registration;
    }

    // =================================================================
    // Cancellation now announces itself, and staff can cancel for an
    // attendee
    //
    // The gap: cancelling a paid booking released the seat and kept the
    // money, because nothing on this path ever reached Payment &
    // Ticketing. Registration & Booking still does not import
    // RefundService - it publishes, and the refund module subscribes.
    // =================================================================

    @Test
    void cancelRegistration_announcesTheCancellationSoAPaidSeatCanBeRefunded() {
        Registration registration = confirmedRegistration();
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        registrationService.cancelRegistration(100L, attendeeActor);

        ArgumentCaptor<RegistrationCancelledEvent> captor =
                ArgumentCaptor.forClass(RegistrationCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(RegistrationStatus.CONFIRMED, captor.getValue().getPreviousStatus(),
                "the status BEFORE cancellation is what decides whether money is owed");
        assertTrue(captor.getValue().heldASeat());
        assertFalse(captor.getValue().isCancelledByStaff());
    }

    @Test
    void cancelRegistration_announcesAWaitlistPlaceAsHavingHeldNoSeat() {
        Registration registration = confirmedRegistration();
        registration.setStatus(RegistrationStatus.WAITLISTED);
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(registration));

        registrationService.cancelRegistration(100L, attendeeActor);

        ArgumentCaptor<RegistrationCancelledEvent> captor =
                ArgumentCaptor.forClass(RegistrationCancelledEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertFalse(captor.getValue().heldASeat(),
                "a waitlist place was never charged for, so it must not refund");
    }

    @Test
    void staffCancellation_isRefusedForAnOrganizer() {
        // Cancelling somebody's paid booking is a platform-level action,
        // not something an organizer does even on their own event.
        assertThrows(AccessDeniedException.class,
                () -> registrationService.cancelRegistrationAsStaff(5L, organizerActor, "why"));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void staffCancellation_isRefusedForAnAttendee() {
        assertThrows(AccessDeniedException.class,
                () -> registrationService.cancelRegistrationAsStaff(5L, attendeeActor, "why"));
    }

    @Test
    void staffCancellation_ignoresThe48HourCutoffAndTellsTheAttendee() {
        // The cutoff protects the organizer's headcount from late attendee
        // changes; staff acting on a support request is the escape hatch
        // that rule's own error message points people at.
        publishedEvent.setStartDatetime(LocalDateTime.now().plusHours(2));
        Registration registration = confirmedRegistration();
        registration.setId(5L);
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(registration));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(publishedEvent));
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());
        when(userRepository.findById(99L)).thenReturn(Optional.of(attendee));

        registrationService.cancelRegistrationAsStaff(5L, adminActor, "duplicate booking");

        assertEquals(RegistrationStatus.CANCELLED, registration.getStatus());
        verify(notificationService).notify(eq(attendee), eq(NotificationType.EVENT_CHANGE),
                any(), contains("cancelled by an administrator"));
        verify(eventPublisher).publishEvent(any(RegistrationCancelledEvent.class));
    }

    @Test
    void bookingSearch_isAdministratorOnly() {
        assertThrows(AccessDeniedException.class,
                () -> registrationService.searchBookings(
                        new com.sliit.eventsphere.dto.BookingSearchCriteria(),
                        org.springframework.data.domain.PageRequest.of(0, 10),
                        organizerActor));
        assertThrows(AccessDeniedException.class,
                () -> registrationService.searchBookings(
                        new com.sliit.eventsphere.dto.BookingSearchCriteria(),
                        org.springframework.data.domain.PageRequest.of(0, 10),
                        attendeeActor));
    }

    // =================================================================
    // Edit booking - resizing a seat count that has not been paid for
    // =================================================================

    @Test
    void updateBooking_rejectsSomeoneEditingAnotherPersonsBooking() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setStatus(RegistrationStatus.PENDING_PAYMENT);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));

        assertThrows(AccessDeniedException.class,
                () -> registrationService.updateBooking(7L, 2, otherAttendeeActor));
        verify(eventRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void updateBooking_rejectsFewerThanOneSeat() {
        assertThrows(InvalidRequestException.class,
                () -> registrationService.updateBooking(7L, 0, attendeeActor));
    }

    @Test
    void updateBooking_rejectsMoreThanTheBookingCap() {
        assertThrows(InvalidRequestException.class,
                () -> registrationService.updateBooking(7L, 11, attendeeActor));
    }

    @Test
    void updateBooking_rejectsAWaitlistedBooking() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setStatus(RegistrationStatus.WAITLISTED);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.updateBooking(7L, 2, attendeeActor));
    }

    @Test
    void updateBooking_rejectsACancelledBooking() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setStatus(RegistrationStatus.CANCELLED);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.updateBooking(7L, 2, attendeeActor));
    }

    @Test
    void updateBooking_rejectsAConfirmedPaidBooking() {
        publishedEvent.setFee(new BigDecimal("2500.00"));
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setStatus(RegistrationStatus.CONFIRMED);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> registrationService.updateBooking(7L, 2, attendeeActor));
        assertTrue(ex.getMessage().contains("already paid"));
    }

    @Test
    void updateBooking_growingAConfirmedFreeBookingChecksCapacity() {
        publishedEvent.setCapacity(5);
        publishedEvent.setSeatsTaken(1);
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setSeats(1);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));

        RegistrationResponse response = registrationService.updateBooking(7L, 3, attendeeActor);

        assertEquals(3, response.getSeats());
        assertEquals(3, publishedEvent.getSeatsTaken()); // 1 existing + 2 more
    }

    @Test
    void updateBooking_rejectsGrowingBeyondWhatIsLeft() {
        publishedEvent.setCapacity(3);
        publishedEvent.setSeatsTaken(3); // full
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setSeats(1);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));

        assertThrows(InvalidRequestException.class,
                () -> registrationService.updateBooking(7L, 2, attendeeActor));
        assertEquals(3, publishedEvent.getSeatsTaken()); // untouched
    }

    @Test
    void updateBooking_shrinkingReleasesTheDifferenceAndWakesTheWaitlist() {
        publishedEvent.setCapacity(3);
        publishedEvent.setSeatsTaken(3); // full
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setSeats(3);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        RegistrationResponse response = registrationService.updateBooking(7L, 1, attendeeActor);

        assertEquals(1, response.getSeats());
        assertEquals(1, publishedEvent.getSeatsTaken()); // 3 - 2 released
        verify(registrationRepository).findWaitlistOrderedForUpdate(100L);
    }

    @Test
    void updateBooking_isANoOpWhenTheSeatCountDoesNotChange() {
        Registration registration = confirmedRegistration();
        registration.setId(7L);
        registration.setSeats(2);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(registration));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(registration));

        registrationService.updateBooking(7L, 2, attendeeActor);

        verify(eventRepository, never()).save(any());
        verify(registrationRepository, never()).save(any());
    }
}
