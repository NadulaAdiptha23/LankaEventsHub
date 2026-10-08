package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.EventSessionRequest;
import com.sliit.eventsphere.dto.EventSessionResponse;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventSession;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.EventSessionRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Function 1 (Event Creation & Management) support: lets an organizer
 * break an event into sessions with their own schedule and speaker
 * ("Session Title, Session Description, Session Start and End Time,
 * Speaker/Facilitator Details").
 *
 * Both cross-event holes are closed here: you cannot add a session to
 * somebody else's event, and you cannot delete a session through an
 * event it does not belong to.
 */
@Service
public class EventSessionServiceImpl implements EventSessionService {

    private final EventRepository eventRepository;
    private final EventSessionRepository eventSessionRepository;
    private final EventAccessPolicy accessPolicy;

    public EventSessionServiceImpl(EventRepository eventRepository,
                                    EventSessionRepository eventSessionRepository,
                                    EventAccessPolicy accessPolicy) {
        this.eventRepository = eventRepository;
        this.eventSessionRepository = eventSessionRepository;
        this.accessPolicy = accessPolicy;
    }

    @Override
    @Transactional
    public EventSessionResponse addSession(Long eventId, EventSessionRequest request, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage sessions for this event.");

        validate(event, request);

        EventSession session = new EventSession();
        session.setEvent(event);
        apply(session, request);

        return EventSessionResponse.from(eventSessionRepository.save(session));
    }

    @Override
    @Transactional
    public EventSessionResponse updateSession(Long eventId, Long sessionId,
                                               EventSessionRequest request, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage sessions for this event.");

        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Session not found with id " + sessionId));
        // The same cross-event check removeSession makes. Without it, an
        // organizer could edit any session on the platform by passing their
        // own event id and somebody else's session id - the event-level
        // permission check above would pass, because they do own that event.
        if (!session.getEvent().getId().equals(eventId)) {
            throw new InvalidRequestException("That session does not belong to this event");
        }

        validate(event, request);
        apply(session, request);

        return EventSessionResponse.from(eventSessionRepository.save(session));
    }

    /**
     * The field rules, in one place so that adding and editing a session
     * can never enforce different ones.
     */
    private void validate(Event event, EventSessionRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new InvalidRequestException("Session title is required");
        }
        if (request.getTitle().length() > 150) {
            throw new InvalidRequestException("Session title must be 150 characters or fewer");
        }
        if (request.getSpeaker() != null && request.getSpeaker().length() > 150) {
            throw new InvalidRequestException("Speaker name must be 150 characters or fewer");
        }
        if (request.getStartTime() != null && request.getEndTime() != null
                && !request.getEndTime().isAfter(request.getStartTime())) {
            throw new InvalidRequestException("A session must end after it starts");
        }
        // A session outside the event's own window is almost always a typo,
        // and it makes the published agenda nonsense - so it is rejected
        // whenever both windows are actually known.
        if (event.getStartDatetime() != null && request.getStartTime() != null
                && request.getStartTime().isBefore(event.getStartDatetime())) {
            throw new InvalidRequestException("A session cannot start before the event starts");
        }
        if (event.getEndDatetime() != null && request.getEndTime() != null
                && request.getEndTime().isAfter(event.getEndDatetime())) {
            throw new InvalidRequestException("A session cannot end after the event ends");
        }
    }

    private void apply(EventSession session, EventSessionRequest request) {
        session.setTitle(request.getTitle().trim());
        session.setDescription(request.getDescription());
        session.setStartTime(request.getStartTime());
        session.setEndTime(request.getEndTime());
        session.setSpeaker(request.getSpeaker());
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventSessionResponse> getSessionsForEvent(Long eventId, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        accessPolicy.requireViewable(event, actor, eventId);
        return eventSessionRepository.findByEventId(eventId).stream()
                .map(EventSessionResponse::from)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void removeSession(Long eventId, Long sessionId, ActingUser actor) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found with id " + eventId));
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage sessions for this event.");

        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found with id " + sessionId));
        if (!session.getEvent().getId().equals(eventId)) {
            throw new InvalidRequestException("That session does not belong to this event");
        }
        eventSessionRepository.delete(session);
    }
}
