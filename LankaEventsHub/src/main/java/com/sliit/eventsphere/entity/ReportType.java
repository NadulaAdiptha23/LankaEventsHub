package com.sliit.eventsphere.entity;

/**
 * The question a saved report asks.
 *
 * Each type carries three things the service needs and would otherwise
 * have to re-derive from a switch statement in four places: who may save
 * it, whether it is about one event, and what to call it on screen.
 *
 * Scope is the important one. A report type is not a filter applied after
 * the fact - it decides whose data the figures are drawn from:
 *
 *   ATTENDEE  - only the signed-in user's own registrations and payments
 *   ORGANIZER - one event, which the organizer must own
 *   ADMIN     - platform-wide
 *
 * So an attendee cannot save a REVENUE_AND_REFUNDS report and see the
 * platform's takings, and an organizer cannot point EVENT_REVENUE at
 * somebody else's event. Both are refused by the service, not hidden by
 * the UI.
 */
public enum ReportType {

    // ---------------- Attendee: my own activity ----------------
    MY_EVENT_ACTIVITY("My event activity", UserRole.ATTENDEE, false, true),
    MY_ATTENDANCE("My attendance", UserRole.ATTENDEE, false, true),
    MY_PAYMENT_HISTORY("My payment history", UserRole.ATTENDEE, false, true),

    // ---------------- Organizer: one event I own ----------------
    EVENT_PERFORMANCE("Event performance", UserRole.ORGANIZER, true, true),
    EVENT_ATTENDANCE("Event attendance", UserRole.ORGANIZER, true, true),
    EVENT_REVENUE("Event revenue", UserRole.ORGANIZER, true, true),
    EVENT_WAITLIST_DEMAND("Waitlist demand", UserRole.ORGANIZER, true, true),

    // ---------------- Administrator: platform-wide ----------------
    // PLATFORM_OVERVIEW and CATEGORY_POPULARITY are current-state
    // snapshots (how many events exist right now, which categories are
    // popular right now) rather than activity within a period - there is
    // no single date column on an event that "when did this happen"
    // could mean, so rather than guess, these two simply do not take a
    // date window at all. dateAware=false is enforced in
    // SavedReportService.applyWindow(), which is what stops a report of
    // one of these types from being saved with a date range the run()
    // figures would then silently ignore.
    PLATFORM_OVERVIEW("Platform overview", UserRole.ADMIN, false, false),
    REGISTRATION_ANALYTICS("Registration analytics", UserRole.ADMIN, false, true),
    ATTENDANCE_ANALYTICS("Attendance analytics", UserRole.ADMIN, false, true),
    REVENUE_AND_REFUNDS("Revenue and refunds", UserRole.ADMIN, false, true),
    CATEGORY_POPULARITY("Category popularity", UserRole.ADMIN, false, false);

    private final String label;
    private final UserRole minimumRole;
    private final boolean eventScoped;
    private final boolean dateAware;

    ReportType(String label, UserRole minimumRole, boolean eventScoped, boolean dateAware) {
        this.label = label;
        this.minimumRole = minimumRole;
        this.eventScoped = eventScoped;
        this.dateAware = dateAware;
    }

    public String getLabel() {
        return label;
    }

    /** Whether this report is about one named event. */
    public boolean isEventScoped() {
        return eventScoped;
    }

    /**
     * Whether this report's figures respect a saved date range at all.
     * false for the two platform snapshots that have no meaningful "when
     * did this happen" column to filter on - see the comment above
     * PLATFORM_OVERVIEW.
     */
    public boolean isDateAware() {
        return dateAware;
    }

    /**
     * Whether this role may save and run this type.
     *
     * Not a rank comparison, deliberately. An ADMIN may use anything, an
     * ORGANIZER may use organizer and attendee types (they register for
     * events too), and an ATTENDEE may use only their own. Writing that
     * as {@code role.ordinal() >= minimumRole.ordinal()} would make the
     * rule depend on the order the enum constants happen to be declared
     * in, which is exactly the kind of thing that breaks silently when
     * somebody adds a role in the middle.
     */
    public boolean isAvailableTo(UserRole role) {
        if (role == null) {
            return false;
        }
        return switch (minimumRole) {
            case ATTENDEE -> true;
            case ORGANIZER -> role == UserRole.ORGANIZER || role == UserRole.ADMIN;
            case ADMIN -> role == UserRole.ADMIN;
        };
    }

    /** Which role this type was designed for, for grouping on screen. */
    public UserRole getMinimumRole() {
        return minimumRole;
    }
}
