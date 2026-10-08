package com.sliit.eventsphere.entity;

import com.sliit.eventsphere.exception.InvalidRequestException;

/**
 * Event lifecycle - and the State Pattern for Function 1 (Event Creation
 * & Management).
 *
 *   DRAFT --(organizer submits)--> PENDING_APPROVAL --(admin approves)--> PUBLISHED
 *     |                                   |                                  |
 *     |                                   |                      (after it ends) --> COMPLETED
 *     |                                   |                                  |
 *     +-----------------(organizer/admin cancels)------------------------------> CANCELLED
 *
 * Nothing ever returns to an earlier state, and CANCELLED/COMPLETED are
 * both terminal. The events.status CHECK constraint keeps the column to
 * this set; this enum is what now enforces which transition is legal
 * from which state.
 *
 * Each constant overrides exactly the transition(s) legal FROM it - DRAFT
 * overrides submitForApproval() and cancel(), PENDING_APPROVAL overrides
 * approve() and cancel(), PUBLISHED overrides complete() and cancel().
 * Every method a constant does not override falls through to the default
 * implementation below, which rejects it. EventServiceImpl no longer
 * contains an "if status != EXPECTED, throw" check for any of the four
 * transitions - it calls event.getStatus().submitForApproval() (or
 * .approve()/.cancel()/.complete()) and either gets the next EventStatus
 * back or this enum throws for it.
 *
 * This is Java's enum-with-per-constant-behaviour form of the State
 * Pattern, not a five-class hierarchy behind a separate EventState
 * interface. The two are functionally equivalent - a fixed set of named
 * states, each knowing its own legal transitions, dispatched
 * polymorphically rather than through an if/switch the caller has to get
 * right every time - but a class hierarchy would mean either EventStatus
 * stops being the plain @Enumerated(EnumType.STRING) column value it has
 * always been, or a second, parallel "state" concept gets introduced that
 * has to be kept in sync with the real column. Neither trade was worth
 * making for identical behaviour, so the transitions live directly on the
 * enum JPA already persists.
 *
 * What deliberately stays OUT of this enum: the actual work a transition
 * does beyond the status change itself - missingForSubmission's
 * completeness check, cancelEvent's seat release and registration
 * cascade and domain event, completeEvent's finished-at-a-real-time
 * check, the two-tier locked-field/admin-override rule on update. Those
 * need repositories, the audit log, and domain events this enum has and
 * should have none of; EventServiceImpl still owns all of that, calling
 * into this enum only to ask "is this transition legal at all". Folding
 * the side-effecting logic into five enum constants would not be a
 * cleaner state machine - it would be a service class wearing an enum's
 * clothes, and a harder one to unit test besides.
 */
public enum EventStatus {
    DRAFT {
        @Override public EventStatus submitForApproval() { return PENDING_APPROVAL; }
        // Included so an organizer can abandon a draft through the same
        // action as any other event - there is no separate "discard"
        // concept to learn, and a cancelled draft still leaves the audit
        // entry that says it existed.
        @Override public EventStatus cancel() { return CANCELLED; }
    },
    PENDING_APPROVAL {
        @Override public EventStatus approve() { return PUBLISHED; }
        @Override public EventStatus cancel() { return CANCELLED; }
    },
    PUBLISHED {
        @Override public EventStatus complete() { return COMPLETED; }
        @Override public EventStatus cancel() { return CANCELLED; }
    },
    COMPLETED,
    CANCELLED;

    /** DRAFT only. Rejected from every other state, including by this default. */
    public EventStatus submitForApproval() {
        throw new InvalidRequestException(
                "Only a draft can be submitted for approval (current status: " + this + ")");
    }

    /** PENDING_APPROVAL only. */
    public EventStatus approve() {
        throw new InvalidRequestException(
                "Only an event that is pending approval can be approved (current status: " + this + ")");
    }

    /** DRAFT, PENDING_APPROVAL or PUBLISHED - never from a terminal state. */
    public EventStatus cancel() {
        throw new InvalidRequestException("This event cannot be cancelled because it is already " + this);
    }

    /** PUBLISHED only. */
    public EventStatus complete() {
        throw new InvalidRequestException(
                "Only a published event can be marked completed (current status: " + this + ")");
    }

    /**
     * Every state except the two terminal ones. Not a transition itself -
     * a query updateEventDetails() makes before touching anything - so it
     * is a plain boolean rather than something that throws, unlike the
     * four transitions above.
     */
    public boolean isEditable() {
        return this != COMPLETED && this != CANCELLED;
    }
}

