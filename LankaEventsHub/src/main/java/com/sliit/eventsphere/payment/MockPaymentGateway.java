package com.sliit.eventsphere.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The stand-in gateway this project runs against until a real provider is
 * integrated.
 *
 * It says so in its own name rather than hiding behind an interface that
 * suggests a real integration. Nothing leaves the machine, no money moves,
 * and no card number is stored - which is the only responsible thing to do
 * in a student project that will be run on other people's laptops.
 *
 * What it is NOT, any more, is a gateway that approves everything. That
 * version made the declined-card path unreachable: the requirement
 * documents describe decline handling and there was no way to demonstrate
 * it, so a marker had to take the code's word for it. This one decides
 * from the card number, which makes the decline something you can do in
 * the running application rather than something you can read about.
 *
 * The decision is DETERMINISTIC, not random. A demo in which the same card
 * sometimes works cannot be rehearsed, and a test that fails one run in
 * six is worse than no test at all.
 *
 * It is a {@code @Component} rather than something the service news up, so
 * replacing it with a real provider is a matter of adding a class and
 * removing this one, with no edit to Payment &amp; Ticketing at all.
 */
@Component
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    /**
     * The cards that always decline, and what the issuer said.
     *
     * Ordinary-looking Visa and Mastercard numbers that PASS the Luhn
     * checksum, because a declined card and a mistyped one have to be
     * distinguishable: a "decline" number that failed the checksum would
     * never reach the gateway at all, and the demo would be showing
     * validation rather than a decline.
     *
     * A LinkedHashMap so the order on the checkout's help panel is the
     * order written here.
     */
    private static final Map<String, String> DECLINED = new LinkedHashMap<>();

    static {
        DECLINED.put("4000000000000002", "insufficient funds");
        DECLINED.put("4000000000009995", "the card was reported lost or stolen");
        DECLINED.put("5105105105105100", "the issuing bank could not be reached");
    }

    /**
     * The card that always approves, quoted on the checkout page so
     * nobody demonstrating this has to guess.
     */
    public static final String APPROVES = "4111 1111 1111 1111";

    /** The three that always decline, for that same panel. */
    public static Map<String, String> declinedCards() {
        return Map.copyOf(DECLINED);
    }

    @Override
    public PaymentResult charge(BigDecimal amount, CardDetails card) {
        String digits = card == null ? "" : card.digits();

        // Belt and braces. The service validates the card before calling,
        // so this should be unreachable - but a gateway that silently
        // approves a number nobody checked is exactly the bug this class
        // was rewritten to remove.
        if (digits.isEmpty() || !CardDetails.passesLuhn(digits)) {
            return PaymentResult.declined("the card number was not valid");
        }

        String reason = DECLINED.get(digits);
        if (reason != null) {
            // Logged WITHOUT the card number. The last four digits are
            // enough to match this line to an attendee's complaint; the
            // rest has no business in a log file.
            log.info("Mock gateway declining a charge of {} on a card ending {}: {}",
                    amount, card.lastFour(), reason);
            return PaymentResult.declined(reason);
        }

        log.debug("Mock gateway approving a charge of {} on a card ending {}",
                amount, card.lastFour());
        return PaymentResult.approved();
    }
}
