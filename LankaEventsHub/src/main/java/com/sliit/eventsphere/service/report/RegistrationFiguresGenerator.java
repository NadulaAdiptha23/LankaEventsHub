package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Backs ReportType.MY_EVENT_ACTIVITY (an attendee's own registrations)
 * and ReportType.REGISTRATION_ANALYTICS (platform-wide) - the same
 * figures, scoped differently by whatever eventId/userId
 * SavedReportService already resolved into the context.
 */
@Component
public class RegistrationFiguresGenerator extends AbstractReportGenerator {

    private final RegistrationRepository registrationRepository;

    public RegistrationFiguresGenerator(RegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        Map<RegistrationStatus, Long> counts = RegistrationStatusCounts.of(registrationRepository,
                context.eventId(), context.userId(), context.from(), context.to());
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Total registrations", total, "every status"),
                ReportResultResponse.Figure.of("Confirmed",
                        counts.getOrDefault(RegistrationStatus.CONFIRMED, 0L), "holding a seat"),
                ReportResultResponse.Figure.of("Awaiting payment",
                        counts.getOrDefault(RegistrationStatus.PENDING_PAYMENT, 0L),
                        "seat held, not yet paid"),
                ReportResultResponse.Figure.of("Waitlisted",
                        counts.getOrDefault(RegistrationStatus.WAITLISTED, 0L), "no seat consumed"),
                ReportResultResponse.Figure.of("Cancelled",
                        counts.getOrDefault(RegistrationStatus.CANCELLED, 0L), "kept as history")
        ));
    }
}
