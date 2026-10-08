package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.entity.SavedReport;

import java.time.LocalDateTime;

/**
 * What SavedReportService has already resolved and authorized by the
 * time a report generator is asked to calculate anything: the report
 * itself, the event/user scope (already ownership-checked), and the
 * date window (already validated as start-before-end).
 *
 * Not every generator uses every field - see the note on
 * {@link AbstractReportGenerator#calculate}. That is expected, not a
 * design smell: which fields a report type needs is exactly the
 * variation the Template Method exists to accommodate.
 */
public record ReportContext(SavedReport report, Long eventId, Long userId,
                             LocalDateTime from, LocalDateTime to) {
}
