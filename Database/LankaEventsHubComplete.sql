-- =====================================================================
-- EventSphere - COMPLETE DATABASE SQL FILE
-- IT2140 Assignment Part 02
--
-- This single file combines:
--   01_schema.sql
--   02_seed_data.sql
--   03_reset_tokens.sql
--   04_routines.sql
--   06_queries.sql
--
-- Fresh installation order:
--   1. Create/select database
--   2. Schema
--   3. Seed data
--   4. Password-reset sample data
--   5. Stored procedures, function and triggers
--   6. Part D queries
--
-- NOTE:
-- 03_upgrade_existing_database.sql is intentionally NOT included.
-- It is an alternative migration script for an already-existing older DB.
-- =====================================================================

CREATE DATABASE IF NOT EXISTS eventsphere
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE eventsphere;

-- =====================================================================
-- PART B - SCHEMA
-- =====================================================================

-- =====================================================================
-- EventSphere Database Schema
-- Java Web-Based Event Management System
--
-- HOW TO RUN THIS FILE:
--   1. Create the database and run this schema file:
--        mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS eventsphere"
--        mysql -u root -p eventsphere < 01_schema.sql
--   2. Then run 02_seed_data.sql the same way.
--   3. Point the Spring Boot app's datasource at this database and start
--      it - the app only CONNECTS, it never creates or modifies tables
--      (spring.jpa.hibernate.ddl-auto=none).
--
-- Requires MySQL 8.0.16 or later: this schema uses enforced CHECK
-- constraints and one STORED generated column.
-- =====================================================================

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS saved_reports;
DROP TABLE IF EXISTS announcements;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS refunds;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS discounts;
DROP TABLE IF EXISTS registrations;
DROP TABLE IF EXISTS event_invitations;
DROP TABLE IF EXISTS event_sessions;
DROP TABLE IF EXISTS events;
DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS password_reset_tokens;
DROP TABLE IF EXISTS users;

-- ---------------------------------------------------------------------
-- Function 5: User & Role Management
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name            VARCHAR(150)    NOT NULL,
    email                VARCHAR(150)    NOT NULL UNIQUE,
    password_hash        VARCHAR(255)    NOT NULL,
    contact_number       VARCHAR(30),
    role                 VARCHAR(20)     NOT NULL DEFAULT 'ATTENDEE'
                         CHECK (role IN ('ATTENDEE', 'ORGANIZER', 'ADMIN')),
    is_active            BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Set by an Administrator only. This is the sole gate on the STUDENT
    -- discount (see StudentDiscountStrategy); checking a student ID is a
    -- manual, off-system step.
    is_verified_student  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
                         ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE audit_logs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT,
    action          VARCHAR(100)    NOT NULL,
    entity_type     VARCHAR(60)     NOT NULL,
    entity_id       BIGINT,
    details         VARCHAR(500),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- SET NULL, not CASCADE: the audit trail has to survive deletion of
    -- the account that produced it, or it is not an audit trail.
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE password_reset_tokens (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    -- SHA-256 hex of the token that went in the link, never the token
    -- itself: a leaked dump must not contain usable reset links.
    token_hash      CHAR(64)        NOT NULL UNIQUE,
    expires_at      TIMESTAMP       NOT NULL,
    used            BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reset_token_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Function 1: Event Creation & Management
-- ---------------------------------------------------------------------
CREATE TABLE events (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    organizer_id            BIGINT          NOT NULL,
    name                    VARCHAR(150)    NOT NULL,
    description             TEXT,
    category                VARCHAR(60),
    event_type              VARCHAR(20)     NOT NULL DEFAULT 'PUBLIC'
                            CHECK (event_type IN ('PUBLIC', 'RESTRICTED')),
    -- Lifecycle: DRAFT -> PENDING_APPROVAL -> PUBLISHED -> COMPLETED,
    -- with CANCELLED reachable from DRAFT, PENDING_APPROVAL or
    -- PUBLISHED. Nothing ever returns to an earlier state.
    --
    -- An event starts as a DRAFT: saved, owned, editable in every field,
    -- invisible to attendees and bookable by nobody. Submitting it is a
    -- separate, deliberate act by the organizer, and that is what makes
    -- PENDING_APPROVAL mean something to the administrator reading the
    -- queue - everything in it is finished and waiting on them.
    status                  VARCHAR(20)     NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN
                                ('DRAFT', 'PENDING_APPROVAL', 'PUBLISHED',
                                 'COMPLETED', 'CANCELLED')),
    venue                   VARCHAR(150),
    start_datetime          DATETIME,
    end_datetime            DATETIME,
    capacity                INT             NOT NULL DEFAULT 0,
    seats_taken             INT             NOT NULL DEFAULT 0,
    registration_open_date  DATETIME,
    registration_deadline   DATETIME,
    fee                     DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    contact_person          VARCHAR(150),
    contact_details         VARCHAR(150),
    banner_url              VARCHAR(255),
    special_instructions    TEXT,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
                            ON UPDATE CURRENT_TIMESTAMP,
    -- RESTRICT: an organizer with events cannot be deleted out from under
    -- them; suspend the account instead (is_active).
    CONSTRAINT fk_event_organizer FOREIGN KEY (organizer_id) REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_event_capacity   CHECK (capacity >= 0),
    CONSTRAINT chk_event_seats      CHECK (seats_taken >= 0),
    -- seats_taken can never exceed the seats that exist. The application
    -- enforces this under a pessimistic row lock too; this is the backstop
    -- if anything ever writes the table directly.
    CONSTRAINT chk_event_seats_fit  CHECK (seats_taken <= capacity),
    CONSTRAINT chk_event_fee        CHECK (fee >= 0),
    CONSTRAINT chk_event_dates      CHECK (start_datetime IS NULL
                                        OR end_datetime IS NULL
                                        OR end_datetime > start_datetime),
    CONSTRAINT chk_event_reg_window CHECK (registration_open_date IS NULL
                                        OR registration_deadline IS NULL
                                        OR registration_deadline > registration_open_date),
    CONSTRAINT chk_event_reg_before_start CHECK (registration_deadline IS NULL
                                        OR start_datetime IS NULL
                                        OR registration_deadline <= start_datetime)
) ENGINE=InnoDB;

CREATE TABLE event_sessions (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT          NOT NULL,
    title           VARCHAR(150)    NOT NULL,
    description     TEXT,
    start_time      DATETIME,
    end_time        DATETIME,
    speaker         VARCHAR(150),
    CONSTRAINT fk_session_event FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_session_times CHECK (start_time IS NULL
                                     OR end_time IS NULL
                                     OR end_time > start_time)
) ENGINE=InnoDB;

-- Restricted-event invitations / access requests
CREATE TABLE event_invitations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at      TIMESTAMP       NULL,
    CONSTRAINT fk_invitation_event FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_invitation_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE,
    -- One request per attendee per event: a rejection is a decision, not
    -- an invitation to ask again.
    CONSTRAINT uq_invitation UNIQUE (event_id, user_id)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Function 2: Registration & Booking
-- ---------------------------------------------------------------------
CREATE TABLE registrations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    -- PENDING_PAYMENT is a held reservation on a paid event: it occupies
    -- a seat (seats_taken counts it) but is not a booking yet, because
    -- nothing has been paid. It is what makes "payment before
    -- confirmation" and "the seat is secured on click" both true at once;
    -- without it, one of the two has to give. A free event goes straight
    -- to CONFIRMED and never passes through it.
    status          VARCHAR(20)     NOT NULL DEFAULT 'CONFIRMED'
                    CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED',
                                      'WAITLISTED', 'CANCELLED')),
    registered_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at    TIMESTAMP       NULL,

    -- When a held seat is given back if it has not been paid for. An
    -- abandoned checkout is the normal case, not the exceptional one, so
    -- the hold has to release itself rather than wait for somebody to
    -- notice. Set for exactly the rows that are PENDING_PAYMENT.
    payment_due_at  DATETIME        NULL,

    -- How many seats this one booking holds.
    --
    -- People arrive in twos and fours, and the alternative - making them
    -- register four separate times - is both a worse experience and a
    -- worse data model, because nothing would then record that the four
    -- bookings are one party who will arrive and leave together.
    --
    -- Every seat figure in the system moves by this number rather than by
    -- one: the capacity check, seats_taken, what the payment costs, what
    -- a cancellation gives back, and how many seats a waitlisted party
    -- needs before it can be promoted. The upper bound is a real rule
    -- rather than a guess at a column width - a booking for fifty is
    -- somebody block-booking an event, which is a conversation with the
    -- organizer, not a form submission.
    seats           INT             NOT NULL DEFAULT 1
                    CHECK (seats >= 1 AND seats <= 10),

    -- How many of that party actually turned up.
    --
    -- Separate from seats because a table of four routinely arrives as
    -- three. Counting the booking as fully attended would overstate every
    -- attendance figure on the platform, and counting it as absent would
    -- understate them - only a number can be honest here.
    seats_checked_in INT            NULL,

    -- Attendance. Booking a seat and turning up are different facts, and
    -- until now only the first was recorded - which is why every report
    -- could say how many people registered and none could say how many
    -- came.
    --
    -- NULL means "not checked in", which is also the state of every row
    -- that existed before this column did. There is no boolean, because
    -- a boolean could only say whether; the timestamp says when, and
    -- "when" is what tells an organizer that half their audience arrived
    -- forty minutes late.
    checked_in_at   DATETIME        NULL,
    -- Who did the checking in, for the same reason audit_logs exists at
    -- all: attendance is a record about a person, and a record nobody is
    -- accountable for is worth less. SET NULL rather than CASCADE - if
    -- the staff account is deleted the attendance still happened.
    checked_in_by   BIGINT          NULL,

    -- The rule is "one ACTIVE registration per attendee per event", which
    -- is NOT the same as "one row per attendee per event". The old
    -- UNIQUE(event_id, user_id) meant cancelling locked an attendee out of
    -- ever registering for that event again, because the cancelled row
    -- still occupied the constraint.
    --
    -- This generated column is NULL for cancelled rows, and MySQL permits
    -- any number of NULLs in a unique index - so an attendee may have a
    -- whole history of cancelled registrations plus at most one live one.
    -- No historical row (and therefore no payment or refund hanging off
    -- it) has to be deleted to let somebody sign up again.
    --
    -- A PENDING_PAYMENT hold counts as live, which is deliberate: it is
    -- holding one of this event's seats, so the same person must not be
    -- able to open a second checkout and hold two.
    active_user_id  BIGINT AS (IF(status = 'CANCELLED', NULL, user_id)) STORED,

    -- RESTRICT on both: registrations carry payment history, so neither a
    -- user nor an event can be deleted out from under them.
    CONSTRAINT fk_registration_event FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_registration_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_registration_checked_in_by FOREIGN KEY (checked_in_by) REFERENCES users(id)
        ON DELETE SET NULL,
    CONSTRAINT uq_registration_active UNIQUE (event_id, active_user_id),
    CONSTRAINT chk_registration_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL)),
    -- A deadline on a row that is not waiting for payment would be a
    -- deadline nothing acts on; a PENDING_PAYMENT row without one would
    -- be a seat held for ever. Neither is allowed to exist.
    CONSTRAINT chk_registration_payment_due CHECK (
        (status = 'PENDING_PAYMENT' AND payment_due_at IS NOT NULL)
        OR (status <> 'PENDING_PAYMENT' AND payment_due_at IS NULL)),
    -- Only somebody holding a seat can have turned up. A waitlisted or
    -- cancelled row with a check-in time would make the attendance
    -- figures nonsense; the application enforces this too, and the
    -- constraint below is the backstop that makes it true of the data
    -- regardless of what writes it.
    -- There is deliberately NO constraint pairing checked_in_at with
    -- checked_in_by. Two reasons, and the second is the real one:
    --
    --   1. MariaDB refuses it outright (error 1901) - a CHECK cannot
    --      reference a column that a foreign key sets to NULL on delete,
    --      because the delete would have to re-evaluate the check.
    --   2. The invariant would be wrong anyway. When a staff account is
    --      deleted, fk_registration_checked_in_by sets checked_in_by to
    --      NULL and leaves checked_in_at alone - which is exactly what
    --      should happen, because the attendee still turned up. A
    --      constraint demanding both or neither would forbid the one
    --      state this design exists to produce.
    --
    -- The application writes both together (see
    -- RegistrationServiceImpl.checkIn); the database only insists on the
    -- part that stays true for ever, which is the rule above: a
    -- check-in time means a CONFIRMED booking.
    CONSTRAINT chk_registration_checked_in CHECK (
        checked_in_at IS NULL OR status = 'CONFIRMED'),
    -- An attendance count and a check-in time are the same fact recorded
    -- twice, so neither may exist without the other. Without this, a row
    -- could claim three people attended while insisting nobody checked
    -- in - and the attendance report would quietly believe it.
    CONSTRAINT chk_registration_seats_checked_in CHECK (
        (checked_in_at IS NULL AND seats_checked_in IS NULL)
        OR (checked_in_at IS NOT NULL AND seats_checked_in IS NOT NULL
            AND seats_checked_in >= 1 AND seats_checked_in <= seats))
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Function 3: Payment & Ticketing
-- ---------------------------------------------------------------------
CREATE TABLE discounts (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        BIGINT          NOT NULL,
    type            VARCHAR(20)     NOT NULL
                    CHECK (type IN ('EARLY_BIRD', 'STUDENT', 'PROMO_CODE', 'GROUP')),
    code            VARCHAR(40),
    percent_off     DECIMAL(5,2),
    valid_from      DATETIME,
    valid_to        DATETIME,
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_discount_event FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE CASCADE,
    CONSTRAINT chk_discount_percent CHECK (percent_off IS NULL
                                        OR (percent_off >= 0 AND percent_off <= 100)),
    CONSTRAINT chk_discount_window  CHECK (valid_from IS NULL
                                        OR valid_to IS NULL
                                        OR valid_to > valid_from),
    -- A promo code only means anything if it is unique within its event.
    CONSTRAINT uq_discount_code UNIQUE (event_id, code)
) ENGINE=InnoDB;

CREATE TABLE payments (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- UNIQUE, not merely a foreign key: this is what makes "pay twice for
    -- the same registration" impossible even if two requests race past the
    -- service-level check.
    registration_id     BIGINT          NOT NULL UNIQUE,
    discount_id         BIGINT          NULL,
    original_amount     DECIMAL(10,2)   NOT NULL,
    discount_amount     DECIMAL(10,2)   NOT NULL DEFAULT 0.00,
    final_amount        DECIMAL(10,2)   NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    -- How the attendee chose to settle.
    --
    -- CARD goes through the payment gateway and either clears or is
    -- declined, there and then. ON_ARRIVAL does not: the attendee has
    -- undertaken to pay at the door, so the seat is held until the event
    -- starts and an organizer marks it received. Both end at the same
    -- place - a COMPLETED payment and a CONFIRMED booking - but only one
    -- of them involves a gateway, and the column is what lets the door
    -- list tell an organizer which people still owe money.
    method              VARCHAR(20)     NOT NULL DEFAULT 'CARD'
                        CHECK (method IN ('CARD', 'ON_ARRIVAL')),

    -- A ticket CODE, not a QR image. The QR square on the ticket page is
    -- drawn in the browser from this string; nothing about the attendee is
    -- encoded in it.
    ticket_code         VARCHAR(255)    UNIQUE,
    paid_at             TIMESTAMP       NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_registration FOREIGN KEY (registration_id)
        REFERENCES registrations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_discount FOREIGN KEY (discount_id)
        REFERENCES discounts(id) ON DELETE SET NULL,
    CONSTRAINT chk_payment_amounts CHECK (
        original_amount >= 0
        AND discount_amount >= 0
        AND final_amount >= 0
        AND discount_amount <= original_amount
        AND final_amount = original_amount - discount_amount)
) ENGINE=InnoDB;

CREATE TABLE refunds (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- UNIQUE: one refund per payment. Cancelling an event twice, or a
    -- listener firing twice, can never produce a second payout.
    payment_id      BIGINT          NOT NULL UNIQUE,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'PROCESSED', 'COMPLETED')),
    amount          DECIMAL(10,2)   NOT NULL,
    requested_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP       NULL,
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_refund_amount CHECK (amount > 0)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Function 4: Notifications
--
-- In-app only. The channel column keeps EMAIL and SMS as legal values
-- because the requirement list names them, but this application only ever
-- writes IN_APP rows - nothing here sends mail or SMS.
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    type            VARCHAR(30)     NOT NULL
                    CHECK (type IN
                        ('REGISTRATION_CONFIRMATION', 'PAYMENT_CONFIRMATION',
                         'TICKET_ISSUED', 'WAITLIST_UPDATE', 'EVENT_CHANGE',
                         'EVENT_CANCELLATION', 'REFUND_PROCESSED',
                         'EVENT_REMINDER', 'INVITATION_APPROVED',
                         'INVITATION_REJECTED',
                         -- The only type whose wording a person writes.
                         -- See the announcements table below.
                         'ANNOUNCEMENT')),
    channel         VARCHAR(20)     NOT NULL DEFAULT 'IN_APP'
                    CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS')),
    message         VARCHAR(500)    NOT NULL,
    is_read         BOOLEAN         NOT NULL DEFAULT FALSE,
    sent_at         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE CASCADE
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- announcements
--
-- A message an Organizer or an Administrator writes BY HAND to the
-- attendees of one event - "the venue entrance has moved", "bring your
-- student ID". Every other notification in this system is produced by the
-- system reacting to something; this is the one a person composes.
--
-- Which is exactly why it needs an approval step. A notification the
-- system generates is bounded by the code that generates it: ten types,
-- fixed wording. A free-text message an organizer writes reaches every
-- attendee's inbox in their own words, and nobody would see it before
-- they did. So an Organizer's announcement is a DRAFT until an
-- Administrator approves it - the same shape as the event lifecycle, for
-- the same reason.
--
-- An Administrator publishes directly. They are the approver; routing
-- their draft into a queue they approve themselves would be ceremony, not
-- control.
--
-- The row survives publication and is never edited afterwards: it is the
-- record of what was sent, to whom, and who approved it. The notification
-- rows it produced are separate and belong to their recipients.
-- ---------------------------------------------------------------------
CREATE TABLE announcements (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,

    -- The audience is "everyone holding a place on this event", resolved
    -- at publish time rather than stored - a list captured at draft time
    -- would already be wrong by the time it was approved.
    event_id        BIGINT          NOT NULL,

    -- Who wrote it. RESTRICT, not CASCADE: an announcement is a record of
    -- something that was said to people, and deleting the author would
    -- erase who said it.
    author_id       BIGINT          NOT NULL,

    title           VARCHAR(150)    NOT NULL,
    message         VARCHAR(1000)   NOT NULL,

    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT'
                    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL',
                                      'PUBLISHED', 'REJECTED')),

    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at    DATETIME        NULL,
    decided_at      DATETIME        NULL,

    -- The Administrator who approved or rejected it. SET NULL rather than
    -- RESTRICT: if that account is ever removed, the decision still
    -- happened even though we can no longer name who made it.
    decided_by      BIGINT          NULL,

    -- Why it was rejected, so the organizer can fix it rather than guess.
    reject_reason   VARCHAR(500)    NULL,

    published_at    DATETIME        NULL,

    -- How many inboxes it actually reached. Stored because it is a fact
    -- about the moment of publication - recounting the event's attendees
    -- later would answer a different question.
    recipient_count INT             NULL,

    CONSTRAINT fk_announcement_event FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_announcement_author FOREIGN KEY (author_id) REFERENCES users(id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_announcement_decided_by FOREIGN KEY (decided_by) REFERENCES users(id)
        ON DELETE SET NULL,

    -- A published announcement must record when it went out and to how
    -- many people; an unpublished one must not pretend it did.
    CONSTRAINT chk_announcement_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL AND recipient_count IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL AND recipient_count IS NULL)),

    -- A rejection without a reason is the thing organizers complain about.
    CONSTRAINT chk_announcement_rejected CHECK (
        status <> 'REJECTED' OR reject_reason IS NOT NULL)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- saved_reports
