package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.ReportSummaryResponse;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.security.ActingUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportingServiceTest {

    @Mock private EventRepository eventRepository;
    @Mock private RegistrationRepository registrationRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;

    @InjectMocks private ReportingService reportingService;

    private final ActingUser adminActor = new ActingUser(1L, UserRole.ADMIN);

    @BeforeEach
    void setUp() {
        lenient().when(eventRepository.count()).thenReturn(6L);
        lenient().when(eventRepository.countByStatus(EventStatus.PUBLISHED)).thenReturn(3L);
        lenient().when(eventRepository.countByStatus(EventStatus.CANCELLED)).thenReturn(1L);
        lenient().when(eventRepository.countByStatus(EventStatus.COMPLETED)).thenReturn(1L);
        lenient().when(eventRepository.countByStatus(EventStatus.PENDING_APPROVAL)).thenReturn(1L);
        lenient().when(eventRepository.countGroupedByCategory())
                .thenReturn(List.of(new Object[]{"Workshop", 2L}, new Object[]{"Conference", 1L}));
        lenient().when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of());
        lenient().when(registrationRepository.countByStatus(RegistrationStatus.CONFIRMED)).thenReturn(9L);
        lenient().when(registrationRepository.countByStatus(RegistrationStatus.WAITLISTED)).thenReturn(2L);
        lenient().when(registrationRepository.countByStatus(RegistrationStatus.CANCELLED)).thenReturn(1L);
        // 6 of the 9 confirmed bookings turned up: a real-looking number
        // rather than a flattering one, so the no-show figure has
        // something to be.
        lenient().when(registrationRepository.countByCheckedInAtIsNotNull()).thenReturn(6L);
        lenient().when(registrationRepository.countCheckedInGroupedByEvent()).thenReturn(List.of());
        lenient().when(paymentRepository.sumCompletedRevenue()).thenReturn(new BigDecimal("15000.00"));
        lenient().when(refundRepository.sumCompletedRefunds()).thenReturn(new BigDecimal("3000.00"));
    }

    @Test
    void summaryIsAdminOnly() {
        assertThrows(AccessDeniedException.class,
                () -> reportingService.getSummary(new ActingUser(2L, UserRole.ORGANIZER)));
        assertThrows(AccessDeniedException.class,
                () -> reportingService.getSummary(new ActingUser(3L, UserRole.ATTENDEE)));
        assertThrows(AccessDeniedException.class, () -> reportingService.getSummary(null));
    }

    @Test
    void summaryReportsTheFiguresTheDataSupports() {
        ReportSummaryResponse summary = reportingService.getSummary(adminActor);

        assertEquals(6, summary.getTotalEvents());
        assertEquals(3, summary.getPublishedEvents());
        assertEquals(1, summary.getCancelledEvents());
        assertEquals(1, summary.getCompletedEvents());
        assertEquals(1, summary.getPendingApprovalEvents());
        assertEquals(9, summary.getConfirmedRegistrations());
        assertEquals(2, summary.getWaitlistedRegistrations());
        assertEquals(1, summary.getCancelledRegistrations());
    }

    @Test
    void netRevenueSubtractsCompletedRefunds() {
        ReportSummaryResponse summary = reportingService.getSummary(adminActor);

        assertEquals(0, summary.getTotalRevenue().compareTo(new BigDecimal("15000.00")));
        assertEquals(0, summary.getTotalRefunded().compareTo(new BigDecimal("3000.00")));
        assertEquals(0, summary.getNetRevenue().compareTo(new BigDecimal("12000.00")));
    }

    @Test
    void categoryTotalsComeFromAGroupedQueryNotFromLoadingEveryEvent() {
        ReportSummaryResponse summary = reportingService.getSummary(adminActor);

        assertEquals(2L, summary.getEventsByCategory().get("Workshop"));
        assertEquals(1L, summary.getEventsByCategory().get("Conference"));
        // findAll() would pull the whole table into memory just to count.
        verify(eventRepository, never()).findAll();
    }

    @Test
    void eventPopularityRanksByConfirmedRegistrations() {
        Event small = event(1L, "Small", 10, "Workshop");
        Event big = event(2L, "Big", 100, "Conference");
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(List.of(small, big));
        when(registrationRepository.countConfirmedGroupedByEvent())
                .thenReturn(List.of(new Object[]{1L, 4L}, new Object[]{2L, 40L}));

        List<ReportSummaryResponse.EventPopularityRow> rows =
                reportingService.getSummary(adminActor).getMostPopularEvents();

        assertEquals("Big", rows.get(0).getEventName());
        assertEquals(40, rows.get(1).getFillPercent()); // "Small": 4 of 10
    }

    @Test
    void eventPopularityUsesOneGroupedQueryRatherThanOnePerEvent() {
        // Regression: the previous version issued a count query inside the
        // loop, so the number of queries grew with the number of published
        // events rather than staying fixed.
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(
                List.of(event(1L, "One", 10, "Workshop"),
                        event(2L, "Two", 10, "Workshop"),
                        event(3L, "Three", 10, "Workshop")));
        // List.<Object[]>of(...) is explicit on purpose: with a single
        // Object[] argument, List.of(T...) treats the array AS the varargs
        // array and infers List<Object>, which does not match the
        // List<Object[]> the repository returns. Written without the
        // witness this file does not compile.
        when(registrationRepository.countConfirmedGroupedByEvent())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 2L}));

        reportingService.getSummary(adminActor);

        verify(registrationRepository, times(1)).countConfirmedGroupedByEvent();
        verify(registrationRepository, never())
                .countByEventIdAndStatus(any(), any());
    }

    @Test
    void anEventWithNoRegistrationsStillAppearsWithZero() {
        // It is absent from a grouped count, which is exactly how a naive
        // map lookup would drop it off the dashboard entirely.
        when(eventRepository.findByStatus(EventStatus.PUBLISHED))
                .thenReturn(List.of(event(7L, "Nobody came", 50, "Workshop")));
        when(registrationRepository.countConfirmedGroupedByEvent()).thenReturn(List.of());

        List<ReportSummaryResponse.EventPopularityRow> rows =
                reportingService.getSummary(adminActor).getMostPopularEvents();

        assertEquals(1, rows.size());
        assertEquals(0L, rows.get(0).getConfirmedRegistrations());
        assertEquals(0, rows.get(0).getFillPercent());
    }

    // =================================================================
    // Attendance
    //
    // The figure the reporting requirement asked for and the previous
    // version could not produce. What matters most here is that it is
    // never silently substituted by a registration count - a report that
    // shows the same number twice under two names is worse than one that
    // shows it once.
    // =================================================================

    @Test
    void attendanceIsReportedAlongsideRegistrations() {
        ReportSummaryResponse summary = reportingService.getSummary(adminActor);

        assertEquals(9, summary.getConfirmedRegistrations());
        assertEquals(6, summary.getAttendedRegistrations());
    }

    @Test
    void attendanceRateAndNoShowsAreTheTwoSidesOfTheSameNumber() {
        ReportSummaryResponse summary = reportingService.getSummary(adminActor);

        assertEquals(67, summary.getAttendanceRate());   // 6 of 9, rounded
        assertEquals(3, summary.getNoShows());
    }

    @Test
    void attendanceComesFromCheckInsNotFromRegistrationCounts() {
        // If someone ever "simplifies" attendance into a registration
        // count, this is what notices.
        reportingService.getSummary(adminActor);

        verify(registrationRepository).countByCheckedInAtIsNotNull();
    }

    @Test
    void attendanceRateIsZeroRatherThanADivisionByZeroWhenNobodyBooked() {
        when(registrationRepository.countByStatus(RegistrationStatus.CONFIRMED)).thenReturn(0L);
        when(registrationRepository.countByCheckedInAtIsNotNull()).thenReturn(0L);

        ReportSummaryResponse summary = reportingService.getSummary(adminActor);

        assertEquals(0, summary.getAttendanceRate());
        assertEquals(0, summary.getNoShows());
    }

    @Test
    void perEventAttendanceUsesOneGroupedQueryRatherThanOnePerEvent() {
        when(eventRepository.findByStatus(EventStatus.PUBLISHED)).thenReturn(
                List.of(event(1L, "One", 10, "Workshop"),
                        event(2L, "Two", 10, "Workshop"),
                        event(3L, "Three", 10, "Workshop")));
        when(registrationRepository.countCheckedInGroupedByEvent())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 2L}));

        reportingService.getSummary(adminActor);

        verify(registrationRepository, times(1)).countCheckedInGroupedByEvent();
        verify(registrationRepository, never())
                .countByEventIdAndCheckedInAtIsNotNull(any());
    }

    @Test
    void perEventTurnoutIsReportedNextToTheHeadcount() {
        when(eventRepository.findByStatus(EventStatus.PUBLISHED))
                .thenReturn(List.of(event(1L, "Workshop", 10, "Workshop")));
        when(registrationRepository.countConfirmedGroupedByEvent())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 8L}));
        when(registrationRepository.countCheckedInGroupedByEvent())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 6L}));

        ReportSummaryResponse.EventPopularityRow row =
                reportingService.getSummary(adminActor).getMostPopularEvents().get(0);

        assertEquals(8L, row.getConfirmedRegistrations());
        assertEquals(6L, row.getAttended());
        assertEquals(75, row.getAttendanceRate());
    }

    @Test
    void anEventNobodyWasCheckedInForReportsZeroNotABlank() {
        when(eventRepository.findByStatus(EventStatus.PUBLISHED))
                .thenReturn(List.of(event(1L, "Workshop", 10, "Workshop")));
        when(registrationRepository.countConfirmedGroupedByEvent())
                .thenReturn(List.<Object[]>of(new Object[]{1L, 8L}));
        when(registrationRepository.countCheckedInGroupedByEvent()).thenReturn(List.of());

        ReportSummaryResponse.EventPopularityRow row =
                reportingService.getSummary(adminActor).getMostPopularEvents().get(0);

        assertEquals(0L, row.getAttended());
        assertEquals(0, row.getAttendanceRate());
    }

    private Event event(Long id, String name, int capacity, String category) {
        Event event = new Event();
        event.setId(id);
        event.setName(name);
        event.setCapacity(capacity);
        event.setCategory(category);
        return event;
    }
}
