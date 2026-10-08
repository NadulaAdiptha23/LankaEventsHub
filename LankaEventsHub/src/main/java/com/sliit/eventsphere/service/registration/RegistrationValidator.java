package com.sliit.eventsphere.service.registration;

/**
 * Chain of Responsibility for Function 2 (Registration &amp; Booking):
 * whether an attendee is allowed to register for an event at all, as
 * distinct from whether a seat is available for them right now - see the
 * note at the bottom on why capacity is deliberately not a link here.
 *
 * Each concrete validator checks exactly one thing about the request and
 * either rejects it (returning a reason) or passes it to the next
 * validator in the chain. {@link #validate} is final; {@link #check} is
 * the one thing each concrete validator provides.
 *
 * Both callers that need to ask "is this attendee allowed to register" -
 * {@code RegistrationServiceImpl.register()} (which throws on the first
 * rejection) and {@code checkAvailability()} (which shows the reason on
 * the event page without throwing) - now ask the same chain the same
 * question, in the same order, rather than register() running three
 * checks through a private registrationBlockReason() helper and
 * checkAvailability() separately re-implementing two more of the same
 * checks with slightly different control flow, which is what both of
 * them did before this existed. That duplication was real, not
 * hypothetical - it is also how the two paths had drifted to use two
 * different sentences for the exact same restricted-event rejection,
 * which this refactor also quietly fixed by having only one place left
 * to word it.
 *
 * DELIBERATELY NOT A LINK IN THIS CHAIN: capacity. Whether there is a
 * seat available is not a reject/pass question - it is a three-way
 * decision (confirm, hold for payment, or waitlist) with a side effect
 * (event.seatsTaken changes) that has to happen inside the same
 * pessimistic lock this chain runs under, in a specific order relative
 * to it: the lock is acquired, then this chain runs against the locked
 * event, then - only if the chain passes - register() itself decides
 * confirm/hold/waitlist and mutates seatsTaken, still inside that same
 * lock. A validator that might mutate the locked Event and might not,
 * depending on which of three outcomes applies, is not what this pattern
 * is for. Folding capacity in here to make the chain longer would have
 * been exactly the kind of forced pattern application this project's
 * design-pattern plan explicitly ruled out - and the more concrete risk,
 * reordering the capacity decision relative to the lock, is exactly the
 * class of bug that would only show up under real concurrent load, not
 * in a normal test run.
 */
public abstract class RegistrationValidator {

    private RegistrationValidator next;

    /**
     * Links the next validator after this one and returns it, so a chain
     * can be assembled fluently: {@code a.linkTo(b).linkTo(c)}.
     */
    public RegistrationValidator linkTo(RegistrationValidator next) {
        this.next = next;
        return next;
    }

    /**
     * Runs this validator, and - only if it passes - every validator
     * after it. Returns the first rejection reason found anywhere in the
     * chain, or null if this validator and everything after it passes.
     */
    public final String validate(RegistrationValidationContext context) {
        String reason = check(context);
        if (reason != null) {
            return reason;
        }
        return next == null ? null : next.validate(context);
    }

    /** The one check this validator is responsible for. */
    protected abstract String check(RegistrationValidationContext context);
}