--
-- A report DEFINITION - a title, a type, an optional event and an
-- optional date window - saved by one user so they can open the same
-- question again tomorrow.
--
-- Read this table carefully, because it is the one place Reporting
-- writes anything, and BR-31 says reporting may never alter platform
-- data. Both are true: the FIGURES are still computed live on every open,
-- from events, registrations and payments, and nothing here stores a
-- number. What is stored is the question, never the answer - so a saved
-- report cannot go stale, and cannot be edited into saying something the
-- data does not.
-- ---------------------------------------------------------------------
CREATE TABLE saved_reports (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT,

    -- Reports are personal. CASCADE is right here and nowhere else in
    -- this schema: a saved question has no meaning once the person who
    -- saved it is gone, and it is not evidence of anything.
    owner_id        BIGINT          NOT NULL,

    title           VARCHAR(120)    NOT NULL,

    report_type     VARCHAR(40)     NOT NULL
                    CHECK (report_type IN (
                        -- Attendee: about their own activity only
                        'MY_EVENT_ACTIVITY', 'MY_ATTENDANCE', 'MY_PAYMENT_HISTORY',
                        -- Organizer: about one event they own
                        'EVENT_PERFORMANCE', 'EVENT_ATTENDANCE', 'EVENT_REVENUE',
                        'EVENT_WAITLIST_DEMAND',
                        -- Administrator: platform-wide
                        'PLATFORM_OVERVIEW', 'REGISTRATION_ANALYTICS',
                        'ATTENDANCE_ANALYTICS', 'REVENUE_AND_REFUNDS',
                        'CATEGORY_POPULARITY')),

    -- Required by the EVENT_* types and forbidden for the others; the
    -- service enforces which, because a CHECK cannot read the enum's own
    -- notion of scope.
    event_id        BIGINT          NULL,

    date_from       DATE            NULL,
    date_to         DATE            NULL,

    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NULL,

    CONSTRAINT fk_saved_report_owner FOREIGN KEY (owner_id) REFERENCES users(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_saved_report_event FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE CASCADE,

    -- A window that ends before it starts would silently report nothing.
    CONSTRAINT chk_saved_report_window CHECK (
        date_from IS NULL OR date_to IS NULL OR date_from <= date_to),

    -- One person cannot save the same report title twice, which is what
    -- stops a list of six identically named "Revenue" reports.
    CONSTRAINT uq_saved_report_title UNIQUE (owner_id, title)
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------
-- Indexes
--
-- One per query pattern the application actually runs - not a blanket
-- index on every column. The UNIQUE constraints above already index
-- payments.registration_id, payments.ticket_code, refunds.payment_id,
-- event_invitations(event_id, user_id) and
-- registrations(event_id, active_user_id), so those are not repeated.
-- ---------------------------------------------------------------------
CREATE INDEX idx_events_status         ON events(status);
CREATE INDEX idx_events_category       ON events(category);
CREATE INDEX idx_events_organizer      ON events(organizer_id);
-- Backs the venue double-booking check (venue + overlapping time window).
CREATE INDEX idx_events_venue_time     ON events(venue, start_datetime, end_datetime);

CREATE INDEX idx_registrations_event_status ON registrations(event_id, status);
-- Backs "my registrations", newest first.
CREATE INDEX idx_registrations_user         ON registrations(user_id, registered_at);
-- Backs the attendance count per event, which the reports page asks for
-- on every load.
CREATE INDEX idx_registrations_checked_in   ON registrations(event_id, checked_in_at);
-- Backs the sweep that releases expired payment holds. It runs every few
-- minutes and must not scan the whole table to find the handful of rows
-- that have timed out.
CREATE INDEX idx_registrations_payment_due  ON registrations(status, payment_due_at);

CREATE INDEX idx_invitations_user      ON event_invitations(user_id);
CREATE INDEX idx_invitations_status    ON event_invitations(event_id, status);

CREATE INDEX idx_discounts_event       ON discounts(event_id);
CREATE INDEX idx_payments_status       ON payments(status);
CREATE INDEX idx_refunds_status        ON refunds(status);

-- Backs the unread-count badge and the notifications page.
CREATE INDEX idx_notifications_user_read ON notifications(user_id, is_read, sent_at);

-- Backs the administrator's approval queue, which asks for exactly one
-- status across every event.
CREATE INDEX idx_announcements_status  ON announcements(status, submitted_at);
-- Backs "the announcements on this event", the organizer's own list.
CREATE INDEX idx_announcements_event   ON announcements(event_id, status);
CREATE INDEX idx_announcements_author  ON announcements(author_id);

-- Backs "my saved reports", which is the only way this table is read.
CREATE INDEX idx_saved_reports_owner   ON saved_reports(owner_id, created_at);

CREATE INDEX idx_audit_entity          ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_reset_token_user      ON password_reset_tokens(user_id, used);
CREATE INDEX idx_users_role            ON users(role, is_active);

SET FOREIGN_KEY_CHECKS = 1;


-- =====================================================================
-- PART C - SEED DATA
-- =====================================================================

-- =====================================================================
-- EventSphere - seed data
--
-- GENERATED FILE. Edit gen_seed.py and re-run:
--     python3 gen_seed.py > 02_seed_data.sql
--
-- Every datetime is an offset from load time, so the data stays
-- current no matter when it is loaded - a PUBLISHED event is always
-- in the future and a COMPLETED one is always in the past.
--
-- Derived values are computed by the generator, not hand-written:
--   events.seats_taken  = SEATS held by CONFIRMED registrations
--   payments.final      = original - discount actually applied
--   refunds.amount      = the completed payment being reversed
--
-- Demo logins (BCrypt hashes, plaintext listed for the team):
--   admin@eventsphere.test      / admin123      (ADMIN)
--   harini.w@eventsphere.test   / admin123      (ADMIN)
--   organizer@eventsphere.test  / organizer123  (ORGANIZER)
--   organizer2@eventsphere.test / organizer123  (ORGANIZER)
--   attendee@eventsphere.test   / attendee123   (ATTENDEE)
--   student@eventsphere.test    / student123    (ATTENDEE, verified student)
--   waitlist@eventsphere.test   / attendee123   (ATTENDEE)
--   suspended@eventsphere.test  / attendee123   (ATTENDEE, is_active = FALSE)
-- =====================================================================

SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM audit_logs;
ALTER TABLE audit_logs AUTO_INCREMENT = 1;
DELETE FROM notifications;
ALTER TABLE notifications AUTO_INCREMENT = 1;
DELETE FROM refunds;
ALTER TABLE refunds AUTO_INCREMENT = 1;
DELETE FROM payments;
ALTER TABLE payments AUTO_INCREMENT = 1;
DELETE FROM event_invitations;
ALTER TABLE event_invitations AUTO_INCREMENT = 1;
DELETE FROM registrations;
ALTER TABLE registrations AUTO_INCREMENT = 1;
DELETE FROM discounts;
ALTER TABLE discounts AUTO_INCREMENT = 1;
DELETE FROM event_sessions;
ALTER TABLE event_sessions AUTO_INCREMENT = 1;
DELETE FROM events;
ALTER TABLE events AUTO_INCREMENT = 1;
DELETE FROM password_reset_tokens;
ALTER TABLE password_reset_tokens AUTO_INCREMENT = 1;
DELETE FROM users;
ALTER TABLE users AUTO_INCREMENT = 1;
SET FOREIGN_KEY_CHECKS = 1;

-- --------------------------------------------------------------- users
INSERT INTO users (id, full_name, email, password_hash, contact_number, role, is_active, is_verified_student, created_at) VALUES
(1, 'Nimal Rajapaksa', 'admin@eventsphere.test', '$2a$10$wmWezCyqQyea3D9avG27eOMUev5G/1QTKr4Vh3x7axgYIDmKSsbiy', '0771000001', 'ADMIN', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -126 DAY), INTERVAL 600 MINUTE)),
(2, 'Kasun Perera', 'organizer@eventsphere.test', '$2a$10$/ln9.Om365x2eZcpZ.bQ4.k.bZWi/gsvmE1M2D7wJMc4i21zS3gGS', '0771000002', 'ORGANIZER', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -302 DAY), INTERVAL 600 MINUTE)),
(3, 'Dilani Wickramasinghe', 'attendee@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000003', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -244 DAY), INTERVAL 600 MINUTE)),
(4, 'Sanduni Jayawardena', 'student@eventsphere.test', '$2a$10$p8gzGa1EOzH.zotV6/QU4eEih0IE4AdHftN.IY3X0MrKuAee3kcb6', '0771000004', 'ATTENDEE', 1, 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -191 DAY), INTERVAL 600 MINUTE)),
(5, 'Ishara Fernando', 'organizer2@eventsphere.test', '$2a$10$/ln9.Om365x2eZcpZ.bQ4.k.bZWi/gsvmE1M2D7wJMc4i21zS3gGS', '0771000005', 'ORGANIZER', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -301 DAY), INTERVAL 600 MINUTE)),
(6, 'Ravindu Bandara', 'waitlist@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000006', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -325 DAY), INTERVAL 600 MINUTE)),
(7, 'Chamara Gunasekara', 'suspended@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000007', 'ATTENDEE', 0, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -147 DAY), INTERVAL 600 MINUTE)),
(8, 'Thilini Abeywardena', 'vip@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000008', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -350 DAY), INTERVAL 600 MINUTE)),
(9, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', '$2a$10$/ln9.Om365x2eZcpZ.bQ4.k.bZWi/gsvmE1M2D7wJMc4i21zS3gGS', '0771000009', 'ORGANIZER', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -261 DAY), INTERVAL 600 MINUTE)),
(10, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', '$2a$10$/ln9.Om365x2eZcpZ.bQ4.k.bZWi/gsvmE1M2D7wJMc4i21zS3gGS', '0771000010', 'ORGANIZER', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -257 DAY), INTERVAL 600 MINUTE)),
(11, 'Malith Ekanayake', 'malith.e@eventsphere.test', '$2a$10$/ln9.Om365x2eZcpZ.bQ4.k.bZWi/gsvmE1M2D7wJMc4i21zS3gGS', '0771000011', 'ORGANIZER', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -245 DAY), INTERVAL 600 MINUTE)),
(12, 'Harini Weerasinghe', 'harini.w@eventsphere.test', '$2a$10$wmWezCyqQyea3D9avG27eOMUev5G/1QTKr4Vh3x7axgYIDmKSsbiy', '0771000012', 'ADMIN', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -213 DAY), INTERVAL 600 MINUTE)),
(13, 'Tharindu Jayasuriya', 'tharindu.j@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000013', 'ATTENDEE', 1, 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -290 DAY), INTERVAL 600 MINUTE)),
(14, 'Nadeesha Silva', 'nadeesha.s@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000014', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -149 DAY), INTERVAL 600 MINUTE)),
(15, 'Kavindu Ratnayake', 'kavindu.r@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000015', 'ATTENDEE', 1, 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -288 DAY), INTERVAL 600 MINUTE)),
(16, 'Amaya Liyanage', 'amaya.l@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000016', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 600 MINUTE)),
(17, 'Shehan Karunaratne', 'shehan.k@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000017', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -100 DAY), INTERVAL 600 MINUTE)),
(18, 'Piyumi Herath', 'piyumi.h@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000018', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -372 DAY), INTERVAL 600 MINUTE)),
(19, 'Dinuka Amarasinghe', 'dinuka.a@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000019', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -333 DAY), INTERVAL 600 MINUTE)),
(20, 'Rashmi Gunawardena', 'rashmi.g@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000020', 'ATTENDEE', 1, 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -237 DAY), INTERVAL 600 MINUTE)),
(21, 'Isuru Madushanka', 'isuru.m@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000021', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -354 DAY), INTERVAL 600 MINUTE)),
(22, 'Nethmi Samarasinghe', 'nethmi.s@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000022', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -68 DAY), INTERVAL 600 MINUTE)),
(23, 'Lahiru Peiris', 'lahiru.p@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000023', 'ATTENDEE', 1, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -135 DAY), INTERVAL 600 MINUTE)),
(24, 'Oshadi Kumarasiri', 'oshadi.k@eventsphere.test', '$2a$10$2JIEyz2VMidaXH53jwpt1ua5ugV9YoPUKtqYBFJmOaMozzWhLptU.', '0771000024', 'ATTENDEE', 0, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -94 DAY), INTERVAL 600 MINUTE));

-- -------------------------------------------------------------- events
INSERT INTO events (id, organizer_id, name, description, category, event_type, status, venue,
                    start_datetime, end_datetime, capacity, seats_taken,
                    registration_open_date, registration_deadline, fee,
                    contact_person, contact_details, banner_url, special_instructions, created_at) VALUES
(1, 5, 'Intro to Cloud Computing Workshop', 'A hands-on workshop covering cloud infrastructure, containers and deployment pipelines, with lab exercises on AWS and Docker.', 'Workshop', 'PUBLIC', 'PUBLISHED', 'BMICH, Colombo 07', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 1500 MINUTE), 250, 16, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 1439 MINUTE), 2500.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1451187580459-43490279c0fa?auto=format&fit=crop&w=1400&q=80', 'Bring a laptop. Power outlets are available at every seat.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -21 DAY), INTERVAL 540 MINUTE)),
(2, 9, 'AI & Data Science Conference 2026', 'Two days of talks on applied machine learning, MLOps and responsible AI, with practitioners from across the region.', 'Conference', 'PUBLIC', 'PUBLISHED', 'Cinnamon Grand Ballroom, Colombo 03', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 1020 MINUTE), 100, 19, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 1439 MINUTE), 0.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1540575467063-178a50c2df87?auto=format&fit=crop&w=1400&q=80', 'Conference pack and lunch included. Parking is limited.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 540 MINUTE)),
(3, 10, 'Executive Leadership Retreat', 'An invitation-only retreat for senior managers covering strategy, negotiation and team development.', 'Business', 'RESTRICTED', 'PUBLISHED', 'Trace Expert City, Colombo 10', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 840 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 1260 MINUTE), 150, 19, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 1439 MINUTE), 4500.00, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', 'https://images.unsplash.com/photo-1511578314322-379afb476865?auto=format&fit=crop&w=1400&q=80', 'Business attire. Accommodation is arranged for out-of-town guests.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 540 MINUTE)),
(4, 11, 'Startup Pitch Night', 'Ten early-stage founders pitch to a panel of investors, followed by open networking over refreshments.', 'Networking', 'PUBLIC', 'PUBLISHED', 'Galle Face Green, Colombo 03', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 960 MINUTE), 300, 16, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -21 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 1439 MINUTE), 0.00, 'Malith Ekanayake', 'malith.e@eventsphere.test', 'https://images.unsplash.com/photo-1505373877841-8d25f7d46678?auto=format&fit=crop&w=1400&q=80', 'Pitch decks must be submitted 48 hours in advance.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -24 DAY), INTERVAL 540 MINUTE)),
(5, 2, 'Photography Masterclass', 'A full-day masterclass on composition, natural light and post-processing, ending with a golden-hour shoot.', 'Arts', 'PUBLIC', 'PUBLISHED', 'Shangri-La Hotel, Colombo 02', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 780 MINUTE), 120, 20, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 1439 MINUTE), 2500.00, 'Kasun Perera', 'organizer@eventsphere.test', 'https://images.unsplash.com/photo-1492684223066-81342ee5ff30?auto=format&fit=crop&w=1400&q=80', 'Bring your own camera. A tripod is recommended.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -29 DAY), INTERVAL 540 MINUTE)),
(6, 5, 'Web Security Bootcamp', 'A practical bootcamp on the OWASP Top Ten, secure session handling and access control, with a live capture-the-flag.', 'Tech', 'PUBLIC', 'PUBLISHED', 'Nelum Pokuna Theatre, Colombo 07', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1560 MINUTE), 300, 17, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 1439 MINUTE), 8000.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1531058020387-3be344556be6?auto=format&fit=crop&w=1400&q=80', 'Laptops required. A virtual machine image is sent beforehand.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 540 MINUTE)),
(7, 9, 'Colombo Jazz Evening', 'An evening of contemporary and classic jazz featuring three local ensembles and a guest vocalist.', 'Music', 'PUBLIC', 'PUBLISHED', 'Hilton Colombo, Colombo 01', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 900 MINUTE), 14, 14, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 1439 MINUTE), 2000.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1544928147-79a2dbc1f389?auto=format&fit=crop&w=1400&q=80', 'Doors open one hour before the first set.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 540 MINUTE)),
(8, 10, 'Full-Stack Java Career Fair', 'Meet twenty hiring companies looking for Java, Spring and React engineers. Bring printed CVs.', 'Networking', 'PUBLIC', 'PUBLISHED', 'Lighthouse Hotel, Galle', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1560 MINUTE), 40, 18, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 1439 MINUTE), 2500.00, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', 'https://images.unsplash.com/photo-1475721027785-f74eccf877e2?auto=format&fit=crop&w=1400&q=80', 'Formal dress. CV review desk opens at 09:00.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 540 MINUTE)),
(9, 11, 'Digital Marketing Summit', 'Sessions on performance marketing, SEO, analytics and content strategy for growing brands.', 'Business', 'PUBLIC', 'PUBLISHED', 'Earl''s Regency, Kandy', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 840 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 1320 MINUTE), 50, 16, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 1439 MINUTE), 8000.00, 'Malith Ekanayake', 'malith.e@eventsphere.test', 'https://images.unsplash.com/photo-1523580494863-6f3031224c94?auto=format&fit=crop&w=1400&q=80', 'Includes a certificate of participation.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 540 MINUTE)),
(10, 2, 'Mobile App Development Workshop', 'Build and publish a cross-platform mobile application in a day using Flutter, from project setup to store submission.', 'Workshop', 'PUBLIC', 'PUBLISHED', 'Waters Edge, Battaramulla', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 780 MINUTE), 60, 19, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 1439 MINUTE), 1000.00, 'Kasun Perera', 'organizer@eventsphere.test', 'https://images.unsplash.com/photo-1540039155733-5bb30b53aa14?auto=format&fit=crop&w=1400&q=80', 'Install Flutter and Android Studio in advance.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 540 MINUTE)),
(11, 5, 'Sri Lanka Tech Innovators Forum', 'A forum on emerging technology policy, digital infrastructure and the local startup ecosystem.', 'Conference', 'PUBLIC', 'PUBLISHED', 'Monarch Imperial, Sri Jayawardenepura', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 720 MINUTE), 50, 19, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 53 DAY), INTERVAL 1439 MINUTE), 8000.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1517457373958-b7bdd4587205?auto=format&fit=crop&w=1400&q=80', 'Registration closes one week before the event.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 540 MINUTE)),
(12, 9, 'Yoga and Mindfulness Morning', 'A guided morning session of hatha yoga and breathing practice suitable for complete beginners.', 'Wellness', 'PUBLIC', 'PUBLISHED', 'Jetwing Blue, Negombo', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1620 MINUTE), 200, 22, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -53 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 1439 MINUTE), 0.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1559223607-a43c990c692c?auto=format&fit=crop&w=1400&q=80', 'Bring a mat and a water bottle. Wear comfortable clothing.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -56 DAY), INTERVAL 540 MINUTE)),
(13, 10, 'Food Festival: Street Eats', 'Thirty vendors serving Sri Lankan street food, with live cooking demonstrations through the afternoon.', 'Food', 'PUBLIC', 'PUBLISHED', 'SLIIT Kandy Uni Centre, Kandy', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 1020 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 1260 MINUTE), 50, 14, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 1439 MINUTE), 1000.00, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', 'https://images.unsplash.com/photo-1478147427282-58a87a120781?auto=format&fit=crop&w=1400&q=80', 'Cash and card accepted at all stalls.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -33 DAY), INTERVAL 540 MINUTE)),
(14, 11, 'Corporate Cricket Sixes', 'A one-day six-a-side tournament for corporate teams, with trophies for the winners and runners-up.', 'Sports', 'PUBLIC', 'PUBLISHED', 'MAS Innovation Hub, Colombo 05', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 840 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 1080 MINUTE), 14, 14, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 60 DAY), INTERVAL 1439 MINUTE), 1000.00, 'Malith Ekanayake', 'malith.e@eventsphere.test', 'https://images.unsplash.com/photo-1526772662000-3f88f10405ff?auto=format&fit=crop&w=1400&q=80', 'Teams of eight including reserves. Whites not required.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 540 MINUTE)),
(15, 2, 'UI/UX Design Sprint', 'A compressed five-stage design sprint: map, sketch, decide, prototype and test, run over a single day.', 'Workshop', 'PUBLIC', 'PUBLISHED', 'Port City Exhibition Hall, Colombo', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 1380 MINUTE), 80, 22, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 1439 MINUTE), 0.00, 'Kasun Perera', 'organizer@eventsphere.test', 'https://images.unsplash.com/photo-1536440136628-849c177e76a1?auto=format&fit=crop&w=1400&q=80', 'Figma account required. Teams are assigned on arrival.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -49 DAY), INTERVAL 540 MINUTE)),
(16, 5, 'Cybersecurity Awareness Seminar', 'A non-technical seminar for staff on phishing, password hygiene, device security and safe remote working.', 'Tech', 'PUBLIC', 'PUBLISHED', 'SLIIT Auditorium, Malabe', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 1080 MINUTE), 150, 16, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 1439 MINUTE), 6000.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1470229722913-7ea0d7a3c3c4?auto=format&fit=crop&w=1400&q=80', 'Suitable for all staff regardless of technical background.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 540 MINUTE)),
(17, 9, 'Annual Alumni Networking Dinner', 'The annual dinner for graduates, with a keynote from a distinguished alumnus and table networking by industry.', 'Networking', 'RESTRICTED', 'PUBLISHED', 'BMICH, Colombo 07', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 44 DAY), INTERVAL 1080 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 44 DAY), INTERVAL 1560 MINUTE), 150, 14, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 41 DAY), INTERVAL 1439 MINUTE), 0.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?auto=format&fit=crop&w=1400&q=80', 'Invitation required. Please confirm dietary requirements.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 540 MINUTE)),
(18, 10, 'Cloud Native Kubernetes Deep Dive', 'An advanced session on Kubernetes operators, service mesh and progressive delivery, with a live cluster.', 'Tech', 'PUBLIC', 'PUBLISHED', 'Cinnamon Grand Ballroom, Colombo 03', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 780 MINUTE), 250, 22, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 1439 MINUTE), 0.00, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', 'https://images.unsplash.com/photo-1533174072545-7a4b6ad7a6c3?auto=format&fit=crop&w=1400&q=80', 'Prior Kubernetes experience assumed.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 540 MINUTE)),
(19, 11, 'Classical Music Gala', 'A gala evening of chamber and orchestral works performed by the national symphony ensemble.', 'Music', 'PUBLIC', 'PUBLISHED', 'Trace Expert City, Colombo 10', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 68 DAY), INTERVAL 1020 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 68 DAY), INTERVAL 1440 MINUTE), 120, 14, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 65 DAY), INTERVAL 1439 MINUTE), 3000.00, 'Malith Ekanayake', 'malith.e@eventsphere.test', 'https://images.unsplash.com/photo-1556761175-b413da4baf72?auto=format&fit=crop&w=1400&q=80', 'Latecomers are seated between movements only.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 540 MINUTE)),
(20, 2, 'Product Management Essentials', 'A structured introduction to discovery, roadmapping, prioritisation frameworks and stakeholder management.', 'Business', 'PUBLIC', 'PUBLISHED', 'Galle Face Green, Colombo 03', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 780 MINUTE), 100, 16, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 1439 MINUTE), 6000.00, 'Kasun Perera', 'organizer@eventsphere.test', 'https://images.unsplash.com/photo-1517048676732-d65bc937f952?auto=format&fit=crop&w=1400&q=80', 'Includes a workbook and templates.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 540 MINUTE)),
(21, 5, 'Marathon Training Clinic', 'A coaching clinic on pacing, nutrition, injury prevention and race-day strategy for first-time marathoners.', 'Sports', 'PUBLIC', 'PENDING_APPROVAL', 'Shangri-La Hotel, Colombo 02', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 75 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 75 DAY), INTERVAL 900 MINUTE), 30, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 74 DAY), INTERVAL 1439 MINUTE), 0.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1509062522246-3755977927d7?auto=format&fit=crop&w=1400&q=80', 'Running shoes required. Session includes a 5km practice run.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 540 MINUTE)),
(22, 9, 'Contemporary Art Exhibition Opening', 'Opening night for a group exhibition of contemporary painting and mixed media by twelve regional artists.', 'Arts', 'PUBLIC', 'PENDING_APPROVAL', 'Nelum Pokuna Theatre, Colombo 07', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 52 DAY), INTERVAL 840 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 52 DAY), INTERVAL 1020 MINUTE), 200, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 50 DAY), INTERVAL 1439 MINUTE), 0.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1524178232363-1fb2b075b655?auto=format&fit=crop&w=1400&q=80', 'Refreshments served. Works are available for purchase.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 540 MINUTE)),
(23, 10, 'DevOps Automation Workshop', 'Build a complete CI/CD pipeline with GitHub Actions, automated testing, and infrastructure as code.', 'Workshop', 'PUBLIC', 'PENDING_APPROVAL', 'Hilton Colombo, Colombo 01', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 1020 MINUTE), 250, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 1439 MINUTE), 6000.00, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', 'https://images.unsplash.com/photo-1497633762265-9d179a990aa6?auto=format&fit=crop&w=1400&q=80', 'A GitHub account is required.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -19 DAY), INTERVAL 540 MINUTE)),
(24, 11, 'Wellness and Nutrition Talk', 'An evidence-based talk on balanced nutrition, sleep and stress management for people in desk-based work.', 'Wellness', 'PUBLIC', 'COMPLETED', 'Lighthouse Hotel, Galle', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 900 MINUTE), 200, 20, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -63 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -20 DAY), INTERVAL 1439 MINUTE), 0.00, 'Malith Ekanayake', 'malith.e@eventsphere.test', 'https://images.unsplash.com/photo-1502920917128-1aa500764cbd?auto=format&fit=crop&w=1400&q=80', 'Question-and-answer session at the end.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 540 MINUTE)),
(25, 2, 'Blockchain and Fintech Forum', 'Industry perspectives on digital payments, regulation and distributed ledger applications in South Asia.', 'Conference', 'PUBLIC', 'COMPLETED', 'Earl''s Regency, Kandy', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 1560 MINUTE), 150, 16, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -92 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -35 DAY), INTERVAL 1439 MINUTE), 4500.00, 'Kasun Perera', 'organizer@eventsphere.test', 'https://images.unsplash.com/photo-1571019613454-1cb2f99b2d8b?auto=format&fit=crop&w=1400&q=80', 'Includes a panel with regulators and fintech founders.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -95 DAY), INTERVAL 540 MINUTE)),
(26, 5, 'Creative Writing Retreat', 'A small-group retreat with structured workshops on narrative voice, structure and revision.', 'Arts', 'RESTRICTED', 'COMPLETED', 'Waters Edge, Battaramulla', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 720 MINUTE), 300, 18, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -90 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 1439 MINUTE), 4500.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1540497077202-7c8a3999166f?auto=format&fit=crop&w=1400&q=80', 'Places are limited to fifteen. A writing sample is requested.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -93 DAY), INTERVAL 540 MINUTE)),
(27, 9, 'Cloud Cost Optimisation Clinic', 'A practical clinic on right-sizing, reserved capacity and tagging strategy to reduce monthly cloud spend.', 'Tech', 'PUBLIC', 'COMPLETED', 'Monarch Imperial, Sri Jayawardenepura', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 840 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 1200 MINUTE), 120, 22, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -54 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 1439 MINUTE), 1000.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?auto=format&fit=crop&w=1400&q=80', 'Bring a recent cloud bill if you would like it reviewed.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 540 MINUTE)),
(28, 10, 'Vegan Cooking Class', 'A hands-on class producing three plant-based Sri Lankan dishes, with all ingredients provided.', 'Food', 'PUBLIC', 'COMPLETED', 'Jetwing Blue, Negombo', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 960 MINUTE), 50, 18, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -105 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 1439 MINUTE), 2000.00, 'Pubudu Senanayake', 'pubudu.s@eventsphere.test', 'https://images.unsplash.com/photo-1511578314322-379afb476865?auto=format&fit=crop&w=1400&q=80', 'Aprons provided. Please declare any allergies.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -108 DAY), INTERVAL 540 MINUTE)),
(29, 11, 'Women in Tech Panel', 'A panel and open discussion on career progression, mentorship and inclusive engineering culture.', 'Networking', 'PUBLIC', 'COMPLETED', 'SLIIT Kandy Uni Centre, Kandy', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -75 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -75 DAY), INTERVAL 780 MINUTE), 50, 22, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -135 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 1439 MINUTE), 6000.00, 'Malith Ekanayake', 'malith.e@eventsphere.test', 'https://images.unsplash.com/photo-1587825140708-dfaf72ae4b04?auto=format&fit=crop&w=1400&q=80', 'Open to all. Refreshments from 17:30.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -138 DAY), INTERVAL 540 MINUTE)),
(30, 2, 'Advanced Spring Boot Training', 'Two days on Spring Boot internals: auto-configuration, transactions, testing strategy and observability.', 'Workshop', 'PUBLIC', 'CANCELLED', 'MAS Innovation Hub, Colombo 05', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 1020 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 1440 MINUTE), 100, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1439 MINUTE), 0.00, 'Kasun Perera', 'organizer@eventsphere.test', 'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?auto=format&fit=crop&w=1400&q=80', 'Java 17 and Maven should be installed beforehand.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 540 MINUTE)),
(31, 5, 'Digital Photography Walk', 'A guided street-photography walk through the old quarter, ending with a group critique session.', 'Arts', 'PUBLIC', 'CANCELLED', 'Port City Exhibition Hall, Colombo', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 1080 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 1260 MINUTE), 100, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -70 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 1439 MINUTE), 8000.00, 'Ishara Fernando', 'organizer2@eventsphere.test', 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?auto=format&fit=crop&w=1400&q=80', 'Any camera, including a phone, is fine.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 540 MINUTE)),
(32, 9, 'Enterprise Architecture Symposium', 'Talks on domain-driven design, integration patterns and modernising legacy estates at scale.', 'Conference', 'PUBLIC', 'CANCELLED', 'SLIIT Auditorium, Malabe', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 900 MINUTE), 150, 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 1439 MINUTE), 1500.00, 'Ayesha Dissanayake', 'ayesha.d@eventsphere.test', 'https://images.unsplash.com/photo-1505236858219-8359eb29e329?auto=format&fit=crop&w=1400&q=80', 'Slides are circulated after the event.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 540 MINUTE));

