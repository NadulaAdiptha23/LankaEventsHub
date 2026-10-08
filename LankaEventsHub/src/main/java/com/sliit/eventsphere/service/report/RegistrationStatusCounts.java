package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.repository.RegistrationRepository;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * The one query three different report generators need
 * (RegistrationFiguresGenerator, EventPerformanceGenerator,
 * WaitlistDemandGenerator each read a different subset of the same
 * status breakdown) - kept here once rather than copied into each, the
 * same way it was one private method on SavedReportService before this
 * package existed.
 */
final class RegistrationStatusCounts {

    private RegistrationStatusCounts() {
    }

    static Map<RegistrationStatus, Long> of(RegistrationRepository registrationRepository,
                                             Long eventId, Long userId,
                                             LocalDateTime from, LocalDateTime to) {
        Map<RegistrationStatus, Long> counts = new EnumMap<>(RegistrationStatus.class);
        for (Object[] row : registrationRepository.countByStatusScoped(eventId, userId, from, to)) {
            counts.put((RegistrationStatus) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }
}
