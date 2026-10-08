package com.sliit.eventsphere.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mock gateway and the card checks in front of it.
 *
 * Worth its own file because this is the part of Payment &amp; Ticketing
 * that decides, and the reason it was rewritten: a gateway that approved
 * everything made the declined path unreachable, so "we handle declines"
 * was a claim nobody could check. These tests are what makes it checkable
 * without a payment provider.
 */
class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    private CardDetails card(String number) {
        return new CardDetails(number, "Nadeesha Silva", 12,
                YearMonth.now().getYear() + 2, "123");
    }

    // =================================================================
    // The gateway's decision
    // =================================================================

    @Test
    void theApprovingTestCardApproves() {
        assertTrue(gateway.charge(new BigDecimal("2500.00"),
                card(MockPaymentGateway.APPROVES)).success());
    }

    @Test
    void theSameCardAlwaysGivesTheSameAnswer() {
        // Deterministic, not random. A demo in which the same card
        // sometimes works cannot be rehearsed, and a test that fails one
        // run in six is worse than no test.
        for (int i = 0; i < 20; i++) {
            assertTrue(gateway.charge(BigDecimal.TEN, card("4111111111111111")).success());
            assertFalse(gateway.charge(BigDecimal.TEN, card("4000000000000002")).success());
        }
    }

    @Test
    void theDecliningTestCardsDecline() {
        MockPaymentGateway.declinedCards().forEach((number, reason) -> {
            PaymentResult result = gateway.charge(new BigDecimal("2500.00"), card(number));
            assertFalse(result.success(), number + " should have been declined");
            // The attendee is told which problem it was. "Declined" alone
            // sends somebody to their bank over a card they have not lost.
            assertEquals(reason, result.reason());
        });
    }

    @Test
    void everyDecliningTestCardPassesTheChecksum() {
        // If a "decline" number failed the Luhn check it would be refused
        // as a typo before the gateway was ever called, and the demo
        // would be showing validation rather than a decline.
        MockPaymentGateway.declinedCards().keySet().forEach(number ->
                assertTrue(CardDetails.passesLuhn(number),
                        number + " must be a checksum-valid number"));
    }

    @Test
    void anOrdinaryValidCardApproves() {
        // Not in either list. A demo must not be blocked because somebody
        // typed a number nobody thought to enumerate.
        assertTrue(gateway.charge(new BigDecimal("500.00"), card("5555555555554444")).success());
    }

    @Test
    void theGatewayRefusesANumberThatFailsTheChecksum() {
        // Unreachable in practice - the service validates first - but a
        // gateway that silently approves a number nobody checked is
        // exactly the bug this class was rewritten to remove.
        assertFalse(gateway.charge(BigDecimal.TEN, card("4111111111111112")).success());
    }

    @Test
    void theGatewayDoesNotThrowOnAMissingCard() {
        // A decline is an answer; an exception is an outage. Throwing here
        // would turn a bad request into a 500.
        assertFalse(gateway.charge(BigDecimal.TEN, null).success());
    }

    // =================================================================
    // The checks in front of it
    // =================================================================

    @Test
    void luhnAcceptsRealCardNumbers() {
        assertTrue(CardDetails.passesLuhn("4111111111111111"));
        assertTrue(CardDetails.passesLuhn("5555555555554444"));
        assertTrue(CardDetails.passesLuhn("378282246310005"));
    }

    @Test
    void luhnCatchesASingleMistypedDigit() {
        // The single most common real failure, and the whole reason the
        // checksum is worth running at all.
        assertFalse(CardDetails.passesLuhn("4111111111111112"));
    }

    @Test
    void spacesAndDashesAreNotPartOfTheNumber() {
        assertNull(card("4111 1111 1111 1111").validationError());
        assertNull(card("4111-1111-1111-1111").validationError());
        assertEquals("4111111111111111", card("4111 1111 1111 1111").digits());
    }

    @Test
    void aMistypedNumberIsReportedAsATypoNotADecline() {
        String problem = card("4111111111111112").validationError();
        assertNotNull(problem);
        // Deliberately does NOT say declined: the money was never asked
        // for, so telling somebody their card was refused would send them
        // to their bank over a typing mistake.
        assertFalse(problem.toLowerCase().contains("declin"));
    }

    @Test
    void anExpiredCardIsRefusedBeforeTheGateway() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        CardDetails expired = new CardDetails("4111111111111111", "Nadeesha Silva",
                lastMonth.getMonthValue(), lastMonth.getYear(), "123");
        assertEquals("That card has expired.", expired.validationError());
    }

    @Test
    void aCardIsGoodUntilTheEndOfItsExpiryMonth() {
        // Treating the 1st as expired would reject a valid card for up to
        // thirty days - a real bug, and one nobody would report as one.
        YearMonth thisMonth = YearMonth.now();
        CardDetails expiringNow = new CardDetails("4111111111111111", "Nadeesha Silva",
                thisMonth.getMonthValue(), thisMonth.getYear(), "123");
        assertNull(expiringNow.validationError());
    }

    @Test
    void aMissingHolderExpiryOrSecurityCodeIsRefused() {
        int year = YearMonth.now().getYear() + 2;
        assertNotNull(new CardDetails("4111111111111111", " ", 12, year, "123")
                .validationError());
        assertNotNull(new CardDetails("4111111111111111", "Nadeesha", null, year, "123")
                .validationError());
        assertNotNull(new CardDetails("4111111111111111", "Nadeesha", 13, year, "123")
                .validationError());
        assertNotNull(new CardDetails("4111111111111111", "Nadeesha", 12, year, "12")
                .validationError());
        assertNotNull(new CardDetails("4111111111111111", "Nadeesha", 12, year, "abcd")
                .validationError());
    }

    @Test
    void aNumberOfTheWrongLengthIsRefused() {
        assertNotNull(card("41111").validationError());
        assertNotNull(card("41111111111111111111").validationError());
        assertNotNull(card("").validationError());
    }

    @Test
    void lastFourNeverThrowsOnAShortNumber() {
        // It is used in a log line on the decline path, and a log line
        // that can throw turns a handled decline into a 500.
        assertEquals("111", card("111").lastFour());
        assertEquals("", card("").lastFour());
        assertEquals("1111", card("4111111111111111").lastFour());
    }
}