-- ------------------------------------------------------ event_sessions
INSERT INTO event_sessions (id, event_id, title, description, start_time, end_time, speaker) VALUES
(1, 2, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 600 MINUTE), 'Event Team'),
(2, 2, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 660 MINUTE), 'Dr. Anusha Rajapaksa'),
(3, 2, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 720 MINUTE), 'Ruwan De Silva'),
(4, 2, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 780 MINUTE), 'Nimali Gunawardena'),
(5, 2, 'Hands-on Lab', 'A guided practical exercise completed in small groups.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 780 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 840 MINUTE), 'Kasun Perera'),
(6, 4, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 600 MINUTE), 'Event Team'),
(7, 4, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 660 MINUTE), 'Dr. Anusha Rajapaksa'),
(8, 4, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE), 'Ruwan De Silva'),
(9, 4, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 780 MINUTE), 'Nimali Gunawardena'),
(10, 4, 'Hands-on Lab', 'A guided practical exercise completed in small groups.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 780 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 840 MINUTE), 'Kasun Perera'),
(11, 6, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1200 MINUTE), 'Event Team'),
(12, 6, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1200 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1260 MINUTE), 'Dr. Anusha Rajapaksa'),
(13, 6, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1260 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 1320 MINUTE), 'Ruwan De Silva'),
(14, 8, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1200 MINUTE), 'Event Team'),
(15, 8, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1200 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1260 MINUTE), 'Dr. Anusha Rajapaksa'),
(16, 8, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1260 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1320 MINUTE), 'Ruwan De Silva'),
(17, 8, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1320 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1380 MINUTE), 'Nimali Gunawardena'),
(18, 8, 'Hands-on Lab', 'A guided practical exercise completed in small groups.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1380 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 1440 MINUTE), 'Kasun Perera'),
(19, 10, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 660 MINUTE), 'Event Team'),
(20, 10, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE), 'Dr. Anusha Rajapaksa'),
(21, 10, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 780 MINUTE), 'Ruwan De Silva'),
(22, 12, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1140 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1200 MINUTE), 'Event Team'),
(23, 12, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1200 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1260 MINUTE), 'Dr. Anusha Rajapaksa'),
(24, 12, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1260 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1320 MINUTE), 'Ruwan De Silva'),
(25, 12, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1320 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1380 MINUTE), 'Nimali Gunawardena'),
(26, 12, 'Hands-on Lab', 'A guided practical exercise completed in small groups.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1380 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 1440 MINUTE), 'Kasun Perera'),
(27, 14, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 840 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 900 MINUTE), 'Event Team'),
(28, 14, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 900 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 960 MINUTE), 'Dr. Anusha Rajapaksa'),
(29, 14, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 960 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 1020 MINUTE), 'Ruwan De Silva'),
(30, 16, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 660 MINUTE), 'Event Team'),
(31, 16, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 720 MINUTE), 'Dr. Anusha Rajapaksa'),
(32, 16, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 780 MINUTE), 'Ruwan De Silva'),
(33, 16, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 780 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 840 MINUTE), 'Nimali Gunawardena'),
(34, 18, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 600 MINUTE), 'Event Team'),
(35, 18, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 660 MINUTE), 'Dr. Anusha Rajapaksa'),
(36, 18, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE), 'Ruwan De Silva'),
(37, 20, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 600 MINUTE), 'Event Team'),
(38, 20, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 660 MINUTE), 'Dr. Anusha Rajapaksa'),
(39, 20, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE), 'Ruwan De Silva'),
(40, 20, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 780 MINUTE), 'Nimali Gunawardena'),
(41, 24, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 600 MINUTE), 'Event Team'),
(42, 24, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 660 MINUTE), 'Dr. Anusha Rajapaksa'),
(43, 24, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 720 MINUTE), 'Ruwan De Silva'),
(44, 24, 'Panel Discussion', 'A moderated panel taking questions from the floor.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 780 MINUTE), 'Nimali Gunawardena'),
(45, 26, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 600 MINUTE), 'Event Team'),
(46, 26, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 660 MINUTE), 'Dr. Anusha Rajapaksa'),
(47, 26, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 720 MINUTE), 'Ruwan De Silva'),
(48, 28, 'Registration and Welcome Coffee', 'Badge collection, refreshments and an introduction to the day.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 600 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 660 MINUTE), 'Event Team'),
(49, 28, 'Opening Keynote', 'The opening address setting out the themes of the programme.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 720 MINUTE), 'Dr. Anusha Rajapaksa'),
(50, 28, 'Technical Deep Dive', 'A detailed technical session with worked examples.', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 720 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -60 DAY), INTERVAL 780 MINUTE), 'Ruwan De Silva');

-- ----------------------------------------------------------- discounts
INSERT INTO discounts (id, event_id, type, code, percent_off, valid_from, valid_to, is_active) VALUES
(1, 3, 'EARLY_BIRD', NULL, 15.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 1439 MINUTE), 1),
(2, 5, 'PROMO_CODE', 'EVENT05', 10.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 1439 MINUTE), 1),
(3, 6, 'EARLY_BIRD', NULL, 15.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 1439 MINUTE), 1),
(4, 6, 'GROUP', NULL, 12.50, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 1439 MINUTE), 1),
(5, 8, 'STUDENT', NULL, 20.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 1439 MINUTE), 1),
(6, 9, 'EARLY_BIRD', NULL, 15.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 1439 MINUTE), 1),
(7, 10, 'PROMO_CODE', 'EVENT10', 10.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 1439 MINUTE), 1),
(8, 16, 'STUDENT', NULL, 20.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 1439 MINUTE), 1),
(9, 20, 'STUDENT', NULL, 20.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 1439 MINUTE), 1),
(10, 20, 'PROMO_CODE', 'EVENT20', 10.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 1439 MINUTE), 1),
(11, 25, 'PROMO_CODE', 'EVENT25', 10.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -92 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -35 DAY), INTERVAL 1439 MINUTE), 1),
(12, 27, 'EARLY_BIRD', NULL, 15.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -54 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -23 DAY), INTERVAL 1439 MINUTE), 1),
(13, 28, 'STUDENT', NULL, 20.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -105 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 1439 MINUTE), 1),
(14, 32, 'STUDENT', NULL, 20.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 0 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 1439 MINUTE), 1);

