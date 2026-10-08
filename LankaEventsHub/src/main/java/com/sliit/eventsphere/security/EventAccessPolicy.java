//central security/authorization class for events
//decide who can manage an event and who can view an event
package com.sliit.eventsphere.security;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.RegistrationRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class EventAccessPolicy {

    private final RegistrationRepository registrationRepository;

    public EventAccessPolicy(RegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    //Checks whether the logged-in user is allowed to manage that specific event
    public boolean canManage(Event event, ActingUser actor) {
        if (event == null || actor == null) {
            return false;
        }
        //Can manage any event
        if (actor.isAdmin()) {
            return true;
        }
        //Can manage only their own events
        Long ownerId = event.getOrganizer() != null ? event.getOrganizer().getId() : null;
        return actor.isOrganizer() && actor.is(ownerId);
    }

    //enforces the rule.
    public void requireManager(Event event, ActingUser actor) {
        if (!canManage(event, actor)) {
            throw new AccessDeniedException("You are not authorized to manage this event.");
        }
    }

    public void requireManager(Event event, ActingUser actor, String message) {
        if (!canManage(event, actor)) {
            throw new AccessDeniedException(message);
        }
    }

    //decides whether someone can see an event's details.
    public boolean canView(Event event, ActingUser actor) {
        if (event == null) {
            return false;
        }
        //Event is PUBLISHED
        if (event.getStatus() == EventStatus.PUBLISHED) {
            return true;
        }
        //User can manage the event
        if (canManage(event, actor)) {
            return true;
        }
        // User has registered for the event
        return actor != null
                && registrationRepository.existsByEventIdAndUserId(event.getId(), actor.id());
    }

    //If the user cannot see the event
    //resource enumeration protection.
    public void requireViewable(Event event, ActingUser actor, Long requestedId) {
        if (!canView(event, actor)) {
            //404 Event not found
            throw new ResourceNotFoundException("Event not found with id " + requestedId);
        }
    }
}
