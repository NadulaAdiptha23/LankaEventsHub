package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;

import java.util.List;

/**
 * The one thing that differs between report types: the headline figures,
 * and optionally a breakdown table underneath them. Everything else in a
 * {@link ReportResultResponse} (title, type label, scope description,
 * window description, generated-at timestamp) is assembled the same way
 * for every report, which is exactly why {@link AbstractReportGenerator}
 * builds that part once instead of every generator repeating it.
 */
public record ReportFigures(List<ReportResultResponse.Figure> figures,
                            String rowsHeading,
                            List<ReportResultResponse.Row> rows) {

    /** Most report types have no breakdown table - only figures. */
    public ReportFigures(List<ReportResultResponse.Figure> figures) {
        this(figures, null, null);
    }
}
