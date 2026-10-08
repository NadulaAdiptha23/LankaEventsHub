package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.*;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.ActiveSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Permanent deletion.
 *
 * This is the only part of the product whose mistakes cannot be repaired
 * from inside it, so the tests are mostly about what it REFUSES and about
 * what survives it - the audit row, the seat counts, the trail naming a
 * person whose account has gone.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurgeServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private UserRepository userRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private SavedReportRepository savedReportRepository;
    @Mock private DiscountRepository discountRepository;
    @Mock private EventSessionRepository eventSessionRepository;
    @Mock private EventInvitationRepository eventInvitationRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private ActiveSessionService activeSessionService;

    private PurgeService purgeService;

    private User admin;
    private User organizer;
    private User attendee;
    private ActingUser adminActor;
    private ActingUser organizerActor;
    private ActingUser attendeeActor;
    private Event event;

    @BeforeEach
    void setUp() {
        purgeService = new PurgeService(eventRepository, registrationRepository,
                paymentRepository, refundRepository, userRepository, announcementRepository,
                savedReportRepository, discountRepository, eventSessionRepository,
                eventInvitationRepository, notificationRepository, passwordResetTokenRepository,
                auditLogRepository, auditLogService, activeSessionService);

        admin = new User("Admin", "admin@test.com", "hash", UserRole.ADMIN);
        admin.setId(99L);
        organizer = new User("Kasun Perera", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        attendee = new User("Nadeesha Silva", "attendee@test.com", "hash", UserRole.ATTENDEE);
        attendee.setId(2L);
        adminActor = ActingUser.of(admin);
        organizerActor = ActingUser.of(organizer);
        attendeeActor = ActingUser.of(attendee);

        event = new Event();
        event.setId(100L);
        event.setName("Cloud Workshop");
        event.setOrganizer(organizer);
        event.setStatus(EventStatus.PUBLISHED);
        event.setCapacity(50);
        event.setSeatsTaken(10);

        when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(attendee));
        when(eventRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(event));
        when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
    }

    private Registration booking(Long id, RegistrationStatus status, int seats) {
        Registration r = new Registration();
        r.setId(id);
        r.setEvent(event);
        r.setUser(attendee);
        r.setStatus(status);
        r.setSeats(seats);
        return r;
    }

    private Payment paymentFor(Registration registration, Long id) {
        Payment p = new Payment();
        p.setId(id);
        p.setRegistration(registration);
        p.setFinalAmount(new BigDecimal("2500.00"));
        p.setStatus(PaymentStatus.COMPLETED);
        return p;
    }

    // =================================================================
    // Who may do it at all
    // =================================================================

    @Test
    void anAttendeeCannotPurgeAnything() {
        assertThrows(AccessDeniedException.class,
                () -> purgeService.purgeEvent(100L, attendeeActor, null));
        assertThrows(AccessDeniedException.class,
                () -> purgeService.purgeRegistration(7L, attendeeActor, null));
        assertThrows(AccessDeniedException.class,
                () -> purgeService.purgeUser(1L, attendeeActor, null));
    }

    @Test
    void anOrganizerCannotPurgeTheirOwnEvent() {
        // The widest blast radius in the system must not be reachable by
        // the person with the narrowest view of it. An organizer's event
        // carries other people's bookings and other people's money.
        assertThrows(AccessDeniedException.class,
                () -> purgeService.purgeEvent(100L, organizerActor, null));
        verify(eventRepository, never()).delete(any(Event.class));
    }

    @Test
    void aNullActorIsRefusedRatherThanTreatedAsAnAdministrator() {
        assertThrows(AccessDeniedException.class,
                () -> purgeService.purgeEvent(100L, null, null));
    }

    // =================================================================
    // Purging an event
    // =================================================================

    @Test
    void purgingAnEventDeletesEverythingUnderIt() {
        Registration booking = booking(7L, RegistrationStatus.CONFIRMED, 2);
        Payment payment = paymentFor(booking, 500L);
        Refund refund = new Refund();
        refund.setId(900L);
        refund.setPayment(payment);

        when(registrationRepository.findByEventId(100L)).thenReturn(List.of(booking));
        when(paymentRepository.findByRegistrationIdIn(List.of(7L))).thenReturn(List.of(payment));
        when(refundRepository.findByPaymentIdIn(List.of(500L))).thenReturn(List.of(refund));

        purgeService.purgeEvent(100L, adminActor, "a test event on a live platform");

        verify(refundRepository).deleteAll(List.of(refund));
        verify(paymentRepository).deleteAll(List.of(payment));
        verify(registrationRepository).deleteAll(List.of(booking));
        verify(eventRepository).delete(event);
    }

    @Test
    void anEventsChildrenGoBeforeTheEventItself() {
        Registration booking = booking(7L, RegistrationStatus.CONFIRMED, 2);
        Payment payment = paymentFor(booking, 500L);
        when(registrationRepository.findByEventId(100L)).thenReturn(List.of(booking));
        when(paymentRepository.findByRegistrationIdIn(List.of(7L))).thenReturn(List.of(payment));

        purgeService.purgeEvent(100L, adminActor, null);

        // Order matters at the database, not only in principle:
        // registrations.event_id is ON DELETE RESTRICT, so deleting the
        // event first fails outright, and payments.registration_id is
        // RESTRICT too.
        InOrder order = inOrder(paymentRepository, registrationRepository, eventRepository);
        order.verify(paymentRepository).deleteAll(List.of(payment));
        order.verify(registrationRepository).deleteAll(List.of(booking));
        order.verify(eventRepository).delete(event);
    }

    @Test
    void theAuditRowIsWrittenBeforeAnythingIsDeleted() {
        when(registrationRepository.findByEventId(100L))
                .thenReturn(List.of(booking(7L, RegistrationStatus.CONFIRMED, 2)));

        purgeService.purgeEvent(100L, adminActor, null);

        // Written afterwards it could not describe any of this, because
        // the rows it counts would no longer exist to be counted.
        InOrder order = inOrder(auditLogService, eventRepository);
        order.verify(auditLogService).log(any(), eq("PURGE_EVENT"), eq("Event"), eq(100L), any());
        order.verify(eventRepository).delete(event);
    }

    @Test
    void theAuditRowSaysWhatWentAndWhy() {
        when(registrationRepository.findByEventId(100L)).thenReturn(
                List.of(booking(7L, RegistrationStatus.CONFIRMED, 2),
                        booking(8L, RegistrationStatus.WAITLISTED, 1)));

        purgeService.purgeEvent(100L, adminActor, "duplicate of event 42");

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), eq("PURGE_EVENT"), eq("Event"), eq(100L),
                details.capture());

        String written = details.getValue();
        // The event is gone; this sentence is all that is left of it, so
        // it has to be readable by somebody who has none of the rows.
        assertTrue(written.contains("Cloud Workshop"), written);
        assertTrue(written.contains("Kasun Perera"), written);
        assertTrue(written.contains("2 registration(s)"), written);
        assertTrue(written.contains("duplicate of event 42"), written);
    }

    @Test
    void purgingAnEventThatDoesNotExistIsANotFoundNotASilentSuccess() {
        when(eventRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> purgeService.purgeEvent(404L, adminActor, null));
    }

    // =================================================================
    // Purging one booking
    // =================================================================

    @Test
    void purgingABookingGivesItsSeatsBack() {
        Registration booking = booking(7L, RegistrationStatus.CONFIRMED, 3);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(booking));

        purgeService.purgeRegistration(7L, adminActor, null);

        // Without this the event is three seats short for the rest of its
        // life, and the booking that accounted for them is gone, so
        // nothing anywhere could explain the discrepancy.
        assertEquals(7, event.getSeatsTaken());
        verify(registrationRepository).delete(booking);
    }

    @Test
    void purgingAWaitlistedBookingChangesNoSeatCount() {
        Registration waiting = booking(7L, RegistrationStatus.WAITLISTED, 3);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(waiting));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(waiting));

        purgeService.purgeRegistration(7L, adminActor, null);

        // It was holding nothing, so returning seats would MANUFACTURE
        // capacity the event does not have.
        assertEquals(10, event.getSeatsTaken());
    }

    @Test
    void purgingABookingTakesItsPaymentAndRefundWithIt() {
        Registration booking = booking(7L, RegistrationStatus.CONFIRMED, 1);
        Payment payment = paymentFor(booking, 500L);
        Refund refund = new Refund();
        refund.setId(900L);
        refund.setPayment(payment);
        refund.setAmount(new BigDecimal("2500.00"));
        refund.setStatus(RefundStatus.COMPLETED);

        when(registrationRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByRegistrationId(7L)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPaymentId(500L)).thenReturn(Optional.of(refund));

        purgeService.purgeRegistration(7L, adminActor, null);

        InOrder order = inOrder(refundRepository, paymentRepository, registrationRepository);
        order.verify(refundRepository).delete(refund);
        order.verify(paymentRepository).delete(payment);
        order.verify(registrationRepository).delete(booking);
    }

    @Test
    void purgingABookingDoesNotPromoteTheWaitlist() {
        Registration booking = booking(7L, RegistrationStatus.CONFIRMED, 2);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(booking));

        purgeService.purgeRegistration(7L, adminActor, null);

        // Promotion belongs to a real cancellation. A purge is an
        // administrator saying this row should never have existed, and
        // moving the queue on the strength of it would be acting on a
        // fiction. The seats simply become available again.
        verify(registrationRepository, never()).findWaitlistOrderedForUpdate(anyLong());
    }

    @Test
    void theBookingAuditRowNamesThePersonAndTheMoney() {
        Registration booking = booking(7L, RegistrationStatus.CONFIRMED, 2);
        Payment payment = paymentFor(booking, 500L);
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(booking));
        when(registrationRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByRegistrationId(7L)).thenReturn(Optional.of(payment));

        purgeService.purgeRegistration(7L, adminActor, null);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), eq("PURGE_REGISTRATION"), eq("Registration"),
                eq(7L), details.capture());

        String written = details.getValue();
        assertTrue(written.contains("Nadeesha Silva"), written);
        assertTrue(written.contains("2500.00"), written);
        assertTrue(written.contains("2 seat(s) returned"), written);
    }

    // =================================================================
    // Purging an account
    // =================================================================

    @Test
    void anAdministratorCannotPurgeTheirOwnAccount() {
        // The same rule that stops them suspending themselves, for the
        // same reason - except that this one cannot be undone at all.
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> purgeService.purgeUser(99L, adminActor, null));
        assertTrue(ex.getMessage().contains("your own account"));
        verify(userRepository, never()).delete(any());
    }

    @Test
    void theLastActiveAdministratorCannotBePurged() {
        User onlyOther = new User("Harini", "harini@test.com", "hash", UserRole.ADMIN);
        onlyOther.setId(98L);
        when(userRepository.findById(98L)).thenReturn(Optional.of(onlyOther));
        when(userRepository.findActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(List.of(onlyOther));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> purgeService.purgeUser(98L, adminActor, null));

        // The one guard in this system whose failure cannot be repaired
        // from inside the product. Leaving it off the irreversible path
        // would make it decorative.
        assertTrue(ex.getMessage().contains("last active administrator"));
        verify(userRepository, never()).delete(any());
    }

    @Test
    void theLastAdministratorCheckIsMadeUnderALock() {
        User onlyOther = new User("Harini", "harini@test.com", "hash", UserRole.ADMIN);
        onlyOther.setId(98L);
        when(userRepository.findById(98L)).thenReturn(Optional.of(onlyOther));
        when(userRepository.findActiveByRoleForUpdate(UserRole.ADMIN))
                .thenReturn(List.of(onlyOther, admin));

        purgeService.purgeUser(98L, adminActor, null);

        // An unlocked count lets two administrators each delete the
        // other's colleague and leave the platform with none.
        verify(userRepository).findActiveByRoleForUpdate(UserRole.ADMIN);
    }

    @Test
    void anAccountThatStillOwnsEventsCannotBePurged() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(eventRepository.countByOrganizerId(1L)).thenReturn(3L);

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> purgeService.purgeUser(1L, adminActor, null));

        // Those events carry other people's bookings and other people's
        // money. Deleting them as a side effect of removing one account
        // would be the widest blast radius in the system reached through
        // the narrowest button.
        assertTrue(ex.getMessage().contains("3 event(s)"), ex.getMessage());
        verify(userRepository, never()).delete(any());
    }

    @Test
    void theOwnedEventCheckCountsEveryStatusNotOnlyLiveOnes() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        when(eventRepository.countByOrganizerId(1L)).thenReturn(0L);

        purgeService.purgeUser(1L, adminActor, null);

        // A cancelled event still holds its registrations and its payment
        // history, and events.organizer_id is ON DELETE RESTRICT - so a
        // check that only looked at live events would pass here and then
        // fail at the database, after the rest of the purge had run.
        verify(eventRepository).countByOrganizerId(1L);
        verify(eventRepository, never()).countByOrganizerIdAndStatusIn(anyLong(), any());
    }

    @Test
    void purgingAnAccountReturnsTheSeatsItsBookingsHeld() {
        Registration held = booking(7L, RegistrationStatus.CONFIRMED, 2);
        Registration alsoHeld = booking(8L, RegistrationStatus.PENDING_PAYMENT, 1);
        Registration gone = booking(9L, RegistrationStatus.CANCELLED, 4);
        when(registrationRepository.findByUserId(2L)).thenReturn(List.of(held, alsoHeld, gone));

        purgeService.purgeUser(2L, adminActor, null);

        // Three seats back - the cancelled booking was holding none, and
        // returning its four would manufacture capacity.
        assertEquals(7, event.getSeatsTaken());
    }

    @Test
    void theAuditTrailSurvivesTheAccountItNames() {
        AuditLog row = new AuditLog();
        row.setId(1L);
        row.setUser(attendee);
        when(auditLogRepository.findByUserId(2L)).thenReturn(List.of(row));

        purgeService.purgeUser(2L, adminActor, null);

        // Kept, with the reference cleared. An audit trail that
        // disappears along with the account is not an audit trail - and
        // the details column names the person in prose, so "who did this"
        // survives.
        verify(auditLogRepository).saveAll(List.of(row));
        assertNull(row.getUser());
        verify(auditLogRepository, never()).deleteAll(any());
    }

    @Test
    void aCheckInPerformedByThisPersonSurvivesWithTheStaffReferenceCleared() {
        Registration checkedIn = booking(7L, RegistrationStatus.CONFIRMED, 1);
        checkedIn.setCheckedInAt(LocalDateTime.now().minusDays(1));
        checkedIn.setCheckedInBy(attendee);
        checkedIn.setSeatsCheckedIn(1);
        when(registrationRepository.findByCheckedInById(2L)).thenReturn(List.of(checkedIn));

        purgeService.purgeUser(2L, adminActor, null);

        // The attendance record is a fact about somebody ELSE. It must not
        // vanish because the person who took it at the door has gone.
        assertNull(checkedIn.getCheckedInBy());
        assertNotNull(checkedIn.getCheckedInAt());
        verify(registrationRepository).saveAll(List.of(checkedIn));
    }

    @Test
    void purgingAnAccountEndsItsSignedInSessions() {
        purgeService.purgeUser(2L, adminActor, null);

        // Their browser still holds a session for an account that no
        // longer exists - the difference between a clean sign-out and a
        // stream of errors on every page they open.
        verify(activeSessionService).invalidateSessionsFor(2L);
    }

    @Test
    void purgingAnAccountDeletesItsNotificationsReportsAndInvitations() {
        Notification note = new Notification();
        SavedReport report = new SavedReport();
        EventInvitation invitation = new EventInvitation();
        when(notificationRepository.findByUserId(2L)).thenReturn(List.of(note));
        when(savedReportRepository.findByOwnerId(2L)).thenReturn(List.of(report));
        when(eventInvitationRepository.findByUserId(2L)).thenReturn(List.of(invitation));

        purgeService.purgeUser(2L, adminActor, null);

        verify(notificationRepository).deleteAll(List.of(note));
        verify(savedReportRepository).deleteAll(List.of(report));
        verify(eventInvitationRepository).deleteAll(List.of(invitation));
        verify(userRepository).delete(attendee);
    }
}
