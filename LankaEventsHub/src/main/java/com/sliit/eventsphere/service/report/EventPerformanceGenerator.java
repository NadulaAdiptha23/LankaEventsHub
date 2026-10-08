package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Backs EVENT_PERFORMANCE - one organizer's own event, fill rate and demand. */
@Component
public class EventPerformanceGenerator extends AbstractReportGenerator {

    private final RegistrationRepository registrationRepository;

    public EventPerformanceGenerator(RegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        Event event = context.report().getEvent();
        Map<RegistrationStatus, Long> counts = RegistrationStatusCounts.of(registrationRepository,
                context.eventId(), null, context.from(), context.to());
        long confirmed = counts.getOrDefault(RegistrationStatus.CONFIRMED, 0L);
        long held = confirmed + counts.getOrDefault(RegistrationStatus.PENDING_PAYMENT, 0L);
        int capacity = event.getCapacity() == null ? 0 : event.getCapacity();

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Capacity", capacity, "seats on offer"),
                ReportResultResponse.Figure.of("Seats taken", held,
                        "confirmed plus seats held for payment"),
                new ReportResultResponse.Figure("Fill rate", percentage(held, capacity),
                        "of capacity"),
                ReportResultResponse.Figure.of("Waitlisted",
                        counts.getOrDefault(RegistrationStatus.WAITLISTED, 0L), "demand beyond capacity"),
                ReportResultResponse.Figure.of("Cancelled",
                        counts.getOrDefault(RegistrationStatus.CANCELLED, 0L), "gave their place up")
        ));
    }
}