-- ------------------------------------------------------- registrations
INSERT INTO registrations (id, event_id, user_id, status, seats, registered_at, cancelled_at) VALUES
(1, 1, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), NULL),
(2, 1, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), NULL),
(3, 1, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), NULL),
(4, 1, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), NULL),
(5, 1, 19, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 720 MINUTE)),
(6, 1, 21, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(7, 1, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 630 MINUTE), NULL),
(8, 1, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), NULL),
(9, 1, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), NULL),
(10, 1, 22, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 630 MINUTE), NULL),
(11, 1, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(12, 1, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(13, 1, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 630 MINUTE), NULL),
(14, 1, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), NULL),
(15, 1, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(16, 2, 16, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(17, 2, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 630 MINUTE), NULL),
(18, 2, 4, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 630 MINUTE), NULL),
(19, 2, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(20, 2, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), NULL),
(21, 2, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), NULL),
(22, 2, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), NULL),
(23, 2, 17, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 630 MINUTE), NULL),
(24, 2, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(25, 2, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), NULL),
(26, 2, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 630 MINUTE), NULL),
(27, 2, 19, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(28, 2, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(29, 2, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(30, 2, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 630 MINUTE), NULL),
(31, 3, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), NULL),
(32, 3, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 630 MINUTE), NULL),
(33, 3, 23, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(34, 3, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 630 MINUTE), NULL),
(35, 3, 6, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(36, 3, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), NULL),
(37, 3, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(38, 3, 19, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 630 MINUTE), NULL),
(39, 3, 15, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(40, 3, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 630 MINUTE), NULL),
(41, 3, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(42, 3, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 630 MINUTE), NULL),
(43, 3, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(44, 3, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 630 MINUTE), NULL),
(45, 3, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 630 MINUTE), NULL),
(46, 4, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(47, 4, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(48, 4, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(49, 4, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(50, 4, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(51, 4, 21, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 630 MINUTE), NULL),
(52, 4, 19, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 630 MINUTE), NULL),
(53, 4, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(54, 4, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(55, 4, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), NULL),
(56, 4, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 630 MINUTE), NULL),
(57, 4, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), NULL),
(58, 4, 16, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -19 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 720 MINUTE)),
(59, 4, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(60, 4, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 630 MINUTE), NULL),
(61, 5, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(62, 5, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 630 MINUTE), NULL),
(63, 5, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), NULL),
(64, 5, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(65, 5, 18, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 720 MINUTE)),
(66, 5, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 630 MINUTE), NULL),
(67, 5, 20, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 630 MINUTE), NULL),
(68, 5, 14, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(69, 5, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), NULL),
(70, 5, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(71, 5, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(72, 5, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 630 MINUTE), NULL),
(73, 5, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(74, 5, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(75, 5, 6, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 630 MINUTE), NULL),
(76, 6, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), NULL),
(77, 6, 18, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 630 MINUTE), NULL),
(78, 6, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(79, 6, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(80, 6, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 630 MINUTE), NULL),
(81, 6, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 630 MINUTE), NULL),
(82, 6, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 630 MINUTE), NULL),
(83, 6, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(84, 6, 20, 'CANCELLED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(85, 6, 8, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(86, 6, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 630 MINUTE), NULL),
(87, 6, 6, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), NULL),
(88, 6, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(89, 6, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(90, 6, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), NULL),
(91, 7, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(92, 7, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(93, 7, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(94, 7, 16, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(95, 7, 6, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), NULL),
(96, 7, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), NULL),
(97, 7, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), NULL),
(98, 7, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 630 MINUTE), NULL),
(99, 7, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), NULL),
(100, 7, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -23 DAY), INTERVAL 630 MINUTE), NULL),
(101, 7, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 630 MINUTE), NULL),
(102, 7, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 630 MINUTE), NULL),
(103, 7, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), NULL),
(104, 7, 18, 'WAITLISTED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 630 MINUTE), NULL),
(105, 7, 21, 'WAITLISTED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(106, 8, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 630 MINUTE), NULL),
(107, 8, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 630 MINUTE), NULL),
(108, 8, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 630 MINUTE), NULL),
(109, 8, 14, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 38 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 39 DAY), INTERVAL 720 MINUTE)),
(110, 8, 23, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 630 MINUTE), NULL),
(111, 8, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 630 MINUTE), NULL),
(112, 8, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 630 MINUTE), NULL),
(113, 8, 13, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 630 MINUTE), NULL),
(114, 8, 22, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(115, 8, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(116, 8, 6, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), NULL),
(117, 8, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 630 MINUTE), NULL),
(118, 8, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 630 MINUTE), NULL),
(119, 8, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 630 MINUTE), NULL),
(120, 8, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 630 MINUTE), NULL),
(121, 9, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(122, 9, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 630 MINUTE), NULL),
(123, 9, 22, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(124, 9, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 630 MINUTE), NULL),
(125, 9, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 630 MINUTE), NULL),
(126, 9, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(127, 9, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 630 MINUTE), NULL),
(128, 9, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), NULL),
(129, 9, 17, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(130, 9, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), NULL),
(131, 9, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(132, 9, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), NULL),
(133, 9, 19, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(134, 9, 18, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(135, 9, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 630 MINUTE), NULL),
(136, 10, 22, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(137, 10, 3, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 630 MINUTE), NULL),
(138, 10, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), NULL),
(139, 10, 14, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(140, 10, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), NULL),
(141, 10, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 630 MINUTE), NULL),
(142, 10, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(143, 10, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), NULL),
(144, 10, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 630 MINUTE), NULL),
(145, 10, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), NULL),
(146, 10, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), NULL),
(147, 10, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), NULL),
(148, 10, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 630 MINUTE), NULL),
(149, 10, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 630 MINUTE), NULL),
(150, 10, 6, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), NULL),
(151, 11, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 630 MINUTE), NULL),
(152, 11, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 630 MINUTE), NULL),
(153, 11, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 630 MINUTE), NULL),
(154, 11, 3, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 630 MINUTE), NULL),
(155, 11, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 630 MINUTE), NULL),
(156, 11, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 630 MINUTE), NULL),
(157, 11, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 630 MINUTE), NULL),
(158, 11, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), NULL),
(159, 11, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 630 MINUTE), NULL),
(160, 11, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 630 MINUTE), NULL),
(161, 11, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 630 MINUTE), NULL),
(162, 11, 19, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 630 MINUTE), NULL),
(163, 11, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 630 MINUTE), NULL),
(164, 11, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), NULL),
(165, 11, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 45 DAY), INTERVAL 630 MINUTE), NULL),
(166, 12, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 630 MINUTE), NULL),
(167, 12, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(168, 12, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(169, 12, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), NULL),
(170, 12, 18, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 630 MINUTE), NULL),
(171, 12, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 630 MINUTE), NULL),
(172, 12, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), NULL),
(173, 12, 21, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), NULL),
(174, 12, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), NULL),
(175, 12, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), NULL),
(176, 12, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 630 MINUTE), NULL),
(177, 12, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(178, 12, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(179, 12, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -21 DAY), INTERVAL 630 MINUTE), NULL),
(180, 12, 19, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(181, 13, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), NULL),
(182, 13, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 630 MINUTE), NULL),
(183, 13, 14, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(184, 13, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 630 MINUTE), NULL),
(185, 13, 15, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(186, 13, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 630 MINUTE), NULL),
(187, 13, 22, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(188, 13, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 630 MINUTE), NULL),
(189, 13, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), NULL),
(190, 13, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 630 MINUTE), NULL),
(191, 13, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), NULL),
(192, 13, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(193, 13, 4, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 630 MINUTE), NULL),
(194, 13, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 630 MINUTE), NULL),
(195, 13, 17, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 630 MINUTE), NULL),
(196, 14, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 49 DAY), INTERVAL 630 MINUTE), NULL),
(197, 14, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 630 MINUTE), NULL),
(198, 14, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 630 MINUTE), NULL),
(199, 14, 13, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 630 MINUTE), NULL),
(200, 14, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 51 DAY), INTERVAL 630 MINUTE), NULL),
(201, 14, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 52 DAY), INTERVAL 630 MINUTE), NULL),
(202, 14, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 630 MINUTE), NULL),
(203, 14, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 55 DAY), INTERVAL 630 MINUTE), NULL),
(204, 14, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 630 MINUTE), NULL),
(205, 14, 8, 'WAITLISTED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 50 DAY), INTERVAL 630 MINUTE), NULL),
(206, 14, 21, 'WAITLISTED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 42 DAY), INTERVAL 630 MINUTE), NULL),
(207, 14, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 630 MINUTE), NULL),
(208, 14, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 630 MINUTE), NULL),
(209, 14, 6, 'WAITLISTED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 630 MINUTE), NULL),
(210, 14, 4, 'WAITLISTED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 630 MINUTE), NULL),
(211, 15, 16, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(212, 15, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(213, 15, 13, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 630 MINUTE), NULL),
(214, 15, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), NULL),
(215, 15, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(216, 15, 8, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 630 MINUTE), NULL),
(217, 15, 4, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(218, 15, 3, 'CANCELLED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(219, 15, 14, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 630 MINUTE), NULL),
(220, 15, 23, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(221, 15, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 630 MINUTE), NULL),
(222, 15, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(223, 15, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), NULL),
(224, 15, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 630 MINUTE), NULL),
(225, 15, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), NULL),
(226, 16, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 630 MINUTE), NULL),
(227, 16, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), NULL),
(228, 16, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), NULL),
(229, 16, 3, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 630 MINUTE), NULL),
(230, 16, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 630 MINUTE), NULL),
(231, 16, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 630 MINUTE), NULL),
(232, 16, 4, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 720 MINUTE)),
(233, 16, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 630 MINUTE), NULL),
(234, 16, 18, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(235, 16, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 630 MINUTE), NULL),
(236, 16, 14, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 630 MINUTE), NULL),
(237, 16, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 630 MINUTE), NULL),
(238, 16, 19, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(239, 16, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), NULL),
(240, 16, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), NULL),
(241, 17, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), NULL),
(242, 17, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 630 MINUTE), NULL),
(243, 17, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), NULL),
(244, 17, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 630 MINUTE), NULL),
(245, 17, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 630 MINUTE), NULL),
(246, 17, 4, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(247, 17, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 630 MINUTE), NULL),
(248, 17, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 630 MINUTE), NULL),
(249, 17, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 630 MINUTE), NULL),
(250, 17, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 630 MINUTE), NULL),
(251, 17, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 630 MINUTE), NULL),
(252, 17, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 630 MINUTE), NULL),
(253, 17, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), NULL),
(254, 17, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 630 MINUTE), NULL),
(255, 17, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 630 MINUTE), NULL),
(256, 18, 15, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), NULL),
(257, 18, 23, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 630 MINUTE), NULL),
(258, 18, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), NULL),
(259, 18, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), NULL),
(260, 18, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 630 MINUTE), NULL),
(261, 18, 21, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 630 MINUTE), NULL),
(262, 18, 13, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 630 MINUTE), NULL),
(263, 18, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 630 MINUTE), NULL),
(264, 18, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(265, 18, 3, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), NULL),
(266, 18, 6, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 720 MINUTE)),
(267, 18, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 630 MINUTE), NULL),
(268, 18, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 630 MINUTE), NULL),
(269, 18, 4, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE)),
(270, 18, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 630 MINUTE), NULL),
(271, 19, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 44 DAY), INTERVAL 630 MINUTE), NULL),
(272, 19, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 60 DAY), INTERVAL 630 MINUTE), NULL),
(273, 19, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 630 MINUTE), NULL),
(274, 19, 18, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 630 MINUTE), NULL),
(275, 19, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 630 MINUTE), NULL),
(276, 19, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 630 MINUTE), NULL),
(277, 19, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 630 MINUTE), NULL),
(278, 19, 22, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 51 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 52 DAY), INTERVAL 720 MINUTE)),
(279, 19, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 41 DAY), INTERVAL 630 MINUTE), NULL),
(280, 19, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 630 MINUTE), NULL),
(281, 19, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 630 MINUTE), NULL),
(282, 19, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 53 DAY), INTERVAL 630 MINUTE), NULL),
(283, 19, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 630 MINUTE), NULL),
(284, 19, 8, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 41 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 42 DAY), INTERVAL 720 MINUTE)),
(285, 19, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 630 MINUTE), NULL),
(286, 20, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), NULL),
(287, 20, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), NULL),
(288, 20, 18, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 630 MINUTE), NULL),
(289, 20, 19, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 630 MINUTE), NULL),
(290, 20, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 630 MINUTE), NULL),
(291, 20, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), NULL),
(292, 20, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), NULL),
(293, 20, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 630 MINUTE), NULL),
(294, 20, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 630 MINUTE), NULL),
(295, 20, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), NULL),
(296, 20, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(297, 20, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 630 MINUTE), NULL),
(298, 20, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), NULL),
(299, 20, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 630 MINUTE), NULL),
(300, 20, 17, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 720 MINUTE)),
(301, 24, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -38 DAY), INTERVAL 630 MINUTE), NULL),
(302, 24, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 630 MINUTE), NULL),
(303, 24, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -41 DAY), INTERVAL 630 MINUTE), NULL),
(304, 24, 6, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 630 MINUTE), NULL),
(305, 24, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 630 MINUTE), NULL),
(306, 24, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -29 DAY), INTERVAL 630 MINUTE), NULL),
(307, 24, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 630 MINUTE), NULL),
(308, 24, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 630 MINUTE), NULL),
(309, 24, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -33 DAY), INTERVAL 630 MINUTE), NULL),
(310, 24, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -24 DAY), INTERVAL 630 MINUTE), NULL),
(311, 24, 19, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 630 MINUTE), NULL),
(312, 24, 17, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 630 MINUTE), NULL),
(313, 24, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -33 DAY), INTERVAL 630 MINUTE), NULL),
(314, 24, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 630 MINUTE), NULL),
(315, 24, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 630 MINUTE), NULL),
(316, 25, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 630 MINUTE), NULL),
(317, 25, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 630 MINUTE), NULL),
(318, 25, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -59 DAY), INTERVAL 630 MINUTE), NULL),
(319, 25, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 630 MINUTE), NULL),
(320, 25, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 630 MINUTE), NULL),
(321, 25, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 630 MINUTE), NULL),
(322, 25, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 630 MINUTE), NULL),
(323, 25, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 630 MINUTE), NULL),
(324, 25, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 630 MINUTE), NULL),
(325, 25, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 630 MINUTE), NULL),
(326, 25, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 630 MINUTE), NULL),
(327, 25, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 630 MINUTE), NULL),
(328, 25, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 630 MINUTE), NULL),
(329, 25, 19, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 630 MINUTE), NULL),
(330, 25, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 630 MINUTE), NULL),
(331, 26, 19, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 630 MINUTE), NULL),
(332, 26, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -71 DAY), INTERVAL 630 MINUTE), NULL),
(333, 26, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 630 MINUTE), NULL),
(334, 26, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -49 DAY), INTERVAL 630 MINUTE), NULL),
(335, 26, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 630 MINUTE), NULL),
(336, 26, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 630 MINUTE), NULL),
(337, 26, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 630 MINUTE), NULL),
(338, 26, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 630 MINUTE), NULL),
(339, 26, 15, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 630 MINUTE), NULL),
(340, 26, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 630 MINUTE), NULL),
(341, 26, 17, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -72 DAY), INTERVAL 630 MINUTE), NULL),
(342, 26, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 630 MINUTE), NULL),
(343, 26, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 630 MINUTE), NULL),
(344, 26, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 630 MINUTE), NULL),
(345, 26, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 630 MINUTE), NULL),
(346, 27, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 630 MINUTE), NULL),
(347, 27, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 630 MINUTE), NULL),
(348, 27, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 630 MINUTE), NULL),
(349, 27, 20, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -21 DAY), INTERVAL 630 MINUTE), NULL),
(350, 27, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 630 MINUTE), NULL),
(351, 27, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 630 MINUTE), NULL),
(352, 27, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 630 MINUTE), NULL),
(353, 27, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 630 MINUTE), NULL),
(354, 27, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 630 MINUTE), NULL),
(355, 27, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 630 MINUTE), NULL),
(356, 27, 15, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 630 MINUTE), NULL),
(357, 27, 4, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 630 MINUTE), NULL),
(358, 27, 18, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 630 MINUTE), NULL),
(359, 27, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 630 MINUTE), NULL),
(360, 27, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 630 MINUTE), NULL),
(361, 28, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 630 MINUTE), NULL),
(362, 28, 15, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 630 MINUTE), NULL),
(363, 28, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 630 MINUTE), NULL),
(364, 28, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 630 MINUTE), NULL),
(365, 28, 18, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 630 MINUTE), NULL),
(366, 28, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 630 MINUTE), NULL),
(367, 28, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 630 MINUTE), NULL),
(368, 28, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 630 MINUTE), NULL),
(369, 28, 22, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 630 MINUTE), NULL),
(370, 28, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 630 MINUTE), NULL),
(371, 28, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 630 MINUTE), NULL),
(372, 28, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -79 DAY), INTERVAL 630 MINUTE), NULL),
(373, 28, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 630 MINUTE), NULL),
(374, 28, 13, 'CONFIRMED', 3, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 630 MINUTE), NULL),
(375, 28, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 630 MINUTE), NULL),
(376, 29, 14, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 630 MINUTE), NULL),
(377, 29, 13, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -81 DAY), INTERVAL 630 MINUTE), NULL),
(378, 29, 15, 'CONFIRMED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -80 DAY), INTERVAL 630 MINUTE), NULL),
(379, 29, 19, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 630 MINUTE), NULL),
(380, 29, 23, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -98 DAY), INTERVAL 630 MINUTE), NULL),
(381, 29, 6, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 630 MINUTE), NULL),
(382, 29, 16, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -87 DAY), INTERVAL 630 MINUTE), NULL),
(383, 29, 21, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 630 MINUTE), NULL),
(384, 29, 8, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 630 MINUTE), NULL),
(385, 29, 4, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 630 MINUTE), NULL),
(386, 29, 3, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -89 DAY), INTERVAL 630 MINUTE), NULL),
(387, 29, 18, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -97 DAY), INTERVAL 630 MINUTE), NULL),
(388, 29, 17, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -79 DAY), INTERVAL 630 MINUTE), NULL),
(389, 29, 20, 'CONFIRMED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -84 DAY), INTERVAL 630 MINUTE), NULL),
(390, 29, 22, 'CONFIRMED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -90 DAY), INTERVAL 630 MINUTE), NULL),
(391, 30, 22, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(392, 30, 19, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(393, 30, 18, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 31 DAY), INTERVAL 720 MINUTE)),
(394, 30, 15, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(395, 30, 4, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(396, 30, 16, 'CANCELLED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(397, 30, 21, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 720 MINUTE)),
(398, 30, 3, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(399, 30, 20, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(400, 30, 8, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(401, 30, 13, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(402, 30, 14, 'CANCELLED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(403, 30, 6, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(404, 30, 23, 'CANCELLED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(405, 30, 17, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(406, 31, 3, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(407, 31, 13, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 720 MINUTE)),
(408, 31, 23, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -38 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 720 MINUTE)),
(409, 31, 17, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -40 DAY), INTERVAL 720 MINUTE)),
(410, 31, 4, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(411, 31, 20, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 720 MINUTE)),
(412, 31, 14, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(413, 31, 8, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 720 MINUTE)),
(414, 31, 6, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -40 DAY), INTERVAL 720 MINUTE)),
(415, 31, 15, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 720 MINUTE)),
(416, 31, 16, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 720 MINUTE)),
(417, 31, 22, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 720 MINUTE)),
(418, 31, 21, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -29 DAY), INTERVAL 720 MINUTE)),
(419, 31, 18, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -41 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 720 MINUTE)),
(420, 31, 19, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 720 MINUTE)),
(421, 32, 23, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(422, 32, 15, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 720 MINUTE)),
(423, 32, 22, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 8 DAY), INTERVAL 720 MINUTE)),
(424, 32, 16, 'CANCELLED', 2, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(425, 32, 20, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE)),
(426, 32, 14, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(427, 32, 19, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(428, 32, 6, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(429, 32, 18, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(430, 32, 21, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 720 MINUTE)),
(431, 32, 8, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(432, 32, 3, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(433, 32, 17, 'CANCELLED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(434, 32, 4, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(435, 32, 13, 'CANCELLED', 4, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 630 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE));

-- -------------------------------------------------- attendance (check-ins)
--
-- Only events that have actually happened have anybody checked in:
-- checking somebody in for next month's workshop would be nonsense, and
-- it would also make every attendance figure on the reports page a lie.
-- Turnout is roughly 80% (every registration whose id is not a multiple
-- of 5), which is what the no-show rate is for - a seeded 100% would
-- make the metric look broken rather than good.
--
-- The organizer of the event does the checking in, which is who would
-- be standing at the door.
--
-- seats_checked_in is set together with the timestamp because
-- chk_registration_seats_checked_in requires both or neither, and
-- because every attendance figure now sums this number rather than
-- counting rows. Every third party booking arrives one short, so the
-- seeded data exercises the partial-arrival case instead of only the
-- tidy one.
UPDATE registrations r
    JOIN events e ON e.id = r.event_id
SET r.checked_in_at = DATE_ADD(e.start_datetime, INTERVAL (15 + (r.id % 40)) MINUTE),
    r.checked_in_by = e.organizer_id,
    r.seats_checked_in = IF(r.seats > 1 AND r.id % 3 = 0, r.seats - 1, r.seats)
WHERE e.status = 'COMPLETED'
  AND r.status = 'CONFIRMED'
  AND r.id % 5 <> 0;


-- ---------------------------------------------------- event_invitations
INSERT INTO event_invitations (id, event_id, user_id, status, requested_at, decided_at) VALUES
(1, 3, 8, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 900 MINUTE)),
(2, 3, 13, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 900 MINUTE)),
(3, 3, 16, 'PENDING', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 540 MINUTE), NULL),
(4, 3, 20, 'REJECTED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 900 MINUTE)),
(5, 3, 15, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 900 MINUTE)),
(6, 3, 19, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 900 MINUTE)),
(7, 17, 3, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 900 MINUTE)),
(8, 17, 4, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 900 MINUTE)),
(9, 17, 8, 'PENDING', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 540 MINUTE), NULL),
(10, 17, 21, 'REJECTED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 900 MINUTE)),
(11, 17, 23, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 900 MINUTE)),
(12, 17, 22, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 900 MINUTE)),
(13, 26, 16, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -88 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 900 MINUTE)),
(14, 26, 8, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -88 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 900 MINUTE)),
(15, 26, 23, 'PENDING', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -88 DAY), INTERVAL 540 MINUTE), NULL),
(16, 26, 19, 'REJECTED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -88 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 900 MINUTE)),
(17, 26, 4, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -88 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 900 MINUTE)),
(18, 26, 18, 'APPROVED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -88 DAY), INTERVAL 540 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 900 MINUTE));

-- ------------------------------------------------------------ payments
INSERT INTO payments (id, registration_id, discount_id, original_amount, discount_amount,
                      final_amount, status, ticket_code, paid_at, created_at) VALUES
(1, 1, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-1-2EDCEE7E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 640 MINUTE)),
(2, 2, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-2-D8B928B2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 640 MINUTE)),
(3, 3, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-3-4A658789', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 640 MINUTE)),
(4, 4, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-4-E8D571F1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 640 MINUTE)),
(5, 6, NULL, 5000.00, 0.00, 5000.00, 'COMPLETED', 'TICKET-1-6-F951E333', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 640 MINUTE)),
(6, 7, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-7-717BBE86', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 640 MINUTE)),
(7, 8, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-8-964ABD5F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 640 MINUTE)),
(8, 9, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-9-FCBF2720', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 640 MINUTE)),
(9, 10, NULL, 5000.00, 0.00, 5000.00, 'COMPLETED', 'TICKET-1-10-221C0686', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 640 MINUTE)),
(10, 11, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-11-C6B5BA96', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 640 MINUTE)),
(11, 12, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-12-60F302CF', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 640 MINUTE)),
(12, 13, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-13-743D712C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 640 MINUTE)),
(13, 14, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-14-C301850E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 640 MINUTE)),
(14, 15, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-1-15-1B9FDA97', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 640 MINUTE)),
(15, 31, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-31-894E6575', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 640 MINUTE)),
(16, 32, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-32-A9978222', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 640 MINUTE)),
(17, 34, 1, 4500.00, 675.00, 3825.00, 'COMPLETED', 'TICKET-3-34-29C0516F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 640 MINUTE)),
(18, 36, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-36-C9148958', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 640 MINUTE)),
(19, 37, 1, 4500.00, 675.00, 3825.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(20, 38, NULL, 18000.00, 0.00, 18000.00, 'COMPLETED', 'TICKET-3-38-86F0E8FE', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 640 MINUTE)),
(21, 39, 1, 18000.00, 2700.00, 15300.00, 'COMPLETED', 'TICKET-3-39-2B80F6E5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(22, 40, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-40-8EDAA1A9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 640 MINUTE)),
(23, 41, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-41-70912679', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(24, 42, 1, 4500.00, 675.00, 3825.00, 'COMPLETED', 'TICKET-3-42-AE195D28', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 640 MINUTE)),
(25, 43, 1, 4500.00, 675.00, 3825.00, 'COMPLETED', 'TICKET-3-43-7FAE9ABD', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(26, 44, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-44-19346732', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 640 MINUTE)),
(27, 45, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-3-45-2ED5FF87', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 640 MINUTE)),
(28, 61, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-5-61-2F40B889', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 640 MINUTE)),
(29, 62, 2, 2500.00, 250.00, 2250.00, 'COMPLETED', 'TICKET-5-62-C6805AEE', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 640 MINUTE)),
(30, 63, 2, 2500.00, 250.00, 2250.00, 'COMPLETED', 'TICKET-5-63-27111881', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 640 MINUTE)),
(31, 64, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-5-64-48FF82D1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 640 MINUTE)),
(32, 65, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-5-65-91370FA8', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 640 MINUTE)),
(33, 66, NULL, 2500.00, 0.00, 2500.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 640 MINUTE)),
(34, 67, NULL, 7500.00, 0.00, 7500.00, 'COMPLETED', 'TICKET-5-67-9D44EAA9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 640 MINUTE)),
(35, 68, NULL, 7500.00, 0.00, 7500.00, 'COMPLETED', 'TICKET-5-68-7C82A667', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 640 MINUTE)),
(36, 69, 2, 2500.00, 250.00, 2250.00, 'COMPLETED', 'TICKET-5-69-E60152FC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 640 MINUTE)),
(37, 70, 2, 2500.00, 250.00, 2250.00, 'COMPLETED', 'TICKET-5-70-361453A1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 640 MINUTE)),
(38, 71, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-5-71-656F1EA6', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(39, 72, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-5-72-AD6205DA', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 640 MINUTE)),
(40, 73, 2, 2500.00, 250.00, 2250.00, 'COMPLETED', 'TICKET-5-73-1416408A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 640 MINUTE)),
(41, 74, 2, 2500.00, 250.00, 2250.00, 'COMPLETED', 'TICKET-5-74-1CEE5D45', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(42, 75, 2, 7500.00, 750.00, 6750.00, 'COMPLETED', 'TICKET-5-75-5447BED8', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 640 MINUTE)),
(43, 76, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-6-76-8DF2B595', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 640 MINUTE)),
(44, 77, 3, 32000.00, 4800.00, 27200.00, 'COMPLETED', 'TICKET-6-77-7B1437AA', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 640 MINUTE)),
(45, 78, 4, 8000.00, 1000.00, 7000.00, 'COMPLETED', 'TICKET-6-78-F5F086F0', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 640 MINUTE)),
(46, 79, 3, 8000.00, 1200.00, 6800.00, 'COMPLETED', 'TICKET-6-79-A5A6EEE9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(47, 80, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-6-80-602D1856', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 640 MINUTE)),
(48, 81, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-6-81-FFD1AEF5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 640 MINUTE)),
(49, 82, 4, 8000.00, 1000.00, 7000.00, 'COMPLETED', 'TICKET-6-82-453482E6', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 640 MINUTE)),
(50, 83, 3, 8000.00, 1200.00, 6800.00, 'COMPLETED', 'TICKET-6-83-44D6945B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(51, 86, 4, 8000.00, 1000.00, 7000.00, 'COMPLETED', 'TICKET-6-86-1CE851B1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 640 MINUTE)),
(52, 87, NULL, 16000.00, 0.00, 16000.00, 'COMPLETED', 'TICKET-6-87-7B49A8A9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 640 MINUTE)),
(53, 88, NULL, 8000.00, 0.00, 8000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(54, 89, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-6-89-B1F9FD14', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 640 MINUTE)),
(55, 90, 4, 8000.00, 1000.00, 7000.00, 'COMPLETED', 'TICKET-6-90-666C80EC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 640 MINUTE)),
(56, 91, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-91-3F34ADF1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 640 MINUTE)),
(57, 92, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-92-77C47D43', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 640 MINUTE)),
(58, 93, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-93-BA8EE90A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 640 MINUTE)),
(59, 94, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-94-1408ECFD', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 640 MINUTE)),
(60, 95, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-7-95-A49B60E6', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(61, 96, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-96-DDF1B802', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(62, 97, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-97-6A95188E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 640 MINUTE)),
(63, 98, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-98-1E704BA3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 640 MINUTE)),
(64, 99, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-99-39DA940A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(65, 100, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-100-6014F46F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -23 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -23 DAY), INTERVAL 640 MINUTE)),
(66, 101, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-101-2FE153C2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 640 MINUTE)),
(67, 102, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-102-E9B2DF9A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 640 MINUTE)),
(68, 103, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-7-103-E38ACEEC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 640 MINUTE)),
(69, 106, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-106-C3602044', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 640 MINUTE)),
(70, 107, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-107-1362B476', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 640 MINUTE)),
(71, 108, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-108-9DFDC285', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 640 MINUTE)),
(72, 110, NULL, 7500.00, 0.00, 7500.00, 'COMPLETED', 'TICKET-8-110-B105A7B4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 640 MINUTE)),
(73, 111, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-111-41DE71F5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 640 MINUTE)),
(74, 112, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-112-84BBC9B6', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 640 MINUTE)),
(75, 113, NULL, 7500.00, 0.00, 7500.00, 'COMPLETED', 'TICKET-8-113-3C79A931', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 640 MINUTE)),
(76, 114, NULL, 10000.00, 0.00, 10000.00, 'COMPLETED', 'TICKET-8-114-6DBF9B7A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 640 MINUTE)),
(77, 115, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-115-8FD8FFFB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 640 MINUTE)),
(78, 116, NULL, 5000.00, 0.00, 5000.00, 'COMPLETED', 'TICKET-8-116-53D5905B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 640 MINUTE)),
(79, 117, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-117-591D726B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 640 MINUTE)),
(80, 118, 5, 2500.00, 500.00, 2000.00, 'FAILED', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 640 MINUTE)),
(81, 119, NULL, 2500.00, 0.00, 2500.00, 'COMPLETED', 'TICKET-8-119-657022DE', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 640 MINUTE)),
(82, 120, 5, 2500.00, 500.00, 2000.00, 'COMPLETED', 'TICKET-8-120-4A66F283', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 640 MINUTE)),
(83, 121, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-121-E561D163', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(84, 122, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-122-FBDB227B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 640 MINUTE)),
(85, 123, NULL, 24000.00, 0.00, 24000.00, 'COMPLETED', 'TICKET-9-123-8C6C540E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 640 MINUTE)),
(86, 124, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-124-62C21224', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 640 MINUTE)),
(87, 125, 6, 8000.00, 1200.00, 6800.00, 'COMPLETED', 'TICKET-9-125-3D0FF404', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 640 MINUTE)),
(88, 126, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-126-E1C40FB3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(89, 127, 6, 8000.00, 1200.00, 6800.00, 'FAILED', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 640 MINUTE)),
(90, 128, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-128-696FC2D5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 640 MINUTE)),
(91, 129, NULL, 16000.00, 0.00, 16000.00, 'COMPLETED', 'TICKET-9-129-FCF84D27', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 640 MINUTE)),
(92, 130, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-130-711D524B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 640 MINUTE)),
(93, 131, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-9-131-4EC84DE5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(94, 132, 6, 8000.00, 1200.00, 6800.00, 'COMPLETED', 'TICKET-9-132-311486A1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 640 MINUTE)),
(95, 134, 6, 8000.00, 1200.00, 6800.00, 'COMPLETED', 'TICKET-9-134-C0FCB8D0', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(96, 135, 6, 8000.00, 1200.00, 6800.00, 'COMPLETED', 'TICKET-9-135-2A78BD24', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 640 MINUTE)),
(97, 136, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-10-136-72154F7D', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 640 MINUTE)),
(98, 137, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-10-137-2C128526', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 640 MINUTE)),
(99, 138, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-10-138-1906A78C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 640 MINUTE)),
(100, 140, 7, 1000.00, 100.00, 900.00, 'COMPLETED', 'TICKET-10-140-BA6BB8D3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 640 MINUTE)),
(101, 141, NULL, 1000.00, 0.00, 1000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 640 MINUTE)),
(102, 142, 7, 1000.00, 100.00, 900.00, 'COMPLETED', 'TICKET-10-142-5BF0A07F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 640 MINUTE)),
(103, 143, 7, 1000.00, 100.00, 900.00, 'COMPLETED', 'TICKET-10-143-E269D9A3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 640 MINUTE)),
(104, 144, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-10-144-5F6B1D68', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 640 MINUTE)),
(105, 145, 7, 1000.00, 100.00, 900.00, 'COMPLETED', 'TICKET-10-145-60E82009', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 640 MINUTE)),
(106, 146, 7, 1000.00, 100.00, 900.00, 'COMPLETED', 'TICKET-10-146-D9359A60', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 640 MINUTE)),
(107, 147, 7, 1000.00, 100.00, 900.00, 'COMPLETED', 'TICKET-10-147-CB6C2BB8', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 640 MINUTE)),
(108, 148, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-10-148-D72D6D2F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 640 MINUTE)),
(109, 149, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-10-149-8E215850', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 640 MINUTE)),
(110, 150, 7, 4000.00, 400.00, 3600.00, 'COMPLETED', 'TICKET-10-150-DFB90331', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 640 MINUTE)),
(111, 151, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-151-1EB1C551', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 640 MINUTE)),
(112, 152, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-152-CBA356DF', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 640 MINUTE)),
(113, 153, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-153-CFB12B2B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 640 MINUTE)),
(114, 154, NULL, 16000.00, 0.00, 16000.00, 'COMPLETED', 'TICKET-11-154-C06454A2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 640 MINUTE)),
(115, 155, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-155-97A9EF43', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 640 MINUTE)),
(116, 156, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-156-BE3195E4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 640 MINUTE)),
(117, 157, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-157-F8C33EA2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 640 MINUTE)),
(118, 158, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-158-58FD5513', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 640 MINUTE)),
(119, 159, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-159-11C30FDB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 640 MINUTE)),
(120, 160, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-160-12B3C25C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 640 MINUTE)),
(121, 161, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-161-718E7D9E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 640 MINUTE)),
(122, 162, NULL, 32000.00, 0.00, 32000.00, 'COMPLETED', 'TICKET-11-162-47867585', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 640 MINUTE)),
(123, 163, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-163-7CC73521', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 640 MINUTE)),
(124, 164, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-164-39D5208F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 640 MINUTE)),
(125, 165, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-11-165-82E5D796', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 45 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 45 DAY), INTERVAL 640 MINUTE)),
(126, 181, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-181-ECEFD924', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 640 MINUTE)),
(127, 182, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-182-A37850F0', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 640 MINUTE)),
(128, 184, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-184-998640B9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 640 MINUTE)),
(129, 186, NULL, 1000.00, 0.00, 1000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 640 MINUTE)),
(130, 187, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-187-FF733531', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 640 MINUTE)),
(131, 188, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-188-414A26ED', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 640 MINUTE)),
(132, 189, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-189-F5F76DA6', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 640 MINUTE)),
(133, 190, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-190-37190EA5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 640 MINUTE)),
(134, 191, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-191-B8770DAD', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 640 MINUTE)),
(135, 192, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-192-43C111A5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 640 MINUTE)),
(136, 193, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-13-193-35369180', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 640 MINUTE)),
(137, 194, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-13-194-13BEF0EC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 640 MINUTE)),
(138, 195, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-13-195-52D5E0D1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 640 MINUTE)),
(139, 196, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-196-28AB5FA5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 49 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 49 DAY), INTERVAL 640 MINUTE)),
(140, 197, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-197-80A341D8', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 640 MINUTE)),
(141, 198, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-198-A860E7D2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 640 MINUTE)),
(142, 199, NULL, 4000.00, 0.00, 4000.00, 'COMPLETED', 'TICKET-14-199-7B647F90', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 640 MINUTE)),
(143, 200, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-200-F11B447A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 51 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 51 DAY), INTERVAL 640 MINUTE)),
(144, 201, NULL, 1000.00, 0.00, 1000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 52 DAY), INTERVAL 640 MINUTE)),
(145, 202, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-202-D02C2A5E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 640 MINUTE)),
(146, 203, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-203-2BA85947', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 55 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 55 DAY), INTERVAL 640 MINUTE)),
(147, 204, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-204-1C49B321', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 640 MINUTE)),
(148, 207, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-207-4D3CABCC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 640 MINUTE)),
(149, 208, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-14-208-58433A74', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 640 MINUTE)),
(150, 226, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-226-1C10FC73', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 640 MINUTE)),
(151, 227, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-227-DD5DA9E9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 640 MINUTE)),
(152, 228, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-228-610BD89F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 640 MINUTE)),
(153, 229, NULL, 12000.00, 0.00, 12000.00, 'COMPLETED', 'TICKET-16-229-C0027BC9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 640 MINUTE)),
(154, 230, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-230-AF1B6AB7', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 640 MINUTE)),
(155, 231, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-231-A91396A0', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 640 MINUTE)),
(156, 232, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-232-E72B8B00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 640 MINUTE)),
(157, 233, 8, 6000.00, 1200.00, 4800.00, 'COMPLETED', 'TICKET-16-233-1CC958E4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 640 MINUTE)),
(158, 234, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-234-F030B661', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 640 MINUTE)),
(159, 235, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-235-524110A7', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 640 MINUTE)),
(160, 236, NULL, 24000.00, 0.00, 24000.00, 'COMPLETED', 'TICKET-16-236-C7263629', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 640 MINUTE)),
(161, 237, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-237-22602273', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 640 MINUTE)),
(162, 239, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-239-7B3FBFEB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 640 MINUTE)),
(163, 240, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-16-240-44019078', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 640 MINUTE)),
(164, 271, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-271-F466F36F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 44 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 44 DAY), INTERVAL 640 MINUTE)),
(165, 272, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-272-47D06538', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 60 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 60 DAY), INTERVAL 640 MINUTE)),
(166, 273, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-273-B7A777AD', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 640 MINUTE)),
(167, 274, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-19-274-6525B8FC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 640 MINUTE)),
(168, 275, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-275-6C7570B3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 640 MINUTE)),
(169, 276, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-276-7E6334F0', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 61 DAY), INTERVAL 640 MINUTE)),
(170, 277, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-277-9D4C1815', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 640 MINUTE)),
(171, 279, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-279-D85717A9', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 41 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 41 DAY), INTERVAL 640 MINUTE)),
(172, 280, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-280-F8011C39', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 640 MINUTE)),
(173, 281, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-281-D55E5E33', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 640 MINUTE)),
(174, 282, NULL, 3000.00, 0.00, 3000.00, 'FAILED', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 53 DAY), INTERVAL 640 MINUTE)),
(175, 283, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-19-283-A2555AEC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 640 MINUTE)),
(176, 285, NULL, 3000.00, 0.00, 3000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 640 MINUTE)),
(177, 286, 10, 6000.00, 600.00, 5400.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 640 MINUTE)),
(178, 287, 9, 6000.00, 1200.00, 4800.00, 'COMPLETED', 'TICKET-20-287-629BDE1E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 640 MINUTE)),
(179, 288, NULL, 12000.00, 0.00, 12000.00, 'COMPLETED', 'TICKET-20-288-5A3D33DF', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 640 MINUTE)),
(180, 289, NULL, 12000.00, 0.00, 12000.00, 'COMPLETED', 'TICKET-20-289-D9E576FE', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 640 MINUTE)),
(181, 290, 10, 6000.00, 600.00, 5400.00, 'COMPLETED', 'TICKET-20-290-DEB44540', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 640 MINUTE)),
(182, 291, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-20-291-EEACB387', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 640 MINUTE)),
(183, 292, 10, 6000.00, 600.00, 5400.00, 'COMPLETED', 'TICKET-20-292-BFEA25CB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(184, 293, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-20-293-80920876', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 640 MINUTE)),
(185, 294, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-20-294-DF192D21', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 640 MINUTE)),
(186, 295, 10, 6000.00, 600.00, 5400.00, 'COMPLETED', 'TICKET-20-295-25CDA469', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(187, 296, 10, 6000.00, 600.00, 5400.00, 'COMPLETED', 'TICKET-20-296-827AEFE5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 640 MINUTE)),
(188, 297, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-20-297-112A0B67', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 640 MINUTE)),
(189, 298, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-20-298-778B503B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 640 MINUTE)),
(190, 299, 10, 6000.00, 600.00, 5400.00, 'COMPLETED', 'TICKET-20-299-FA78E52B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 640 MINUTE)),
(191, 300, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-20-300-62817AA1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(192, 316, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-25-316-EBE4B6DF', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 640 MINUTE)),
(193, 317, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-25-317-68935837', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 640 MINUTE)),
(194, 318, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-25-318-D0F613FC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -59 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -59 DAY), INTERVAL 640 MINUTE)),
(195, 319, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-319-23FB097E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 640 MINUTE)),
(196, 320, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-25-320-F73FA715', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 640 MINUTE)),
(197, 321, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-321-F3C472E1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 640 MINUTE)),
(198, 322, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-25-322-37CB4510', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 640 MINUTE)),
(199, 323, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-323-DFEE1ACD', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 640 MINUTE)),
(200, 324, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-324-94DE5910', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 640 MINUTE)),
(201, 325, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-325-22924561', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 640 MINUTE)),
(202, 326, NULL, 4500.00, 0.00, 4500.00, 'FAILED', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 640 MINUTE)),
(203, 327, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-327-BDC6F091', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 640 MINUTE)),
(204, 328, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-25-328-84C74D18', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 640 MINUTE)),
(205, 329, 11, 9000.00, 900.00, 8100.00, 'FAILED', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 640 MINUTE)),
(206, 330, 11, 4500.00, 450.00, 4050.00, 'COMPLETED', 'TICKET-25-330-F76BB77D', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 640 MINUTE)),
(207, 331, NULL, 13500.00, 0.00, 13500.00, 'COMPLETED', 'TICKET-26-331-DB152766', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 640 MINUTE)),
(208, 332, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-332-C3F8FF07', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -71 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -71 DAY), INTERVAL 640 MINUTE)),
(209, 333, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-333-19DF6564', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 640 MINUTE)),
(210, 334, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-334-C2C37D8B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -49 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -49 DAY), INTERVAL 640 MINUTE)),
(211, 335, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-335-F223F267', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 640 MINUTE)),
(212, 336, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-336-D297199F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 640 MINUTE)),
(213, 337, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-337-FD30ACAC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 640 MINUTE)),
(214, 338, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-338-D4EFBF4B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 640 MINUTE)),
(215, 339, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-339-F70724E1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 640 MINUTE)),
(216, 340, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-340-959D73EC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 640 MINUTE)),
(217, 341, NULL, 9000.00, 0.00, 9000.00, 'COMPLETED', 'TICKET-26-341-736B88BB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -72 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -72 DAY), INTERVAL 640 MINUTE)),
(218, 342, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-342-F6B591DC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 640 MINUTE)),
(219, 343, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-343-3A72569B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 640 MINUTE)),
(220, 344, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-344-BB875448', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 640 MINUTE)),
(221, 345, NULL, 4500.00, 0.00, 4500.00, 'COMPLETED', 'TICKET-26-345-67E2180A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 640 MINUTE)),
(222, 346, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-27-346-5F8788C3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 640 MINUTE)),
(223, 347, 12, 1000.00, 150.00, 850.00, 'COMPLETED', 'TICKET-27-347-BE1112AE', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 640 MINUTE)),
(224, 348, 12, 1000.00, 150.00, 850.00, 'COMPLETED', 'TICKET-27-348-CC9D1ACB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 640 MINUTE)),
(225, 349, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-27-349-2F9AFF5B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -21 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -21 DAY), INTERVAL 640 MINUTE)),
(226, 350, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-27-350-BFECE52C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 640 MINUTE)),
(227, 351, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-27-351-FC86022E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 640 MINUTE)),
(228, 352, 12, 1000.00, 150.00, 850.00, 'COMPLETED', 'TICKET-27-352-D881973C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 640 MINUTE)),
(229, 353, 12, 1000.00, 150.00, 850.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 640 MINUTE)),
(230, 354, NULL, 1000.00, 0.00, 1000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 640 MINUTE)),
(231, 355, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-27-355-91B4D9BA', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 640 MINUTE)),
(232, 356, NULL, 4000.00, 0.00, 4000.00, 'COMPLETED', 'TICKET-27-356-8E9CD969', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 640 MINUTE)),
(233, 357, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-27-357-341CBD53', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 640 MINUTE)),
(234, 358, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-27-358-4F5A33CF', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 640 MINUTE)),
(235, 359, NULL, 1000.00, 0.00, 1000.00, 'COMPLETED', 'TICKET-27-359-F28E04AB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 640 MINUTE)),
(236, 360, 12, 1000.00, 150.00, 850.00, 'COMPLETED', 'TICKET-27-360-1784956C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 640 MINUTE)),
(237, 361, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-361-B64E891C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 640 MINUTE)),
(238, 362, NULL, 4000.00, 0.00, 4000.00, 'COMPLETED', 'TICKET-28-362-BC82A093', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 640 MINUTE)),
(239, 363, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-363-BEC1E7F3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 640 MINUTE)),
(240, 364, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-364-B58AACCA', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 640 MINUTE)),
(241, 365, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-365-68308EE7', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 640 MINUTE)),
(242, 366, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-366-BB3AF9EC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 640 MINUTE)),
(243, 367, NULL, 2000.00, 0.00, 2000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 640 MINUTE)),
(244, 368, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-368-ABBEA0AD', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 640 MINUTE)),
(245, 369, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-369-2E6565DC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 640 MINUTE)),
(246, 370, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-370-530E722F', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 640 MINUTE)),
(247, 371, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-371-F9BDCBCA', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 640 MINUTE)),
(248, 372, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-372-159F00E8', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -79 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -79 DAY), INTERVAL 640 MINUTE)),
(249, 373, NULL, 2000.00, 0.00, 2000.00, 'COMPLETED', 'TICKET-28-373-B5D5E5E3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 640 MINUTE)),
(250, 374, 13, 6000.00, 1200.00, 4800.00, 'COMPLETED', 'TICKET-28-374-D36B2075', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 640 MINUTE)),
(251, 375, NULL, 2000.00, 0.00, 2000.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 640 MINUTE)),
(252, 376, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-376-996B4F1B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 640 MINUTE)),
(253, 377, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-377-FD332607', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -81 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -81 DAY), INTERVAL 640 MINUTE)),
(254, 378, NULL, 12000.00, 0.00, 12000.00, 'COMPLETED', 'TICKET-29-378-C8553472', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -80 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -80 DAY), INTERVAL 640 MINUTE)),
(255, 379, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-379-1E6F9395', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 640 MINUTE)),
(256, 380, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-380-1B916E36', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -98 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -98 DAY), INTERVAL 640 MINUTE)),
(257, 381, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-381-B5D28273', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 640 MINUTE)),
(258, 382, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-382-EBD66554', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -87 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -87 DAY), INTERVAL 640 MINUTE)),
(259, 383, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-383-7810A127', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 640 MINUTE)),
(260, 384, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-384-B911C322', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 640 MINUTE)),
(261, 385, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-385-D018670C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 640 MINUTE)),
(262, 386, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-386-EC8C46CB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -89 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -89 DAY), INTERVAL 640 MINUTE)),
(263, 387, NULL, 24000.00, 0.00, 24000.00, 'COMPLETED', 'TICKET-29-387-D1DFEBE0', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -97 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -97 DAY), INTERVAL 640 MINUTE)),
(264, 388, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-388-59A19DCE', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -79 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -79 DAY), INTERVAL 640 MINUTE)),
(265, 389, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-29-389-EBE501BB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -84 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -84 DAY), INTERVAL 640 MINUTE)),
(266, 390, NULL, 24000.00, 0.00, 24000.00, 'COMPLETED', 'TICKET-29-390-4CDFEE69', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -90 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -90 DAY), INTERVAL 640 MINUTE)),
(267, 406, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-406-E0C01506', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 640 MINUTE)),
(268, 407, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-407-74907A21', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 640 MINUTE)),
(269, 408, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-408-2196438A', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -38 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -38 DAY), INTERVAL 640 MINUTE)),
(270, 409, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-409-F0CE1208', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 640 MINUTE)),
(271, 410, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-410-7F55A7AC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 640 MINUTE)),
(272, 411, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-411-B7955049', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 640 MINUTE)),
(273, 412, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-412-33B8AD67', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 640 MINUTE)),
(274, 413, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-413-810B13C2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 640 MINUTE)),
(275, 414, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-414-9221AAAA', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 640 MINUTE)),
(276, 415, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-415-8FA05CA5', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 640 MINUTE)),
(277, 416, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-416-5C1C048B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 640 MINUTE)),
(278, 417, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-417-28AC711C', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 640 MINUTE)),
(279, 418, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-418-6F01CFCB', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 640 MINUTE)),
(280, 419, NULL, 32000.00, 0.00, 32000.00, 'COMPLETED', 'TICKET-31-419-B7BB38F2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -41 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -41 DAY), INTERVAL 640 MINUTE)),
(281, 420, NULL, 8000.00, 0.00, 8000.00, 'COMPLETED', 'TICKET-31-420-17A32C06', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 640 MINUTE)),
(282, 421, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-421-EE222E8E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 640 MINUTE)),
(283, 422, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-422-9325CD6B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 640 MINUTE)),
(284, 423, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-423-E0EA5761', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 640 MINUTE)),
(285, 424, NULL, 3000.00, 0.00, 3000.00, 'COMPLETED', 'TICKET-32-424-5A610999', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(286, 425, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-425-E6EDEED1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE)),
(287, 426, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-426-A4D26A1E', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 640 MINUTE)),
(288, 427, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-427-751ED690', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 640 MINUTE)),
(289, 428, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-428-ED357A0B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 640 MINUTE)),
(290, 429, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-429-D25E73A3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 640 MINUTE)),
(291, 430, NULL, 1500.00, 0.00, 1500.00, 'FAILED', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 640 MINUTE)),
(292, 431, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-431-6EB88AFC', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 640 MINUTE)),
(293, 432, NULL, 1500.00, 0.00, 1500.00, 'PENDING', NULL, NULL, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 640 MINUTE)),
(294, 433, NULL, 1500.00, 0.00, 1500.00, 'COMPLETED', 'TICKET-32-433-6F215451', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 640 MINUTE)),
(295, 434, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-32-434-E1092001', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 640 MINUTE)),
(296, 435, NULL, 6000.00, 0.00, 6000.00, 'COMPLETED', 'TICKET-32-435-B24FB44B', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 645 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 640 MINUTE));

-- ------------------------------------------------------------- refunds
INSERT INTO refunds (id, payment_id, status, amount, requested_at, processed_at) VALUES
(1, 267, 'PROCESSED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(2, 268, 'COMPLETED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(3, 269, 'PENDING', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), NULL),
(4, 270, 'PROCESSED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(5, 271, 'COMPLETED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(6, 272, 'PENDING', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), NULL),
(7, 273, 'PROCESSED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(8, 274, 'COMPLETED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(9, 275, 'PENDING', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), NULL),
(10, 276, 'PROCESSED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(11, 277, 'COMPLETED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(12, 278, 'PENDING', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), NULL),
(13, 279, 'PROCESSED', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(14, 280, 'COMPLETED', 32000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 840 MINUTE)),
(15, 281, 'PENDING', 8000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 660 MINUTE), NULL),
(16, 282, 'PROCESSED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(17, 283, 'COMPLETED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(18, 284, 'PENDING', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), NULL),
(19, 285, 'PROCESSED', 3000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(20, 286, 'COMPLETED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(21, 287, 'PENDING', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), NULL),
(22, 288, 'PROCESSED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(23, 289, 'COMPLETED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(24, 290, 'PENDING', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), NULL),
(25, 292, 'PROCESSED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(26, 294, 'COMPLETED', 1500.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE)),
(27, 295, 'PENDING', 6000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), NULL),
(28, 296, 'PROCESSED', 6000.00, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 660 MINUTE), DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 840 MINUTE));

-- ------------------------------------------------------- notifications
INSERT INTO notifications (id, user_id, type, channel, message, is_read, sent_at) VALUES
(1, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Intro to Cloud Computing Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(2, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Intro to Cloud Computing Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(3, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Intro to Cloud Computing Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(4, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Intro to Cloud Computing Workshop" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 720 MINUTE)),
(5, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Intro to Cloud Computing Workshop" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(6, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 720 MINUTE)),
(7, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(8, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(9, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(10, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(11, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(12, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(13, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "AI & Data Science Conference 2026" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(14, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(15, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(16, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(17, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 720 MINUTE)),
(18, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(19, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(20, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(21, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(22, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Executive Leadership Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 720 MINUTE)),
(23, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(24, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(25, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 720 MINUTE)),
(26, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 720 MINUTE)),
(27, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(28, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(29, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Startup Pitch Night" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(30, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(31, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(32, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(33, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 720 MINUTE)),
(34, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(35, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(36, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE)),
(37, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(38, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(39, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Photography Masterclass" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 720 MINUTE)),
(40, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(41, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(42, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 720 MINUTE)),
(43, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(44, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(45, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(46, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(47, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Web Security Bootcamp" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(48, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Colombo Jazz Evening" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(49, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Colombo Jazz Evening" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(50, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Colombo Jazz Evening" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(51, 18, 'WAITLIST_UPDATE', 'IN_APP', '"Colombo Jazz Evening" is full. You have been added to the waitlist.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 720 MINUTE)),
(52, 21, 'WAITLIST_UPDATE', 'IN_APP', '"Colombo Jazz Evening" is full. You have been added to the waitlist.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(53, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(54, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 720 MINUTE)),
(55, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 720 MINUTE)),
(56, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(57, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(58, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(59, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Full-Stack Java Career Fair" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(60, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(61, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(62, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(63, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(64, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(65, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(66, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(67, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(68, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(69, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Digital Marketing Summit" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 720 MINUTE)),
(70, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(71, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(72, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(73, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(74, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 720 MINUTE)),
(75, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(76, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(77, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(78, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Mobile App Development Workshop" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(79, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Sri Lanka Tech Innovators Forum" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(80, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Sri Lanka Tech Innovators Forum" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 720 MINUTE)),
(81, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Sri Lanka Tech Innovators Forum" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(82, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Sri Lanka Tech Innovators Forum" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 720 MINUTE)),
(83, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Sri Lanka Tech Innovators Forum" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 720 MINUTE)),
(84, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Sri Lanka Tech Innovators Forum" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(85, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Yoga and Mindfulness Morning" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(86, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Yoga and Mindfulness Morning" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(87, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Yoga and Mindfulness Morning" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 720 MINUTE)),
(88, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Yoga and Mindfulness Morning" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(89, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Yoga and Mindfulness Morning" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(90, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Yoga and Mindfulness Morning" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(91, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Food Festival: Street Eats" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(92, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Food Festival: Street Eats" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(93, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Food Festival: Street Eats" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(94, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Food Festival: Street Eats" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(95, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Food Festival: Street Eats" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(96, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Corporate Cricket Sixes" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 720 MINUTE)),
(97, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Corporate Cricket Sixes" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(98, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Corporate Cricket Sixes" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 720 MINUTE)),
(99, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Corporate Cricket Sixes" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 720 MINUTE)),
(100, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Corporate Cricket Sixes" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 55 DAY), INTERVAL 720 MINUTE)),
(101, 8, 'WAITLIST_UPDATE', 'IN_APP', '"Corporate Cricket Sixes" is full. You have been added to the waitlist.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 50 DAY), INTERVAL 720 MINUTE)),
(102, 21, 'WAITLIST_UPDATE', 'IN_APP', '"Corporate Cricket Sixes" is full. You have been added to the waitlist.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 42 DAY), INTERVAL 720 MINUTE)),
(103, 6, 'WAITLIST_UPDATE', 'IN_APP', '"Corporate Cricket Sixes" is full. You have been added to the waitlist.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 720 MINUTE)),
(104, 4, 'WAITLIST_UPDATE', 'IN_APP', '"Corporate Cricket Sixes" is full. You have been added to the waitlist.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 720 MINUTE)),
(105, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 720 MINUTE)),
(106, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(107, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(108, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 720 MINUTE)),
(109, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(110, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 720 MINUTE)),
(111, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(112, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "UI/UX Design Sprint" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(113, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cybersecurity Awareness Seminar" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(114, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cybersecurity Awareness Seminar" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(115, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cybersecurity Awareness Seminar" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 720 MINUTE)),
(116, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cybersecurity Awareness Seminar" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(117, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cybersecurity Awareness Seminar" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(118, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(119, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(120, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 720 MINUTE)),
(121, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 720 MINUTE)),
(122, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 720 MINUTE)),
(123, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 26 DAY), INTERVAL 720 MINUTE)),
(124, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Annual Alumni Networking Dinner" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 720 MINUTE)),
(125, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Native Kubernetes Deep Dive" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(126, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Native Kubernetes Deep Dive" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 720 MINUTE)),
(127, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Native Kubernetes Deep Dive" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(128, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Native Kubernetes Deep Dive" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(129, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Native Kubernetes Deep Dive" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(130, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Native Kubernetes Deep Dive" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(131, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 44 DAY), INTERVAL 720 MINUTE)),
(132, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 60 DAY), INTERVAL 720 MINUTE)),
(133, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 720 MINUTE)),
(134, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 720 MINUTE)),
(135, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 720 MINUTE)),
(136, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 720 MINUTE)),
(137, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 41 DAY), INTERVAL 720 MINUTE)),
(138, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 53 DAY), INTERVAL 720 MINUTE)),
(139, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Classical Music Gala" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 720 MINUTE)),
(140, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Product Management Essentials" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(141, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Product Management Essentials" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(142, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Product Management Essentials" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(143, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Product Management Essentials" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(144, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Product Management Essentials" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(145, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Product Management Essentials" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 720 MINUTE)),
(146, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 720 MINUTE)),
(147, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -41 DAY), INTERVAL 720 MINUTE)),
(148, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 720 MINUTE)),
(149, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 720 MINUTE)),
(150, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 720 MINUTE)),
(151, 3, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -33 DAY), INTERVAL 720 MINUTE)),
(152, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 720 MINUTE)),
(153, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 720 MINUTE)),
(154, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Wellness and Nutrition Talk" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 720 MINUTE)),
(155, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Blockchain and Fintech Forum" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 720 MINUTE)),
(156, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Blockchain and Fintech Forum" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 720 MINUTE)),
(157, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Blockchain and Fintech Forum" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 720 MINUTE)),
(158, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Blockchain and Fintech Forum" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 720 MINUTE)),
(159, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 720 MINUTE)),
(160, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -71 DAY), INTERVAL 720 MINUTE)),
(161, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 720 MINUTE)),
(162, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 720 MINUTE)),
(163, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 720 MINUTE)),
(164, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 720 MINUTE)),
(165, 20, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Creative Writing Retreat" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 720 MINUTE)),
(166, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 720 MINUTE)),
(167, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 720 MINUTE)),
(168, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(169, 22, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 720 MINUTE)),
(170, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(171, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 720 MINUTE)),
(172, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(173, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(174, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Cloud Cost Optimisation Clinic" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 720 MINUTE)),
(175, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 720 MINUTE)),
(176, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 720 MINUTE)),
(177, 14, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 720 MINUTE)),
(178, 16, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 720 MINUTE)),
(179, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 720 MINUTE)),
(180, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 720 MINUTE)),
(181, 19, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 720 MINUTE)),
(182, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(183, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 720 MINUTE)),
(184, 17, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -73 DAY), INTERVAL 720 MINUTE)),
(185, 13, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Vegan Cooking Class" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(186, 15, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -80 DAY), INTERVAL 720 MINUTE)),
(187, 23, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -98 DAY), INTERVAL 720 MINUTE)),
(188, 6, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 720 MINUTE)),
(189, 21, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 720 MINUTE)),
(190, 8, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 720 MINUTE)),
(191, 4, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(192, 18, 'REGISTRATION_CONFIRMATION', 'IN_APP', 'Your registration for "Women in Tech Panel" is confirmed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -97 DAY), INTERVAL 720 MINUTE)),
(193, 22, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(194, 19, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(195, 18, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(196, 15, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(197, 4, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(198, 16, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(199, 21, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(200, 3, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(201, 20, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(202, 8, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(203, 13, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(204, 14, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(205, 6, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(206, 23, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(207, 17, 'EVENT_CANCELLATION', 'IN_APP', '"Advanced Spring Boot Training" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(208, 3, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(209, 13, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(210, 23, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(211, 17, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(212, 4, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(213, 20, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(214, 14, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(215, 8, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(216, 6, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(217, 15, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(218, 16, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(219, 22, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(220, 21, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(221, 18, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(222, 19, 'EVENT_CANCELLATION', 'IN_APP', '"Digital Photography Walk" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(223, 23, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(224, 15, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(225, 22, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(226, 16, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(227, 20, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(228, 14, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(229, 19, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(230, 6, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(231, 18, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(232, 21, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(233, 8, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(234, 3, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(235, 17, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(236, 4, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(237, 13, 'EVENT_CANCELLATION', 'IN_APP', '"Enterprise Architecture Symposium" has been cancelled. Any payment will be refunded.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(238, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(239, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-3-4A658789', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(240, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(241, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-4-E8D571F1', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(242, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(243, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-8-964ABD5F', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(244, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(245, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-9-FCBF2720', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(246, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(247, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-12-60F302CF', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(248, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 720 MINUTE)),
(249, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-13-743D712C', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 720 MINUTE)),
(250, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(251, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-14-C301850E', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 720 MINUTE)),
(252, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Intro to Cloud Computing Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(253, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Intro to Cloud Computing Workshop" is ready: TICKET-1-15-1B9FDA97', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(254, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Executive Leadership Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(255, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-31-894E6575', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(256, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Executive Leadership Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(257, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-32-A9978222', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(258, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 3825.00 confirmed for "Executive Leadership Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(259, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-34-29C0516F', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(260, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 15300.00 confirmed for "Executive Leadership Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(261, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-39-2B80F6E5', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(262, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Executive Leadership Retreat".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(263, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-40-8EDAA1A9', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(264, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Executive Leadership Retreat".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(265, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-41-70912679', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(266, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Executive Leadership Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(267, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-44-19346732', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(268, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Executive Leadership Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 720 MINUTE)),
(269, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Executive Leadership Retreat" is ready: TICKET-3-45-2ED5FF87', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 720 MINUTE)),
(270, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Photography Masterclass".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(271, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-61-2F40B889', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(272, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2250.00 confirmed for "Photography Masterclass".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(273, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-62-C6805AEE', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(274, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2250.00 confirmed for "Photography Masterclass".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(275, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-63-27111881', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(276, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Photography Masterclass".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(277, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-65-91370FA8', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 720 MINUTE)),
(278, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 7500.00 confirmed for "Photography Masterclass".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(279, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-67-9D44EAA9', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(280, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 7500.00 confirmed for "Photography Masterclass".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(281, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-68-7C82A667', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 720 MINUTE)),
(282, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2250.00 confirmed for "Photography Masterclass".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(283, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-69-E60152FC', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(284, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Photography Masterclass".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(285, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-71-656F1EA6', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(286, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Photography Masterclass".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE)),
(287, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-72-AD6205DA', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 720 MINUTE)),
(288, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2250.00 confirmed for "Photography Masterclass".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(289, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-73-1416408A', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(290, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2250.00 confirmed for "Photography Masterclass".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(291, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Photography Masterclass" is ready: TICKET-5-74-1CEE5D45', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(292, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Web Security Bootcamp".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(293, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Web Security Bootcamp" is ready: TICKET-6-76-8DF2B595', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(294, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 27200.00 confirmed for "Web Security Bootcamp".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(295, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Web Security Bootcamp" is ready: TICKET-6-77-7B1437AA', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 720 MINUTE)),
(296, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 7000.00 confirmed for "Web Security Bootcamp".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(297, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Web Security Bootcamp" is ready: TICKET-6-78-F5F086F0', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(298, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6800.00 confirmed for "Web Security Bootcamp".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(299, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Web Security Bootcamp" is ready: TICKET-6-79-A5A6EEE9', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(300, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6800.00 confirmed for "Web Security Bootcamp".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(301, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Web Security Bootcamp" is ready: TICKET-6-83-44D6945B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(302, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 720 MINUTE)),
(303, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-91-3F34ADF1', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 720 MINUTE)),
(304, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 720 MINUTE)),
(305, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-92-77C47D43', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 720 MINUTE)),
(306, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Colombo Jazz Evening".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(307, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-95-A49B60E6', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(308, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(309, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-96-DDF1B802', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(310, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 720 MINUTE)),
(311, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-98-1E704BA3', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 720 MINUTE)),
(312, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(313, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-99-39DA940A', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(314, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 720 MINUTE)),
(315, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-101-2FE153C2', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 720 MINUTE)),
(316, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 720 MINUTE)),
(317, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-102-E9B2DF9A', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 720 MINUTE)),
(318, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Colombo Jazz Evening".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(319, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Colombo Jazz Evening" is ready: TICKET-7-103-E38ACEEC', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 720 MINUTE)),
(320, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Full-Stack Java Career Fair".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 720 MINUTE)),
(321, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-106-C3602044', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 720 MINUTE)),
(322, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Full-Stack Java Career Fair".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(323, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-107-1362B476', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(324, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 7500.00 confirmed for "Full-Stack Java Career Fair".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 720 MINUTE)),
(325, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-110-B105A7B4', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 40 DAY), INTERVAL 720 MINUTE)),
(326, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Full-Stack Java Career Fair".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 720 MINUTE)),
(327, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-111-41DE71F5', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 720 MINUTE)),
(328, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 7500.00 confirmed for "Full-Stack Java Career Fair".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(329, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-113-3C79A931', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 720 MINUTE)),
(330, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 10000.00 confirmed for "Full-Stack Java Career Fair".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(331, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-114-6DBF9B7A', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(332, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Full-Stack Java Career Fair".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(333, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-115-8FD8FFFB', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(334, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Full-Stack Java Career Fair".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 720 MINUTE)),
(335, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-117-591D726B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 720 MINUTE)),
(336, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2500.00 confirmed for "Full-Stack Java Career Fair".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(337, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Full-Stack Java Career Fair" is ready: TICKET-8-119-657022DE', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 720 MINUTE)),
(338, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(339, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-121-E561D163', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(340, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(341, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-122-FBDB227B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(342, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 24000.00 confirmed for "Digital Marketing Summit".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(343, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-123-8C6C540E', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(344, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(345, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-124-62C21224', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(346, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(347, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-126-E1C40FB3', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(348, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(349, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-128-696FC2D5', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(350, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 16000.00 confirmed for "Digital Marketing Summit".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(351, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-129-FCF84D27', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(352, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(353, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-130-711D524B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(354, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Marketing Summit".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(355, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-131-4EC84DE5', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(356, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6800.00 confirmed for "Digital Marketing Summit".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(357, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-132-311486A1', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(358, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6800.00 confirmed for "Digital Marketing Summit".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 720 MINUTE)),
(359, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Marketing Summit" is ready: TICKET-9-135-2A78BD24', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 720 MINUTE)),
(360, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Mobile App Development Workshop".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(361, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Mobile App Development Workshop" is ready: TICKET-10-136-72154F7D', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(362, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 900.00 confirmed for "Mobile App Development Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(363, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Mobile App Development Workshop" is ready: TICKET-10-140-BA6BB8D3', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 720 MINUTE)),
(364, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 900.00 confirmed for "Mobile App Development Workshop".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(365, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Mobile App Development Workshop" is ready: TICKET-10-145-60E82009', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 720 MINUTE)),
(366, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 900.00 confirmed for "Mobile App Development Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(367, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Mobile App Development Workshop" is ready: TICKET-10-147-CB6C2BB8', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 720 MINUTE)),
(368, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Mobile App Development Workshop".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(369, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Mobile App Development Workshop" is ready: TICKET-10-148-D72D6D2F', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 720 MINUTE)),
(370, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(371, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-152-CBA356DF', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(372, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(373, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-153-CFB12B2B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(374, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 16000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 720 MINUTE)),
(375, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-154-C06454A2', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 720 MINUTE)),
(376, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(377, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-156-BE3195E4', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(378, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 720 MINUTE)),
(379, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-157-F8C33EA2', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 46 DAY), INTERVAL 720 MINUTE)),
(380, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(381, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-158-58FD5513', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(382, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(383, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-160-12B3C25C', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 720 MINUTE)),
(384, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 32000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(385, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-162-47867585', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(386, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Sri Lanka Tech Innovators Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(387, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Sri Lanka Tech Innovators Forum" is ready: TICKET-11-164-39D5208F', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(388, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Food Festival: Street Eats".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(389, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Food Festival: Street Eats" is ready: TICKET-13-181-ECEFD924', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(390, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Food Festival: Street Eats".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 720 MINUTE)),
(391, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Food Festival: Street Eats" is ready: TICKET-13-188-414A26ED', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 17 DAY), INTERVAL 720 MINUTE)),
(392, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Food Festival: Street Eats".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(393, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Food Festival: Street Eats" is ready: TICKET-13-191-B8770DAD', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 720 MINUTE)),
(394, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Food Festival: Street Eats".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(395, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Food Festival: Street Eats" is ready: TICKET-13-192-43C111A5', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 720 MINUTE)),
(396, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Food Festival: Street Eats".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(397, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Food Festival: Street Eats" is ready: TICKET-13-195-52D5E0D1', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(398, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 49 DAY), INTERVAL 720 MINUTE)),
(399, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-196-28AB5FA5', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 49 DAY), INTERVAL 720 MINUTE)),
(400, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(401, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-198-A860E7D2', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 35 DAY), INTERVAL 720 MINUTE)),
(402, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4000.00 confirmed for "Corporate Cricket Sixes".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 720 MINUTE)),
(403, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-199-7B647F90', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 720 MINUTE)),
(404, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 51 DAY), INTERVAL 720 MINUTE)),
(405, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-200-F11B447A', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 51 DAY), INTERVAL 720 MINUTE)),
(406, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 720 MINUTE)),
(407, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-202-D02C2A5E', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 56 DAY), INTERVAL 720 MINUTE)),
(408, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 55 DAY), INTERVAL 720 MINUTE)),
(409, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-203-2BA85947', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 55 DAY), INTERVAL 720 MINUTE)),
(410, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 720 MINUTE)),
(411, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-204-1C49B321', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 720 MINUTE)),
(412, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Corporate Cricket Sixes".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 720 MINUTE)),
(413, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Corporate Cricket Sixes" is ready: TICKET-14-208-58433A74', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 57 DAY), INTERVAL 720 MINUTE)),
(414, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(415, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-226-1C10FC73', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 720 MINUTE)),
(416, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(417, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-227-DD5DA9E9', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 720 MINUTE)),
(418, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 12000.00 confirmed for "Cybersecurity Awareness Seminar".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 720 MINUTE)),
(419, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-229-C0027BC9', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 720 MINUTE)),
(420, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 720 MINUTE)),
(421, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-230-AF1B6AB7', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 720 MINUTE)),
(422, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(423, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-231-A91396A0', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(424, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 720 MINUTE)),
(425, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-232-E72B8B00', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 720 MINUTE)),
(426, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(427, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-235-524110A7', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 720 MINUTE)),
(428, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(429, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-237-22602273', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 10 DAY), INTERVAL 720 MINUTE)),
(430, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Cybersecurity Awareness Seminar".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(431, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cybersecurity Awareness Seminar" is ready: TICKET-16-239-7B3FBFEB', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 720 MINUTE)),
(432, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Classical Music Gala".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 720 MINUTE)),
(433, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Classical Music Gala" is ready: TICKET-19-274-6525B8FC', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 54 DAY), INTERVAL 720 MINUTE)),
(434, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 3000.00 confirmed for "Classical Music Gala".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 720 MINUTE)),
(435, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Classical Music Gala" is ready: TICKET-19-275-6C7570B3', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 48 DAY), INTERVAL 720 MINUTE)),
(436, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 3000.00 confirmed for "Classical Music Gala".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 720 MINUTE)),
(437, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Classical Music Gala" is ready: TICKET-19-277-9D4C1815', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 720 MINUTE)),
(438, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 3000.00 confirmed for "Classical Music Gala".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 720 MINUTE)),
(439, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Classical Music Gala" is ready: TICKET-19-281-D55E5E33', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 47 DAY), INTERVAL 720 MINUTE)),
(440, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 3000.00 confirmed for "Classical Music Gala".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 720 MINUTE)),
(441, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Classical Music Gala" is ready: TICKET-19-283-A2555AEC', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 64 DAY), INTERVAL 720 MINUTE)),
(442, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4800.00 confirmed for "Product Management Essentials".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(443, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-287-629BDE1E', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 720 MINUTE)),
(444, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 12000.00 confirmed for "Product Management Essentials".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(445, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-288-5A3D33DF', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 720 MINUTE)),
(446, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 12000.00 confirmed for "Product Management Essentials".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 720 MINUTE)),
(447, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-289-D9E576FE', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 720 MINUTE)),
(448, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 5400.00 confirmed for "Product Management Essentials".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(449, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-292-BFEA25CB', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(450, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Product Management Essentials".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 720 MINUTE)),
(451, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-293-80920876', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 720 MINUTE)),
(452, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Product Management Essentials".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 720 MINUTE)),
(453, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-294-DF192D21', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 720 MINUTE)),
(454, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 5400.00 confirmed for "Product Management Essentials".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(455, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-295-25CDA469', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(456, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Product Management Essentials".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(457, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-298-778B503B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(458, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 5400.00 confirmed for "Product Management Essentials".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 720 MINUTE)),
(459, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Product Management Essentials" is ready: TICKET-20-299-FA78E52B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 720 MINUTE)),
(460, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 720 MINUTE)),
(461, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-316-EBE4B6DF', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 720 MINUTE)),
(462, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4050.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 720 MINUTE)),
(463, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-319-23FB097E', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -43 DAY), INTERVAL 720 MINUTE)),
(464, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 720 MINUTE)),
(465, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-320-F73FA715', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 720 MINUTE)),
(466, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4050.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 720 MINUTE)),
(467, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-321-F3C472E1', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 720 MINUTE)),
(468, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4050.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 720 MINUTE)),
(469, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-323-DFEE1ACD', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -57 DAY), INTERVAL 720 MINUTE)),
(470, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4050.00 confirmed for "Blockchain and Fintech Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 720 MINUTE)),
(471, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-324-94DE5910', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 720 MINUTE)),
(472, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4050.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 720 MINUTE)),
(473, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-327-BDC6F091', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 720 MINUTE)),
(474, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Blockchain and Fintech Forum".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 720 MINUTE)),
(475, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-328-84C74D18', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 720 MINUTE)),
(476, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4050.00 confirmed for "Blockchain and Fintech Forum".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 720 MINUTE)),
(477, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Blockchain and Fintech Forum" is ready: TICKET-25-330-F76BB77D', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 720 MINUTE)),
(478, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 720 MINUTE)),
(479, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-333-19DF6564', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 720 MINUTE)),
(480, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -49 DAY), INTERVAL 720 MINUTE)),
(481, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-334-C2C37D8B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -49 DAY), INTERVAL 720 MINUTE)),
(482, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 720 MINUTE)),
(483, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-336-D297199F', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -62 DAY), INTERVAL 720 MINUTE)),
(484, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 720 MINUTE)),
(485, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-338-D4EFBF4B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 720 MINUTE)),
(486, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 720 MINUTE)),
(487, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-340-959D73EC', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 720 MINUTE)),
(488, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 9000.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -72 DAY), INTERVAL 720 MINUTE)),
(489, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-341-736B88BB', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -72 DAY), INTERVAL 720 MINUTE)),
(490, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 720 MINUTE)),
(491, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-342-F6B591DC', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -67 DAY), INTERVAL 720 MINUTE)),
(492, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 720 MINUTE)),
(493, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-343-3A72569B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -69 DAY), INTERVAL 720 MINUTE)),
(494, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4500.00 confirmed for "Creative Writing Retreat".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 720 MINUTE)),
(495, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Creative Writing Retreat" is ready: TICKET-26-345-67E2180A', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -51 DAY), INTERVAL 720 MINUTE)),
(496, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Cloud Cost Optimisation Clinic".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 720 MINUTE)),
(497, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-346-5F8788C3', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -34 DAY), INTERVAL 720 MINUTE)),
(498, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 850.00 confirmed for "Cloud Cost Optimisation Clinic".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 720 MINUTE)),
(499, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-347-BE1112AE', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -25 DAY), INTERVAL 720 MINUTE)),
(500, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Cloud Cost Optimisation Clinic".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 720 MINUTE)),
(501, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-350-BFECE52C', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 720 MINUTE)),
(502, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Cloud Cost Optimisation Clinic".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(503, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-351-FC86022E', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(504, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 850.00 confirmed for "Cloud Cost Optimisation Clinic".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 720 MINUTE)),
(505, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-352-D881973C', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 720 MINUTE)),
(506, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Cloud Cost Optimisation Clinic".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 720 MINUTE)),
(507, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-355-91B4D9BA', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 720 MINUTE)),
(508, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4000.00 confirmed for "Cloud Cost Optimisation Clinic".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(509, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-356-8E9CD969', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 720 MINUTE)),
(510, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Cloud Cost Optimisation Clinic".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 720 MINUTE)),
(511, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-358-4F5A33CF', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -37 DAY), INTERVAL 720 MINUTE)),
(512, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1000.00 confirmed for "Cloud Cost Optimisation Clinic".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(513, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Cloud Cost Optimisation Clinic" is ready: TICKET-27-359-F28E04AB', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(514, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Vegan Cooking Class".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 720 MINUTE)),
(515, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Vegan Cooking Class" is ready: TICKET-28-361-B64E891C', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 720 MINUTE)),
(516, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4000.00 confirmed for "Vegan Cooking Class".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 720 MINUTE)),
(517, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Vegan Cooking Class" is ready: TICKET-28-362-BC82A093', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -66 DAY), INTERVAL 720 MINUTE)),
(518, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Vegan Cooking Class".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 720 MINUTE)),
(519, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Vegan Cooking Class" is ready: TICKET-28-363-BEC1E7F3', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -83 DAY), INTERVAL 720 MINUTE)),
(520, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Vegan Cooking Class".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 720 MINUTE)),
(521, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Vegan Cooking Class" is ready: TICKET-28-365-68308EE7', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -78 DAY), INTERVAL 720 MINUTE)),
(522, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 2000.00 confirmed for "Vegan Cooking Class".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(523, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Vegan Cooking Class" is ready: TICKET-28-370-530E722F', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(524, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 4800.00 confirmed for "Vegan Cooking Class".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(525, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Vegan Cooking Class" is ready: TICKET-28-374-D36B2075', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(526, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -81 DAY), INTERVAL 720 MINUTE)),
(527, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-377-FD332607', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -81 DAY), INTERVAL 720 MINUTE)),
(528, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 720 MINUTE)),
(529, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-379-1E6F9395', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -99 DAY), INTERVAL 720 MINUTE)),
(530, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -98 DAY), INTERVAL 720 MINUTE)),
(531, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-380-1B916E36', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -98 DAY), INTERVAL 720 MINUTE)),
(532, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 720 MINUTE)),
(533, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-381-B5D28273', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -82 DAY), INTERVAL 720 MINUTE)),
(534, 8, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 720 MINUTE)),
(535, 8, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-384-B911C322', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -103 DAY), INTERVAL 720 MINUTE)),
(536, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -89 DAY), INTERVAL 720 MINUTE)),
(537, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-386-EC8C46CB', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -89 DAY), INTERVAL 720 MINUTE)),
(538, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 24000.00 confirmed for "Women in Tech Panel".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -97 DAY), INTERVAL 720 MINUTE)),
(539, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-387-D1DFEBE0', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -97 DAY), INTERVAL 720 MINUTE)),
(540, 20, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Women in Tech Panel".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -84 DAY), INTERVAL 720 MINUTE)),
(541, 20, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Women in Tech Panel" is ready: TICKET-29-389-EBE501BB', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -84 DAY), INTERVAL 720 MINUTE)),
(542, 3, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 720 MINUTE)),
(543, 3, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-406-E0C01506', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 720 MINUTE)),
(544, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -38 DAY), INTERVAL 720 MINUTE)),
(545, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-408-2196438A', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -38 DAY), INTERVAL 720 MINUTE)),
(546, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 720 MINUTE)),
(547, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-409-F0CE1208', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -42 DAY), INTERVAL 720 MINUTE)),
(548, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 720 MINUTE)),
(549, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-415-8FA05CA5', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 720 MINUTE)),
(550, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 720 MINUTE)),
(551, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-416-5C1C048B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -50 DAY), INTERVAL 720 MINUTE)),
(552, 22, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 720 MINUTE)),
(553, 22, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-417-28AC711C', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -39 DAY), INTERVAL 720 MINUTE)),
(554, 21, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(555, 21, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-418-6F01CFCB', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 720 MINUTE)),
(556, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 8000.00 confirmed for "Digital Photography Walk".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 720 MINUTE)),
(557, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Digital Photography Walk" is ready: TICKET-31-420-17A32C06', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -46 DAY), INTERVAL 720 MINUTE)),
(558, 23, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(559, 23, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-421-EE222E8E', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 720 MINUTE)),
(560, 15, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(561, 15, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-422-9325CD6B', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 720 MINUTE)),
(562, 16, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 3000.00 confirmed for "Enterprise Architecture Symposium".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(563, 16, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-424-5A610999', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(564, 14, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(565, 14, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-426-A4D26A1E', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -10 DAY), INTERVAL 720 MINUTE)),
(566, 19, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 720 MINUTE)),
(567, 19, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-427-751ED690', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 720 MINUTE)),
(568, 6, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(569, 6, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-428-ED357A0B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(570, 18, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(571, 18, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-429-D25E73A3', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(572, 17, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 1500.00 confirmed for "Enterprise Architecture Symposium".', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(573, 17, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-433-6F215451', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 720 MINUTE)),
(574, 4, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Enterprise Architecture Symposium".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(575, 4, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-434-E1092001', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 720 MINUTE)),
(576, 13, 'PAYMENT_CONFIRMATION', 'IN_APP', 'Payment of LKR 6000.00 confirmed for "Enterprise Architecture Symposium".', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(577, 13, 'TICKET_ISSUED', 'IN_APP', 'Your e-ticket for "Enterprise Architecture Symposium" is ready: TICKET-32-435-B24FB44B', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 720 MINUTE)),
(578, 13, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 8000.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 720 MINUTE)),
(579, 4, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 8000.00 has been processed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 720 MINUTE)),
(580, 8, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 8000.00 has been processed.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 720 MINUTE)),
(581, 16, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 8000.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 720 MINUTE)),
(582, 18, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 32000.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 720 MINUTE)),
(583, 15, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 1500.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(584, 20, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 1500.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(585, 6, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 1500.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(586, 17, 'REFUND_PROCESSED', 'IN_APP', 'Your refund of LKR 1500.00 has been processed.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 720 MINUTE)),
(587, 22, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(588, 3, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(589, 19, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(590, 6, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(591, 23, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(592, 14, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(593, 15, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(594, 20, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(595, 4, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(596, 17, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(597, 13, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(598, 8, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Colombo Jazz Evening" starts in 5 day(s) at Hilton Colombo, Colombo 01.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(599, 20, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(600, 4, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(601, 22, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(602, 14, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(603, 6, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(604, 15, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(605, 21, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(606, 16, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(607, 17, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(608, 23, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(609, 3, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(610, 8, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(611, 13, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Digital Marketing Summit" has been updated to Earl''s Regency, Kandy.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 720 MINUTE)),
(612, 8, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(613, 13, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(614, 4, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(615, 14, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(616, 18, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(617, 3, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(618, 6, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(619, 21, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(620, 23, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(621, 22, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(622, 20, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(623, 15, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(624, 17, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(625, 16, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(626, 19, 'EVENT_REMINDER', 'IN_APP', 'Reminder: "Yoga and Mindfulness Morning" starts in 7 day(s) at Jetwing Blue, Negombo.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(627, 15, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(628, 23, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(629, 17, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(630, 8, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(631, 16, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(632, 21, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(633, 13, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(634, 20, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(635, 22, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(636, 3, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(637, 14, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(638, 19, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(639, 18, 'EVENT_CHANGE', 'IN_APP', 'The venue for "Cloud Native Kubernetes Deep Dive" has been updated to Cinnamon Grand Ballroom, Colombo 03.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 720 MINUTE)),
(640, 8, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Executive Leadership Retreat" was approved.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(641, 13, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Executive Leadership Retreat" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(642, 20, 'INVITATION_REJECTED', 'IN_APP', 'Your invitation request for "Executive Leadership Retreat" was not approved.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(643, 15, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Executive Leadership Retreat" was approved.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(644, 19, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Executive Leadership Retreat" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -1 DAY), INTERVAL 720 MINUTE)),
(645, 3, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Annual Alumni Networking Dinner" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(646, 4, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Annual Alumni Networking Dinner" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(647, 21, 'INVITATION_REJECTED', 'IN_APP', 'Your invitation request for "Annual Alumni Networking Dinner" was not approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(648, 23, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Annual Alumni Networking Dinner" was approved.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(649, 22, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Annual Alumni Networking Dinner" was approved.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 18 DAY), INTERVAL 720 MINUTE)),
(650, 16, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Creative Writing Retreat" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(651, 8, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Creative Writing Retreat" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(652, 19, 'INVITATION_REJECTED', 'IN_APP', 'Your invitation request for "Creative Writing Retreat" was not approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(653, 4, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Creative Writing Retreat" was approved.', 0, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE)),
(654, 18, 'INVITATION_APPROVED', 'IN_APP', 'Your invitation request for "Creative Writing Retreat" was approved.', 1, DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -86 DAY), INTERVAL 720 MINUTE));

-- ---------------------------------------------------------- audit_logs
INSERT INTO audit_logs (id, user_id, action, entity_type, entity_id, details, created_at) VALUES
(1, 5, 'CREATE_EVENT', 'Event', 1, 'Created event "Intro to Cloud Computing Workshop"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -20 DAY), INTERVAL 555 MINUTE)),
(2, 1, 'APPROVE_EVENT', 'Event', 1, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -19 DAY), INTERVAL 555 MINUTE)),
(3, 9, 'CREATE_EVENT', 'Event', 2, 'Created event "AI & Data Science Conference 2026"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 555 MINUTE)),
(4, 1, 'APPROVE_EVENT', 'Event', 2, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -35 DAY), INTERVAL 555 MINUTE)),
(5, 10, 'CREATE_EVENT', 'Event', 3, 'Created event "Executive Leadership Retreat"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 555 MINUTE)),
(6, 1, 'APPROVE_EVENT', 'Event', 3, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 555 MINUTE)),
(7, 11, 'CREATE_EVENT', 'Event', 4, 'Created event "Startup Pitch Night"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -23 DAY), INTERVAL 555 MINUTE)),
(8, 1, 'APPROVE_EVENT', 'Event', 4, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -22 DAY), INTERVAL 555 MINUTE)),
(9, 2, 'CREATE_EVENT', 'Event', 5, 'Created event "Photography Masterclass"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 555 MINUTE)),
(10, 1, 'APPROVE_EVENT', 'Event', 5, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 555 MINUTE)),
(11, 5, 'CREATE_EVENT', 'Event', 6, 'Created event "Web Security Bootcamp"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -29 DAY), INTERVAL 555 MINUTE)),
(12, 1, 'APPROVE_EVENT', 'Event', 6, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -28 DAY), INTERVAL 555 MINUTE)),
(13, 9, 'CREATE_EVENT', 'Event', 7, 'Created event "Colombo Jazz Evening"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -27 DAY), INTERVAL 555 MINUTE)),
(14, 1, 'APPROVE_EVENT', 'Event', 7, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -26 DAY), INTERVAL 555 MINUTE)),
(15, 10, 'CREATE_EVENT', 'Event', 8, 'Created event "Full-Stack Java Career Fair"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 555 MINUTE)),
(16, 1, 'APPROVE_EVENT', 'Event', 8, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 555 MINUTE)),
(17, 11, 'CREATE_EVENT', 'Event', 9, 'Created event "Digital Marketing Summit"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -16 DAY), INTERVAL 555 MINUTE)),
(18, 1, 'APPROVE_EVENT', 'Event', 9, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 555 MINUTE)),
(19, 2, 'CREATE_EVENT', 'Event', 10, 'Created event "Mobile App Development Workshop"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 555 MINUTE)),
(20, 1, 'APPROVE_EVENT', 'Event', 10, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(21, 5, 'CREATE_EVENT', 'Event', 11, 'Created event "Sri Lanka Tech Innovators Forum"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 22 DAY), INTERVAL 555 MINUTE)),
(22, 1, 'APPROVE_EVENT', 'Event', 11, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 555 MINUTE)),
(23, 9, 'CREATE_EVENT', 'Event', 12, 'Created event "Yoga and Mindfulness Morning"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 555 MINUTE)),
(24, 1, 'APPROVE_EVENT', 'Event', 12, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -54 DAY), INTERVAL 555 MINUTE)),
(25, 10, 'CREATE_EVENT', 'Event', 13, 'Created event "Food Festival: Street Eats"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -32 DAY), INTERVAL 555 MINUTE)),
(26, 1, 'APPROVE_EVENT', 'Event', 13, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 555 MINUTE)),
(27, 11, 'CREATE_EVENT', 'Event', 14, 'Created event "Corporate Cricket Sixes"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 555 MINUTE)),
(28, 1, 'APPROVE_EVENT', 'Event', 14, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 555 MINUTE)),
(29, 2, 'CREATE_EVENT', 'Event', 15, 'Created event "UI/UX Design Sprint"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -48 DAY), INTERVAL 555 MINUTE)),
(30, 1, 'APPROVE_EVENT', 'Event', 15, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -47 DAY), INTERVAL 555 MINUTE)),
(31, 5, 'CREATE_EVENT', 'Event', 16, 'Created event "Cybersecurity Awareness Seminar"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 555 MINUTE)),
(32, 1, 'APPROVE_EVENT', 'Event', 16, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(33, 9, 'CREATE_EVENT', 'Event', 17, 'Created event "Annual Alumni Networking Dinner"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 555 MINUTE)),
(34, 1, 'APPROVE_EVENT', 'Event', 17, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(35, 10, 'CREATE_EVENT', 'Event', 18, 'Created event "Cloud Native Kubernetes Deep Dive"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -41 DAY), INTERVAL 555 MINUTE)),
(36, 1, 'APPROVE_EVENT', 'Event', 18, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -40 DAY), INTERVAL 555 MINUTE)),
(37, 11, 'CREATE_EVENT', 'Event', 19, 'Created event "Classical Music Gala"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 36 DAY), INTERVAL 555 MINUTE)),
(38, 1, 'APPROVE_EVENT', 'Event', 19, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 37 DAY), INTERVAL 555 MINUTE)),
(39, 2, 'CREATE_EVENT', 'Event', 20, 'Created event "Product Management Essentials"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -36 DAY), INTERVAL 555 MINUTE)),
(40, 1, 'APPROVE_EVENT', 'Event', 20, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -35 DAY), INTERVAL 555 MINUTE)),
(41, 5, 'CREATE_EVENT', 'Event', 21, 'Created event "Marathon Training Clinic"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 28 DAY), INTERVAL 555 MINUTE)),
(42, 9, 'CREATE_EVENT', 'Event', 22, 'Created event "Contemporary Art Exhibition Opening"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 20 DAY), INTERVAL 555 MINUTE)),
(43, 10, 'CREATE_EVENT', 'Event', 23, 'Created event "DevOps Automation Workshop"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -18 DAY), INTERVAL 555 MINUTE)),
(44, 11, 'CREATE_EVENT', 'Event', 24, 'Created event "Wellness and Nutrition Talk"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -65 DAY), INTERVAL 555 MINUTE)),
(45, 1, 'APPROVE_EVENT', 'Event', 24, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -64 DAY), INTERVAL 555 MINUTE)),
(46, 1, 'COMPLETE_EVENT', 'Event', 24, 'Marked completed', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -17 DAY), INTERVAL 555 MINUTE)),
(47, 2, 'CREATE_EVENT', 'Event', 25, 'Created event "Blockchain and Fintech Forum"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -94 DAY), INTERVAL 555 MINUTE)),
(48, 1, 'APPROVE_EVENT', 'Event', 25, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -93 DAY), INTERVAL 555 MINUTE)),
(49, 1, 'COMPLETE_EVENT', 'Event', 25, 'Marked completed', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -31 DAY), INTERVAL 555 MINUTE)),
(50, 5, 'CREATE_EVENT', 'Event', 26, 'Created event "Creative Writing Retreat"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -92 DAY), INTERVAL 555 MINUTE)),
(51, 1, 'APPROVE_EVENT', 'Event', 26, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -91 DAY), INTERVAL 555 MINUTE)),
(52, 1, 'COMPLETE_EVENT', 'Event', 26, 'Marked completed', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -44 DAY), INTERVAL 555 MINUTE)),
(53, 9, 'CREATE_EVENT', 'Event', 27, 'Created event "Cloud Cost Optimisation Clinic"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -56 DAY), INTERVAL 555 MINUTE)),
(54, 1, 'APPROVE_EVENT', 'Event', 27, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -55 DAY), INTERVAL 555 MINUTE)),
(55, 1, 'COMPLETE_EVENT', 'Event', 27, 'Marked completed', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(56, 10, 'CREATE_EVENT', 'Event', 28, 'Created event "Vegan Cooking Class"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -107 DAY), INTERVAL 555 MINUTE)),
(57, 1, 'APPROVE_EVENT', 'Event', 28, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -106 DAY), INTERVAL 555 MINUTE)),
(58, 1, 'COMPLETE_EVENT', 'Event', 28, 'Marked completed', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -59 DAY), INTERVAL 555 MINUTE)),
(59, 11, 'CREATE_EVENT', 'Event', 29, 'Created event "Women in Tech Panel"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -137 DAY), INTERVAL 555 MINUTE)),
(60, 1, 'APPROVE_EVENT', 'Event', 29, 'Approved and published', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -136 DAY), INTERVAL 555 MINUTE)),
(61, 1, 'COMPLETE_EVENT', 'Event', 29, 'Marked completed', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -74 DAY), INTERVAL 555 MINUTE)),
(62, 2, 'CREATE_EVENT', 'Event', 30, 'Created event "Advanced Spring Boot Training"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -12 DAY), INTERVAL 555 MINUTE)),
(63, 2, 'CANCEL_EVENT', 'Event', 30, 'Event cancelled; registrations cancelled and refunds queued', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 30 DAY), INTERVAL 555 MINUTE)),
(64, 5, 'CREATE_EVENT', 'Event', 31, 'Created event "Digital Photography Walk"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -72 DAY), INTERVAL 555 MINUTE)),
(65, 5, 'CANCEL_EVENT', 'Event', 31, 'Event cancelled; registrations cancelled and refunds queued', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -30 DAY), INTERVAL 555 MINUTE)),
(66, 9, 'CREATE_EVENT', 'Event', 32, 'Created event "Enterprise Architecture Symposium"', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -45 DAY), INTERVAL 555 MINUTE)),
(67, 9, 'CANCEL_EVENT', 'Event', 32, 'Event cancelled; registrations cancelled and refunds queued', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 555 MINUTE)),
(68, 13, 'REGISTER', 'Registration', 1, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(69, 16, 'REGISTER', 'Registration', 2, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 555 MINUTE)),
(70, 14, 'REGISTER', 'Registration', 3, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 555 MINUTE)),
(71, 20, 'REGISTER', 'Registration', 4, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(72, 19, 'REGISTER', 'Registration', 5, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(73, 21, 'REGISTER', 'Registration', 6, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 555 MINUTE)),
(74, 15, 'REGISTER', 'Registration', 7, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 555 MINUTE)),
(75, 3, 'REGISTER', 'Registration', 8, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(76, 18, 'REGISTER', 'Registration', 9, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 555 MINUTE)),
(77, 22, 'REGISTER', 'Registration', 10, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 555 MINUTE)),
(78, 8, 'REGISTER', 'Registration', 11, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(79, 23, 'REGISTER', 'Registration', 12, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 555 MINUTE)),
(80, 17, 'REGISTER', 'Registration', 13, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 555 MINUTE)),
(81, 6, 'REGISTER', 'Registration', 14, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 555 MINUTE)),
(82, 4, 'REGISTER', 'Registration', 15, 'Registered for event 1', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(83, 16, 'REGISTER', 'Registration', 16, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 555 MINUTE)),
(84, 23, 'REGISTER', 'Registration', 17, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 5 DAY), INTERVAL 555 MINUTE)),
(85, 4, 'REGISTER', 'Registration', 18, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 555 MINUTE)),
(86, 8, 'REGISTER', 'Registration', 19, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 555 MINUTE)),
(87, 3, 'REGISTER', 'Registration', 20, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 555 MINUTE)),
(88, 6, 'REGISTER', 'Registration', 21, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 555 MINUTE)),
(89, 21, 'REGISTER', 'Registration', 22, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 555 MINUTE)),
(90, 17, 'REGISTER', 'Registration', 23, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 19 DAY), INTERVAL 555 MINUTE)),
(91, 22, 'REGISTER', 'Registration', 24, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 555 MINUTE)),
(92, 14, 'REGISTER', 'Registration', 25, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 16 DAY), INTERVAL 555 MINUTE)),
(93, 18, 'REGISTER', 'Registration', 26, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 14 DAY), INTERVAL 555 MINUTE)),
(94, 19, 'REGISTER', 'Registration', 27, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 555 MINUTE)),
(95, 20, 'REGISTER', 'Registration', 28, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 21 DAY), INTERVAL 555 MINUTE)),
(96, 15, 'REGISTER', 'Registration', 29, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 555 MINUTE)),
(97, 13, 'REGISTER', 'Registration', 30, 'Registered for event 2', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 15 DAY), INTERVAL 555 MINUTE)),
(98, 17, 'REGISTER', 'Registration', 31, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 555 MINUTE)),
(99, 16, 'REGISTER', 'Registration', 32, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 555 MINUTE)),
(100, 23, 'REGISTER', 'Registration', 33, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 24 DAY), INTERVAL 555 MINUTE)),
(101, 13, 'REGISTER', 'Registration', 34, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 555 MINUTE)),
(102, 6, 'REGISTER', 'Registration', 35, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 555 MINUTE)),
(103, 18, 'REGISTER', 'Registration', 36, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 555 MINUTE)),
(104, 8, 'REGISTER', 'Registration', 37, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(105, 19, 'REGISTER', 'Registration', 38, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 555 MINUTE)),
(106, 15, 'REGISTER', 'Registration', 39, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(107, 22, 'REGISTER', 'Registration', 40, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 555 MINUTE)),
(108, 14, 'REGISTER', 'Registration', 41, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(109, 4, 'REGISTER', 'Registration', 42, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 555 MINUTE)),
(110, 3, 'REGISTER', 'Registration', 43, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(111, 21, 'REGISTER', 'Registration', 44, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 555 MINUTE)),
(112, 20, 'REGISTER', 'Registration', 45, 'Registered for event 3', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 555 MINUTE)),
(113, 3, 'REGISTER', 'Registration', 46, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 555 MINUTE)),
(114, 17, 'REGISTER', 'Registration', 47, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 555 MINUTE)),
(115, 8, 'REGISTER', 'Registration', 48, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 555 MINUTE)),
(116, 4, 'REGISTER', 'Registration', 49, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 555 MINUTE)),
(117, 13, 'REGISTER', 'Registration', 50, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 555 MINUTE)),
(118, 21, 'REGISTER', 'Registration', 51, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 555 MINUTE)),
(119, 19, 'REGISTER', 'Registration', 52, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 3 DAY), INTERVAL 555 MINUTE)),
(120, 14, 'REGISTER', 'Registration', 53, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 555 MINUTE)),
(121, 22, 'REGISTER', 'Registration', 54, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 0 DAY), INTERVAL 555 MINUTE)),
(122, 18, 'REGISTER', 'Registration', 55, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 555 MINUTE)),
(123, 23, 'REGISTER', 'Registration', 56, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 555 MINUTE)),
(124, 20, 'REGISTER', 'Registration', 57, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 555 MINUTE)),
(125, 16, 'REGISTER', 'Registration', 58, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -19 DAY), INTERVAL 555 MINUTE)),
(126, 6, 'REGISTER', 'Registration', 59, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(127, 15, 'REGISTER', 'Registration', 60, 'Registered for event 4', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -13 DAY), INTERVAL 555 MINUTE)),
(128, NULL, 'PAY', 'Registration', 1, 'Payment 1 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(129, NULL, 'PAY', 'Registration', 2, 'Payment 2 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 555 MINUTE)),
(130, NULL, 'PAY', 'Registration', 3, 'Payment 3 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -4 DAY), INTERVAL 555 MINUTE)),
(131, NULL, 'PAY', 'Registration', 4, 'Payment 4 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(132, NULL, 'PAY', 'Registration', 6, 'Payment 5 completed for 5000.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -14 DAY), INTERVAL 555 MINUTE)),
(133, NULL, 'PAY', 'Registration', 7, 'Payment 6 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -7 DAY), INTERVAL 555 MINUTE)),
(134, NULL, 'PAY', 'Registration', 8, 'Payment 7 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(135, NULL, 'PAY', 'Registration', 9, 'Payment 8 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 555 MINUTE)),
(136, NULL, 'PAY', 'Registration', 10, 'Payment 9 completed for 5000.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -11 DAY), INTERVAL 555 MINUTE)),
(137, NULL, 'PAY', 'Registration', 11, 'Payment 10 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(138, NULL, 'PAY', 'Registration', 12, 'Payment 11 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -3 DAY), INTERVAL 555 MINUTE)),
(139, NULL, 'PAY', 'Registration', 13, 'Payment 12 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -15 DAY), INTERVAL 555 MINUTE)),
(140, NULL, 'PAY', 'Registration', 14, 'Payment 13 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 2 DAY), INTERVAL 555 MINUTE)),
(141, NULL, 'PAY', 'Registration', 15, 'Payment 14 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -8 DAY), INTERVAL 555 MINUTE)),
(142, NULL, 'PAY', 'Registration', 31, 'Payment 15 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 29 DAY), INTERVAL 555 MINUTE)),
(143, NULL, 'PAY', 'Registration', 32, 'Payment 16 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 555 MINUTE)),
(144, NULL, 'PAY', 'Registration', 34, 'Payment 17 completed for 3825.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 34 DAY), INTERVAL 555 MINUTE)),
(145, NULL, 'PAY', 'Registration', 36, 'Payment 18 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 12 DAY), INTERVAL 555 MINUTE)),
(146, NULL, 'PAY', 'Registration', 38, 'Payment 20 completed for 18000.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 33 DAY), INTERVAL 555 MINUTE)),
(147, NULL, 'PAY', 'Registration', 39, 'Payment 21 completed for 15300.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(148, NULL, 'PAY', 'Registration', 40, 'Payment 22 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 25 DAY), INTERVAL 555 MINUTE)),
(149, NULL, 'PAY', 'Registration', 41, 'Payment 23 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(150, NULL, 'PAY', 'Registration', 42, 'Payment 24 completed for 3825.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 27 DAY), INTERVAL 555 MINUTE)),
(151, NULL, 'PAY', 'Registration', 43, 'Payment 25 completed for 3825.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 13 DAY), INTERVAL 555 MINUTE)),
(152, NULL, 'PAY', 'Registration', 44, 'Payment 26 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 23 DAY), INTERVAL 555 MINUTE)),
(153, NULL, 'PAY', 'Registration', 45, 'Payment 27 completed for 4500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 32 DAY), INTERVAL 555 MINUTE)),
(154, NULL, 'PAY', 'Registration', 61, 'Payment 28 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 555 MINUTE)),
(155, NULL, 'PAY', 'Registration', 62, 'Payment 29 completed for 2250.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 6 DAY), INTERVAL 555 MINUTE)),
(156, NULL, 'PAY', 'Registration', 63, 'Payment 30 completed for 2250.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -6 DAY), INTERVAL 555 MINUTE)),
(157, NULL, 'PAY', 'Registration', 64, 'Payment 31 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 555 MINUTE)),
(158, NULL, 'PAY', 'Registration', 65, 'Payment 32 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 4 DAY), INTERVAL 555 MINUTE)),
(159, NULL, 'PAY', 'Registration', 67, 'Payment 34 completed for 7500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 11 DAY), INTERVAL 555 MINUTE)),
(160, NULL, 'PAY', 'Registration', 68, 'Payment 35 completed for 7500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -2 DAY), INTERVAL 555 MINUTE)),
(161, NULL, 'PAY', 'Registration', 69, 'Payment 36 completed for 2250.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -5 DAY), INTERVAL 555 MINUTE)),
(162, NULL, 'PAY', 'Registration', 70, 'Payment 37 completed for 2250.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 555 MINUTE)),
(163, NULL, 'PAY', 'Registration', 71, 'Payment 38 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 7 DAY), INTERVAL 555 MINUTE)),
(164, NULL, 'PAY', 'Registration', 72, 'Payment 39 completed for 2500.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 9 DAY), INTERVAL 555 MINUTE)),
(165, NULL, 'PAY', 'Registration', 73, 'Payment 40 completed for 2250.00', DATE_ADD(DATE_ADD(CURDATE(), INTERVAL -9 DAY), INTERVAL 555 MINUTE));



-- =====================================================================
-- Consistency pass: "a confirmed booking on a paid event has been paid
-- for".
--
-- That is now an invariant of the system rather than a hope - a paid
-- event reserves the seat as PENDING_PAYMENT and only payment promotes
-- it to CONFIRMED. The rows above were written when it was not, so
-- eleven of them say CONFIRMED with no payment behind them: precisely
-- the state the reservation model exists to make impossible.
--
-- Written as two statements against the data rather than as eleven
-- hand-edited rows, so it stays correct if the rows above are changed.
-- =====================================================================

-- 1. Events that have already happened, whose attendees were checked in
--    at the door. They were there, so they paid: the payment attempt
--    recorded against them is completed rather than left hanging.
UPDATE payments p
    JOIN registrations r ON r.id = p.registration_id
    JOIN events e ON e.id = r.event_id
SET p.status = 'COMPLETED',
    p.paid_at = COALESCE(p.paid_at, r.registered_at)
WHERE r.status = 'CONFIRMED'
  AND e.fee > 0
  AND p.status <> 'COMPLETED'
  AND e.status IN ('COMPLETED', 'CANCELLED');

-- 2. Events still to come, with no completed payment behind them. The
--    seat is held, not booked - which is what PENDING_PAYMENT means, and
--    what gives the "pay within 30 minutes" prompt something real to
--    appear on. The seat count does not change, because a held seat is
--    as taken as a confirmed one.
UPDATE registrations r
    JOIN events e ON e.id = r.event_id
    LEFT JOIN payments pc ON pc.registration_id = r.id AND pc.status = 'COMPLETED'
SET r.status = 'PENDING_PAYMENT',
    r.payment_due_at = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
WHERE r.status = 'CONFIRMED'
  AND e.fee > 0
  AND pc.id IS NULL
  AND e.status NOT IN ('COMPLETED', 'CANCELLED');

-- 3. A payment left PENDING against a held seat would block the retry,
--    because only a FAILED attempt may be reused. Marking it failed is
--    also simply true: it never completed.
UPDATE payments p
    JOIN registrations r ON r.id = p.registration_id
SET p.status = 'FAILED'
WHERE r.status = 'PENDING_PAYMENT'
  AND p.status = 'PENDING';

-- 4. Free events are ticketed at registration, so a confirmed free
--    booking with no ticket is a state the application can no longer
--    produce. These rows predate that rule; without this they would show
--    a free attendee a confirmed booking and no ticket to get in with.
--
--    A zero-value COMPLETED payment is what carries a ticket code, and
--    being worth 0.00 keeps it out of revenue and out of refunds.
INSERT INTO payments (registration_id, discount_id, original_amount, discount_amount,
                      final_amount, status, ticket_code, paid_at, created_at)
SELECT r.id, NULL, 0.00, 0.00, 0.00, 'COMPLETED',
       CONCAT('TICKET-', e.id, '-', r.id, '-FREESEED'),
       r.registered_at, r.registered_at
FROM registrations r
    JOIN events e ON e.id = r.event_id
    LEFT JOIN payments p ON p.registration_id = r.id
WHERE r.status = 'CONFIRMED'
  AND e.fee = 0
  AND p.id IS NULL;

-- =====================================================================
-- Announcements (Function 4) and saved reports (Function 6)
--
-- Seeded so the approval queue is not empty on a fresh install - a queue
-- with nothing in it demonstrates nothing, and the four announcement
-- states are the point of the feature.
-- =====================================================================

-- One in each state, so every branch of the screen has something to show.
INSERT INTO announcements (event_id, author_id, title, message, status,
                            submitted_at, decided_at, decided_by, reject_reason,
                            published_at, recipient_count) VALUES

-- PUBLISHED: an organizer wrote it, the administrator approved it, and it
-- reached everyone holding a place.
(5, 2, 'Venue entrance has moved',
 'Please use the Galle Road entrance on the day - the main gate is closed for works. Allow ten extra minutes.',
 'PUBLISHED', NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY, 1, NULL,
 NOW() - INTERVAL 5 DAY,
 (SELECT COUNT(*) FROM registrations WHERE event_id = 5 AND status IN ('CONFIRMED','PENDING_PAYMENT'))),

-- PENDING_APPROVAL: waiting in the administrator's queue right now.
(10, 2, 'Bring your own laptop',
 'This is a hands-on workshop. Please bring a laptop with Android Studio already installed - there is no time to install it on the day.',
 'PENDING_APPROVAL', NOW() - INTERVAL 6 HOUR, NULL, NULL, NULL, NULL, NULL),

-- REJECTED: turned down with a reason the author can act on.
(5, 2, 'Change of plan',
 'The event might move to a different week, we will confirm later.',
 'REJECTED', NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 2 DAY, 1,
 'Too vague to send to 40 people - confirm the new date first, then announce it once.',
 NULL, NULL),

-- DRAFT: written, not yet submitted. Only its author and an
-- administrator can see it.
(1, 5, 'Parking is limited',
 'There are only fifteen visitor spaces. Public transport is the easier option.',
 'DRAFT', NULL, NULL, NULL, NULL, NULL, NULL);

-- The notifications the PUBLISHED one produced. Seeded explicitly rather
-- than left out, because an announcement that says it reached 40 inboxes
-- and left nothing in any of them would be a lie told by the seed data.
INSERT INTO notifications (user_id, type, channel, message, is_read, sent_at)
SELECT r.user_id, 'ANNOUNCEMENT', 'IN_APP',
       CONCAT('Photography Masterclass - Venue entrance has moved: ',
              'Please use the Galle Road entrance on the day - the main gate is closed for works. ',
              'Allow ten extra minutes.'),
       FALSE, NOW() - INTERVAL 5 DAY
FROM registrations r
WHERE r.event_id = 5 AND r.status IN ('CONFIRMED', 'PENDING_PAYMENT');

-- Saved report definitions - one per role, so each dashboard has
-- something in it. Note again that no figure is stored: every row here is
-- a question, and the numbers are computed when it is opened.
INSERT INTO saved_reports (owner_id, title, report_type, event_id, date_from, date_to) VALUES
-- Attendee
(3, 'My attendance', 'MY_ATTENDANCE', NULL, NULL, NULL),
(3, 'What I have spent', 'MY_PAYMENT_HISTORY', NULL, NULL, NULL),
-- Organizer, about their own events
(2, 'Masterclass performance', 'EVENT_PERFORMANCE', 5, NULL, NULL),
(2, 'Workshop revenue', 'EVENT_REVENUE', 10, NULL, NULL),
(2, 'Who actually turned up', 'EVENT_ATTENDANCE', 5, NULL, NULL),
-- Administrator, platform-wide
(1, 'Platform overview', 'PLATFORM_OVERVIEW', NULL, NULL, NULL),
(1, 'Revenue and refunds', 'REVENUE_AND_REFUNDS', NULL, NULL, NULL),
(1, 'Which categories sell', 'CATEGORY_POPULARITY', NULL, NULL, NULL);

SELECT 'announcements seeded' AS check_, COUNT(*) AS rows_ FROM announcements
UNION ALL
SELECT 'saved reports seeded', COUNT(*) FROM saved_reports
UNION ALL
-- Must match the recipient_count recorded on the published announcement,
-- or the record and the inboxes disagree.
SELECT 'announcement notifications delivered', COUNT(*)
FROM notifications WHERE type = 'ANNOUNCEMENT';



-- =====================================================================
-- PART C - PASSWORD RESET TOKEN SAMPLE DATA
-- =====================================================================

-- =====================================================================
-- EventSphere - supplementary sample data: password_reset_tokens
--
-- The application creates these rows at runtime (a user clicks "Forgot
-- password"), so the main seed file had none. IT2140 Part C requires at
-- least five rows in every table, and a reset-token table with no rows
-- also gives the queries nothing to demonstrate against.
--
-- token_hash holds the SHA-256 hex of the token that went in the e-mail
-- link, never the token itself - see the schema comment on the column.
-- The values below are real SHA-256 digests of throwaway strings; they
-- unlock nothing, because the matching plaintext was never mailed.
-- =====================================================================

INSERT INTO password_reset_tokens
    (user_id, token_hash, expires_at, used, created_at)
VALUES
    -- live, unused, still inside the 60-minute window
    (3,  SHA2('demo-reset-token-alpha',   256),
         DATE_ADD(NOW(), INTERVAL 45 MINUTE),  FALSE, NOW()),
    (4,  SHA2('demo-reset-token-bravo',   256),
         DATE_ADD(NOW(), INTERVAL 58 MINUTE),  FALSE, NOW()),

    -- already consumed: the user completed the reset
    (6,  SHA2('demo-reset-token-charlie', 256),
         DATE_ADD(NOW(), INTERVAL -2 HOUR),    TRUE,
         DATE_ADD(NOW(), INTERVAL -3 HOUR)),
    (8,  SHA2('demo-reset-token-delta',   256),
         DATE_ADD(NOW(), INTERVAL -20 HOUR),   TRUE,
         DATE_ADD(NOW(), INTERVAL -21 HOUR)),

    -- expired without ever being used - the common case
    (13, SHA2('demo-reset-token-echo',    256),
         DATE_ADD(NOW(), INTERVAL -1 DAY),     FALSE,
         DATE_ADD(NOW(), INTERVAL -25 HOUR)),
    (14, SHA2('demo-reset-token-foxtrot', 256),
         DATE_ADD(NOW(), INTERVAL -4 DAY),     FALSE,
         DATE_ADD(NOW(), INTERVAL -97 HOUR)),

    -- a second request by a user who already has one outstanding
    (3,  SHA2('demo-reset-token-golf',    256),
         DATE_ADD(NOW(), INTERVAL -6 HOUR),    FALSE,
         DATE_ADD(NOW(), INTERVAL -7 HOUR));


-- =====================================================================
-- PART E + F - STORED ROUTINES AND TRIGGERS
-- =====================================================================

-- =====================================================================
-- EventSphere - Stored routines and triggers
-- IT2140 Assignment Part 02, Parts E and F
--
-- RUN ORDER:  01_schema.sql -> 02_seed_data.sql -> 03_reset_tokens.sql
--             -> 04_routines.sql  (this file)
--
-- Written for MySQL 8.0.16+ ; verified on MariaDB 10.11 as well.
-- Every statement below is standard SQL/PSM: DECLARE, cursors-free
-- SELECT ... INTO, SIGNAL SQLSTATE '45000', and row locking with
-- SELECT ... FOR UPDATE.
-- =====================================================================

DROP PROCEDURE IF EXISTS sp_register_attendee;
DROP PROCEDURE IF EXISTS sp_cancel_registration;
DROP FUNCTION  IF EXISTS fn_event_net_revenue;
DROP TRIGGER   IF EXISTS trg_registrations_before_insert;
DROP TRIGGER   IF EXISTS trg_registrations_after_update;

DELIMITER $$

-- =====================================================================
-- PART E (1 of 3) - STORED PROCEDURE: book a seat on an event
--
-- This is the single most important write path in EventSphere, and the
-- reason it is a stored procedure rather than three separate statements
-- is CONCURRENCY. Two attendees clicking "Register" on the last seat at
-- the same moment must not both be confirmed.
--
--   SELECT ... FOR UPDATE takes an exclusive row lock on the event for
--   the rest of the transaction. The second session blocks on that lock
--   until the first commits, and therefore reads seats_taken AFTER the
--   first increment - never the stale value it would have read without
--   the lock. This is exactly what RegistrationServiceImpl does in Java
--   with @Lock(PESSIMISTIC_WRITE).
--
-- Business rules enforced, in order:
--   1. the event must exist
--   2. the event must be PUBLISHED (not pending, cancelled or completed)
--   3. now must be inside the registration window
--   4. the attendee must not already hold an active registration
--   5. a RESTRICTED event requires an APPROVED invitation for this attendee
--   6. if the event is full        -> WAITLISTED, seats_taken untouched
--      if a seat is free and free  -> CONFIRMED,       seats_taken +1
--      if a seat is free but has a fee -> PENDING_PAYMENT, seats_taken +1,
--         payment_due_at = NOW() + 30 minutes
--
-- Rule 5 mirrors EventInvitationServiceImpl.isEligibleToRegister() exactly,
-- so a RESTRICTED event is gated the same way whether the caller is the
-- Java application or a direct SQL client.
--
-- Rule 6 mirrors RegistrationServiceImpl.register() exactly: PENDING_PAYMENT
-- is a seat HOLDER, same as CONFIRMED - the seat count goes up the moment
-- the hold is placed, not when the (separate, application-level) payment
-- completes. A registration this procedure leaves PENDING_PAYMENT is
-- confirmed by the same payment step the web application uses; this
-- routine's job stops at correctly starting that state, not completing it.
-- =====================================================================
CREATE PROCEDURE sp_register_attendee(
    IN  p_event_id        BIGINT,
    IN  p_user_id         BIGINT,
    OUT p_registration_id BIGINT,
    OUT p_status          VARCHAR(20),
    OUT p_message         VARCHAR(255)
)
MODIFIES SQL DATA
BEGIN
    DECLARE v_capacity      INT;
    DECLARE v_seats_taken   INT;
    DECLARE v_event_status  VARCHAR(20);
    DECLARE v_open_date     DATETIME;
    DECLARE v_deadline      DATETIME;
    DECLARE v_event_name    VARCHAR(150);
    DECLARE v_fee           DECIMAL(10,2);
    DECLARE v_event_type    VARCHAR(20);
    DECLARE v_existing      INT DEFAULT 0;
    DECLARE v_invited       INT DEFAULT 0;
    DECLARE v_user_active   BOOLEAN;

    -- Any SQL exception rolls the whole thing back and reports it,
    -- rather than leaving a half-written booking behind.
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SET p_registration_id = NULL;
        SET p_status  = 'ERROR';
        SET p_message = 'Registration failed and was rolled back.';
    END;

    SET p_registration_id = NULL;

    START TRANSACTION;

    -- ---- 1 + 2 + 3 : lock the event row, then read it --------------
    -- FOR UPDATE is what serialises concurrent bookings.
    SELECT capacity, seats_taken, status, name, fee, event_type,
           registration_open_date, registration_deadline
      INTO v_capacity, v_seats_taken, v_event_status, v_event_name, v_fee, v_event_type,
           v_open_date, v_deadline
      FROM events
     WHERE id = p_event_id
     FOR UPDATE;

    IF v_event_status IS NULL THEN
        SET p_status  = 'REJECTED';
        SET p_message = CONCAT('No event with id ', p_event_id, '.');
        ROLLBACK;

    ELSEIF v_event_status <> 'PUBLISHED' THEN
        SET p_status  = 'REJECTED';
        SET p_message = CONCAT('Event "', v_event_name,
                               '" is ', v_event_status,
                               ' and is not open for registration.');
        ROLLBACK;

    ELSEIF v_open_date IS NOT NULL AND NOW() < v_open_date THEN
        SET p_status  = 'REJECTED';
        SET p_message = CONCAT('Registration for "', v_event_name,
                               '" opens on ', v_open_date, '.');
        ROLLBACK;

    ELSEIF v_deadline IS NOT NULL AND NOW() > v_deadline THEN
        SET p_status  = 'REJECTED';
        SET p_message = CONCAT('Registration for "', v_event_name,
                               '" closed on ', v_deadline, '.');
        ROLLBACK;

    ELSE
        -- ---- the attendee must exist and be active -----------------
        SELECT is_active INTO v_user_active
          FROM users WHERE id = p_user_id;

        IF v_user_active IS NULL THEN
            SET p_status  = 'REJECTED';
            SET p_message = CONCAT('No user with id ', p_user_id, '.');
            ROLLBACK;

        ELSEIF v_user_active = FALSE THEN
            SET p_status  = 'REJECTED';
            SET p_message = 'This account is suspended.';
            ROLLBACK;

        ELSE
            -- ---- 4 : one ACTIVE registration per attendee ----------
            -- A CANCELLED row does not count: the attendee is allowed
            -- to sign up again after cancelling.
            SELECT COUNT(*) INTO v_existing
              FROM registrations
             WHERE event_id = p_event_id
               AND user_id  = p_user_id
               AND status  <> 'CANCELLED';

            IF v_existing > 0 THEN
                SET p_status  = 'REJECTED';
                SET p_message = 'You are already registered for this event.';
                ROLLBACK;

            ELSE
                -- ---- 5 : a RESTRICTED event requires an APPROVED
                -- invitation for this attendee - a PUBLIC event needs
                -- nothing here, so v_invited is simply set to "yes"
                -- without a lookup.
                IF v_event_type = 'RESTRICTED' THEN
                    SELECT COUNT(*) INTO v_invited
                      FROM event_invitations
                     WHERE event_id = p_event_id
                       AND user_id  = p_user_id
                       AND status   = 'APPROVED';
                ELSE
                    SET v_invited = 1;
                END IF;

                IF v_invited = 0 THEN
                    SET p_status  = 'REJECTED';
                    SET p_message = CONCAT('"', v_event_name,
                                           '" is a restricted event - an ',
                                           'approved invitation is required.');
                    ROLLBACK;

                ELSE
                    -- ---- 6 : seat available, or waitlist ---------------
                    IF v_seats_taken < v_capacity THEN
                        -- A seat is HELD the moment it is granted, whether
                        -- the event is free or paid - PENDING_PAYMENT is a
                        -- seat holder exactly like CONFIRMED is, which is
                        -- why the increment below runs for both branches
                        -- and is not duplicated inside the IF/ELSE.
                        IF v_fee > 0 THEN
                            INSERT INTO registrations
                                   (event_id, user_id, status, payment_due_at)
                            VALUES (p_event_id, p_user_id, 'PENDING_PAYMENT',
                                   NOW() + INTERVAL 30 MINUTE);

                            SET p_status  = 'PENDING_PAYMENT';
                            SET p_message = CONCAT('Seat held for "', v_event_name,
                                                   '" - complete payment within 30 ',
                                                   'minutes to confirm it. ',
                                                   (v_capacity - v_seats_taken - 1),
                                                   ' seat(s) remaining.');
                        ELSE
                            INSERT INTO registrations (event_id, user_id, status)
                                 VALUES (p_event_id, p_user_id, 'CONFIRMED');

                            SET p_status  = 'CONFIRMED';
                            SET p_message = CONCAT('Seat confirmed for "', v_event_name,
                                                   '". ',
                                                   (v_capacity - v_seats_taken - 1),
                                                   ' seat(s) remaining.');
                        END IF;

                        UPDATE events
                           SET seats_taken = seats_taken + 1
                         WHERE id = p_event_id;
                    ELSE
                        INSERT INTO registrations (event_id, user_id, status)
                             VALUES (p_event_id, p_user_id, 'WAITLISTED');

                        SET p_status  = 'WAITLISTED';
                        SET p_message = CONCAT('"', v_event_name,
                                               '" is full (', v_capacity,
                                               '/', v_capacity,
                                               '). You have been waitlisted.');
                    END IF;

                    SET p_registration_id = LAST_INSERT_ID();
                    COMMIT;
                END IF;
            END IF;
        END IF;
    END IF;
END$$


-- =====================================================================
-- PART E (2 of 3) - STORED PROCEDURE: cancel a registration
--
-- The mirror image of the booking procedure, and it exists for the same
-- reason: seats_taken must only ever be changed by code that holds the
-- event row lock. CONFIRMED and PENDING_PAYMENT both hold a seat (see the
-- booking procedure above), so cancelling either one gives it back;
-- cancelling a WAITLISTED row must NOT decrement, or the count drifts
-- below the truth.
-- =====================================================================
CREATE PROCEDURE sp_cancel_registration(
    IN  p_registration_id BIGINT,
    IN  p_user_id         BIGINT,
    OUT p_status          VARCHAR(20),
    OUT p_message         VARCHAR(255)
)
MODIFIES SQL DATA
BEGIN
    DECLARE v_owner       BIGINT;
    DECLARE v_status      VARCHAR(20);
    DECLARE v_event_id    BIGINT;
    DECLARE v_seat_holder BOOLEAN;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        SET p_status  = 'ERROR';
        SET p_message = 'Cancellation failed and was rolled back.';
    END;

    START TRANSACTION;

    SELECT user_id, status, event_id
      INTO v_owner, v_status, v_event_id
      FROM registrations
     WHERE id = p_registration_id
     FOR UPDATE;

    IF v_owner IS NULL THEN
        SET p_status  = 'REJECTED';
        SET p_message = CONCAT('No registration with id ', p_registration_id, '.');
        ROLLBACK;

    -- Ownership is checked in the database, not taken on trust from the
    -- caller. Passing somebody else's registration id must not cancel it.
    ELSEIF v_owner <> p_user_id THEN
        SET p_status  = 'FORBIDDEN';
        SET p_message = 'This registration belongs to another attendee.';
        ROLLBACK;

    ELSEIF v_status = 'CANCELLED' THEN
        SET p_status  = 'REJECTED';
        SET p_message = 'This registration is already cancelled.';
        ROLLBACK;

    ELSE
        UPDATE registrations
           SET status         = 'CANCELLED',
               cancelled_at   = NOW(),
               payment_due_at = NULL
         WHERE id = p_registration_id;

        -- Only a seat holder gives one back - CONFIRMED or
        -- PENDING_PAYMENT, never WAITLISTED.
        SET v_seat_holder = (v_status = 'CONFIRMED' OR v_status = 'PENDING_PAYMENT');
        IF v_seat_holder THEN
            UPDATE events
               SET seats_taken = seats_taken - 1
             WHERE id = v_event_id
               AND seats_taken > 0;
        END IF;

        SET p_status  = 'CANCELLED';
        SET p_message = CONCAT('Registration ', p_registration_id,
                               ' cancelled',
                               IF(v_seat_holder,
                                  ' and the seat released.', '.'));
        COMMIT;
    END IF;
END$$


-- =====================================================================
-- PART E (3 of 3) - STORED FUNCTION: net revenue for one event
--
-- A FUNCTION rather than a procedure because it returns a single value
-- and takes no action, which means it can be called from inside an
-- ordinary SELECT list, WHERE clause or ORDER BY - see Part D, Query 8.
-- A procedure cannot be used that way.
--
--   net revenue = completed payments - completed refunds
--
-- COALESCE is essential: SUM() over zero rows returns NULL, not 0, so an
-- event that has sold nothing would otherwise return NULL and poison any
-- arithmetic it takes part in.
--
-- READS SQL DATA (not DETERMINISTIC): the same argument can return a
-- different answer tomorrow, because the underlying rows change.
-- =====================================================================
CREATE FUNCTION fn_event_net_revenue(p_event_id BIGINT)
RETURNS DECIMAL(12,2)
READS SQL DATA
BEGIN
    DECLARE v_paid     DECIMAL(12,2);
    DECLARE v_refunded DECIMAL(12,2);

    SELECT COALESCE(SUM(p.final_amount), 0)
      INTO v_paid
      FROM payments p
      JOIN registrations r ON r.id = p.registration_id
     WHERE r.event_id = p_event_id
       AND p.status   = 'COMPLETED';

    SELECT COALESCE(SUM(rf.amount), 0)
      INTO v_refunded
      FROM refunds rf
      JOIN payments p      ON p.id = rf.payment_id
      JOIN registrations r ON r.id = p.registration_id
     WHERE r.event_id = p_event_id
       AND rf.status  = 'COMPLETED';

    RETURN v_paid - v_refunded;
END$$


-- =====================================================================
-- PART F (1 of 2) - TRIGGER: validate before a registration is inserted
--
-- WHY A TRIGGER AND NOT A CHECK CONSTRAINT
-- A CHECK constraint can only see columns of the row being written. The
-- rule here - "you may not register for an event that is not published,
-- or whose deadline has passed" - lives in the EVENTS table, a different
-- row in a different table. No CHECK constraint can express it. A
-- trigger can, because it may run queries.
--
-- WHY IT MATTERS
-- EventSphere already enforces this rule in Java. That protects the
-- application, not the data: anyone with a SQL prompt could still insert
-- a registration for a cancelled event. With this trigger the rule is in
-- the database, so it holds no matter which client is writing.
-- =====================================================================
CREATE TRIGGER trg_registrations_before_insert
BEFORE INSERT ON registrations
FOR EACH ROW
BEGIN
    DECLARE v_status   VARCHAR(20);
    DECLARE v_deadline DATETIME;
    DECLARE v_name     VARCHAR(150);

    SELECT status, registration_deadline, name
      INTO v_status, v_deadline, v_name
      FROM events
     WHERE id = NEW.event_id;

    IF v_status IS NULL THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cannot register: the event does not exist.';
    END IF;

    IF v_status <> 'PUBLISHED' THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cannot register: the event is not PUBLISHED.';
    END IF;

    IF v_deadline IS NOT NULL AND NOW() > v_deadline THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cannot register: the registration deadline has passed.';
    END IF;
END$$


-- =====================================================================
-- PART F (2 of 2) - TRIGGER: audit every registration status change
--
-- Writes one audit_logs row whenever, and only whenever, the status
-- column actually changes. The NULL-safe comparison operator <=> is used
-- rather than <> so that a change to or from NULL is still detected;
-- with <> a NULL on either side makes the whole test NULL, which is not
-- TRUE, and the audit row would silently never be written.
--
-- NOTE - deliberately NOT done here: freeing the seat.
-- Cancelling a registration should decrement events.seats_taken, and it
-- would be easy to do that in this trigger. It would also be WRONG,
-- because sp_cancel_registration (and the Java service) already do it
-- inside their own transaction - the seat would be given back twice and
-- the count would drift below the true figure. One rule, one owner. A
-- trigger is the right place to RECORD that something happened; it is
-- the wrong place to duplicate a rule that already has a home.
-- =====================================================================
CREATE TRIGGER trg_registrations_after_update
AFTER UPDATE ON registrations
FOR EACH ROW
BEGIN
    IF NOT (OLD.status <=> NEW.status) THEN
        INSERT INTO audit_logs (user_id, action, entity_type, entity_id, details)
        VALUES (
            NEW.user_id,
            CONCAT('REGISTRATION_', NEW.status),
            'Registration',
            NEW.id,
            CONCAT('Status changed ', OLD.status, ' -> ', NEW.status,
                   ' for event ', NEW.event_id,
                   ' (attendee ', NEW.user_id, ')')
        );
    END IF;
END$$

DELIMITER ;


-- =====================================================================
-- PART D - SQL QUERIES
-- =====================================================================

-- =====================================================================
-- EventSphere - Part D : SQL Queries
-- IT2140 Assignment Part 02
--
-- The brief asks for at least five queries covering: a simple SELECT, a
-- JOIN, an aggregation, a GROUP BY / HAVING, and a subquery. Eight are
-- given. Q6, Q7 and Q8 go beyond the minimum and cover the "Advanced SQL
-- Features" half of the assignment title: NOT EXISTS, a common table
-- expression with a window function, and a stored function called from
-- inside a SELECT.
--
-- Run with:  mysql --table eventsphere < 06_queries.sql
-- =====================================================================


-- ---------------------------------------------------------------------
-- Q1 - SIMPLE SELECT
--
-- Every published event that is still open for registration, cheapest
-- first. One table, a compound WHERE, a computed column and an ORDER BY.
--
-- seats_left is computed in the SELECT list rather than stored, because
-- a stored copy is a third number that can disagree with the two it is
-- derived from. This is the DERIVED ATTRIBUTE from the EER, realised.
-- The index idx_events_status backs the status test.
-- ---------------------------------------------------------------------
SELECT  id,
        name,
        category,
        venue,
        DATE(start_datetime)            AS starts_on,
        capacity,
        seats_taken,
        capacity - seats_taken          AS seats_left,
        fee
  FROM  events
 WHERE  status = 'PUBLISHED'
   AND  registration_deadline > NOW()
   AND  seats_taken < capacity
 ORDER BY fee ASC, start_datetime ASC
 LIMIT  10;


-- ---------------------------------------------------------------------
-- Q2 - JOIN across four tables
--
-- The attendee's own booking history: who registered, for what, and
-- whether it was paid for.
--
-- The three inner joins are inner because a registration cannot exist
-- without a user and an event - the foreign keys guarantee it. The join
-- to payments is a LEFT JOIN because it CAN be absent: a free event, or
-- a booking that was never paid for. Using an inner join there would
-- silently delete unpaid registrations from the report, which is the
-- classic way a join produces a confidently wrong answer.
--
-- COALESCE turns the resulting NULL into the word NOT PAID, so the
-- report says what it means instead of showing a blank.
-- ---------------------------------------------------------------------
SELECT  r.id                                   AS registration_id,
        u.full_name                            AS attendee,
        e.name                                 AS event_name,
        r.status                               AS registration_status,
        COALESCE(p.status, 'NOT PAID')         AS payment_status,
        COALESCE(p.final_amount, 0.00)         AS amount_paid,
        p.ticket_code
  FROM  registrations r
  JOIN  users  u ON u.id = r.user_id
  JOIN  events e ON e.id = r.event_id
  LEFT JOIN payments p ON p.registration_id = r.id
 WHERE  e.status = 'PUBLISHED'
 ORDER BY r.id
 LIMIT  12;


-- ---------------------------------------------------------------------
-- Q3 - AGGREGATION
--
-- One row of platform totals. Every figure is computed by the database;
-- none is stored anywhere.
--
-- Two things worth explaining:
--   * COUNT(DISTINCT ...) rather than COUNT(...). A user with four
--     bookings is one attendee, not four.
--   * COUNT(CASE WHEN ... THEN 1 END) is a conditional count. CASE yields
--     NULL when the condition fails, and COUNT ignores NULLs, so this
--     counts only the matching rows - four different counts from a single
--     pass over the table instead of four separate queries.
-- ---------------------------------------------------------------------
SELECT  COUNT(*)                                                AS total_registrations,
        COUNT(DISTINCT r.user_id)                               AS distinct_attendees,
        COUNT(DISTINCT r.event_id)                              AS events_with_bookings,
        COUNT(CASE WHEN r.status = 'CONFIRMED'  THEN 1 END)     AS confirmed,
        COUNT(CASE WHEN r.status = 'WAITLISTED' THEN 1 END)     AS waitlisted,
        COUNT(CASE WHEN r.status = 'CANCELLED'  THEN 1 END)     AS cancelled,
        ROUND(100.0 * COUNT(CASE WHEN r.status = 'CANCELLED' THEN 1 END)
                    / COUNT(*), 2)                              AS cancellation_rate_pct
  FROM  registrations r;


-- ---------------------------------------------------------------------
-- Q4 - GROUP BY with HAVING
--
-- Revenue by event category, keeping only the categories that are worth
-- reporting on.
--
-- WHERE vs HAVING is the point of this query:
--   * WHERE filters ROWS, before grouping. Here it keeps only completed
--     payments, so failed and pending ones never reach a total.
--   * HAVING filters GROUPS, after grouping. Here it keeps only the
--     categories that have at least two events and more than 50,000 in
--     revenue.
--   * The HAVING condition cannot be rewritten as a WHERE, because COUNT
--     and SUM do not exist until the rows have been grouped.
-- ---------------------------------------------------------------------
SELECT  e.category,
        COUNT(DISTINCT e.id)              AS events,
        COUNT(p.id)                       AS payments_taken,
        SUM(p.final_amount)               AS gross_revenue,
        ROUND(AVG(p.final_amount), 2)     AS avg_ticket_value,
        MAX(p.final_amount)               AS dearest_ticket
  FROM  events e
  JOIN  registrations r ON r.event_id = e.id
  JOIN  payments      p ON p.registration_id = r.id
 WHERE  p.status = 'COMPLETED'          -- filters ROWS, before grouping
 GROUP BY e.category
HAVING  COUNT(DISTINCT e.id) >= 2       -- filters GROUPS, after grouping
   AND  SUM(p.final_amount)  > 50000
 ORDER BY gross_revenue DESC;


-- ---------------------------------------------------------------------
-- Q5 - SUBQUERY in the WHERE clause (non-correlated, scalar)
--
-- Events that have sold more seats than the platform average.
--
-- The inner SELECT runs ONCE, produces a single number, and that number
-- is then compared against every outer row. It is non-correlated: it
-- makes no reference to the outer query, so the optimiser can evaluate
-- it a single time.
--
-- The inner query deliberately excludes capacity = 0, because dividing
-- an average by rows that can never sell a seat drags the benchmark
-- down and would let almost every event qualify.
-- ---------------------------------------------------------------------
SELECT  e.id,
        e.name,
        e.category,
        e.capacity,
        e.seats_taken,
        ROUND(100.0 * e.seats_taken / e.capacity, 1) AS pct_full
  FROM  events e
 WHERE  e.status = 'PUBLISHED'
   AND  e.capacity > 0
   AND  e.seats_taken > (SELECT AVG(seats_taken)
                           FROM events
                          WHERE status = 'PUBLISHED'
                            AND capacity > 0)
 ORDER BY pct_full DESC
 LIMIT  10;


-- ---------------------------------------------------------------------
-- Q6 - CORRELATED SUBQUERY with NOT EXISTS
--
-- Published events that nobody has ever cancelled out of, with the
-- organiser who ran them. These are the events worth studying, because
-- whatever they did, it held.
--
-- Two subqueries, and they are of different kinds:
--   * The NOT EXISTS in the WHERE clause is correlated - it mentions e.id
--     from the outer query, so it is evaluated once per candidate event,
--     and it can stop at the very first cancellation it finds.
--   * The subquery in the SELECT list is also correlated, and returns a
--     single value per row, which makes it a scalar subquery.
--
-- NOT EXISTS rather than NOT IN is deliberate. If the inner query can
-- ever produce a NULL, `x NOT IN (... NULL ...)` evaluates to UNKNOWN
-- for every row and the whole query silently returns nothing at all -
-- a wrong answer that looks like an empty table rather than an error.
-- NOT EXISTS has no such trap.
-- ---------------------------------------------------------------------
SELECT  e.id,
        e.name                                AS event_name,
        e.category,
        o.full_name                           AS organizer,
        e.seats_taken,
        (SELECT COUNT(*) FROM registrations r2
          WHERE r2.event_id = e.id
            AND r2.status    = 'CONFIRMED')    AS confirmed_bookings
  FROM  events e
  JOIN  users  o ON o.id = e.organizer_id
 WHERE  e.status = 'PUBLISHED'
   AND  NOT EXISTS (SELECT 1
                      FROM registrations r
                     WHERE r.event_id = e.id
                       AND r.status   = 'CANCELLED')
 ORDER BY confirmed_bookings DESC, e.id
 LIMIT  10;


-- ---------------------------------------------------------------------
-- Q7 - COMMON TABLE EXPRESSION + WINDOW FUNCTIONS
--
-- Ranking each event within its own category by revenue.
--
-- The WITH clause names an intermediate result so the query reads
-- top-down instead of inside-out. The window functions then run over
-- that result WITHOUT collapsing it:
--   * RANK() OVER (PARTITION BY category ORDER BY revenue DESC) gives the
--     event's position inside its own category.
--   * SUM(...) OVER (PARTITION BY category) gives that whole category's
--     revenue, repeated on every row belonging to it.
--
-- This is what separates a window function from GROUP BY: GROUP BY would
-- give one row per category and lose the individual events. A window
-- function keeps every row AND adds the group-level figure to it, so the
-- share-of-category percentage can be computed in the same pass.
--
-- Requires MySQL 8.0 or MariaDB 10.2+.
-- ---------------------------------------------------------------------
WITH event_revenue AS (
    SELECT  e.id,
            e.name,
            e.category,
            COALESCE(SUM(p.final_amount), 0) AS revenue
      FROM  events e
      LEFT JOIN registrations r ON r.event_id = e.id
      LEFT JOIN payments      p ON p.registration_id = r.id
                               AND p.status = 'COMPLETED'
     WHERE  e.status = 'PUBLISHED'
     GROUP BY e.id, e.name, e.category
)
SELECT  category,
        RANK() OVER (PARTITION BY category ORDER BY revenue DESC) AS rank_in_category,
        name,
        revenue,
        SUM(revenue) OVER (PARTITION BY category)                 AS category_total,
        -- NULLIF stops a division by zero for a category that has sold
        -- nothing at all; COALESCE then reports that case as 0.0 rather
        -- than leaving a NULL in a percentage column.
        COALESCE(ROUND(100.0 * revenue
              / NULLIF(SUM(revenue) OVER (PARTITION BY category), 0), 1), 0.0)
                                                                  AS pct_of_category
  FROM  event_revenue
 ORDER BY category, rank_in_category;


-- ---------------------------------------------------------------------
-- Q8 - THE STORED FUNCTION USED INSIDE A QUERY
--
-- Part E's fn_event_net_revenue called from the SELECT list, the WHERE
-- clause and the ORDER BY of an ordinary query. This is the practical
-- difference between a FUNCTION and a PROCEDURE: a procedure could not
-- appear in any of those three places.
--
-- The organiser's own summary: their events, what each one actually
-- earned after refunds, and how full it is.
-- ---------------------------------------------------------------------
SELECT  o.full_name                       AS organizer,
        e.id,
        e.name                            AS event_name,
        e.status,
        e.seats_taken,
        e.capacity,
        fn_event_net_revenue(e.id)        AS net_revenue
  FROM  events e
  JOIN  users  o ON o.id = e.organizer_id
 WHERE  fn_event_net_revenue(e.id) > 0
 ORDER BY net_revenue DESC
 LIMIT  10;
