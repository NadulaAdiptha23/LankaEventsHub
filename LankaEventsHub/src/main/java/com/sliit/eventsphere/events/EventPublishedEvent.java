package com.sliit.eventsphere.events;

import com.sliit.eventsphere.entity.Event;
import org.springframework.context.ApplicationEvent;

/**
 * OBSERVER PATTERN (Subject side)
 * ---------------------------------------------------------------------
 * Published by EventServiceImpl.approveEvent() the moment an event
 * becomes visible to attendees. EventServiceImpl has no idea who is
 * listening, or how many listeners there are - it just announces the
 * fact. See NotificationEventListener for a concrete subscriber.
 */
public class EventPublishedEvent extends ApplicationEvent {

    private final Event event;

    public EventPublishedEvent(Object source, Event event) {
        super(source);
        this.event = event;
    }

    public Event getEvent() {
        return event;
    }
}
