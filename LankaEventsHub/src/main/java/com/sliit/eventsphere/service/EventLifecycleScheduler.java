package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.NotificationChannel;
import com.sliit.eventsphere.entity.NotificationType;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.NotificationRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The two time-driven parts of the event lifecycle.
 *
 * Both existed only as intentions before this class. NotificationType
 * declared EVENT_REMINDER and nothing ever produced one, and an event
 * that had finished stayed PUBLISHED for ever unless a human remembered
 * to press "mark complete" - so a finished event went on advertising
 * itself as upcoming and, worse, went on accepting registrations right up
 * until its registration deadline.
 *
 * All three jobs are written to be safe to run repeatedly:
 *
 *   - the reminder job only notifies registrations it has not already
 *     notified at that particular lead time, checked against the
 *     notifications table itself rather than a flag column, so a restart
 *     or an overlapping run cannot produce duplicates;
 *   - the completion job only moves PUBLISHED events whose end time has
 *     passed, which is idempotent by construction;
 *   - the hold-release job only touches registrations that are still
 *     PENDING_PAYMENT past their deadline, so a hold that has already
 *     been released is never picked up twice.
 *
 * Timings are configurable so a demo does not have to wait for real
 * clock time; the defaults are sensible for a running deployment.
 */
@Service
public class EventLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventLifecycleScheduler.class);

    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final RegistrationService registrationService;

    /**
     * The lead times at which reminders go out, largest first.
     *
     * The requirement is two: a week ahead, so people can still arrange
     * to attend, and a day ahead, so they do not forget. One reminder
     * cannot do both jobs, which is why this is a list and not a number.
     */
    private final List<Long> leadTimesHours;

    public EventLifecycleScheduler(EventRepository eventRepository,
                                   RegistrationRepository registrationRepository,
                                   NotificationRepository notificationRepository,
                                   NotificationService notificationService,
                                   RegistrationService registrationService,
                                   @Value("${app.reminders.lead-times-hours:168,24}")
                                   String leadTimesHours) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
        this.registrationService = registrationService;
        this.leadTimesHours = parseLeadTimes(leadTimesHours);
    }

    /**
     * Turns the configured string into the list the sweep works from.
     *
     * Deliberately forgiving about spacing and ordering and deliberately
     * strict about everything else: a malformed entry stops startup
     * rather than silently reducing how many reminders attendees get.
     * A misconfiguration that shows up as "nobody was reminded" three
     * weeks later is the worst possible failure mode for this job.
     */
    private static List<Long> parseLeadTimes(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalArgumentException(
                    "app.reminders.lead-times-hours must list at least one lead time in hours.");
        }
        List<Long> parsed = new ArrayList<>();
        for (String piece : configured.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            long hours;
            try {
                hours = Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "app.reminders.lead-times-hours contains \"" + trimmed
                                + "\", which is not a whole number of hours.", ex);
            }
            if (hours <= 0) {
                throw new IllegalArgumentException(
                        "app.reminders.lead-times-hours contains " + hours
                                + "; a lead time must be greater than zero.");
            }
            if (!parsed.contains(hours)) {
                parsed.add(hours);
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "app.reminders.lead-times-hours must list at least one lead time in hours.");
        }
        parsed.sort(Comparator.reverseOrder());
        return List.copyOf(parsed);
    }

    /** Exposed for tests and for the startup log; never modified. */
    public List<Long> getLeadTimesHours() {
        return leadTimesHours;
    }

    /**
     * Sends a reminder to every confirmed attendee of an event that has
     * just entered one of the configured lead-time bands.
     *
     * Each lead time owns a band that stops where the next smaller one
     * begins: with 168 and 24 configured, the week-ahead reminder covers
     * events 24 to 168 hours away and the day-ahead reminder covers
     * events under 24 hours away. Without that lower bound, an event
     * three hours from starting would match both thresholds and an
     * attendee would be told "starts in 7 days" about something happening
     * this afternoon.
     *
     * Runs every 30 minutes by default. The duplicate guard is a lookup
     * against notifications already sent to that user, which is why the
     * message embeds both the event name and the lead time - together
     * they are what make one reminder distinguishable from the other
     * without adding a column.
     */
    @Scheduled(fixedDelayString = "${app.reminders.interval-ms:1800000}",
               initialDelayString = "${app.reminders.initial-delay-ms:60000}")
    @Transactional
    public void sendUpcomingEventReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Event> published = eventRepository.findByStatus(EventStatus.PUBLISHED).stream()
                .filter(e -> e.getStartDatetime() != null)
                .filter(e -> e.getStartDatetime().isAfter(now))
                .toList();

        int sent = 0;
        for (int i = 0; i < leadTimesHours.size(); i++) {
            long leadHours = leadTimesHours.get(i);
            // The band's floor is the next smaller lead time, or the
            // present moment for the smallest one.
            long floorHours = (i + 1 < leadTimesHours.size()) ? leadTimesHours.get(i + 1) : 0L;

            LocalDateTime ceiling = now.plusHours(leadHours);
            LocalDateTime floor = now.plusHours(floorHours);

            for (Event event : published) {
                LocalDateTime start = event.getStartDatetime();
                if (start.isAfter(ceiling) || !start.isAfter(floor)) {
                    continue;
                }
                sent += remind(event, leadHours);
            }
        }
        if (sent > 0) {
            log.info("Event reminder sweep: {} reminder(s) sent across lead times {}",
                    sent, leadTimesHours);
        }
    }

    /** Reminds everyone confirmed on one event at one lead time. */
    private int remind(Event event, long leadHours) {
        String marker = reminderMarker(event, leadHours);
        int sent = 0;
        List<Registration> confirmed = registrationRepository
                .findByEventIdAndStatus(event.getId(), RegistrationStatus.CONFIRMED);
        for (Registration registration : confirmed) {
            boolean already = notificationRepository
                    .existsByUserIdAndTypeAndMessage(registration.getUser().getId(),
                            NotificationType.EVENT_REMINDER, marker);
            if (already) {
                continue;
            }
            notificationService.notify(registration.getUser(),
                    NotificationType.EVENT_REMINDER,
                    NotificationChannel.IN_APP,
                    marker);
            sent++;
        }
        return sent;
    }

    /**
     * Moves a PUBLISHED event to COMPLETED once its end time has passed.
     *
     * This is the transition that makes "a completed event does not
     * accept registrations" true by itself rather than by an organizer
     * remembering. Events with no end time recorded are left alone -
     * there is nothing to compare against, and guessing would be worse
     * than leaving the organizer to close it.
     */
    @Scheduled(fixedDelayString = "${app.lifecycle.interval-ms:900000}",
               initialDelayString = "${app.lifecycle.initial-delay-ms:30000}")
    @Transactional
    public void completeFinishedEvents() {
        LocalDateTime now = LocalDateTime.now();
        List<Event> finished = eventRepository.findByStatus(EventStatus.PUBLISHED).stream()
                .filter(e -> e.getEndDatetime() != null)
                .filter(e -> e.getEndDatetime().isBefore(now))
                .toList();

        for (Event event : finished) {
            event.setStatus(EventStatus.COMPLETED);
            eventRepository.save(event);
            log.info("Event {} (\"{}\") ended at {} and was moved to COMPLETED",
                    event.getId(), event.getName(), event.getEndDatetime());
        }
    }

    /**
     * Gives back seats whose payment hold has run out.
     *
     * Runs on a short cycle by default - five minutes - because the cost
     * of being late here is a seat nobody can buy while somebody who has
     * walked away from a checkout still appears to hold it. The work
     * itself lives in Registration &amp; Booking, which owns seats and
     * the waitlist; this class only decides when it happens.
     */
    @Scheduled(fixedDelayString = "${app.bookings.hold-sweep-interval-ms:300000}",
               initialDelayString = "${app.bookings.hold-sweep-initial-delay-ms:45000}")
    public void releaseExpiredPaymentHolds() {
        registrationService.releaseExpiredPaymentHolds();
    }

    /**
     * The exact text a reminder for this event at this lead time carries.
     * Kept in one place because it is both the message the attendee reads
     * and the key the duplicate check matches on - which is why the lead
     * time has to appear in it. Two reminders with identical text would
     * be indistinguishable to the guard, and the second would never send.
     */
    private String reminderMarker(Event event, long leadHours) {
        return "Reminder: \"" + event.getName() + "\" starts in " + describeLead(leadHours)
                + " - on " + event.getStartDatetime().toLocalDate()
                + " at " + (event.getVenue() == null ? "the announced venue" : event.getVenue()) + ".";
    }

    /** "7 days", "24 hours", "1 hour" - whichever reads naturally. */
    private String describeLead(long leadHours) {
        if (leadHours % 24 == 0) {
            long days = leadHours / 24;
            return days == 1 ? "1 day" : days + " days";
        }
        return leadHours == 1 ? "1 hour" : leadHours + " hours";
    }
}
