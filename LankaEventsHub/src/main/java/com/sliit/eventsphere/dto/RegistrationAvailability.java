package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.RegistrationStatus;

/**
 * What the event detail page needs in order to show the right thing:
 * whether this visitor can register right now, what would happen if they
 * did (seat vs waitlist), what state they are already in, and - when they
 * cannot - the exact reason, in the same words the server would use if
 * they tried anyway.
 *
 * Computing this in the service rather than in the template is what stops
 * the page from offering a "Register now" button that the backend will
 * then refuse.
 */
public class RegistrationAvailability {

    private final boolean canRegister;
    private final boolean wouldWaitlist;
    private final String blockedReason;
    private final RegistrationStatus myStatus;      // null when not registered
    private final boolean canCancel;
    private final String cancelBlockedReason;
    private final boolean invitationRequired;
    private final String myInvitationStatus;        // null when never requested
    /**
     * True when this person has paid for this place and cancelling it
     * themselves would not give the money back. The page has to be able
     * to say so BEFORE the button is pressed - finding out afterwards is
     * how a support ticket starts.
     */
    private final boolean cancelForfeitsPayment;
    /** The caller's own registration id, so the page can link to checkout. */
    private final Long myRegistrationId;
    /** When the held seat lapses, or null when nothing is being held. */
    private final java.time.LocalDateTime paymentDueAt;
    /**
     * Seats still unsold, and the largest party one booking may hold.
     *
     * Both are here so the seat selector on the event page can offer a
     * range somebody can actually book, instead of listing 1 to 10 and
     * letting the server refuse most of them. The server still re-checks
     * under a lock - this is what the page shows, not what the rules are.
     */
    private final int seatsRemaining;
    private final int maxSeatsPerBooking;
    /** How many seats the caller's own booking holds, or null when none. */
    private final Integer mySeats;

    public RegistrationAvailability(boolean canRegister, boolean wouldWaitlist, String blockedReason,
                                     RegistrationStatus myStatus, boolean canCancel,
                                     String cancelBlockedReason, boolean invitationRequired,
                                     String myInvitationStatus, boolean cancelForfeitsPayment,
                                     Long myRegistrationId,
                                     java.time.LocalDateTime paymentDueAt,
                                     int seatsRemaining, int maxSeatsPerBooking, Integer mySeats) {
        this.seatsRemaining = seatsRemaining;
        this.maxSeatsPerBooking = maxSeatsPerBooking;
        this.mySeats = mySeats;
        this.canRegister = canRegister;
        this.wouldWaitlist = wouldWaitlist;
        this.blockedReason = blockedReason;
        this.myStatus = myStatus;
        this.canCancel = canCancel;
        this.cancelBlockedReason = cancelBlockedReason;
        this.invitationRequired = invitationRequired;
        this.myInvitationStatus = myInvitationStatus;
        this.cancelForfeitsPayment = cancelForfeitsPayment;
        this.myRegistrationId = myRegistrationId;
        this.paymentDueAt = paymentDueAt;
    }

    public boolean isCanRegister() { return canRegister; }
    public boolean isWouldWaitlist() { return wouldWaitlist; }
    public String getBlockedReason() { return blockedReason; }
    public RegistrationStatus getMyStatus() { return myStatus; }
    public boolean isRegistered() { return myStatus != null; }
    public boolean isCanCancel() { return canCancel; }
    public String getCancelBlockedReason() { return cancelBlockedReason; }
    public boolean isInvitationRequired() { return invitationRequired; }
    public String getMyInvitationStatus() { return myInvitationStatus; }
    public boolean isCancelForfeitsPayment() { return cancelForfeitsPayment; }
    public Long getMyRegistrationId() { return myRegistrationId; }
    public java.time.LocalDateTime getPaymentDueAt() { return paymentDueAt; }
    public int getSeatsRemaining() { return seatsRemaining; }
    public int getMaxSeatsPerBooking() { return maxSeatsPerBooking; }
    public Integer getMySeats() { return mySeats; }

    /**
     * The largest party this visitor could book right now: the per-booking
     * cap, or what is left if that is smaller.
     *
     * Never less than one, so the selector always has something in it -
     * on a full event the single option leads to the waitlist, which is a
     * real outcome rather than a dead control.
     */
    public int getMaxSeatsBookable() {
        return Math.max(1, Math.min(maxSeatsPerBooking, seatsRemaining));
    }

    /**
     * True when a seat is being held for this person and they still have
     * to pay for it. The event page shows a "pay now" prompt and a
     * deadline rather than a confirmation they have not earned.
     */
    public boolean isAwaitingPayment() {
        return myStatus == RegistrationStatus.PENDING_PAYMENT;
    }
    public boolean isAwaitingInvitationDecision() { return "PENDING".equals(myInvitationStatus); }
    public boolean isInvitationRejected() { return "REJECTED".equals(myInvitationStatus); }
    public boolean isCanRequestInvitation() {
        return invitationRequired && myInvitationStatus == null && myStatus == null;
    }
}
