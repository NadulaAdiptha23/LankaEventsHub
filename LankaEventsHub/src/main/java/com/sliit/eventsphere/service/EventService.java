package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.EventCreateRequest;
import com.sliit.eventsphere.dto.EventResponse;
import com.sliit.eventsphere.dto.EventSearchCriteria;
import com.sliit.eventsphere.dto.EventUpdateRequest;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;

public interface EventService {

    /**
     * Creates a new event as a DRAFT. A draft is visible only to its
     * organizer and to Administrators, is editable in every field, and
     * cannot be booked. Nothing reaches an attendee until the organizer
     * submits it (see submitForApproval) and an Administrator approves it
     * (see approveEvent).
     *
     * The owning organizer is the acting user. An ADMIN - and only an
     * ADMIN - may create an event on behalf of another organizer by
     * setting organizerId on the request; for everyone else that field is
     * ignored, so a client cannot assign an event to an arbitrary user.
     */
    EventResponse createEvent(EventCreateRequest request, ActingUser actor);

    /**
     * Moves an event from DRAFT to PENDING_APPROVAL, putting it in the
     * Administrator's queue. Only the owning organizer or an ADMIN may do
     * this, and only from DRAFT.
     *
     * A draft still missing what an attendee would need in order to
     * decide - venue, start time, capacity, registration deadline - is
     * rejected here with every gap named at once, rather than reaching an
     * administrator half-finished.
     */
    EventResponse submitForApproval(Long eventId, ActingUser actor);

    /**
     * Admin-only: moves an event from PENDING_APPROVAL to PUBLISHED,
     * making it visible to attendees. No other transition into PUBLISHED
     * exists - a draft cannot skip the queue, and a cancelled or
     * completed event can never be re-published.
     */
    EventResponse approveEvent(Long eventId, ActingUser actor);

    /** Returns all events regardless of status (Admin view). */
    List<EventResponse> getAllEvents();

    /** Events owned by one organizer, regardless of status. */
    List<EventResponse> getEventsForOrganizer(Long organizerId);

    /** Returns only PUBLISHED events (what attendees are allowed to see). */
    List<EventResponse> getPublishedEvents();

    /**
     * Search/filter over published events. Any unset field on the
     * criteria skips that filter, so empty criteria returns every
     * published event.
     */
    List<EventResponse> searchPublishedEvents(EventSearchCriteria criteria);

    /**
     * The categories that published events actually use, for the listing
     * page's category filter. Built from the data rather than from a
     * hard-coded list, so the filter can never offer a category with
     * nothing behind it.
     */
    List<String> getPublishedCategories();

    /**
     * One entry in the homepage's featured grid: a category that really
     * exists, and how many published events sit behind it.
     *
     * @param name  the category exactly as organizers spelled it, which
     *              is also what the listing page's filter expects
     * @param count published events currently in it - never zero, because
     *              the query counts rows rather than offering options
     */
    record FeaturedCategory(String name, long count) {}

    /**
     * The busiest published categories, for the homepage's featured
     * section. Empty when the platform has no published events, which is
     * the signal for the homepage to leave the section out entirely
     * rather than draw an empty grid.
     *
     * @param limit how many to return; anything below one returns nothing
     */
    List<FeaturedCategory> getFeaturedCategories(int limit);

    /**
     * Unrestricted lookup by id, for callers that have already established
     * the user is allowed to see this event.
     *
     * Every management path does exactly that - it loads the event and
     * then calls requireManager on it. Anything reachable WITHOUT a
     * management check must use {@link #getEventForViewer} instead, or an
     * unapproved draft is one guessed id away from the public.
     */
    EventResponse getEventById(Long id);

    /**
     * The lookup behind the public detail page and GET /api/events/{id}.
     *
     * Not named "getPublishedEventById" because published is not quite the
     * test: an organizer previewing their own draft and an attendee
     * opening a booking for an event that has since been cancelled both
     * have to work. EventAccessPolicy.canView holds the actual rule.
     *
     * @param actor the signed-in user, or null for an anonymous visitor
     * @throws com.sliit.eventsphere.exception.ResourceNotFoundException
     *         if the event does not exist OR this viewer may not see it -
     *         the same answer either way, on purpose
     */
    EventResponse getEventForViewer(Long id, ActingUser actor);

    /**
     * Enforces the two-tier edit policy: once registrations exist, the
     * date, time, venue, price, event type, and capacity are locked and
     * any attempt to change them is rejected with a clear explanation.
     * Description, banner, contact details, and instructions stay editable
     * for the whole life of the event. Partial update - only fields
     * present on the request are considered.
     *
     * Only the owning organizer or an ADMIN may update an event.
     */
    EventResponse updateEventDetails(Long eventId, EventUpdateRequest request, ActingUser actor);

    /**
     * Cancels an event. Only the owning organizer or an ADMIN may do this,
     * and only from DRAFT / PENDING_APPROVAL / PUBLISHED - cancelling an
     * already cancelled or completed event is rejected rather than
     * silently re-firing the refund and notification listeners.
     */
    void cancelEvent(Long eventId, ActingUser actor);

    /**
     * Marks a PUBLISHED event as COMPLETED once it has finished. Only the
     * owning organizer or an ADMIN may do this, and only after the event's
     * end time has passed.
     */
    EventResponse completeEvent(Long eventId, ActingUser actor);

    /**
     * Throws AccessDeniedException unless the acting user owns the event
     * (or is an ADMIN). Shared by the other services that hang off an
     * event - sessions, invitations, registration lists - so the ownership
     * rule lives in exactly one place.
     */
    void requireEventManager(Long eventId, ActingUser actor);
}
