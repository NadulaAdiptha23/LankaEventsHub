package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Backs EVENT_WAITLIST_DEMAND - how much demand an event turned away. */
@Component
public class WaitlistDemandGenerator extends AbstractReportGenerator {

    private final RegistrationRepository registrationRepository;

    public WaitlistDemandGenerator(RegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        Event event = context.report().getEvent();
        Map<RegistrationStatus, Long> counts = RegistrationStatusCounts.of(registrationRepository,
                context.eventId(), null, context.from(), context.to());
        long waitlisted = counts.getOrDefault(RegistrationStatus.WAITLISTED, 0L);
        int capacity = event.getCapacity() == null ? 0 : event.getCapacity();

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Waitlisted", waitlisted, "wanted a seat and missed out"),
                ReportResultResponse.Figure.of("Capacity", capacity, "seats on offer"),
                new ReportResultResponse.Figure("Unmet demand", percentage(waitlisted, capacity),
                        "waitlist as a share of capacity"),
                ReportResultResponse.Figure.of("Confirmed",
                        counts.getOrDefault(RegistrationStatus.CONFIRMED, 0L), "for comparison")
        ));
    }
}
