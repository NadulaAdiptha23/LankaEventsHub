package com.sliit.eventsphere.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The figures a saved report produces when it is opened.
 *
 * This object is built, read once and thrown away - it is never persisted,
 * and there is no table it could be persisted into. That is the whole
 * point of the split: the definition is stored, the answer is computed.
 * Open the same saved report tomorrow and the numbers will be tomorrow's.
 *
 * {@code generatedAt} is on the screen for the same reason. A figure with
 * no timestamp invites somebody to screenshot it and quote it a month
 * later as though it were still true.
 */
public class ReportResultResponse {

    private final String title;
    private final String typeLabel;
    private final String scope;
    private final String window;
    private final LocalDateTime generatedAt;
    private final List<Figure> figures;
    private final List<Row> rows;
    private final String rowsHeading;

    public ReportResultResponse(String title, String typeLabel, String scope, String window,
                                 LocalDateTime generatedAt, List<Figure> figures,
                                 String rowsHeading, List<Row> rows) {
        this.title = title;
        this.typeLabel = typeLabel;
        this.scope = scope;
        this.window = window;
        this.generatedAt = generatedAt;
        this.figures = List.copyOf(figures);
        this.rowsHeading = rowsHeading;
        this.rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public String getTitle() { return title; }
    public String getTypeLabel() { return typeLabel; }
    public String getScope() { return scope; }
    public String getWindow() { return window; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public List<Figure> getFigures() { return figures; }
    public List<Row> getRows() { return rows; }
    public String getRowsHeading() { return rowsHeading; }
    public boolean isHasRows() { return !rows.isEmpty(); }

    /**
     * One headline number.
     *
     * {@code hint} exists because a number without its definition is the
     * easiest thing in a report to misread - "attendance rate 62%" means
     * nothing until it says "of confirmed registrations".
     */
    public record Figure(String label, String value, String hint) {
        public static Figure of(String label, long value, String hint) {
            return new Figure(label, String.valueOf(value), hint);
        }
    }

    /** One line in a breakdown table, e.g. a category or an event. */
    public record Row(String label, String value, String secondary) {
    }
}
