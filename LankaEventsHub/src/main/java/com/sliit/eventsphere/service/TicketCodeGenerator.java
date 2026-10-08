package com.sliit.eventsphere.service;

import com.sliit.eventsphere.entity.Registration;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Makes the code that identifies one ticket.
 *
 * Extracted into its own component because two services now issue
 * tickets: Payment &amp; Ticketing when a paid booking clears, and
 * Registration &amp; Booking when a free event is confirmed on the spot.
 * Two copies of this would be two formats the moment somebody edited one
 * of them, and a ticket code is the thing staff read out at a door.
 *
 * WHAT IS IN THE CODE, AND WHAT IS DELIBERATELY NOT. The event id, the
 * registration id, and eight random characters - nothing about the
 * attendee. The ticket page draws its QR square in the browser from this
 * string, so whatever goes in here is what ends up scannable on a phone
 * screen and photographed by anyone standing behind them. A name or an
 * email address in a ticket code is a privacy leak with a very long tail.
 *
 * The random suffix is what stops the code being guessable from the two
 * ids, which are both visible in ordinary URLs.
 */
@Component
public class TicketCodeGenerator {

    public String generate(Registration registration) {
        return "TICKET-"
                + registration.getEvent().getId()
                + "-"
                + registration.getId()
                + "-"
                + UUID.randomUUID().toString()
                        .substring(0, 8)
                        .toUpperCase(Locale.ROOT);
    }
}
