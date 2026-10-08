package com.sliit.eventsphere.service.report;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Backs MY_ATTENDANCE, EVENT_ATTENDANCE and ATTENDANCE_ANALYTICS - the
 * same shape at three different scopes (an attendee's own history, one
 * event, or the whole platform), which is exactly what eventId/userId in
 * the context already carry.
 */
@Component
public class AttendanceFiguresGenerator extends AbstractReportGenerator {

    private final RegistrationRepository registrationRepository;

    public AttendanceFiguresGenerator(RegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    @Override
    protected ReportFigures calculate(ReportContext context) {
        Long eventId = context.eventId();
        Long userId = context.userId();
        var from = context.from();
        var to = context.to();

        // Both halves counted in PEOPLE, not bookings. One registration
        // can be a party of four, so a rate built from arriving people
        // over booked rows would read as 400% for a party that turned up
        // in full.
        long confirmed = registrationRepository.sumConfirmedSeatsScoped(eventId, userId, from, to);
        long attended = registrationRepository.sumSeatsAttendedScoped(eventId, userId, from, to);
        // Never below zero, whatever the data does: a check-in recorded
        // against a registration later cancelled would otherwise show a
        // negative no-show count, which is nonsense on a dashboard.
        long noShows = Math.max(0, confirmed - attended);

        return new ReportFigures(List.of(
                ReportResultResponse.Figure.of("Confirmed", confirmed, "seats held"),
                ReportResultResponse.Figure.of("Attended", attended, "people through the door"),
                new ReportResultResponse.Figure("Attendance rate",
                        percentage(attended, confirmed), "of confirmed seats"),
                ReportResultResponse.Figure.of("No-shows", noShows,
                        "seats paid for that nobody used")
        ));
    }
}
