package com.sliit.eventsphere.payment;

import java.time.YearMonth;

/**
 * The card a person typed into the checkout, in the one shape the gateway
 * is given it.
 *
 * A record with no setters and no entity behind it, deliberately: this
 * object exists for the length of one request and is never written
 * anywhere. Nothing in this project persists a card number, an expiry or
 * a security code - not even masked - because a student project that is
 * going to be run on other people's laptops has no business holding
 * payment credentials, and nothing on a ticket or a receipt needs them.
 *
 * The checks live here rather than in the gateway because they are facts
 * about the number itself, not decisions a provider makes. A number that
 * fails the Luhn checksum was mistyped; that is worth saying plainly and
 * immediately, and it is a different sentence from "your bank refused
 * this".
 */
public record CardDetails(String number, String holder, Integer expiryMonth,
                           Integer expiryYear, String securityCode) {

    /**
     * Digits only, with the spaces and dashes people actually type
     * removed. Everything below works on this rather than on the raw
     * field, so "4111 1111 1111 1111" and "4111-1111-1111-1111" are the
     * same card.
     */
    public String digits() {
        return number == null ? "" : number.replaceAll("[^0-9]", "");
    }

    /** The last four digits, for a message. Never stored. */
    public String lastFour() {
        String d = digits();
        return d.length() < 4 ? d : d.substring(d.length() - 4);
    }

    /**
     * Why this card cannot be sent to the gateway at all, or null when it
     * can.
     *
     * Returns a sentence rather than throwing, because the checkout page
     * has to show all of this next to the field the person is still
     * looking at, and because "this was mistyped" is not a decline - the
     * money was never asked for, so there is nothing to record as a
     * failed attempt.
     */
    public String validationError() {
        String d = digits();
        if (d.isEmpty()) {
            return "Enter the card number.";
        }
        if (d.length() < 13 || d.length() > 19) {
            return "A card number is between 13 and 19 digits.";
        }
        if (!passesLuhn(d)) {
            // The single most common real failure: one digit wrong. It is
            // worth its own sentence, because "declined" would send
            // somebody to their bank over a typo.
            return "That card number is not valid - please check it and try again.";
        }
        if (holder == null || holder.isBlank()) {
            return "Enter the name printed on the card.";
        }
        if (expiryMonth == null || expiryYear == null
                || expiryMonth < 1 || expiryMonth > 12) {
            return "Enter the expiry date as MM/YY.";
        }
        // The card is good until the END of its expiry month, which is why
        // this compares months rather than dates. Treating the 1st as
        // expired would reject a valid card for up to thirty days.
        if (YearMonth.of(expiryYear, expiryMonth).isBefore(YearMonth.now())) {
            return "That card has expired.";
        }
        if (securityCode == null || !securityCode.matches("[0-9]{3,4}")) {
            return "Enter the 3 or 4 digit security code from the back of the card.";
        }
        return null;
    }

    /**
     * The Luhn checksum every card number carries.
     *
     * Doubling every second digit from the right and summing the digits of
     * the results gives a multiple of ten for a genuine number. It catches
     * a single mistyped digit and most transposed pairs, which is exactly
     * the mistake a checkout should catch before involving anybody's bank.
     */
    public static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
