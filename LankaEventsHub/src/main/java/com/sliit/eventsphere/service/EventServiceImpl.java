package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.EventCreateRequest;
import com.sliit.eventsphere.dto.EventResponse;
import com.sliit.eventsphere.dto.EventSearchCriteria;
import com.sliit.eventsphere.dto.EventUpdateRequest;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.events.EventPublishedEvent;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.EventSpecifications;
import com.sliit.eventsphere.repository.RegistrationRepository;
import com.sliit.eventsphere.repository.UserRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EventServiceImpl implements EventService {

    /**
     * Application-level caps on the two free-text columns. Both are TEXT
     * in MySQL, so without these a paste of any size is accepted and only
     * discovered later.
     */
    private static final int MAX_DESCRIPTION_LENGTH = 5000;
    private static final int MAX_INSTRUCTIONS_LENGTH = 2000;

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final RegistrationRepository registrationRepository;
    private final AuditLogService auditLogService;
    private final EventAccessPolicy accessPolicy;
    private final ApplicationEventPublisher eventPublisher;

    public EventServiceImpl(EventRepository eventRepository,
                             UserRepository userRepository,
                             RegistrationRepository registrationRepository,
                             AuditLogService auditLogService,
                             EventAccessPolicy accessPolicy,
                             ApplicationEventPublisher eventPublisher) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.registrationRepository = registrationRepository;
        this.auditLogService = auditLogService;
        this.accessPolicy = accessPolicy;
        this.eventPublisher = eventPublisher;
    }

    // =================================================================
    // Create
    // =================================================================

    @Override
    @Transactional
    public EventResponse createEvent(EventCreateRequest request, ActingUser actor) {
        // The organizer is the authenticated user. organizerId on the
        // request is honoured ONLY for an ADMIN creating an event on behalf
        // of someone else - otherwise a client could post
        // {"organizerId": 999} and hand the event to an arbitrary account.
        Long organizerId = actor.id();
        if (actor.isAdmin() && request.getOrganizerId() != null) {
            organizerId = request.getOrganizerId();
        }
        final Long resolvedOrganizerId = organizerId;

        User organizer = userRepository.findById(resolvedOrganizerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Organizer not found with id " + resolvedOrganizerId));

        if (organizer.getRole() != UserRole.ORGANIZER && organizer.getRole() != UserRole.ADMIN) {
            throw new InvalidRequestException("Only users with the ORGANIZER role can own an event");
        }
        if (!organizer.isActive()) {
            throw new InvalidRequestException("The organizer account is suspended and cannot own new events");
        }

        validateEventFields(request.getName(), request.getVenue(), request.getCategory(),
                request.getContactPerson(), request.getContactDetails(), request.getBannerUrl(),
                request.getDescription(), request.getSpecialInstructions(),
                request.getEventType() == null ? EventType.PUBLIC : request.getEventType(),
                request.getCapacity(), request.getFee());
        validateSchedule(request.getStartDatetime(), request.getEndDatetime(),
                request.getRegistrationOpenDate(), request.getRegistrationDeadline());

        // Venue double-booking check: runs as a single indexed database
        // query (see EventRepository.findOverlappingEvents) rather than
        // loading every event into memory and filtering in Java.
        requireVenueIsFree(null, request.getVenue(),
                request.getStartDatetime(), request.getEndDatetime());

        Event event = new Event();
        event.setOrganizer(organizer);
        event.setName(request.getName().trim());
        event.setDescription(request.getDescription());
        event.setCategory(request.getCategory());
        event.setEventType(request.getEventType());
        // A new event is a DRAFT. Nothing is waiting on it and nobody
        // else can see it until the organizer says it is finished
        // (submitForApproval) and an administrator approves it.
        event.setStatus(EventStatus.DRAFT);
        event.setVenue(request.getVenue());
        event.setStartDatetime(request.getStartDatetime());
        event.setEndDatetime(request.getEndDatetime());
        event.setCapacity(request.getCapacity());
        event.setRegistrationOpenDate(request.getRegistrationOpenDate());
        event.setRegistrationDeadline(request.getRegistrationDeadline());
        event.setFee(request.getFee() == null ? BigDecimal.ZERO : request.getFee());
        event.setContactPerson(request.getContactPerson());
        event.setContactDetails(request.getContactDetails());
        event.setBannerUrl(request.getBannerUrl());
        event.setSpecialInstructions(request.getSpecialInstructions());

        Event saved = eventRepository.save(event);
        auditLogService.log(organizer, "CREATE_EVENT", "Event", saved.getId(),
                "Event \"" + saved.getName() + "\" saved as a draft");
        return EventResponse.from(saved);
    }

    // =================================================================
    // Status transitions
    // =================================================================

    @Override
    @Transactional
    public EventResponse submitForApproval(Long eventId, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        requireManager(event, actor);

        // STATE PATTERN: ask the current state whether this transition is
        // legal, rather than an inline "status != DRAFT" check - the enum
        // throws InvalidRequestException itself if it is not. Resolved
        // before the completeness check and applied after it, preserving
        // the original order: wrong status is reported before an
        // incomplete draft is.
        EventStatus nextStatus = event.getStatus().submitForApproval();

        // A draft is allowed to be incomplete - that is the entire point
        // of it. Submitting is the moment that stops being acceptable,
        // because an administrator is about to be asked to approve it and
        // an attendee is about to be asked to book it. So the checks that
        // create() cannot insist on are made here instead.
        List<String> missing = missingForSubmission(event);
        if (!missing.isEmpty()) {
            throw new InvalidRequestException(
                    "This draft is not ready to submit yet. Still needed: "
                            + String.join(", ", missing) + ".");
        }

        event.setStatus(nextStatus);
        Event saved = eventRepository.save(event);
        auditLogService.log(loadActor(actor), "SUBMIT_EVENT", "Event", eventId,
                "Draft submitted for approval");
        return EventResponse.from(saved);
    }

    /**
     * What a draft still lacks before anyone else should see it.
     *
     * Returned as a list rather than thrown one at a time, so an
     * organizer finds out everything that is missing in one go instead of
     * discovering it field by field.
     */
    private List<String> missingForSubmission(Event event) {
        List<String> missing = new java.util.ArrayList<>();
        if (event.getVenue() == null || event.getVenue().isBlank()) {
            missing.add("a venue");
        }
        if (event.getStartDatetime() == null) {
            missing.add("a start date and time");
        }
        if (event.getCapacity() <= 0) {
            missing.add("a capacity of at least one seat");
        }
        if (event.getRegistrationDeadline() == null) {
            missing.add("a registration deadline");
        }
        // An event whose date has already passed cannot usefully be
        // approved or booked, and a draft can easily sit long enough for
        // that to happen.
        if (event.getStartDatetime() != null
                && event.getStartDatetime().isBefore(LocalDateTime.now())) {
            missing.add("a start date in the future (this one has passed)");
        }
        return missing;
    }

    @Override
    @Transactional
    public EventResponse approveEvent(Long eventId, ActingUser actor) {
        if (!actor.isAdmin()) {
            throw new AccessDeniedException("Only an Administrator can approve an event.");
        }
        Event event = getEventOrThrow(eventId);
        // STATE PATTERN: throws if not PENDING_APPROVAL.
        event.setStatus(event.getStatus().approve());
        Event saved = eventRepository.save(event);
        auditLogService.log(loadActor(actor), "APPROVE_EVENT", "Event", eventId,
                "Event published");
        eventPublisher.publishEvent(new EventPublishedEvent(this, saved));
        return EventResponse.from(saved);
    }

    @Override
    @Transactional
    public void cancelEvent(Long eventId, ActingUser actor) {
        // Locked read, not a plain findById. Two cancellation requests
        // arriving together could otherwise both see PUBLISHED, both write
        // CANCELLED, and both publish EventCancelledEvent - producing two
        // rounds of notifications (the UNIQUE constraint on refunds would
        // have caught the duplicate refunds, but nothing caught those).
        // The second transaction now blocks here, and when it proceeds it
        // sees CANCELLED and is rejected by the status check below.
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        requireManager(event, actor);

        // STATE PATTERN: legal from DRAFT, PENDING_APPROVAL or PUBLISHED;
        // throws from COMPLETED or CANCELLED. Resolved before the seat
        // snapshot below and applied after it, same ordering reasoning as
        // submitForApproval above.
        EventStatus nextStatus = event.getStatus().cancel();

        // Snapshot everyone who still holds a place, under a row lock and
        // BEFORE anything is modified, recording the status each of them
        // held. This list is what the subscribers work from.
        List<EventCancelledEvent.AffectedRegistration> affected =
                registrationRepository.findActiveByEventIdForUpdate(eventId).stream()
                        .map(EventCancelledEvent.AffectedRegistration::of)
                        .collect(Collectors.toList());

        event.setStatus(nextStatus);

        // A cancelled event must not leave live registrations behind. Before
        // this, the database could hold event=CANCELLED with
        // registration=CONFIRMED and a payment that still looked like a
        // valid ticket. Now the seats are released and every place is
        // cancelled, so "My registrations" and the ticket page both tell
        // the attendee the truth.
        LocalDateTime cancelledAt = LocalDateTime.now();
        for (EventCancelledEvent.AffectedRegistration entry : affected) {
            Registration registration = entry.registration();
            registration.setStatus(RegistrationStatus.CANCELLED);
            registration.setCancelledAt(cancelledAt);
            registrationRepository.save(registration);
        }
        event.setSeatsTaken(0); // nobody holds a seat on a cancelled event

        Event saved = eventRepository.save(event);
        auditLogService.log(loadActor(actor), "CANCEL_EVENT", "Event", eventId,
                "Event cancelled by " + actor.role() + "; " + affected.size()
                        + " registration(s) cancelled");

        // OBSERVER PATTERN: EventServiceImpl does not know or care who is
        // listening. NotificationEventListener and RefundTriggerListener
        // both react to this independently - see the `events` and
        // `listener` packages. This keeps Function 1 (this class) fully
        // decoupled from Function 3 (Payment & Ticketing) and Function 4
        // (Notifications).
        //
        // The row lock plus the status guard above are what make firing
        // this safe to reason about: it happens at most once per event,
        // even under concurrent requests, so no subscriber can be run twice.
        eventPublisher.publishEvent(new EventCancelledEvent(this, saved, affected));
    }

    @Override
    @Transactional
    public EventResponse completeEvent(Long eventId, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        requireManager(event, actor);

        // STATE PATTERN: throws if not PUBLISHED. Resolved before the
        // finished-at-a-real-time check and applied after it.
        EventStatus nextStatus = event.getStatus().complete();
        // "Has it finished?" needs a defined finishing time. end_datetime is
        // nullable, so fall back to the start time - an event with a start
        // but no end is treated as finished once it has begun. If neither is
        // set there is no basis for the judgement at all, and silently
        // allowing completion would let an organizer close an event that has
        // not happened.
        LocalDateTime finishedAt = event.getEndDatetime() != null
                ? event.getEndDatetime()
                : event.getStartDatetime();
        if (finishedAt == null) {
            throw new InvalidRequestException(
                    "This event has no start or end date, so there is no way to tell whether it has "
                            + "finished. Set its schedule before marking it completed.");
        }
        if (finishedAt.isAfter(LocalDateTime.now())) {
            throw new InvalidRequestException("This event has not finished yet, so it cannot be marked completed");
        }

        event.setStatus(nextStatus);
        Event saved = eventRepository.save(event);
        auditLogService.log(loadActor(actor), "COMPLETE_EVENT", "Event", eventId, "Event marked completed");
        return EventResponse.from(saved);
    }

    // =================================================================
    // Reads
    // =================================================================

    @Override
    @Transactional(readOnly = true)
    public List<EventResponse> getAllEvents() {
        return eventRepository.findAll().stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventResponse> getEventsForOrganizer(Long organizerId) {
        return eventRepository.findByOrganizerId(organizerId).stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventResponse> getPublishedEvents() {
        return eventRepository.findByStatus(EventStatus.PUBLISHED).stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventResponse> searchPublishedEvents(EventSearchCriteria criteria) {
        if (criteria == null || criteria.isEmpty()) {
            // Nothing was filled in. Skip the filtered query rather than
            // running a predicate that matches everything.
            return getPublishedEvents();
        }
        return eventRepository
                .findAll(EventSpecifications.matching(EventStatus.PUBLISHED, criteria)).stream()
                .map(EventResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getPublishedCategories() {
        return eventRepository.findDistinctCategoriesByStatus(EventStatus.PUBLISHED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventService.FeaturedCategory> getFeaturedCategories(int limit) {
        if (limit < 1) {
            return List.of();
        }
        return eventRepository.countPublishedGroupedByCategory(EventStatus.PUBLISHED).stream()
                .limit(limit)
                .map(row -> new EventService.FeaturedCategory(
                        (String) row[0],
                        ((Number) row[1]).longValue()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public EventResponse getEventById(Long id) {
        return EventResponse.from(getEventOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public EventResponse getEventForViewer(Long id, ActingUser actor) {
        Event event = getEventOrThrow(id);
        accessPolicy.requireViewable(event, actor, id);
        return EventResponse.from(event);
    }

    // =================================================================
    // Update
    // =================================================================

    @Override
    @Transactional
    public EventResponse updateEventDetails(Long eventId, EventUpdateRequest request, ActingUser actor) {
        Event event = getEventOrThrow(eventId);
        requireManager(event, actor);

        if (!event.getStatus().isEditable()) {
            throw new InvalidRequestException(
                    "A " + event.getStatus() + " event can no longer be edited");
        }

        boolean hasRegistrations = registrationRepository.countActiveByEventId(eventId) > 0;

        // Which of the locked fields is this request actually trying to
        // change? Worked out BEFORE anything is written, so a rejected
        // update never half-applies. (The old code applied the freely
        // editable fields first and relied on the transaction rolling
        // back, which made the method's behaviour hard to reason about.)
        List<String> lockedChanges = lockedFieldChanges(event, request);

        // BR-05: once people have registered, the terms they signed up to
        // are fixed - for the ORGANIZER. An Administrator can still make
        // the change, which is what "requires Administrator approval"
        // means when there is a real administrator who can act.
        //
        // An earlier pass removed a message that claimed this workflow
        // existed while nothing implemented it. Removing the claim was
        // right then; the fix now is to make the claim true, not to
        // restore the wording. Every such change is audited by name (see
        // the audit entry at the end of this method), so an override is a
        // recorded act rather than a quiet one.
        if (!lockedChanges.isEmpty() && hasRegistrations && !actor.isAdmin()) {
            throw new InvalidRequestException(
                    "This event already has active registrations, so " + String.join(", ", lockedChanges)
                            + " can only be changed by an Administrator. Fields that do not affect "
                            + "what attendees signed up for (description, banner, contact details, "
                            + "special instructions) can still be edited here.");
        }

        // Validate the *resulting* event, not just the submitted fields, so
        // a partial update cannot leave an inconsistent schedule behind.
        LocalDateTime newStart = request.getStartDatetime() != null
                ? request.getStartDatetime() : event.getStartDatetime();
        LocalDateTime newEnd = request.getEndDatetime() != null
                ? request.getEndDatetime() : event.getEndDatetime();
        LocalDateTime newOpen = request.getRegistrationOpenDate() != null
                ? request.getRegistrationOpenDate() : event.getRegistrationOpenDate();
        LocalDateTime newDeadline = request.getRegistrationDeadline() != null
                ? request.getRegistrationDeadline() : event.getRegistrationDeadline();
        String newVenue = request.getVenue() != null ? request.getVenue() : event.getVenue();
        Integer newCapacity = request.getCapacity() != null ? request.getCapacity() : event.getCapacity();
        BigDecimal newFee = request.getFee() != null ? request.getFee() : event.getFee();

        validateEventFields(request.getName() != null ? request.getName() : event.getName(),
                newVenue,
                request.getCategory() != null ? request.getCategory() : event.getCategory(),
                request.getContactPerson() != null ? request.getContactPerson() : event.getContactPerson(),
                request.getContactDetails() != null ? request.getContactDetails() : event.getContactDetails(),
                request.getBannerUrl() != null ? request.getBannerUrl() : event.getBannerUrl(),
                request.getDescription() != null ? request.getDescription() : event.getDescription(),
                request.getSpecialInstructions() != null
                        ? request.getSpecialInstructions() : event.getSpecialInstructions(),
                request.getEventType() != null ? request.getEventType() : event.getEventType(),
                newCapacity, newFee);
        validateSchedule(newStart, newEnd, newOpen, newDeadline);

        if (newCapacity != null && event.getSeatsTaken() != null && newCapacity < event.getSeatsTaken()) {
            throw new InvalidRequestException(
                    "Capacity cannot be reduced below the " + event.getSeatsTaken()
                            + " seat(s) already taken");
        }

        // Venue conflicts must be re-checked on update, not only on create -
        // otherwise a venue can be double-booked by editing an event into
        // an occupied slot. The event being edited is excluded from the
        // query so it never conflicts with itself.
        requireVenueIsFree(eventId, newVenue, newStart, newEnd);

        // Freely editable fields.
        if (request.getName() != null) event.setName(request.getName().trim());
        if (request.getDescription() != null) event.setDescription(request.getDescription());
        if (request.getCategory() != null) event.setCategory(request.getCategory());
        if (request.getBannerUrl() != null) event.setBannerUrl(request.getBannerUrl());
        if (request.getContactPerson() != null) event.setContactPerson(request.getContactPerson());
        if (request.getContactDetails() != null) event.setContactDetails(request.getContactDetails());
        if (request.getSpecialInstructions() != null) event.setSpecialInstructions(request.getSpecialInstructions());

        // Locked fields - only reachable when there are no registrations.
        if (request.getStartDatetime() != null) event.setStartDatetime(request.getStartDatetime());
        if (request.getEndDatetime() != null) event.setEndDatetime(request.getEndDatetime());
        if (request.getVenue() != null) event.setVenue(request.getVenue());
        if (request.getFee() != null) event.setFee(request.getFee());
        if (request.getEventType() != null) event.setEventType(request.getEventType());
        if (request.getCapacity() != null) event.setCapacity(request.getCapacity());
        if (request.getRegistrationOpenDate() != null) event.setRegistrationOpenDate(request.getRegistrationOpenDate());
        if (request.getRegistrationDeadline() != null) event.setRegistrationDeadline(request.getRegistrationDeadline());

        Event saved = eventRepository.save(event);

        // An administrator overriding the lock is the one case here worth
        // being able to find later: it changes the terms under which
        // people already registered. "Event details updated" would bury
        // it among ordinary description edits, so it is named instead.
        boolean adminOverride = actor.isAdmin() && !lockedChanges.isEmpty() && hasRegistrations;
        String auditDetails = adminOverride
                ? "Administrator changed locked field(s) on an event with active registrations: "
                        + String.join(", ", lockedChanges)
                : "Event details updated";
        auditLogService.log(loadActor(actor), "UPDATE_EVENT", "Event", eventId, auditDetails);
        return EventResponse.from(saved);
    }

    // =================================================================
    // Authorization
    // =================================================================

    @Override
    @Transactional(readOnly = true)
    public void requireEventManager(Long eventId, ActingUser actor) {
        requireManager(getEventOrThrow(eventId), actor);
    }

    private void requireManager(Event event, ActingUser actor) {
        accessPolicy.requireManager(event, actor);
    }

    // =================================================================
    // Validation helpers
    // =================================================================

    /**
     * Server-side validation of the value-shape rules. The DTO annotations
     * cover the same ground for JSON clients, but the Thymeleaf form posts
     * plain request parameters, so the rules have to live here too - the
     * browser is never the last line of defence.
     */
    /**
     * Every field rule that must hold whatever route the data arrived by.
     *
     * The DTOs carry Bean Validation annotations, but those only fire on
     * the JSON/API path where @Valid is applied to the request body. The
     * Thymeleaf forms bind through the same service, so any rule that
     * exists only as an annotation is a rule the web forms do not have.
     * Everything here is therefore checked in the service, for both
     * routes, and the annotations are the earlier and friendlier of two
     * layers rather than the only one.
     *
     * The description and special-instructions limits are the ones most
     * easily missed: both columns are TEXT, so nothing rejects a
     * multi-megabyte paste at the database either.
     */
    private void validateEventFields(String name, String venue, String category,
                                      String contactPerson, String contactDetails,
                                      String bannerUrl, String description,
                                      String specialInstructions, EventType eventType,
                                      Integer capacity, BigDecimal fee) {
        if (name == null || name.isBlank()) {
            throw new InvalidRequestException("Event name is required");
        }
        if (name.trim().length() < 3 || name.trim().length() > 150) {
            throw new InvalidRequestException("Event name must be between 3 and 150 characters");
        }
        if (venue != null && venue.length() > 150) {
            throw new InvalidRequestException("Venue must be 150 characters or fewer");
        }
        if (category != null && category.length() > 60) {
            throw new InvalidRequestException("Category must be 60 characters or fewer");
        }
        if (contactPerson != null && contactPerson.length() > 150) {
            throw new InvalidRequestException("Contact person must be 150 characters or fewer");
        }
        if (contactDetails != null && contactDetails.length() > 150) {
            throw new InvalidRequestException("Contact details must be 150 characters or fewer");
        }
        if (bannerUrl != null && !bannerUrl.isBlank()) {
            if (bannerUrl.length() > 255) {
                throw new InvalidRequestException("Banner URL must be 255 characters or fewer");
            }
            String lower = bannerUrl.trim().toLowerCase();
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new InvalidRequestException("Banner URL must start with http:// or https://");
            }
        }
        if (capacity == null || capacity < 1) {
            throw new InvalidRequestException("Capacity must be at least 1");
        }
        if (fee != null && fee.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidRequestException("Fee cannot be negative");
        }
        if (fee != null && fee.scale() > 2) {
            throw new InvalidRequestException("Fee cannot have more than 2 decimal places");
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidRequestException(
                    "Description must be " + MAX_DESCRIPTION_LENGTH + " characters or fewer (it is currently "
                            + description.length() + ")");
        }
        if (specialInstructions != null && specialInstructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            throw new InvalidRequestException(
                    "Special instructions must be " + MAX_INSTRUCTIONS_LENGTH
                            + " characters or fewer (they are currently "
                            + specialInstructions.length() + ")");
        }
        // event_type is NOT NULL in the schema with no application default
        // on the update path, so a null here would surface as a constraint
        // violation at flush time instead of a message the organizer can act on.
        if (eventType == null) {
            throw new InvalidRequestException("Event type is required (PUBLIC or RESTRICTED)");
        }
    }

    /**
     * All the date rules in one place: an event has to end after it starts,
     * the registration window has to open before it closes, and it has to
     * close no later than the event itself begins (registering for
     * something that has already started makes no sense).
     */
    private void validateSchedule(LocalDateTime start, LocalDateTime end,
                                   LocalDateTime registrationOpen, LocalDateTime registrationDeadline) {
        if (start != null && end != null && !end.isAfter(start)) {
            throw new InvalidRequestException("The event end time must be after its start time");
        }
        if (registrationOpen != null && registrationDeadline != null
                && !registrationDeadline.isAfter(registrationOpen)) {
            throw new InvalidRequestException(
                    "The registration deadline must be after the registration opening date");
        }
        if (registrationDeadline != null && start != null && registrationDeadline.isAfter(start)) {
            throw new InvalidRequestException(
                    "The registration deadline cannot be after the event start time");
        }
        if (registrationOpen != null && start != null && registrationOpen.isAfter(start)) {
            throw new InvalidRequestException(
                    "Registration cannot open after the event has already started");
        }
    }

    /**
     * BR-02: two non-cancelled events may not occupy the same venue with
     * overlapping time windows.
     *
     * KNOWN LIMITATION, stated rather than left to be discovered. This is a
     * check-then-act, and unlike the seat count it is not backed by a lock
     * or a constraint: two organizers submitting overlapping bookings for
     * the same venue in the same instant can both read "no conflict" and
     * both insert. Every other invariant in this system has a database-level
     * backstop - seats have a row lock, one active registration per attendee
     * has a unique index - and this one does not, because no unique index can
     * express "these two datetime ranges overlap". MySQL has no exclusion
     * constraint; PostgreSQL's EXCLUDE USING gist would enforce it outright.
     *
     * The honest reading: the rule holds under every realistic load for this
     * system, where creating an event is a deliberate act by one of a handful
     * of organizers, and the window is the few milliseconds between this
     * query and the insert. Closing it properly would mean an advisory lock
     * keyed on the venue name (MySQL GET_LOCK) or moving the check into the
     * database - neither of which is worth the added failure modes here, but
     * both of which are the answer if this ever runs somewhere busier.
     *
     * @param excludeEventId the event being edited, so it is not treated as
     *                       a conflict with itself; null when creating.
     */
    private void requireVenueIsFree(Long excludeEventId, String venue,
                                     LocalDateTime start, LocalDateTime end) {
        if (venue == null || venue.isBlank() || start == null || end == null) {
            return; // nothing to compare against
        }
        boolean conflict = !eventRepository.findOverlappingEvents(
                venue, start, end, EventStatus.CANCELLED,
                excludeEventId == null ? -1L : excludeEventId).isEmpty();
        if (conflict) {
            throw new InvalidRequestException(
                    "Scheduling conflict: venue '" + venue
                            + "' is already booked for an overlapping time slot");
        }
    }

    /** Names the locked fields this request would actually change. */
    private List<String> lockedFieldChanges(Event event, EventUpdateRequest request) {
        List<String> changed = new java.util.ArrayList<>();
        if (request.getStartDatetime() != null && !request.getStartDatetime().equals(event.getStartDatetime())) {
            changed.add("start time");
        }
        if (request.getEndDatetime() != null && !request.getEndDatetime().equals(event.getEndDatetime())) {
            changed.add("end time");
        }
        if (request.getVenue() != null && !request.getVenue().equalsIgnoreCase(nullToEmpty(event.getVenue()))) {
            changed.add("venue");
        }
        if (request.getFee() != null && event.getFee() != null
                && request.getFee().compareTo(event.getFee()) != 0) {
            changed.add("price");
        }
        if (request.getEventType() != null && request.getEventType() != event.getEventType()) {
            changed.add("event type");
        }
        if (request.getCapacity() != null && !request.getCapacity().equals(event.getCapacity())) {
            changed.add("capacity");
        }
        if (request.getRegistrationOpenDate() != null
                && !request.getRegistrationOpenDate().equals(event.getRegistrationOpenDate())) {
            changed.add("registration opening date");
        }
        if (request.getRegistrationDeadline() != null
                && !request.getRegistrationDeadline().equals(event.getRegistrationDeadline())) {
            changed.add("registration deadline");
        }
        return changed;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private User loadActor(ActingUser actor) {
        return userRepository.findById(actor.id()).orElse(null);
    }

    private Event getEventOrThrow(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + id));
    }
}
