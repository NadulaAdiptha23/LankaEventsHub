package com.sliit.eventsphere.payment;

import java.math.BigDecimal;

/**
 * Whatever actually takes the money.
 *
 * This interface exists so that Payment &amp; Ticketing does not contain a
 * line that reads {@code payment.setStatus(COMPLETED)} immediately after
 * deciding to charge somebody. That line was true of the mock and would
 * be false of every real provider, and worse, it made the declined-card
 * path unreachable - the requirement documents describe gateway decline
 * handling, and there was no way to demonstrate it.
 *
 * With the charge behind an interface, the service has to handle both
 * answers, the failure path is real code rather than a comment, and a
 * test can decline a payment without pretending to be a payment provider.
 *
 * A real integration would implement this by calling out to a provider
 * and, in most cases, handling an asynchronous callback rather than
 * returning inline. {@link PaymentStatus#PENDING} in the entity model is
 * what that callback would land on.
 */
public interface PaymentGateway {

    /**
     * Attempts to take {@code amount} from {@code card}.
     *
     * Implementations must not throw for an ordinary decline - a refused
     * card is an answer, not an error, and the caller needs to record it
     * as a FAILED payment the attendee can retry. Throwing is reserved
     * for the gateway being unreachable.
     *
     * The card is passed rather than stored. Implementations must not
     * persist any part of it, and must not log it: the whole object exists
     * for the length of this call and is discarded with the response.
     */
    PaymentResult charge(BigDecimal amount, CardDetails card);
}
