package com.sliit.eventsphere.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * NO uniqueConstraints here, deliberately.
 *
 * This class used to declare UNIQUE(event_id, user_id), which is the
 * rule the database had BEFORE re-registration after cancelling was
 * fixed, and the opposite of what it enforces now. The real rule is one
 * ACTIVE registration per attendee per event, enforced by
 * uq_registration_active over the generated active_user_id column - so a
 * cancelled row keeps its history and the attendee can sign up again.
 *
 * With ddl-auto=none this annotation generated nothing, which is exactly
 * why it survived: it was wrong in a place nothing would ever fail on.
 * The SQL schema is the source of truth for this project, so a Java
 * annotation contradicting it is documentation that lies.
 */
@Entity
@Table(name = "registrations")
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegistrationStatus status = RegistrationStatus.CONFIRMED;

    @Column(name = "registered_at", updatable = false)
    private LocalDateTime registeredAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * When a held seat is given back if it has still not been paid for.
     *
     * Set for exactly the rows that are PENDING_PAYMENT, and cleared
     * when the hold resolves one way or the other. An abandoned checkout
     * is the normal case rather than the exceptional one, so the hold
     * has to release itself instead of waiting for somebody to notice.
     */
    @Column(name = "payment_due_at")
    private LocalDateTime paymentDueAt;

    /**
     * How many seats this one booking holds.
     *
     * People arrive in twos and fours, and making them register four
     * separate times is both a worse experience and a worse data model -
     * nothing would then record that the four bookings are one party.
     *
     * Every seat figure in the system moves by this number rather than by
     * one: the capacity check, seatsTaken, what the payment costs, what a
     * cancellation gives back, and how many seats a waitlisted party needs
     * before it can be promoted. Getting any one of those wrong is how an
     * event ends up oversold, so they all read this field instead of
     * assuming a booking is worth a single seat.
     */
    @Column(nullable = false)
    private Integer seats = 1;

    /**
     * How many of the party actually turned up, or null before check-in.
     *
     * Separate from {@link #seats} because a table of four routinely
     * arrives as three. Counting the booking as fully attended would
     * overstate every attendance figure on the platform; counting it as
     * absent would understate them. Only a number is honest here, which is
     * why attendance reports sum this rather than counting rows.
     */
    @Column(name = "seats_checked_in")
    private Integer seatsCheckedIn;

    /**
     * When this attendee was checked in at the door, or null if they were
     * not. Registering and turning up are different facts; this is the
     * second one, and without it no report can say how many of the people
     * who booked actually came.
     *
     * A timestamp rather than a boolean, because "when" answers questions
     * "whether" cannot - such as how much of the audience arrived after
     * the opening session had started.
     */
    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    /**
     * The staff member who performed the check-in. Attendance is a record
     * about a person, so somebody is accountable for it.
     *
     * Deliberately not cascaded on delete: if the account goes, the fact
     * that the attendee turned up does not.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_in_by")
    private User checkedInBy;

    @PrePersist
    protected void onCreate() {
        registeredAt = LocalDateTime.now();
    }

    public Registration() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public RegistrationStatus getStatus() {
        return status;
    }

    public void setStatus(RegistrationStatus status) {
        this.status = status;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public LocalDateTime getCheckedInAt() {
        return checkedInAt;
    }

    public void setCheckedInAt(LocalDateTime checkedInAt) {
        this.checkedInAt = checkedInAt;
    }

    public User getCheckedInBy() {
        return checkedInBy;
    }

    public void setCheckedInBy(User checkedInBy) {
        this.checkedInBy = checkedInBy;
    }

    public LocalDateTime getPaymentDueAt() {
        return paymentDueAt;
    }

    public void setPaymentDueAt(LocalDateTime paymentDueAt) {
        this.paymentDueAt = paymentDueAt;
    }

    /**
     * Never null in practice - the column defaults to 1 and the service
     * validates the range - but old rows loaded before the column existed
     * would read as null, and a null here would mean a NullPointerException
     * inside seat arithmetic rather than a visible error. One seat is the
     * only defensible reading of a booking that does not say otherwise.
     */
    public int getSeats() {
        return seats == null ? 1 : seats;
    }

    public void setSeats(Integer seats) {
        this.seats = seats;
    }

    public Integer getSeatsCheckedIn() {
        return seatsCheckedIn;
    }

    public void setSeatsCheckedIn(Integer seatsCheckedIn) {
        this.seatsCheckedIn = seatsCheckedIn;
    }

    /**
     * How many people this booking should count as having attended.
     *
     * Zero when nobody was checked in, so callers can sum this across a
     * whole event without first filtering out the absentees.
     */
    public int attendedSeats() {
        if (!isCheckedIn()) {
            return 0;
        }
        return seatsCheckedIn == null ? getSeats() : seatsCheckedIn;
    }

    /** Reads better than a null check at every call site. */
    public boolean isCheckedIn() {
        return checkedInAt != null;
    }

    /**
     * Whether this booking is holding one of the event's seats.
     *
     * A held reservation counts, because the seat is genuinely unavailable
     * to anybody else while it lasts - that is the whole point of it.
     * Everything that adjusts seats_taken asks this rather than comparing
     * against CONFIRMED, so releasing a seat and counting one can never
     * disagree about what a seat is.
     */
    public boolean holdsASeat() {
        return status == RegistrationStatus.CONFIRMED
                || status == RegistrationStatus.PENDING_PAYMENT;
    }
}
