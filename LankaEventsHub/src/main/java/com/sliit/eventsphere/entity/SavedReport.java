package com.sliit.eventsphere.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A report DEFINITION somebody saved so they can ask the same question
 * again tomorrow.
 *
 * Read the field list and notice what is missing: there is no figure
 * anywhere. No total, no count, no revenue. This row stores the QUESTION -
 * a type, an optional event, an optional date window - and nothing else.
 * Every number is computed from events, registrations and payments each
 * time the report is opened.
 *
 * That is what keeps BR-31 true while giving Reporting a table of its own.
 * Reporting still never alters platform data; it owns one table, and that
 * table holds questions. A saved report therefore cannot go stale, and
 * cannot be edited into saying something the data does not - the only
 * things editable here are which question is asked and what it is called.
 */
@Entity
@Table(name = "saved_reports")
public class SavedReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Whose report this is. Every read, update and delete is scoped to the
     * signed-in user against this column - there is no "all saved reports"
     * view, not even for an administrator.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 40)
    private ReportType reportType;

    /**
     * The event this report is about, for the EVENT_* types only.
     *
     * Required by those and forbidden for the rest - a rule the service
     * enforces, because a CHECK constraint cannot read the enum's own
     * notion of which types are event-scoped.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public SavedReport() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public void setReportType(ReportType reportType) {
        this.reportType = reportType;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** Whether a date window was set at all. */
    public boolean hasWindow() {
        return dateFrom != null || dateTo != null;
    }
}
