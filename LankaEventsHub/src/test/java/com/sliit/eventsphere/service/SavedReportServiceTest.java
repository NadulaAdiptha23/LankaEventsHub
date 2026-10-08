package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.dto.SavedReportRequest;
import com.sliit.eventsphere.dto.SavedReportResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.*;
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
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Saved report definitions - the CRUD half of Function 6.
 *
 * Two families of case matter most. First, that a report type is an
 * authorization boundary rather than a dropdown: an attendee must not be
 * able to save or run a platform revenue report by posting its name.
 * Second, that nothing here ever stores a figure - the definition is
 * saved, the answer is computed on every open.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedReportServiceTest {

    @Mock private SavedReportRepository savedReportRepository;
    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private UserRepository userRepository;

    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(
            org.mockito.Mockito.mock(RegistrationRepository.class));

    private SavedReportService service;

    private User attendee;
    private User organizer;
    private User otherOrganizer;
    private User admin;

    private ActingUser attendeeActor;
    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser adminActor;

    private Event myEvent;

    @BeforeEach
    void setUp() {
        attendee = user(10L, "Nadeesha", UserRole.ATTENDEE);
        organizer = user(1L, "Kasun", UserRole.ORGANIZER);
        otherOrganizer = user(7L, "Ishara", UserRole.ORGANIZER);
        admin = user(99L, "Admin", UserRole.ADMIN);

        attendeeActor = ActingUser.of(attendee);
        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = ActingUser.of(otherOrganizer);
        adminActor = ActingUser.of(admin);

        myEvent = new Event();
        myEvent.setId(100L);
        myEvent.setName("Photography Masterclass");
        myEvent.setOrganizer(organizer);
        myEvent.setStatus(EventStatus.PUBLISHED);
        myEvent.setCapacity(50);

        lenient().when(eventRepository.findById(100L)).thenReturn(Optional.of(myEvent));
        lenient().when(userRepository.findById(10L)).thenReturn(Optional.of(attendee));
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(organizer));
        lenient().when(userRepository.findById(7L)).thenReturn(Optional.of(otherOrganizer));
        lenient().when(userRepository.findById(99L)).thenReturn(Optional.of(admin));
        lenient().when(savedReportRepository.save(any(SavedReport.class))).thenAnswer(inv -> {
            SavedReport r = inv.getArgument(0);
            if (r.getId() == null) r.setId(300L);
            return r;
        });
        lenient().when(paymentRepository.completedCountAndSumScoped(any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, BigDecimal.ZERO}));
        lenient().when(refundRepository.sumCompletedRefundsScoped(any(), any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        lenient().when(registrationRepository.countByStatusScoped(any(), any(), any(), any()))
                .thenReturn(List.of());

        // service now takes the seven Template Method generators rather
        // than the repositories directly (see AbstractReportGenerator) -
        // constructed here, by hand, from REAL generator instances built
        // on the same mocked repositories above, rather than mocking the
        // generators themselves. Mocking them would return null/empty
        // from every generate() call and silently break every assertion
        // below that checks an actual calculated figure - the point of
        // this test file is that the real calculation math is exercised,
        // not skipped.
        service = new SavedReportService(savedReportRepository, eventRepository, userRepository, accessPolicy,
                new com.sliit.eventsphere.service.report.RegistrationFiguresGenerator(registrationRepository),
                new com.sliit.eventsphere.service.report.AttendanceFiguresGenerator(registrationRepository),
                new com.sliit.eventsphere.service.report.MoneyFiguresGenerator(paymentRepository, refundRepository),
                new com.sliit.eventsphere.service.report.EventPerformanceGenerator(registrationRepository),
                new com.sliit.eventsphere.service.report.WaitlistDemandGenerator(registrationRepository),
                new com.sliit.eventsphere.service.report.PlatformOverviewGenerator(
                        paymentRepository, refundRepository, eventRepository, registrationRepository),
                new com.sliit.eventsphere.service.report.CategoryPopularityGenerator(eventRepository));
    }

    // =================================================================
    // Report type as an authorization boundary
    // =================================================================

    @Test
    void anAttendeeCannotSaveAPlatformRevenueReport() {
        // The case this class exists for. The dropdown would not have
        // offered it - but a dropdown is not a security control.
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.create(request("Takings", "REVENUE_AND_REFUNDS", null), attendeeActor));
        assertTrue(ex.getMessage().contains("not a report your account can run"));
        verify(savedReportRepository, never()).save(any());
    }

    @Test
    void anAttendeeCannotSaveAnOrganizerReportEither() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(request("Perf", "EVENT_PERFORMANCE", 100L), attendeeActor));
    }

    @Test
    void anOrganizerCannotSaveAnAdministratorReport() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(request("Overview", "PLATFORM_OVERVIEW", null), organizerActor));
    }

    @Test
    void anOrganizerMaySaveAttendeeReportsToo() {
        // They register for events like anybody else, so their own
        // activity is legitimately theirs to measure.
        assertDoesNotThrow(() ->
                service.create(request("Mine", "MY_ATTENDANCE", null), organizerActor));
    }

    @Test
    void anAdministratorMaySaveAnyType() {
        for (ReportType type : ReportType.values()) {
            assertTrue(type.isAvailableTo(UserRole.ADMIN), type + " should be open to an admin");
        }
    }

    @Test
    void theTypeListOfferedIsFilteredByRole() {
        assertEquals(3, service.getAvailableTypes(attendeeActor).size());
        assertEquals(7, service.getAvailableTypes(organizerActor).size());
        assertEquals(ReportType.values().length, service.getAvailableTypes(adminActor).size());
    }

    // =================================================================
    // Event scoping
    // =================================================================

    @Test
    void anEventReportMustNameAnEvent() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.create(request("Perf", "EVENT_PERFORMANCE", null), organizerActor));
        assertTrue(ex.getMessage().contains("choose which"));
    }

    @Test
    void anOrganizerCannotPointAnEventReportAtSomebodyElsesEvent() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(request("Perf", "EVENT_PERFORMANCE", 100L), otherOrganizerActor));
    }

    @Test
    void aNonEventReportRefusesAStrayEventId() {
        // Silently dropping it would let somebody save "Platform overview,
        // event 7", open it, and reasonably believe the platform-wide
        // figures they are reading are about event 7.
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.create(request("Overview", "PLATFORM_OVERVIEW", 100L), adminActor));
        assertTrue(ex.getMessage().contains("not about a single event"));
    }

    // =================================================================
    // Validation
    // =================================================================

    @Test
    void aTitleIsRequired() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(request("   ", "MY_ATTENDANCE", null), attendeeActor));
    }

    @Test
    void anUnknownTypeIsRefused() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(request("X", "TOTAL_WORLD_DOMINATION", null), adminActor));
    }

    @Test
    void aBackwardsDateWindowIsRefused() {
        SavedReportRequest backwards = request("Q1", "MY_ATTENDANCE", null);
        backwards.setDateFrom(LocalDate.of(2026, 3, 31));
        backwards.setDateTo(LocalDate.of(2026, 1, 1));

        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.create(backwards, attendeeActor));
        assertTrue(ex.getMessage().contains("cannot be after"));
    }

    @Test
    void aDuplicateTitleIsRefusedWithAReadableMessage() {
        when(savedReportRepository.existsByOwnerIdAndTitleIgnoreCase(10L, "My attendance"))
                .thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.create(request("My attendance", "MY_ATTENDANCE", null), attendeeActor));
        assertTrue(ex.getMessage().contains("already have a report called"));
    }

    @Test
    void thereIsACapOnHowManyReportsOnePersonCanSave() {
        when(savedReportRepository.countByOwnerId(10L))
                .thenReturn((long) SavedReportService.MAX_REPORTS_PER_USER);

        assertThrows(ConflictException.class,
                () -> service.create(request("One more", "MY_ATTENDANCE", null), attendeeActor));
    }

    // =================================================================
    // Ownership
    // =================================================================

    @Test
    void somebodyElsesReportIsReportedAsNotFoundRatherThanForbidden() {
        // "403" on id 300 confirms the report exists and is not yours,
        // which is information its owner did not offer.
        SavedReport theirs = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(theirs));

        assertThrows(ResourceNotFoundException.class, () -> service.getById(300L, organizerActor));
        assertThrows(ResourceNotFoundException.class, () -> service.delete(300L, organizerActor));
        assertThrows(ResourceNotFoundException.class, () -> service.run(300L, organizerActor));
    }

    @Test
    void anAdministratorCannotReadSomebodyElsesSavedReports() {
        // Saved questions are as personal as notifications. There is no
        // route and no repository method that would widen this.
        SavedReport theirs = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(theirs));

        assertThrows(ResourceNotFoundException.class, () -> service.getById(300L, adminActor));
    }

    @Test
    void myReportsIsScopedToMe() {
        service.getMyReports(attendeeActor);
        verify(savedReportRepository).findByOwnerIdOrderByCreatedAtDesc(10L);
    }

    // =================================================================
    // Delete
    // =================================================================

    @Test
    void deletingAReportIsARealDeleteBecauseNothingDependsOnIt() {
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        service.delete(300L, attendeeActor);

        verify(savedReportRepository).delete(mine);
    }

    // =================================================================
    // Running - figures are computed, never stored
    // =================================================================

    @Test
    void runningAReportComputesFromTheLiveTables() {
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));
        // Both halves are seat sums, not row counts: four seats confirmed,
        // three people through the door.
        when(registrationRepository.sumConfirmedSeatsScoped(isNull(), eq(10L), isNull(), isNull()))
                .thenReturn(4L);
        when(registrationRepository.sumSeatsAttendedScoped(isNull(), eq(10L), isNull(), isNull()))
                .thenReturn(3L);

        ReportResultResponse result = service.run(300L, attendeeActor);

        assertEquals("4", figure(result, "Confirmed"));
        assertEquals("3", figure(result, "Attended"));
        assertEquals("75.0%", figure(result, "Attendance rate"));
        assertEquals("1", figure(result, "No-shows"));
        assertNotNull(result.getGeneratedAt());
        // Nothing was written while producing an answer.
        verify(savedReportRepository, never()).save(any());
    }

    @Test
    void anAttendeeReportOnlyEverAsksAboutThatAttendee() {
        // The scope is what keeps "my attendance" from becoming
        // "everybody's attendance" - so the user id must reach the query.
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        service.run(300L, attendeeActor);

        verify(registrationRepository).sumConfirmedSeatsScoped(isNull(), eq(10L), isNull(), isNull());
        verify(registrationRepository).sumSeatsAttendedScoped(isNull(), eq(10L), isNull(), isNull());
    }

    @Test
    void anAdministratorReportIsNotNarrowedToOnePerson() {
        SavedReport theirs = saved(ReportType.REGISTRATION_ANALYTICS, admin);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(theirs));

        service.run(300L, adminActor);

        verify(registrationRepository).countByStatusScoped(isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void attendanceRateIsNotAnArithmeticErrorWhenNobodyRegistered() {
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));
        when(registrationRepository.countByStatusScoped(any(), any(), any(), any()))
                .thenReturn(List.of());

        ReportResultResponse result = service.run(300L, attendeeActor);

        assertEquals("n/a", figure(result, "Attendance rate"));
    }

    @Test
    void noShowsNeverGoNegative() {
        // A check-in against a registration later cancelled would
        // otherwise produce a negative count on a dashboard.
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));
        when(registrationRepository.sumConfirmedSeatsScoped(any(), any(), any(), any()))
                .thenReturn(1L);
        when(registrationRepository.sumSeatsAttendedScoped(any(), any(), any(), any())).thenReturn(5L);

        assertEquals("0", figure(service.run(300L, attendeeActor), "No-shows"));
    }

    @Test
    void theDateWindowEndsAtTheEndOfItsLastDay() {
        // An exclusive bound on the day AFTER dateTo, so a window ending
        // "31 March" includes everything that happened on the 31st rather
        // than only the midnight at its start.
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        mine.setDateFrom(LocalDate.of(2026, 3, 1));
        mine.setDateTo(LocalDate.of(2026, 3, 31));
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        service.run(300L, attendeeActor);

        verify(registrationRepository).sumConfirmedSeatsScoped(isNull(), eq(10L),
                eq(LocalDate.of(2026, 3, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 4, 1).atStartOfDay()));
    }

    @Test
    void runningRechecksTheRoleAndNotJustTheOwner() {
        // A report saved while somebody was an administrator must stop
        // answering the moment they are demoted - otherwise a saved
        // definition becomes a way to keep reading data your role no
        // longer entitles you to.
        //
        // PLATFORM_OVERVIEW deliberately, not an EVENT_* type: an
        // event-scoped report is also refused by the ownership check, so
        // it would pass this test even with the role check removed. Only a
        // report with no event can prove the role is what refused it.
        SavedReport savedWhileAdmin = saved(ReportType.PLATFORM_OVERVIEW, admin);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(savedWhileAdmin));

        ActingUser demoted = new ActingUser(99L, UserRole.ORGANIZER);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.run(300L, demoted));
        assertTrue(ex.getMessage().contains("not a report your account can run"),
                "the refusal must come from the role check, not from event ownership");
    }

    @Test
    void runningRechecksEventOwnershipToo() {
        // Ownership of the event can change after the report was saved.
        SavedReport mine = saved(ReportType.EVENT_REVENUE, organizer);
        Event nowSomebodyElses = new Event();
        nowSomebodyElses.setId(100L);
        nowSomebodyElses.setName("Photography Masterclass");
        nowSomebodyElses.setOrganizer(otherOrganizer);
        mine.setEvent(nowSomebodyElses);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        assertThrows(AccessDeniedException.class, () -> service.run(300L, organizerActor));
    }

    @Test
    void moneyFiguresExcludePendingAndFailedPayments() {
        SavedReport mine = saved(ReportType.EVENT_REVENUE, organizer);
        mine.setEvent(myEvent);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));
        when(paymentRepository.completedCountAndSumScoped(eq(100L), isNull(), isNull(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[]{3L, new BigDecimal("7500.00")}));
        when(refundRepository.sumCompletedRefundsScoped(eq(100L), isNull(), isNull(), isNull()))
                .thenReturn(new BigDecimal("2500.00"));

        ReportResultResponse result = service.run(300L, organizerActor);

        assertEquals("3", figure(result, "Completed payments"));
        assertEquals("LKR 7500.00", figure(result, "Gross"));
        assertEquals("LKR 2500.00", figure(result, "Refunded"));
        assertEquals("LKR 5000.00", figure(result, "Net"));
    }

    @Test
    void moneyFiguresScopeRefundsToTheSameDateWindowAsRevenue() {
        // Regression test: refunds used to be summed with no date filter
        // at all, so a report scoped to "1-10 September" could subtract
        // a refund from March and call the result "Net" for September.
        // Both queries must now receive the same from/to.
        SavedReport mine = saved(ReportType.EVENT_REVENUE, organizer);
        mine.setEvent(myEvent);
        mine.setDateFrom(LocalDate.of(2026, 9, 1));
        mine.setDateTo(LocalDate.of(2026, 9, 10));
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));
        when(paymentRepository.completedCountAndSumScoped(eq(100L), isNull(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, new BigDecimal("1000.00")}));
        when(refundRepository.sumCompletedRefundsScoped(eq(100L), isNull(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        service.run(300L, organizerActor);

        verify(refundRepository).sumCompletedRefundsScoped(eq(100L), isNull(),
                eq(LocalDate.of(2026, 9, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 9, 11).atStartOfDay()));
    }

    @Test
    void theResultNamesWhatItIsAboutAndWhen() {
        SavedReport mine = saved(ReportType.EVENT_PERFORMANCE, organizer);
        mine.setEvent(myEvent);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        ReportResultResponse result = service.run(300L, organizerActor);

        assertEquals("Photography Masterclass", result.getScope());
        assertEquals("All time", result.getWindow());
        assertEquals("Event performance", result.getTypeLabel());
    }

    // =================================================================
    // Update
    // =================================================================

    @Test
    void updatingChangesTheQuestionAndNothingElse() {
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        SavedReportResponse updated = service.update(300L,
                request("Renamed", "MY_PAYMENT_HISTORY", null), attendeeActor);

        assertEquals("Renamed", updated.getTitle());
        assertEquals(ReportType.MY_PAYMENT_HISTORY, updated.getReportType());
    }

    @Test
    void updatingCannotEscalateTheReportType() {
        SavedReport mine = saved(ReportType.MY_ATTENDANCE, attendee);
        when(savedReportRepository.findById(300L)).thenReturn(Optional.of(mine));

        assertThrows(AccessDeniedException.class, () -> service.update(300L,
                request("Sneaky", "REVENUE_AND_REFUNDS", null), attendeeActor));
    }

    // =================================================================
    // helpers
    // =================================================================

    private String figure(ReportResultResponse result, String label) {
        return result.getFigures().stream()
                .filter(f -> f.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no figure called " + label))
                .value();
    }

    private SavedReportRequest request(String title, String type, Long eventId) {
        SavedReportRequest req = new SavedReportRequest();
        req.setTitle(title);
        req.setReportType(type);
        req.setEventId(eventId);
        return req;
    }

    private SavedReport saved(ReportType type, User owner) {
        SavedReport report = new SavedReport();
        report.setId(300L);
        report.setOwner(owner);
        report.setTitle("A report");
        report.setReportType(type);
        return report;
    }

    private User user(Long id, String name, UserRole role) {
        User u = new User(name, name.toLowerCase() + "@test.com", "hash", role);
        u.setId(id);
        return u;
    }
}
