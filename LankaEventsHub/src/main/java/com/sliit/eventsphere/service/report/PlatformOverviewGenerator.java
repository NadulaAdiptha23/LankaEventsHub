package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backs PLATFORM_OVERVIEW - a current-state snapshot, not an
 * activity-in-a-period report. Deliberately ignores
 * context.eventId()/userId()/from()/to(): ReportType.isDateAware() is
 * false for this type specifically because there is no single "when did
 * this happen" column across events, published counts and platform
 * revenue to filter by - see the comment on that flag before adding one.
 */
@Component
public class PlatformOverviewGenerator extends AbstractReportGenerator {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;

    public PlatformOverviewGenerator(PaymentRepository paymentRepository, RefundRepository refundRepository,
                                      EventRepository eventRepository, RegistrationRepository registrationRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        Object[] row = paymentRepository.completedCountAndSumScoped(null, null, null, null).get(0);
        BigDecimal revenue = (BigDecimal) row[1];
        BigDecimal refunded = refundRepository.sumCompletedRefundsScoped(null, null, null, null);

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Events", eventRepository.count(), "all statuses"),
                ReportResultResponse.Figure.of("Published",
                        eventRepository.countByStatus(EventStatus.PUBLISHED), "open to attendees"),
                ReportResultResponse.Figure.of("Awaiting approval",
                        eventRepository.countByStatus(EventStatus.PENDING_APPROVAL), "in your queue"),
                ReportResultResponse.Figure.of("Confirmed registrations",
                        registrationRepository.countByStatus(RegistrationStatus.CONFIRMED), "platform-wide"),
                ReportResultResponse.Figure.of("Attended",
                        registrationRepository.countByCheckedInAtIsNotNull(), "checked in at a door"),
                new ReportResultResponse.Figure("Net revenue", money(revenue.subtract(refunded)),
                        "gross less completed refunds")
        ));
    }
}
