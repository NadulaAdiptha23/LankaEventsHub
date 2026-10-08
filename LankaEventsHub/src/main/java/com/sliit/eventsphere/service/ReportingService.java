package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.ReportSummaryResponse;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.security.ActingUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function 6: Reporting & Analytics.
 *
 * Covers the metrics the data actually supports: registrations by state,
 * attendance against those registrations, events by state and category,
 * gross and net revenue, and event popularity by confirmed headcount.
 *
 * Attendance comes from registrations.checked_in_at - a real check-in
 * performed by an organizer at a door - and never from registration
 * counts. Reporting the one as the other would be reporting the same
 * number twice under two names.
 *
 * The counts are computed by the database (count/sum/group by) rather
 * than by streaming every row into Java, which is what the previous
 * version did for the category breakdown and the published-event count.
 */
@Service
public class ReportingService {

    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public ReportingService(EventRepository eventRepository,
                             RegistrationRepository registrationRepository,
                             PaymentRepository paymentRepository,
                             RefundRepository refundRepository) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional(readOnly = true)
    public ReportSummaryResponse getSummary(ActingUser actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new AccessDeniedException("Only an Administrator can view platform reports.");
        }

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Object[] row : eventRepository.countGroupedByCategory()) {
            byCategory.put((String) row[0], ((Number) row[1]).longValue());
        }

        return new ReportSummaryResponse(
                eventRepository.count(),
                eventRepository.countByStatus(EventStatus.PUBLISHED),
                eventRepository.countByStatus(EventStatus.CANCELLED),
                eventRepository.countByStatus(EventStatus.COMPLETED),
                eventRepository.countByStatus(EventStatus.PENDING_APPROVAL),
                registrationRepository.countByStatus(RegistrationStatus.CONFIRMED),
                registrationRepository.countByStatus(RegistrationStatus.WAITLISTED),
                registrationRepository.countByStatus(RegistrationStatus.CANCELLED),
                registrationRepository.countByCheckedInAtIsNotNull(),
                paymentRepository.sumCompletedRevenue(),
                refundRepository.sumCompletedRefunds(),
                byCategory,
                topEventsByRegistrations(5)
        );
    }

    /**
     * "Event popularity" - the events with the most confirmed
     * registrations.
     *
     * Two queries in total, whatever the number of events: one for the
     * published events, one grouped count over registrations. The previous
     * version issued a separate count query inside the loop, so a platform
     * with 200 published events ran 201 queries to render one dashboard
     * panel - the classic N+1, and it grew with the data rather than with
     * the size of the list actually shown.
     *
     * Events with no confirmed registrations are absent from the grouped
     * result, so they are read back as zero rather than being dropped.
     */
    private List<ReportSummaryResponse.EventPopularityRow> topEventsByRegistrations(int limit) {
        Map<Long, Long> confirmedByEvent = new HashMap<>();
        for (Object[] row : registrationRepository.countConfirmedGroupedByEvent()) {
            confirmedByEvent.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        // A third grouped query, not a per-event one. Three queries
        // whatever the number of events; the alternative grows with the
        // platform for no benefit.
        Map<Long, Long> attendedByEvent = new HashMap<>();
        for (Object[] row : registrationRepository.countCheckedInGroupedByEvent()) {
            attendedByEvent.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }

        List<ReportSummaryResponse.EventPopularityRow> rows = new ArrayList<>();
        for (Event event : eventRepository.findByStatus(EventStatus.PUBLISHED)) {
            long confirmed = confirmedByEvent.getOrDefault(event.getId(), 0L);
            long attended = attendedByEvent.getOrDefault(event.getId(), 0L);
            rows.add(new ReportSummaryResponse.EventPopularityRow(
                    event.getName(), event.getCategory(), confirmed, attended,
                    event.getCapacity() == null ? 0 : event.getCapacity()));
        }
        rows.sort(Comparator.comparingLong(
                ReportSummaryResponse.EventPopularityRow::getConfirmedRegistrations).reversed());
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }
}
