package com.sliit.eventsphere.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Function 6: Reporting & Analytics.
 *
 * Attendance is reported separately from registration, because they are
 * separate facts: {@code confirmedRegistrations} is how many people
 * booked a seat and {@code attendedRegistrations} is how many were
 * checked in at a door. The gap between them - the no-show rate - is the
 * figure an organizer plans catering and seating from, and it is the one
 * thing registration counts alone can never tell them.
 *
 * Every figure here is one the data genuinely supports. Attendance is
 * counted from registrations.checked_in_at, which is written only by a
 * real check-in; an event nobody checked in for reports zero attendance
 * rather than guessing from registrations.
 */
public class ReportSummaryResponse {

    private final long totalEvents;
    private final long publishedEvents;
    private final long cancelledEvents;
    private final long completedEvents;
    private final long pendingApprovalEvents;
    private final long confirmedRegistrations;
    private final long waitlistedRegistrations;
    private final long cancelledRegistrations;
    /** Confirmed bookings that were checked in at the door. */
    private final long attendedRegistrations;
    private final BigDecimal totalRevenue;
    private final BigDecimal totalRefunded;
    private final Map<String, Long> eventsByCategory;
    private final List<EventPopularityRow> mostPopularEvents;

    public ReportSummaryResponse(long totalEvents, long publishedEvents, long cancelledEvents,
                                  long completedEvents, long pendingApprovalEvents,
                                  long confirmedRegistrations, long waitlistedRegistrations,
                                  long cancelledRegistrations, long attendedRegistrations,
                                  BigDecimal totalRevenue,
                                  BigDecimal totalRefunded, Map<String, Long> eventsByCategory,
                                  List<EventPopularityRow> mostPopularEvents) {
        this.totalEvents = totalEvents;
        this.publishedEvents = publishedEvents;
        this.cancelledEvents = cancelledEvents;
        this.completedEvents = completedEvents;
        this.pendingApprovalEvents = pendingApprovalEvents;
        this.confirmedRegistrations = confirmedRegistrations;
        this.waitlistedRegistrations = waitlistedRegistrations;
        this.cancelledRegistrations = cancelledRegistrations;
        this.attendedRegistrations = attendedRegistrations;
        this.totalRevenue = totalRevenue;
        this.totalRefunded = totalRefunded;
        this.eventsByCategory = eventsByCategory;
        this.mostPopularEvents = mostPopularEvents;
    }

    public long getTotalEvents() { return totalEvents; }
    public long getPublishedEvents() { return publishedEvents; }
    public long getCancelledEvents() { return cancelledEvents; }
    public long getCompletedEvents() { return completedEvents; }
    public long getPendingApprovalEvents() { return pendingApprovalEvents; }
    public long getConfirmedRegistrations() { return confirmedRegistrations; }
    public long getWaitlistedRegistrations() { return waitlistedRegistrations; }
    public long getCancelledRegistrations() { return cancelledRegistrations; }

    public long getAttendedRegistrations() { return attendedRegistrations; }

    /**
     * Attendance as a percentage of confirmed bookings.
     *
     * Zero when nobody booked, rather than a division by zero or a
     * misleading 100%. Not capped at 100 by arithmetic because it cannot
     * exceed it: only a CONFIRMED registration can be checked in, which
     * the database enforces as well as the application.
     */
    public int getAttendanceRate() {
        return confirmedRegistrations <= 0
                ? 0
                : (int) Math.round((attendedRegistrations * 100.0) / confirmedRegistrations);
    }

    /** The other side of the same number: booked but did not turn up. */
    public long getNoShows() {
        return Math.max(0, confirmedRegistrations - attendedRegistrations);
    }

    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public BigDecimal getTotalRefunded() { return totalRefunded; }
    /** Net of refunds actually completed - the figure that matters for budgeting. */
    public BigDecimal getNetRevenue() { return totalRevenue.subtract(totalRefunded); }
    public Map<String, Long> getEventsByCategory() { return eventsByCategory; }
    public List<EventPopularityRow> getMostPopularEvents() { return mostPopularEvents; }

    /** One row of the "event popularity" table: an event and its confirmed headcount. */
    public static class EventPopularityRow {
        private final String eventName;
        private final String category;
        private final long confirmedRegistrations;
        private final long attended;
        private final int capacity;

        public EventPopularityRow(String eventName, String category,
                                   long confirmedRegistrations, long attended, int capacity) {
            this.eventName = eventName;
            this.category = category;
            this.confirmedRegistrations = confirmedRegistrations;
            this.attended = attended;
            this.capacity = capacity;
        }

        public String getEventName() { return eventName; }
        public String getCategory() { return category; }
        public long getConfirmedRegistrations() { return confirmedRegistrations; }
        public long getAttended() { return attended; }
        public int getCapacity() { return capacity; }

        /** Turnout for this event; 0 when nobody booked it. */
        public int getAttendanceRate() {
            return confirmedRegistrations <= 0
                    ? 0
                    : (int) Math.round((attended * 100.0) / confirmedRegistrations);
        }
        public int getFillPercent() {
            return capacity <= 0 ? 0 : (int) Math.min(100, (confirmedRegistrations * 100) / capacity);
        }
    }
}
