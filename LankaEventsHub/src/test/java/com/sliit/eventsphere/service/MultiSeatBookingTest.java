package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.RegistrationAvailability;
import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.InvalidRequestException;
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
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Multi-seat booking: one registration row can hold a party.
 *
 * Kept in its own file rather than added to RegistrationServiceImplTest,
 * which is already 900 lines about single seats. Every test here is about
 * the same question asked in a different place - does this figure move by
 * the number of seats, or by one? Getting that wrong in any one of them
 * oversells the event, and an oversold event is only discovered at a door.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MultiSeatBookingTest {

    @Mock private EventRepository eventRepository;
    @Mock private UserRepository userRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private NotificationService notificationService;
    @Mock private AuditLogService auditLogService;
    @Mock private EventInvitationService eventInvitationService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Spy private EventAccessPolicy accessPolicy =
            new EventAccessPolicy(mock(RegistrationRepository.class));
    @Spy private RefundPolicy refundPolicy = new RefundPolicy();

    private RegistrationServiceImpl service;

    private User attendee;
    private User organizer;
    private ActingUser attendeeActor;
    private ActingUser organizerActor;
    private Event event;

    @BeforeEach
    void setUp() {
        service = new RegistrationServiceImpl(eventRepository, userRepository,
                registrationRepository, notificationService, auditLogService,
                eventInvitationService, accessPolicy, eventPublisher,
                paymentRepository, refundRepository, refundPolicy,
                new TicketCodeGenerator(), 30L);

        organizer = new User("Kasun Perera", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        attendee = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(2L);
        attendeeActor = ActingUser.of(attendee);
        organizerActor = ActingUser.of(organizer);

        event = new Event();
        event.setId(100L);
        event.setName("Cloud Workshop");
        event.setOrganizer(organizer);
        event.setStatus(EventStatus.PUBLISHED);
        event.setEventType(EventType.PUBLIC);
        event.setCapacity(10);
        event.setSeatsTaken(0);
        event.setFee(BigDecimal.ZERO);
        event.setStartDatetime(LocalDateTime.now().plusDays(10));

        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(event));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(attendee));
        when(eventInvitationService.isEligibleToRegister(100L, 2L)).thenReturn(true);
        when(registrationRepository.save(any(Registration.class))).thenAnswer(inv -> {
            Registration r = inv.getArgument(0);
            if (r.getId() == null) r.setId(7L);
            return r;
        });
    }

    // =================================================================
    // Booking a party
    // =================================================================

    @Test
    void aPartyTakesAsManySeatsAsItBooked() {
        RegistrationResponse response = service.register(100L, 4, attendeeActor);

        assertEquals(4, response.getSeats());
        assertEquals(RegistrationStatus.CONFIRMED, response.getStatus());
        // Four, not one. This single assertion is the whole feature: if
        // seatsTaken moves by one for a party of four, the event sells
        // thirty seats in a room that holds ten.
        assertEquals(4, event.getSeatsTaken());
    }

    @Test
    void aBookingThatSaysNothingAboutSeatsIsOneSeat() {
        // Every caller written before parties existed still works, and
        // still books exactly one seat.
        RegistrationResponse response = service.register(100L, attendeeActor);

        assertEquals(1, response.getSeats());
        assertEquals(1, event.getSeatsTaken());
    }

    @Test
    void aHeldPartyTakesItsSeatsBeforeAnyMoneyArrives() {
        event.setFee(new BigDecimal("2500.00"));

        RegistrationResponse response = service.register(100L, 3, attendeeActor);

        assertEquals(RegistrationStatus.PENDING_PAYMENT, response.getStatus());
        // The seats are unavailable to anybody else for the length of the
        // hold, which is the point of a hold. A held party that took no
        // seats would let the same three seats be sold twice.
        assertEquals(3, event.getSeatsTaken());
    }

    @Test
    void zeroSeatsIsRefused() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.register(100L, 0, attendeeActor));
        assertTrue(ex.getMessage().contains("at least one"));
        assertEquals(0, event.getSeatsTaken());
    }

    @Test
    void aNegativeSeatCountCannotGiveSeatsBack() {
        // Without the lower bound this would DECREASE seatsTaken, which
        // is a way to manufacture free capacity by posting -5.
        assertThrows(InvalidRequestException.class,
                () -> service.register(100L, -5, attendeeActor));
        assertEquals(0, event.getSeatsTaken());
    }

    @Test
    void aPartyLargerThanTheBookingLimitIsRefused() {
        event.setCapacity(500);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.register(100L, 11, attendeeActor));

        // The database CHECK allows at most 10 too, so a request that got
        // past the service would fail on insert instead of being answered.
        assertTrue(ex.getMessage().contains("10"));
        assertEquals(0, event.getSeatsTaken());
    }

    @Test
    void aPartyIsNeverSplitAcrossTheCapacityLine() {
        event.setSeatsTaken(8);   // two seats left, party of four

        RegistrationResponse response = service.register(100L, 4, attendeeActor);

        assertEquals(RegistrationStatus.WAITLISTED, response.getStatus());
        // Crucially, seatsTaken did NOT move: a waitlisted party holds
        // nothing. Seating two of the four and waitlisting the rest would
        // split a group that arrived together.
        assertEquals(8, event.getSeatsTaken());
    }

    @Test
    void aPartyThatExactlyFillsTheRoomIsSeated() {
        event.setSeatsTaken(6);   // exactly four left

        RegistrationResponse response = service.register(100L, 4, attendeeActor);

        // The boundary, stated: <= capacity, not < capacity. An off-by-one
        // here turns the last four seats of every event into dead stock.
        assertEquals(RegistrationStatus.CONFIRMED, response.getStatus());
        assertEquals(10, event.getSeatsTaken());
    }

    @Test
    void theWaitlistMessageForAPartyDoesNotClaimTheEventIsFull() {
        event.setSeatsTaken(8);

        service.register(100L, 4, attendeeActor);

        verify(notificationService).notify(eq(attendee), eq(NotificationType.WAITLIST_UPDATE),
                any(), contains("does not have 4 seats free together"));
    }

    // =================================================================
    // Giving the seats back
    // =================================================================

    @Test
    void cancellingAPartyReturnsAllOfItsSeats() {
        event.setSeatsTaken(4);
        Registration party = confirmedParty(4);
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(party));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        service.cancelRegistration(100L, attendeeActor);

        // All four, not one. Releasing a single seat would leave three
        // permanently unsellable for the life of the event.
        assertEquals(0, event.getSeatsTaken());
    }

    @Test
    void anExpiredHoldGivesBackTheWholeParty() {
        event.setFee(new BigDecimal("2500.00"));
        event.setSeatsTaken(3);
        Registration hold = new Registration();
        hold.setId(7L);
        hold.setEvent(event);
        hold.setUser(attendee);
        hold.setSeats(3);
        hold.setStatus(RegistrationStatus.PENDING_PAYMENT);
        hold.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));
        when(registrationRepository.findExpiredHolds(any())).thenReturn(List.of(hold));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(hold));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L)).thenReturn(List.of());

        assertEquals(1, service.releaseExpiredPaymentHolds());
        assertEquals(0, event.getSeatsTaken());
    }

    // =================================================================
    // The waitlist
    // =================================================================

    @Test
    void promotionTakesAsManySeatsAsThePromotedPartyNeeds() {
        event.setSeatsTaken(4);
        Registration leaving = confirmedParty(4);
        Registration waiting = waitlisted(8L, 3);
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(leaving));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L))
                .thenReturn(List.of(waiting));

        service.cancelRegistration(100L, attendeeActor);

        assertEquals(RegistrationStatus.CONFIRMED, waiting.getStatus());
        // Four released, three taken by the promoted party.
        assertEquals(3, event.getSeatsTaken());
    }

    @Test
    void freeingSeveralSeatsCanPromoteSeveralParties() {
        // Four seats come back; two waiting pairs fit in them. Promoting
        // exactly one booking - which is what the single-seat code did -
        // would leave two seats empty with people still waiting for them.
        event.setSeatsTaken(4);
        Registration leaving = confirmedParty(4);
        Registration firstPair = waitlisted(8L, 2);
        Registration secondPair = waitlisted(9L, 2);
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(leaving));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L))
                .thenReturn(List.of(firstPair, secondPair));

        service.cancelRegistration(100L, attendeeActor);

        assertEquals(RegistrationStatus.CONFIRMED, firstPair.getStatus());
        assertEquals(RegistrationStatus.CONFIRMED, secondPair.getStatus());
        assertEquals(4, event.getSeatsTaken());
    }

    @Test
    void aPartyTooLargeForTheFreedSeatsKeepsWaitingAndDoesNotBlockTheQueue() {
        // Two seats come back. The party of six at the head cannot use
        // them; the pair behind can. Stopping at the first booking that
        // does not fit would leave those two seats empty until the event
        // happened.
        event.setSeatsTaken(2);
        Registration leaving = confirmedParty(2);
        Registration bigParty = waitlisted(8L, 6);
        Registration pair = waitlisted(9L, 2);
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(leaving));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L))
                .thenReturn(List.of(bigParty, pair));
        event.setCapacity(2);   // only the two freed seats exist

        service.cancelRegistration(100L, attendeeActor);

        assertEquals(RegistrationStatus.WAITLISTED, bigParty.getStatus());
        assertEquals(RegistrationStatus.CONFIRMED, pair.getStatus());
        assertEquals(2, event.getSeatsTaken());
    }

    @Test
    void promotionNeverPushesSeatsTakenPastCapacity() {
        event.setCapacity(4);
        event.setSeatsTaken(4);
        Registration leaving = confirmedParty(1);
        event.setSeatsTaken(4);
        Registration waiting = waitlisted(8L, 3);
        when(registrationRepository.findActiveByEventIdAndUserIdForUpdate(100L, 2L))
                .thenReturn(Optional.of(leaving));
        when(registrationRepository.findWaitlistOrderedForUpdate(100L))
                .thenReturn(List.of(waiting));

        service.cancelRegistration(100L, attendeeActor);

        // One seat came back and the party needs three, so nothing moves.
        assertEquals(RegistrationStatus.WAITLISTED, waiting.getStatus());
        assertEquals(3, event.getSeatsTaken());
        assertTrue(event.getSeatsTaken() <= event.getCapacity());
    }

    // =================================================================
    // Check-in - how many of the party actually arrived
    // =================================================================

    @Test
    void checkInRecordsHowManyOfThePartyArrived() {
        Registration party = confirmedParty(4);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(party));

        RegistrationResponse response = service.checkIn(7L, 3, organizerActor);

        // Three of four. A boolean could only say "the booking attended",
        // which would overstate this event's attendance by one person.
        assertEquals(3, response.getSeatsCheckedIn());
        assertEquals(4, response.getSeats());
        assertTrue(party.isCheckedIn());
    }

    @Test
    void checkInWithNoCountMeansTheWholeParty() {
        Registration party = confirmedParty(4);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(party));

        assertEquals(4, service.checkIn(7L, organizerActor).getSeatsCheckedIn());
    }

    @Test
    void moreArrivalsThanSeatsIsRefused() {
        Registration party = confirmedParty(4);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(party));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.checkIn(7L, 6, organizerActor));

        // Otherwise attendance can exceed the seats that were sold, and
        // every attendance rate on the platform can read above 100%.
        assertTrue(ex.getMessage().contains("4 seats"));
        assertFalse(party.isCheckedIn());
    }

    @Test
    void checkingInNobodyIsRefusedRatherThanStored() {
        Registration party = confirmedParty(4);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(party));

        assertThrows(InvalidRequestException.class,
                () -> service.checkIn(7L, 0, organizerActor));
        // Absence is recorded by the booking having no check-in at all.
        // A check-in row meaning "nobody came" would make checked_in_at
        // lie about what it records.
        assertFalse(party.isCheckedIn());
    }

    @Test
    void undoingACheckInClearsTheHeadCountToo() {
        Registration party = confirmedParty(4);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(party));
        service.checkIn(7L, 3, organizerActor);

        service.undoCheckIn(7L, organizerActor);

        assertNull(party.getCheckedInAt());
        // Left behind, this figure would go on being summed by every
        // attendance report for a booking that is no longer checked in -
        // and it would violate the table's own CHECK.
        assertNull(party.getSeatsCheckedIn());
    }

    // =================================================================
    // What the page is told
    // =================================================================

    @Test
    void availabilityOffersOnlyAsManySeatsAsTheEventCanActuallySeat() {
        event.setCapacity(10);
        event.setSeatsTaken(7);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.empty());

        RegistrationAvailability availability = service.checkAvailability(100L, attendeeActor);

        assertEquals(3, availability.getSeatsRemaining());
        // Offering 1-10 on an event with three seats left invites a
        // refusal the page could have avoided.
        assertEquals(3, availability.getMaxSeatsBookable());
    }

    @Test
    void aFullEventStillOffersOneSeatSoTheWaitlistIsReachable() {
        event.setSeatsTaken(10);
        when(registrationRepository.findActiveByEventIdAndUserId(100L, 2L))
                .thenReturn(Optional.empty());

        RegistrationAvailability availability = service.checkAvailability(100L, attendeeActor);

        assertEquals(0, availability.getSeatsRemaining());
        // One option, leading to the waitlist - a real outcome, rather
        // than an empty dropdown the visitor cannot use at all.
        assertEquals(1, availability.getMaxSeatsBookable());
    }

    // =================================================================
    // Helpers
    // =================================================================

    private Registration confirmedParty(int seats) {
        Registration r = new Registration();
        r.setId(7L);
        r.setEvent(event);
        r.setUser(attendee);
        r.setSeats(seats);
        r.setStatus(RegistrationStatus.CONFIRMED);
        return r;
    }

    private Registration waitlisted(Long id, int seats) {
        Registration r = new Registration();
        r.setId(id);
        r.setEvent(event);
        r.setUser(attendee);
        r.setSeats(seats);
        r.setStatus(RegistrationStatus.WAITLISTED);
        return r;
    }
}
