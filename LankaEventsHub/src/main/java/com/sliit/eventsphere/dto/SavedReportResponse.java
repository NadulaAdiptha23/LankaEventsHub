package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.ReportType;
import com.sliit.eventsphere.entity.SavedReport;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A saved report definition as a screen or an API client sees it. */
public class SavedReportResponse {

    private Long id;
    private String title;
    private ReportType reportType;
    private String reportTypeLabel;
    private Long eventId;
    private String eventName;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static SavedReportResponse from(SavedReport r) {
        SavedReportResponse dto = new SavedReportResponse();
        dto.id = r.getId();
        dto.title = r.getTitle();
        dto.reportType = r.getReportType();
        dto.reportTypeLabel = r.getReportType().getLabel();
        dto.eventId = r.getEvent() == null ? null : r.getEvent().getId();
        dto.eventName = r.getEvent() == null ? null : r.getEvent().getName();
        dto.dateFrom = r.getDateFrom();
        dto.dateTo = r.getDateTo();
        dto.createdAt = r.getCreatedAt();
        dto.updatedAt = r.getUpdatedAt();
        return dto;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public ReportType getReportType() { return reportType; }
    public String getReportTypeLabel() { return reportTypeLabel; }
    public Long getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public LocalDate getDateFrom() { return dateFrom; }
    public LocalDate getDateTo() { return dateTo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public boolean isEventScoped() {
        return reportType != null && reportType.isEventScoped();
    }

    public boolean hasWindow() {
        return dateFrom != null || dateTo != null;
    }
}
