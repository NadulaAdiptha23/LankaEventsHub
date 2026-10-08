package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.entity.SavedReport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Template Method for Function 6 (Reporting &amp; Analytics).
 *
 * Every report type does the same two things in the same order: work out
 * the figures, then wrap them in the standard response envelope (title,
 * type label, a human description of the scope, a human description of
 * the date window, and a generation timestamp). Only the first step
 * differs between report types - {@link #generate} is the fixed
 * skeleton, {@link #calculate} is the one step each concrete report type
 * overrides.
 *
 * Before this existed, every report method on SavedReportService
 * (registrationFigures, moneyFigures, platformOverview, ...) ended with
 * an identical call to a private result(...) helper that built this same
 * envelope by hand - the "assemble the envelope correctly" step was
 * duplicated by convention across seven methods rather than guaranteed
 * by the shape of a class. A new report type can now only produce
 * figures; it cannot forget to wrap them, because generate() is final
 * and calculate() has nothing to do with the envelope at all.
 *
 * describeScope/describeWindow/percentage/money used to be private
 * methods on SavedReportService; they moved here because they are about
 * how to describe a report's output, which is this class's concern, not
 * SavedReportService's (CRUD, ownership, and dispatch).
 */
public abstract class AbstractReportGenerator {

    public final ReportResultResponse generate(ReportContext context) {
        ReportFigures figures = calculate(context);
        SavedReport report = context.report();
        return new ReportResultResponse(
                report.getTitle(),
                report.getReportType().getLabel(),
                describeScope(report),
                describeWindow(report),
                LocalDateTime.now(),
                figures.figures(),
                figures.rowsHeading(),
                figures.rows());
    }

    /**
     * The one step every concrete report type provides.
     *
     * Not every field on {@code context} is relevant to every generator -
     * PlatformOverviewGenerator and CategoryPopularityGenerator ignore
     * eventId/userId/from/to entirely, because a platform-wide snapshot
     * has nothing to scope by (see ReportType.isDateAware() and
     * SavedReportService.applyWindow(), which refuse to even save a date
     * range against one of these). Reading a field that was never
     * resolved for a given report type would be the mistake; ignoring
     * one that was resolved but is not needed is normal.
     */
    protected abstract ReportFigures calculate(ReportContext context);

    // ---------------- shared formatting, used by every generator ----------------

    protected static String describeScope(SavedReport report) {
        if (report.getReportType().isEventScoped()) {
            return report.getEvent().getName();
        }
        return switch (report.getReportType().getMinimumRole()) {
            case ATTENDEE -> "Your own activity";
            case ORGANIZER -> "Your events";
            case ADMIN -> "Platform-wide";
        };
    }

    protected static String describeWindow(SavedReport report) {
        if (!report.hasWindow()) {
            return "All time";
        }
        if (report.getDateFrom() == null) {
            return "Up to " + report.getDateTo();
        }
        if (report.getDateTo() == null) {
            return "From " + report.getDateFrom();
        }
        return report.getDateFrom() + " to " + report.getDateTo();
    }

    /** Guards against the divide-by-zero an empty event would otherwise cause. */
    protected static String percentage(long part, long whole) {
        if (whole <= 0) {
            return "n/a";
        }
        return BigDecimal.valueOf(part * 100.0 / whole)
                .setScale(1, RoundingMode.HALF_UP) + "%";
    }

    protected static String money(BigDecimal amount) {
        return "LKR " + (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
    }
}
