package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backs MY_PAYMENT_HISTORY, EVENT_REVENUE and REVENUE_AND_REFUNDS.
 *
 * Refunds are scoped to the exact same eventId/userId/from/to window as
 * the revenue they are subtracted from - see RefundRepository's own
 * documentation for why that matters: a "Net" figure that subtracted
 * every refund ever issued, regardless of the report's date window, used
 * to be a real bug here.
 */
@Component
public class MoneyFiguresGenerator extends AbstractReportGenerator {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public MoneyFiguresGenerator(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        Long eventId = context.eventId();
        Long userId = context.userId();
        var from = context.from();
        var to = context.to();

        Object[] row = paymentRepository.completedCountAndSumScoped(eventId, userId, from, to).get(0);
        long payments = ((Number) row[0]).longValue();
        BigDecimal revenue = (BigDecimal) row[1];
        BigDecimal refunded = refundRepository.sumCompletedRefundsScoped(eventId, userId, from, to);

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Completed payments", payments,
                        "pending and failed attempts excluded"),
                new ReportResultResponse.Figure("Gross", money(revenue), "sum of what was charged"),
                new ReportResultResponse.Figure("Refunded", money(refunded),
                        "completed refunds in the same window"),
                new ReportResultResponse.Figure("Net", money(revenue.subtract(refunded)),
                        "gross less refunded")
        ));
    }
}
