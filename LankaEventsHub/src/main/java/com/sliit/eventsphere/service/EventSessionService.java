package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.EventSessionRequest;
import com.sliit.eventsphere.dto.EventSessionResponse;
import com.sliit.eventsphere.security.ActingUser;

import java.util.List;

public interface EventSessionService {

    /** Organizer (own event) or Admin only. */
    EventSessionResponse addSession(Long eventId, EventSessionRequest request, ActingUser actor);

    /**
     * The agenda, for anyone allowed to see the event it belongs to.
     *
     * The agenda used to be readable by event id alone, which made it the
     * second way to read an unapproved draft: the titles, times and
     * speakers of a programme nobody had approved yet, through a
     * permitAll endpoint. It follows the event's own visibility now, so
     * there is one answer to "can this person see this event", not two.
     *
     * @param actor the signed-in user, or null for an anonymous visitor
     */
    List<EventSessionResponse> getSessionsForEvent(Long eventId, ActingUser actor);

    /**
     * Organizer (own event) or Admin only. Replaces the session's title,
     * description, schedule and speaker.
     *
     * Sessions could be created and deleted but never edited, which meant
     * a typo in a session title - or a speaker who changed - could only be
     * fixed by deleting the session and adding it again under a new id.
     *
     * The session must belong to the event named in the URL, so one
     * organizer cannot edit another event's agenda by guessing a session
     * id.
     */
    EventSessionResponse updateSession(Long eventId, Long sessionId,
                                        EventSessionRequest request, ActingUser actor);

    /**
     * Organizer (own event) or Admin only. The session must belong to the
     * event named in the URL, so a session cannot be deleted through
     * another event's endpoint.
     */
    void removeSession(Long eventId, Long sessionId, ActingUser actor);
}
