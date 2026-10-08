package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.entity.UserRole;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The figures the public pages are allowed to quote.
 *
 * Every one of these is COUNTED FROM THE DATABASE. A marketing page that
 * says "500+ events managed" over a platform holding thirty is not a
 * design decision, it is a false statement to every visitor who reads it,
 * and it is the first thing that makes a site read as a template rather
 * than as a real company's. So the homepage asks this, and if the answer
 * is small the homepage says the small number.
 *
 * Four counts, four queries, on a page that is cached by nothing - hence
 * the single read-only transaction rather than four separate ones.
 */
@Service
public class PublicStatsService {

    private final EventRepository eventRepository;
    private final RegistrationRepository registrationRepository;
    private final UserRepository userRepository;

    public PublicStatsService(EventRepository eventRepository,
                               RegistrationRepository registrationRepository,
                               UserRepository userRepository) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.userRepository = userRepository;
    }

    /**
     * What the homepage's statistics row renders.
     *
     * @param eventsDelivered  events that have actually happened
     * @param eventsLive       events open for registration right now
     * @param attendees        confirmed bookings across the platform
     * @param organizers       active organizer accounts
     * @param attendanceRate   percentage of confirmed seats checked in at
     *                         a door, or -1 when nothing has been checked
     *                         in yet - a rate over no data is not 0%, it
     *                         is "not measurable", and the template says
     *                         so rather than printing a damning zero
     */
    public record PublicStats(long eventsDelivered, long eventsLive, long attendees,
                               long organizers, int attendanceRate) {

        /** True when there is enough history for the row to be worth showing. */
        public boolean isMeaningful() {
            return eventsDelivered + eventsLive > 0;
        }

        public boolean hasAttendanceRate() {
            return attendanceRate >= 0;
        }
    }

    @Transactional(readOnly = true)
    public PublicStats get() {
        long delivered = eventRepository.countByStatus(EventStatus.COMPLETED);
        long live = eventRepository.countByStatus(EventStatus.PUBLISHED);
        long attendees = registrationRepository.countByStatus(RegistrationStatus.CONFIRMED);
        long organizers = userRepository.countByRoleAndActiveTrue(UserRole.ORGANIZER);

        long checkedIn = registrationRepository.countByCheckedInAtIsNotNull();
        // Guarded rather than computed blindly: a platform with no
        // check-ins yet would otherwise advertise 0% attendance.
        int rate = (checkedIn > 0 && attendees > 0)
                ? (int) Math.round((checkedIn * 100.0) / attendees)
                : -1;

        return new PublicStats(delivered, live, attendees, organizers, rate);
    }
}
