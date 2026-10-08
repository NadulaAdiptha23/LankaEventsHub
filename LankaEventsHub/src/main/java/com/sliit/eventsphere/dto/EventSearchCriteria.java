package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The filters the event listing page offers, as one object.
 *
 * A criteria object rather than an eight-argument method: the arguments
 * are all optional and several share a type, so a positional call would
 * be a row of nulls that nobody can read and that swaps two Strings
 * without the compiler noticing.
 *
 * Every field is nullable and null means "do not filter on this", so the
 * empty criteria returns every published event. That is deliberate - the
 * listing page submits one form whether or not the visitor filled
 * anything in, and a blank input must not become a filter for the empty
 * string.
 */
public class EventSearchCriteria {

    private String keyword;
    private String category;
    private String venue;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private BigDecimal maxFee;
    private EventType eventType;
    /** True = hide events with no seats left. Never null; absent means false. */
    private boolean availableOnly;

    public EventSearchCriteria() {
    }

    /** The three original filters, for callers that only need those. */
    public static EventSearchCriteria of(String keyword, String category, String venue) {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.keyword = keyword;
        criteria.category = category;
        criteria.venue = venue;
        return criteria;
    }

    /**
     * True when nothing at all was filled in, so the caller can skip the
     * filtered query entirely and take the plain published-events list.
     */
    public boolean isEmpty() {
        return blank(keyword) && blank(category) && blank(venue)
                && dateFrom == null && dateTo == null
                && maxFee == null && eventType == null && !availableOnly;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }

    public LocalDate getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDate dateFrom) { this.dateFrom = dateFrom; }

    public LocalDate getDateTo() { return dateTo; }
    public void setDateTo(LocalDate dateTo) { this.dateTo = dateTo; }

    public BigDecimal getMaxFee() { return maxFee; }
    public void setMaxFee(BigDecimal maxFee) { this.maxFee = maxFee; }

    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }

    public boolean isAvailableOnly() { return availableOnly; }
    public void setAvailableOnly(boolean availableOnly) { this.availableOnly = availableOnly; }
}
