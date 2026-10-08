package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.dto.SavedReportRequest;
import com.sliit.eventsphere.dto.SavedReportResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.*;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import com.sliit.eventsphere.service.report.AttendanceFiguresGenerator;
import com.sliit.eventsphere.service.report.CategoryPopularityGenerator;
import com.sliit.eventsphere.service.report.EventPerformanceGenerator;
import com.sliit.eventsphere.service.report.MoneyFiguresGenerator;
import com.sliit.eventsphere.service.report.PlatformOverviewGenerator;
import com.sliit.eventsphere.service.report.RegistrationFiguresGenerator;
import com.sliit.eventsphere.service.report.ReportContext;
import com.sliit.eventsphere.service.report.WaitlistDemandGenerator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Function 6: saved report definitions, with CRUD - and the one place in
 * Reporting that writes anything.
 *
 * READ THIS BEFORE DECIDING IT CONTRADICTS BR-31.
 *
 * BR-31 says reporting may never alter event, registration or payment
 * data, and it still does not. What this service writes is the QUESTION:
 * a title, a type, optionally an event and optionally a date window. No
 * figure is ever stored. {@link #run} recomputes every number from the
 * live tables each time a report is opened, which means a saved report
 * cannot go stale and cannot be edited into saying something the data
 * does not support. The definition is CRUD; the answer is always
 * calculated.
 *
 * SCOPE IS AUTHORIZATION, NOT A FILTER.
 *
 * {@link ReportType} carries which role may use it, and the scope decides
 * whose data the figures are drawn from - an attendee's own rows, one
 * event an organizer owns, or the whole platform. Both halves are checked
 * on save AND again on every run, because a report saved while somebody
 * was an organizer must not keep answering after they have been demoted.
 */
@Service
public class SavedReportService {

    /**
     * A cap, because this table is the one users can add rows to freely
     * and nothing else would ever stop them.
     */
    static final int MAX_REPORTS_PER_USER = 30;

    private final SavedReportRepository savedReportRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final EventAccessPolicy accessPolicy;

    // The seven Template Method generators - see
    // com.sliit.eventsphere.service.report.AbstractReportGenerator. This
    // service no longer computes a single figure itself; it authorizes,
    // resolves scope, and dispatches to whichever generator a ReportType
    // maps to.
    private final RegistrationFiguresGenerator registrationFiguresGenerator;
    private final AttendanceFiguresGenerator attendanceFiguresGenerator;
    private final MoneyFiguresGenerator moneyFiguresGenerator;
    private final EventPerformanceGenerator eventPerformanceGenerator;
    private final WaitlistDemandGenerator waitlistDemandGenerator;
    private final PlatformOverviewGenerator platformOverviewGenerator;
    private final CategoryPopularityGenerator categoryPopularityGenerator;

    public SavedReportService(SavedReportRepository savedReportRepository,
                               EventRepository eventRepository,
                               UserRepository userRepository,
                               EventAccessPolicy accessPolicy,
                               RegistrationFiguresGenerator registrationFiguresGenerator,
                               AttendanceFiguresGenerator attendanceFiguresGenerator,
                               MoneyFiguresGenerator moneyFiguresGenerator,
                               EventPerformanceGenerator eventPerformanceGenerator,
                               WaitlistDemandGenerator waitlistDemandGenerator,
                               PlatformOverviewGenerator platformOverviewGenerator,
                               CategoryPopularityGenerator categoryPopularityGenerator) {
        this.savedReportRepository = savedReportRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.registrationFiguresGenerator = registrationFiguresGenerator;
        this.attendanceFiguresGenerator = attendanceFiguresGenerator;
        this.moneyFiguresGenerator = moneyFiguresGenerator;
        this.eventPerformanceGenerator = eventPerformanceGenerator;
        this.waitlistDemandGenerator = waitlistDemandGenerator;
        this.platformOverviewGenerator = platformOverviewGenerator;
        this.categoryPopularityGenerator = categoryPopularityGenerator;
    }

    // =================================================================
    // CREATE
    // =================================================================

    @Transactional
    public SavedReportResponse create(SavedReportRequest request, ActingUser actor) {
        requireSignedIn(actor);
        ReportType type = parseType(request.getReportType());
        requireTypeAvailable(type, actor);

        String title = requireTitle(request.getTitle());
        if (savedReportRepository.existsByOwnerIdAndTitleIgnoreCase(actor.id(), title)) {
            throw new ConflictException(
                    "You already have a report called \"" + title + "\". Pick another name.");
        }
        if (savedReportRepository.countByOwnerId(actor.id()) >= MAX_REPORTS_PER_USER) {
            throw new ConflictException(
                    "You have reached the limit of " + MAX_REPORTS_PER_USER
                            + " saved reports. Delete one you no longer use.");
        }

        // Resolved and authorized BEFORE the owner is loaded, so a report
        // aimed at somebody else's event is refused for that reason rather
        // than for whatever the next lookup happens to find.
        Event event = resolveEvent(type, request.getEventId(), actor);

        SavedReport report = new SavedReport();
        report.setOwner(requireUser(actor));
        report.setTitle(title);
        report.setReportType(type);
        report.setEvent(event);
        applyWindow(report, request.getDateFrom(), request.getDateTo());

        return SavedReportResponse.from(savedReportRepository.save(report));
    }

    // =================================================================
    // READ
    // =================================================================

    /**
     * Everything this user has saved.
     *
     * Scoped to the actor with no way to widen it - there is no "all saved
     * reports" view, not even for an administrator. Somebody's saved
     * questions are as personal as their notifications.
     */
    @Transactional(readOnly = true)
    public List<SavedReportResponse> getMyReports(ActingUser actor) {
        requireSignedIn(actor);
        return savedReportRepository.findByOwnerIdOrderByCreatedAtDesc(actor.id()).stream()
                .map(SavedReportResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SavedReportResponse getById(Long reportId, ActingUser actor) {
        return SavedReportResponse.from(requireOwnReport(reportId, actor));
    }

    /**
     * The report types this user is allowed to save, for the dropdown.
     *
     * The list is filtered here rather than in the template, so a role
     * that cannot use a type never sees it AND cannot post it either.
     */
    @Transactional(readOnly = true)
    public List<ReportType> getAvailableTypes(ActingUser actor) {
        requireSignedIn(actor);
        return java.util.Arrays.stream(ReportType.values())
                .filter(t -> t.isAvailableTo(actor.role()))
                .collect(Collectors.toList());
    }

    // =================================================================
    // UPDATE
    // =================================================================

    @Transactional
    public SavedReportResponse update(Long reportId, SavedReportRequest request, ActingUser actor) {
        SavedReport report = requireOwnReport(reportId, actor);

        ReportType type = parseType(request.getReportType());
        requireTypeAvailable(type, actor);

        String title = requireTitle(request.getTitle());
        if (savedReportRepository.existsByOwnerIdAndTitleIgnoreCaseAndIdNot(
                actor.id(), title, reportId)) {
            throw new ConflictException(
                    "You already have another report called \"" + title + "\".");
        }

        report.setTitle(title);
        report.setReportType(type);
        report.setEvent(resolveEvent(type, request.getEventId(), actor));
        applyWindow(report, request.getDateFrom(), request.getDateTo());

        return SavedReportResponse.from(savedReportRepository.save(report));
    }

    // =================================================================
    // DELETE
    // =================================================================

    /**
     * A real delete, and one of the very few in this system.
     *
     * Safe precisely because nothing depends on a saved report: it holds
     * no figures, nothing references it, and deleting it destroys no
     * history - the events, registrations and payments it asked about are
     * all untouched. Compare the discount delete, which is refused once a
     * payment has used it.
     */
    @Transactional
    public void delete(Long reportId, ActingUser actor) {
        SavedReport report = requireOwnReport(reportId, actor);
        savedReportRepository.delete(report);
    }

    // =================================================================
    // RUN - where every figure is computed, never stored
    // =================================================================

    @Transactional(readOnly = true)
    public ReportResultResponse run(Long reportId, ActingUser actor) {
        SavedReport report = requireOwnReport(reportId, actor);
        ReportType type = report.getReportType();

        // Re-checked on every run, not just on save. A report saved while
        // somebody was an organizer must stop answering the moment they
        // are demoted - otherwise a saved definition becomes a way to keep
        // reading data your role no longer entitles you to.
        requireTypeAvailable(type, actor);

        Long eventId = null;
        if (type.isEventScoped()) {
            Event event = report.getEvent();
            if (event == null) {
                throw new InvalidRequestException(
                        "This report is missing the event it was about. Edit it and choose one.");
            }
            // And the ownership check again, for the same reason.
            accessPolicy.requireManager(event, actor,
                    "This report is about an event you no longer manage.");
            eventId = event.getId();
        }

        Long userId = type.getMinimumRole() == UserRole.ATTENDEE ? actor.id() : null;
        LocalDateTime from = report.getDateFrom() == null ? null : report.getDateFrom().atStartOfDay();
        // Exclusive upper bound on the day AFTER dateTo, so a window
        // ending "31 March" includes everything that happened on the 31st
        // rather than only the midnight at its start.
        LocalDateTime to = report.getDateTo() == null ? null : report.getDateTo().plusDays(1).atStartOfDay();

        ReportContext context = new ReportContext(report, eventId, userId, from, to);

        // Template Method dispatch: which ReportType maps to which
        // generator is the only thing that changed here from the old
        // switch that called a private calculation method directly -
        // every generator.generate(context) below runs the exact same
        // fixed skeleton (calculate, then wrap in the response envelope).
        return switch (type) {
            case MY_EVENT_ACTIVITY, REGISTRATION_ANALYTICS -> registrationFiguresGenerator.generate(context);
            case MY_ATTENDANCE, EVENT_ATTENDANCE, ATTENDANCE_ANALYTICS -> attendanceFiguresGenerator.generate(context);
            case MY_PAYMENT_HISTORY, EVENT_REVENUE, REVENUE_AND_REFUNDS -> moneyFiguresGenerator.generate(context);
            case EVENT_PERFORMANCE -> eventPerformanceGenerator.generate(context);
            case EVENT_WAITLIST_DEMAND -> waitlistDemandGenerator.generate(context);
            case PLATFORM_OVERVIEW -> platformOverviewGenerator.generate(context);
            case CATEGORY_POPULARITY -> categoryPopularityGenerator.generate(context);
        };
    }

    // =================================================================
    // Helpers
    // =================================================================

    private ReportType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("Choose what the report should measure.");
        }
        try {
            return ReportType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("That is not a report type this system offers.");
        }
    }

    /**
     * The authorization check that makes report types mean something.
     *
     * Without it, an attendee could post REVENUE_AND_REFUNDS and read the
     * platform's takings - the dropdown would not have offered it, but a
     * dropdown is not a security control.
     */
    private void requireTypeAvailable(ReportType type, ActingUser actor) {
        if (!type.isAvailableTo(actor.role())) {
            throw new AccessDeniedException(
                    "\"" + type.getLabel() + "\" is not a report your account can run.");
        }
    }

    /**
     * Resolves and authorizes the event for the EVENT_* types, and
     * insists on its absence for every other type.
     *
     * Refusing a stray event id rather than ignoring it matters: silently
     * dropping it would let somebody save "Platform overview, event 7",
     * open it, and reasonably believe the platform-wide figures they are
     * reading are about event 7.
     */
    private Event resolveEvent(ReportType type, Long eventId, ActingUser actor) {
        if (!type.isEventScoped()) {
            if (eventId != null) {
                throw new InvalidRequestException(
                        "\"" + type.getLabel() + "\" is not about a single event, so it cannot "
                                + "have one attached.");
            }
            return null;
        }
        if (eventId == null) {
            throw new InvalidRequestException(
                    "\"" + type.getLabel() + "\" is about one event - choose which.");
        }
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        accessPolicy.requireManager(event, actor,
                "You can only report on events you manage.");
        return event;
    }

    private void applyWindow(SavedReport report, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException(
                    "The start of the date range cannot be after its end.");
        }
        // PLATFORM_OVERVIEW and CATEGORY_POPULARITY are current-state
        // snapshots with no date column to filter on - see the comment on
        // ReportType.isDateAware(). Saving a date range against one of
        // these used to be silently accepted and then silently ignored by
        // run(), which made the report screen imply a filter that was
        // never actually applied. Rejecting it here is more honest than
        // accepting a range the figures were always going to ignore.
        if (!report.getReportType().isDateAware() && (from != null || to != null)) {
            throw new InvalidRequestException(
                    "\"" + report.getReportType().getLabel()
                            + "\" is a platform-wide snapshot and does not support a date range.");
        }
        report.setDateFrom(from);
        report.setDateTo(to);
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidRequestException("Give the report a name.");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 120) {
            throw new InvalidRequestException("Keep the name to 120 characters or fewer.");
        }
        return trimmed;
    }

    /**
     * Loads a report and proves it belongs to the caller.
     *
     * Reports a report owned by somebody else as NOT FOUND rather than
     * forbidden, for the same reason a hidden event does: "403" on id 12
     * confirms that report 12 exists and that it is not yours, which is
     * information the owner did not offer.
     */
    private SavedReport requireOwnReport(Long reportId, ActingUser actor) {
        requireSignedIn(actor);
        SavedReport report = savedReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Saved report not found with id " + reportId));
        if (!actor.is(report.getOwner().getId())) {
            throw new ResourceNotFoundException("Saved report not found with id " + reportId);
        }
        return report;
    }

    private void requireSignedIn(ActingUser actor) {
        if (actor == null) {
            throw new AccessDeniedException("Sign in to use saved reports.");
        }
    }

    private User requireUser(ActingUser actor) {
        return userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + actor.id()));
    }
}
