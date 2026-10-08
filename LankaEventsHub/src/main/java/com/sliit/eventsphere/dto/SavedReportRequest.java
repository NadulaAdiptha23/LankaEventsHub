package com.sliit.eventsphere.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * What somebody submits when saving or editing a report definition.
 *
 * Only ever the question: a title, a type, optionally an event and
 * optionally a date window. There is no field here that could carry a
 * figure, because no figure is ever stored - which is what keeps a saved
 * report from going stale and what keeps Reporting honest about never
 * altering platform data.
 *
 * ownerId is absent on purpose. The owner is the signed-in user; a
 * request body that could name one would be a way to write a report into
 * somebody else's list.
 */
public class SavedReportRequest {

    @NotBlank(message = "Give the report a name.")
    @Size(max = 120, message = "Keep the name to 120 characters or fewer.")
    private String title;

    @NotBlank(message = "Choose what the report should measure.")
    private String reportType;

    /** Required by the EVENT_* types, and rejected for every other type. */
    private Long eventId;

    private LocalDate dateFrom;
    private LocalDate dateTo;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(LocalDate dateFrom) {
        this.dateFrom = dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public void setDateTo(LocalDate dateTo) {
        this.dateTo = dateTo;
    }
}
