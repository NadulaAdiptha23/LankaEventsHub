package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.BookingRow;
import com.sliit.eventsphere.dto.BookingSearchCriteria;
import com.sliit.eventsphere.dto.RegistrationAvailability;
import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.entity.Registration;
import com.sliit.eventsphere.security.ActingUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface RegistrationService {

    /**
     * Registers the ACTING USER for an event - never a user id supplied by
     * the caller, so nobody can register somebody else. If a seat is
     * available the registration is CONFIRMED immediately; if the event is
     * full it is WAITLISTED instead. Concurrency-safe: two simultaneous
     * calls for the last seat cannot both succeed.
     */
    RegistrationResponse register(Long eventId, ActingUser actor);

    /**
     * Registers the acting user for a party of {@code seats} people.
     *
     * One row, one party. The whole booking is seated or the whole booking
     * waits - splitting a group of four into three seats and one waitlist
     * place would separate people who arrived together and leave nothing
     * in the data recording that they belong to each other.
     *
     * {@code seats} must be between 1 and the per-booking maximum, which
     * is the same number the database CHECK enforces, so a request that
     * gets past this method cannot then fail on insert. Anything outside
     * the range is an InvalidRequestException, never a silent clamp - an
     * attendee who asked for 40 seats and quietly got 10 would find out
     * at the door.
     */
    RegistrationResponse register(Long eventId, int seats, ActingUser actor);

    /**
     * Changes the number of seats on the acting user's own booking.
     * Allowed only while no payment has completed for it yet -
     * PENDING_PAYMENT, or CONFIRMED on a free event - because a completed
     * payment was for a specific amount and seat count that this method
     * has no refund/re-charge flow to reconcile against. Growing the
     * booking is subject to the same capacity check as a new
     * registration; shrinking it releases the difference and can promote
     * the next WAITLISTED registration, the same as a cancellation would.
     */
    RegistrationResponse updateBooking(Long registrationId, int newSeats, ActingUser actor);

    /**
     * Cancels the acting user's own registration. A CONFIRMED place can
     * only be given up until 48 hours before the event starts; a
     * WAITLISTED place can be given up at any time. If the cancelled
     * registration was CONFIRMED, the next WAITLISTED registration (oldest
     * first) is automatically promoted inside the same transaction.
     *
     * If the place had been paid for, a PENDING refund for the full amount
     * is raised (via RegistrationCancelledEvent). Before that was wired up
     * this method silently kept the attendee's money.
     */
    void cancelRegistration(Long eventId, ActingUser actor);

    /**
     * Administrator action from the bookings screen: cancels somebody
     * else's booking, releases the seat, promotes the waitlist, tells the
     * attendee, and raises a refund if the place had been paid for.
     *
     * Two deliberate differences from {@link #cancelRegistration}. The
     * 48-hour cutoff does not apply, because staff acting on a support
     * request is exactly the escape hatch that rule's own error message
     * points people at. And the attendee IS notified, because unlike a
     * self-service cancellation they did not press the button.
     *
     * Admin-only. An organizer cannot use this even on their own event:
     * cancelling somebody's paid booking is a platform-level action.
     *
     * @param reason free text recorded in the audit log and shown to the
     *               attendee; may be blank
     */
    void cancelRegistrationAsStaff(Long registrationId, ActingUser actor, String reason);

    /**
     * The admin bookings screen: every registration on the platform with
     * its payment and refund state, filtered and paged.
     *
     * Admin-only. An organizer sees their own event's attendees through
     * {@link #getRegistrationsForEvent}; this is the cross-platform view
     * and it exposes who paid what, so it is not theirs to read.
     *
     * Assembled in three queries regardless of page size - the page of
     * registrations, then all its payments, then all their refunds - not
     * one lookup per row.
     */
    Page<BookingRow> searchBookings(BookingSearchCriteria criteria,
                                     Pageable pageable,
                                     ActingUser actor);

    /**
     * The attendee list for one event. Restricted to the owning organizer
     * and Administrators - an attendee must never be able to pull the full
     * roster of everyone else attending an event.
     */
    List<RegistrationResponse> getRegistrationsForEvent(Long eventId, ActingUser actor);

    /**
     * The same ownership check as {@link #requireOwnRegistration}, but
     * holding a write lock on the registration row.
     *
     * Used by payment, where the row is read, checked and then written
     * in one transaction. Without the lock two simultaneous payment
     * requests can both read "no completed payment exists" before either
     * writes one, and the second only fails when it hits the UNIQUE
     * constraint on payments.registration_id - a database error surfacing
     * where a clean rejection belongs.
     */
    Registration requireOwnRegistrationForUpdate(Long registrationId, ActingUser actor);

    /**
     * Persists a registration that payment has just promoted from
     * PENDING_PAYMENT to CONFIRMED.
     *
     * Payment &amp; Ticketing owns the decision - it is the module that
     * knows the money arrived - but Registration &amp; Booking owns the
     * registration row, so the write goes through here rather than
     * through a repository the payment service reaches into directly.
     * The caller has already set the status; this saves it.
     */
    void confirmPaidRegistration(Registration registration);

    /**
     * Releases every payment hold whose deadline has passed: the
     * registration is cancelled, the seat goes back to the event, and the
     * waitlist moves up.
     *
     * Called on a timer rather than lazily, because a seat that is
     * silently still held until somebody happens to look at that event is
     * a seat nobody can buy. Safe to run repeatedly - a hold that has
     * already been released is no longer PENDING_PAYMENT and is not
     * picked up again.
     *
     * @return how many holds were released
     */
    int releaseExpiredPaymentHolds();

    /**
     * Moves a held seat's deadline, for a booking that will be settled at
     * the door.
     *
     * Needed because the ordinary hold is a few minutes - long enough to
     * finish a checkout - and a seat being paid for on arrival has to
     * survive until the event. Without this the release sweep would take
     * the seat back that evening, for want of a payment nobody expected
     * yet.
     *
     * The booking stays PENDING_PAYMENT. This extends the promise; it
     * does not keep it. A null deadline means the hold never lapses,
     * which is only correct for an event with no start time.
     */
    void extendPaymentHold(Registration registration, java.time.LocalDateTime newDeadline);

    /**
     * Records that an attendee turned up. Only the owning organizer or an
     * ADMIN may do this, and only for a CONFIRMED booking on an event
     * that has not been cancelled.
     *
     * Idempotent: checking somebody in twice keeps the first time, which
     * is when they actually arrived. Pressing the button again at a busy
     * door is the most likely thing to happen to this method, and
     * failing on it would be worse than useless.
     *
     * Counts the whole party as having arrived. Use
     * {@link #checkIn(Long, Integer, ActingUser)} when only some of them
     * did.
     */
    RegistrationResponse checkIn(Long registrationId, ActingUser actor);

    /**
     * Checks in part of a party: {@code seatsArrived} of the seats this
     * booking holds.
     *
     * A booking for four routinely arrives as three, and there is no
     * honest way to record that with a boolean. Counting the whole party
     * as present would overstate attendance on every report; counting it
     * as absent would understate it.
     *
     * Must be between 1 and the number of seats booked. Null means the
     * whole party, which is what {@link #checkIn(Long, ActingUser)}
     * passes. Zero is refused rather than stored - nobody arriving is
     * recorded by leaving the booking un-checked-in, not by a check-in
     * that says nobody came.
     */
    RegistrationResponse checkIn(Long registrationId, Integer seatsArrived, ActingUser actor);

    /**
     * Reverses a check-in made by mistake. Same authorization as
     * {@link #checkIn}.
     *
     * This exists because check-in happens on a phone at a door and the
     * wrong name gets tapped. An organizer with no way back would simply
     * stop using the feature, and the attendance figures would be worse
     * for it rather than better.
     */
    RegistrationResponse undoCheckIn(Long registrationId, ActingUser actor);

    /** The acting user's own registrations. */
    List<RegistrationResponse> getRegistrationsForUser(ActingUser actor);

    /**
     * One registration, readable by its owner, the organizer who owns the
     * event, or an Administrator. Anyone else gets 403 rather than another
     * attendee's details.
     */
    RegistrationResponse getRegistrationById(Long registrationId, ActingUser actor);

    /**
     * Everything the event detail page needs to decide what to offer this
     * visitor: whether registration is open, whether they already hold a
     * place, whether a seat or a waitlist spot is what they would get, and
     * the precise reason when they cannot register. Uses the same rules
     * register() enforces, so the page can never show a button the backend
     * will refuse.
     *
     * @param actor null for an anonymous visitor
     */
    RegistrationAvailability checkAvailability(Long eventId, ActingUser actor);

    /**
     * Loads a registration and throws AccessDeniedException unless the
     * acting user owns it. Used by the payment and ticket flows, which are
     * strictly owner-only - not even the organizer may pay for, or view
     * the ticket of, somebody else's registration.
     */
    Registration requireOwnRegistration(Long registrationId, ActingUser actor);
}
