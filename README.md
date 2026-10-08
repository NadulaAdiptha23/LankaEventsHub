# Verdant Events

Java web-based event management system — Spring Boot 3.3 + Spring Security
+ Spring Data JPA + MySQL 8 + Thymeleaf + Bootstrap 5.

> **On the two names.** Everything a visitor sees says **Verdant Events**.
> The Java package is still `com.sliit.eventsphere` and the MySQL schema is
> still `eventsphere`, because renaming a package and a database is a
> refactor of every import, every configuration file and every existing
> installation, and it buys nothing a user can see. Where "EventSphere"
> appears below, it is naming a package, a class or a database — never
> the product.

```
Browser (Thymeleaf pages)  ─┐
                            ├─→ Controllers ─→ Services ─→ Repositories ─→ MySQL
REST clients (/api/**)     ─┘                     │
                                                  └─→ Domain events → Listeners
                                                      (notifications, refunds)
```

---

## 1. Prerequisites

- **JDK 17** or later
- **Maven 3.9+** (or your IDE's bundled Maven)
- **MySQL 8.0.16 or later** — the schema uses enforced `CHECK` constraints
  and one `STORED` generated column, both of which need 8.0.16+. A local
  install works, or use the included `docker-compose.yml`.
- An IDE with Spring Boot support (IntelliJ IDEA, Eclipse/STS, or VS Code
  with the Java Extension Pack)

> **Build note.** Maven Central is blocked in the environment this project
> was last worked in, so **no `mvn` build was ever run** and no jar was
> produced there. The code was instead compiled with `javac` against a
> local copy of the dependencies, started against a real MySQL server, and
> exercised through a real browser — so "it compiles", "it starts" and
> "the features work" are all observed rather than assumed. What remains
> unproven is specifically the *Maven* build: that `pom.xml` resolves and
> that the plugin configuration is right. **`mvn clean test` on your
> machine is that test**, and it is the first thing to run. See
> [§12 What was verified, and how](#12-what-was-verified-and-how) for the
> exact split.

## 2. First-time setup

The database and the application are set up **separately**: you create and
populate the database, then point the app at it. Nothing auto-creates or
auto-resets your database on startup.

**Step 1 — get a MySQL server running.**

```bash
cp example.env .env      # then edit the passwords in .env
docker compose up -d
```

Or use an existing local MySQL 8 install.

**Step 2 — create the database and load the schema and seed data.**

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS eventsphere"
mysql -u root -p eventsphere < database/01_schema.sql
mysql -u root -p eventsphere < database/02_seed_data.sql
```

(With the Docker container, use `-h 127.0.0.1 -u eventsphere -p` and the
password from your `.env`.)

**Already have a database with data in it?** Run
`database/03_upgrade_existing_database.sql` instead of dropping and
reloading. It is idempotent — every step checks whether it has already
been applied — and it ends by printing a list of checks with the value
each one must report. If any line does not match, something above it did
not apply, and the application would go on to misreport rather than fail
loudly, which is the worse of the two outcomes.

```bash
mysql -u root -p eventsphere < database/03_upgrade_existing_database.sql
```

**Step 3 — tell the app where the database is.**

Nothing is hard-coded. `application.properties` reads environment
variables and falls back to local development defaults:

| Variable | Default | What it is |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/eventsphere?useSSL=false&serverTimezone=UTC` | JDBC URL |
| `DB_USERNAME` | `eventsphere` | database user |
| `DB_PASSWORD` | `eventsphere` | database password |
| `APP_PASSWORD_RESET_DEV_MODE` | `false` | show password-reset links on screen — **off by default**, see §7 |
| `SHOW_SQL` | `false` | log every SQL statement |
| `MAIL_HOST` | *(empty)* | SMTP host. **Empty means email is off** — in-app notifications only (see §8) |
| `MAIL_PORT` | `587` | SMTP port |
| `MAIL_USERNAME` | *(empty)* | SMTP user; empty means no authentication |
| `MAIL_PASSWORD` | *(empty)* | SMTP password — never committed |
| `MAIL_FROM` | *(falls back to `MAIL_USERNAME`)* | envelope sender |
| `APP_REMINDER_LEAD_HOURS` | `168,24` | when reminders go out: 7 days and 24 hours ahead |
| `APP_PAYMENT_HOLD_MINUTES` | `30` | how long a seat on a paid event is held while the attendee pays |
| `SERVER_PORT` | `8080` | HTTP port |

```bash
export DB_USERNAME=eventsphere
export DB_PASSWORD='...'
```

**Step 4 — run it.**

```bash
mvn spring-boot:run
```

- **http://localhost:8080/events** — public event list
- **http://localhost:8080/login** — sign in with a demo account below
- **http://localhost:8080/swagger-ui.html** — interactive API docs

### Demo accounts

All from `database/02_seed_data.sql`. Passwords are stored as real BCrypt
hashes; the plaintext is listed here only so the team can sign in.

| Email | Password | Role | Why it exists |
|---|---|---|---|
| `admin@eventsphere.test` | `admin123` | ADMIN | approvals, users, reports, refunds |
| `harini.w@eventsphere.test` | `admin123` | ADMIN | a second admin, so "you cannot remove your own admin access" is testable |
| `organizer@eventsphere.test` | `organizer123` | ORGANIZER | owns several events |
| `organizer2@eventsphere.test` | `organizer123` | ORGANIZER | owns different events — use this to check one organizer **cannot** manage another's |
| `attendee@eventsphere.test` | `attendee123` | ATTENDEE | registered and paid for several events |
| `student@eventsphere.test` | `student123` | ATTENDEE | **verified student** — the student discount only applies to accounts like this |
| `waitlist@eventsphere.test` | `attendee123` | ATTENDEE | waitlisted on a full event |
| `vip@eventsphere.test` | `attendee123` | ATTENDEE | approved invitee on a restricted event |
| `suspended@eventsphere.test` | `attendee123` | ATTENDEE | **suspended — cannot sign in**, by design |

Every other seeded attendee uses `attendee123`.

### Already have a database from an earlier version?

If you created your database with an older `01_schema.sql` and then
pulled this code, **every page that loads a registration will return
500** — `/my-registrations` first. The log says:

```
Unknown column 'checked_in_at' in 'field list'
```

Nothing is wrong with the code; the database is a version behind. This
release adds three columns (`payment_due_at`, `checked_in_at`,
`checked_in_by`) and two status values (`DRAFT`, `PENDING_PAYMENT`).

**If you don't need the data** (the usual case), just reload:

```bash
mysql -u root -p eventsphere < database/01_schema.sql
mysql -u root -p eventsphere < database/02_seed_data.sql
```

**If you want to keep your data**, upgrade in place:

```bash
mysql -u root -p eventsphere < database/03_upgrade_existing_database.sql
```

That script adds the columns, widens the two CHECK constraints, and
brings existing rows in line with the new rules — a confirmed booking on
an upcoming paid event with nothing paid becomes a held seat, because
that is what it now means. It is safe to run twice and prints a
before/after summary plus two invariant checks that must both read 0.

### Resetting the database

```bash
mysql -u root -p -e "DROP DATABASE IF EXISTS eventsphere; CREATE DATABASE eventsphere CHARACTER SET utf8mb4"
mysql -u root -p eventsphere < database/01_schema.sql
mysql -u root -p eventsphere < database/02_seed_data.sql
```

### Regenerating the seed data

`database/02_seed_data.sql` is generated, not hand-written. The generator
computes the values that must agree with each other — `events.seats_taken`
is the count of CONFIRMED registrations, a payment's final amount is the
fee minus the discount actually applied, a refund equals the payment it
reverses — so those cannot drift the way hand-maintained rows do.

Every datetime is written as an offset from `NOW()`, so a PUBLISHED event
is always in the future and a COMPLETED one always in the past, no matter
when the file is loaded. The previous seed used fixed 2027 dates, which
would have quietly turned every published event into a past-dated one.

```bash
cd database
python3 gen_seed.py > 02_seed_data.sql
```

Current contents: 24 users, 34 events across all five statuses and 10
categories, 48 agenda sessions, 14 discounts covering all four discount
types, 435 registrations (confirmed, waitlisted and cancelled), 18
invitations, ~294 payments (completed, pending and failed), ~27 refunds in
all three states, ~615 notifications covering all nine notification types
that the system actually produces, and ~164 audit rows.

## 3. Project structure

```
eventsphere/
├── docker-compose.yml          Local MySQL
├── example.env                 Copy to .env and edit; .env is git-ignored
├── database/
│   ├── 01_schema.sql           Full DDL — 13 tables (run first)
│   ├── 02_seed_data.sql        Demo data covering every flow (run second)
│   ├── 03_upgrade_existing_database.sql   Migrations for a database that
│   │                                      already has data (see §2)
│   └── gen_seed.py             Regenerates 02_seed_data.sql; that file is
│                               generated, so edit this rather than it
├── pom.xml
└── src/
    ├── main/java/com/sliit/eventsphere/
    │   ├── entity/        JPA entities — one class per table
    │   ├── repository/    Spring Data JPA repositories
    │   ├── dto/           Request/response objects (no entity ever reaches a view)
    │   ├── service/       Business logic and authorization
    │   │   └── discount/  Strategy pattern — discount calculation
    │   ├── events/        Domain events (Observer — publisher side)
    │   ├── listener/      Domain event listeners (Observer — subscriber side)
    │   ├── payment/       PaymentGateway, the mock gateway, card checks
    │   ├── security/      UserDetails adapter, ActingUser, EventAccessPolicy
    │   ├── controller/    REST endpoints + Thymeleaf page controllers
    │   ├── config/        SecurityConfig, OpenApiConfig
    │   └── exception/     Custom exceptions + global error handler
    ├── main/resources/
    │   ├── application.properties
    │   ├── static/css/theme.css
    │   └── templates/     Thymeleaf pages + shared fragments
    └── test/java/…        JUnit 5 + Mockito unit tests (no database needed)
```

## 4. User roles

| Role | Can |
|---|---|
| **ATTENDEE** | browse and search events; register for themselves; cancel their own registration; request an invitation to a restricted event; pay for their own registration and view their own ticket; read their own notifications; edit their own profile and password |
| **ORGANIZER** | everything an attendee can, plus: create events as drafts and submit them for approval; edit, cancel and complete **their own** events; create, view, update and delete sessions on **their own** events; create and manage discounts on **their own** events; handle invitation requests for **their own** events; view the attendee roster for and **check attendees in at** **their own** events; **take payment at the door** from attendees who chose to pay on arrival; process refunds for **their own** events |
| **ADMIN** | everything, across all events; approve submitted events; search every booking on the platform and cancel one on an attendee's behalf; **permanently delete an event, a booking or an account, with everything under it**; read the audit log; manage user accounts (roles, suspend/reactivate, student verification); view platform reports, export them as CSV, and work the refund queue |

**Signing up** offers a choice between **attendee** and **organizer** —
they are two jobs rather than two privilege levels, and neither can touch
the other's data. **ADMIN is categorically different and cannot be
self-granted**: a request carrying `role=ADMIN` is rejected with an error
rather than quietly downgraded, so an attempt to escalate is visible
rather than silent. The allowlist lives in one place on the server
(`UserRegistrationService`); the form is a convenience, not the control.

Every account created through public signup is an **ATTENDEE**. Organizer
access is granted by an administrator from `/admin/users` and there is no
self-service route to it.

## 5. Major business rules (enforced in code, not just documented)

**Events**

- An event is created as a **`DRAFT`** — saved, owned, editable in every
  field, invisible to attendees and bookable by nobody. Submitting it is a
  separate, deliberate act by the organizer, which is what makes
  `PENDING_APPROVAL` mean something to the administrator reading the
  queue: everything in it is finished and waiting on them.
  Lifecycle: `DRAFT → PENDING_APPROVAL → PUBLISHED → COMPLETED`, with
  `CANCELLED` reachable from the first three. `CANCELLED` and `COMPLETED`
  are terminal — nothing ever returns to an earlier state.
- **A draft is allowed to be incomplete; submitting it is not.** A draft
  still missing what an attendee would need in order to decide — venue,
  start time, capacity, registration deadline — is refused at submission
  with every gap named at once, rather than reaching an administrator
  half-finished. An administrator cannot publish a draft directly either:
  there is no way to skip the organizer's own review.
- **Venue double-booking** is refused on both create *and* update; on
  update the event being edited is excluded from the conflict query.
- **Two-tier edit lock**: once an event has active registrations, date,
  time, venue, price, event type, capacity and the registration window
  are frozen for the **organizer**; description, banner, contact details
  and special instructions stay editable by anyone who can manage the
  event. **An Administrator can still change a locked field** (BR-05),
  and when they do the audit entry names the fields rather than reading
  "Event details updated" — an override that changes the terms people
  already registered under should be findable later. The venue
  double-booking check still applies to an administrator: the lock
  protects attendees from changed terms, it is not permission to
  double-book a room.
- Capacity can never be reduced below the seats already taken.
- **Cancelling an event cancels everything on it.** Under a row lock, the
  service snapshots every registration that still holds a place, sets each
  one to `CANCELLED`, releases the seats, and only then publishes the
  domain event. That leaves no window in which the database holds
  `event = CANCELLED` alongside `registration = CONFIRMED` and a ticket
  that still looks valid — which is the state the ticket page used to
  render happily. The ticket page and *My registrations* now show a voided
  ticket with no QR code.
- The event row is locked before the status check, so two simultaneous
  cancellations cannot both succeed: the second blocks, then sees
  `CANCELLED` and is refused. Nothing is notified or refunded twice.
- **Marking an event completed** needs a finishing time to judge by. It
  uses `end_datetime`, falls back to `start_datetime` when there is no end
  date, and refuses with an explanatory message when the event has neither
  — rather than quietly allowing an event that has not happened to be
  closed.
- **The listing search takes six filters** — keyword, category, venue,
  date range, maximum price and event type, plus "only events with seats
  left" — and all six are applied by the database through
  `EventSpecifications`, not in Java. A filter left blank contributes no
  SQL at all, so an empty text box never becomes a search for the empty
  string. Old links still work: `?keyword=ai&category=Workshop` binds
  exactly as it did.
- Field limits (name 3–150, venue/category/contact lengths, banner URL
  scheme, description ≤ 5000, special instructions ≤ 2000, event type
  required) are checked **in the service**, so the Thymeleaf forms get the
  same rules as the JSON API rather than only the annotations the API path
  applies.

**Registration**

- A user can register only when the event is `PUBLISHED`, the registration
  window is open, the event has not started, and — for a restricted event
  — their invitation has been approved. Each refusal names its own reason.
- Registration is race-safe: the event row is locked with
  `PESSIMISTIC_WRITE` for the "check seats, then take one" sequence, so two
  simultaneous requests for the last seat cannot both succeed. The loser
  is waitlisted.
- **On a paid event, registering HOLDS the seat rather than confirming the
  booking.** The registration is created as `PENDING_PAYMENT`, the seat is
  taken from the pool immediately, and only a completed payment promotes
  it to `CONFIRMED`. This is what makes two requirements true at once that
  otherwise contradict each other — "payment happens before the booking is
  confirmed" and "the seat is secured when the attendee clicks". Before
  this, bookings were confirmed the moment they were created, so an event
  could sell out to people who never paid a thing.
- **A hold expires.** `app.bookings.payment-hold-minutes` (default 30)
  sets how long; a background sweep cancels lapsed holds, returns the seat
  and promotes the waitlist, and the attendee is told their seat went. An
  abandoned checkout is the normal case, not the exceptional one, so the
  hold releases itself rather than waiting for somebody to notice.
- A **free** event has nothing to pay for and goes straight to
  `CONFIRMED`; it never passes through a hold.
- **Waitlist promotion respects the same rule.** On a paid event the
  promoted attendee gets a hold with a deadline, not a confirmed booking —
  otherwise unpaid confirmation would simply arrive through a side door.
- **A booking can be for a party, and the party is one row.** People
  arrive in twos and fours, and making them register four separate times
  is both a worse experience and a worse data model — nothing would then
  record that the four bookings belong together. `registrations.seats`
  (1–10, enforced by the service *and* by a database CHECK) is what every
  seat figure in the system moves by: the capacity check, `seats_taken`,
  what the payment costs, what a cancellation gives back, and how many
  seats a waitlisted party needs before it can be promoted.
- **A party is never split across the capacity line.** The question asked
  of an event is not "is there a seat" but "are there *this many*". A
  booking for four against two free seats is waitlisted whole, holding
  nothing — seating two of them and waitlisting the rest would separate
  people who arrived together. The waitlist message says so too, rather
  than claiming the event is full when the page plainly shows two seats
  left.
- **One active registration per attendee per event** — and that is a
  different rule from one *row* per attendee per event. Cancelling leaves
  the old row in place (payments and refunds hang off it) and the attendee
  may register again. Enforced by the database, not just the service:
  `registrations.active_user_id` is a generated column that is `NULL` for
  cancelled rows, under `UNIQUE (event_id, active_user_id)`.
- **Cancellation cutoff**: a confirmed seat can be given up until 48 hours
  before the event starts. Leaving a *waitlist*, or walking away from an
  unpaid hold, is allowed at any time — neither costs the organizer a
  confirmed head, and the hold gives its seat straight back.
- Cancelling a booking — confirmed or held — frees **all** of its seats
  and fills them from the waitlist, atomically, in the same locked
  transaction. A booking for four returns four.
- **Freeing several seats can promote several parties.** The waitlist is
  walked in order and every booking that fits is promoted, rather than
  exactly one: four seats coming back should be able to seat four people
  who have been waiting, not one of them with three seats left empty.
  Where a party does not fit, it stays on the waitlist and the walk
  continues past it. That is a deliberate exception to strict
  first-come-first-served and it cuts both ways — a party of eight near
  the front will watch smaller bookings behind it be seated. The
  alternative, stopping dead at the first booking that does not fit,
  lets one large party block every seat on the event until it cancels or
  the event happens, which is worse for everybody including them. Order
  is still honoured for every booking that *can* be seated.

**Attendance**

- **Registering and turning up are different facts, and both are
  recorded.** An organizer checks attendees in from the roster for their
  own event; `registrations.checked_in_at` stores when, and
  `checked_in_by` stores who did it. Only a `CONFIRMED` booking on an
  event that has not been cancelled can be checked in, which the database
  enforces as well as the application.
- **Check-in records how many of the party arrived**, not merely that
  somebody did. A table of four routinely arrives as three; counting the
  booking as fully attended overstates every attendance figure on the
  platform, and counting it as absent understates them. Only a number is
  honest, so `registrations.seats_checked_in` holds one, and every
  attendance figure sums it rather than counting rows. Checking in more
  people than the booking holds is refused, and checking in *nobody* is
  refused too — an absence is recorded by the booking having no check-in
  at all, not by a check-in row that says nobody came.
- Check-in is **idempotent**: pressing the button twice at a busy door
  keeps the first time, which is when the attendee actually arrived. It is
  also reversible, because the likeliest mistake this feature will ever
  see is tapping the wrong name on a phone. Undoing it clears the head
  count along with the timestamp — the two are either both set or both
  null, which the database enforces, and a figure left behind would go on
  being summed by every attendance report.
- Deleting the staff account that performed a check-in sets `checked_in_by`
  to `NULL` and leaves `checked_in_at` alone — the attendee still turned
  up.

**Payment and ticketing**

- **Payment requires a HELD seat (`PENDING_PAYMENT`) that belongs to the
  caller, and paying is what confirms it.** Nothing else in the system
  promotes a registration to `CONFIRMED`, which is what makes "confirmed
  implies paid" a fact rather than a convention. Paying against an expired
  hold is refused: the seat may already belong to somebody else, so taking
  the money would be selling something we do not have.
- A **declined** payment can be retried. Because
  `payments.registration_id` is `UNIQUE`, the retry reuses the failed row
  rather than inserting beside it — otherwise a declined card would lock
  somebody out of the seat they are still holding.
- The amount is **always recalculated on the server**. The checkout page's
  live preview is a preview.
- At most one discount per payment, and the discount must belong to the
  event being paid for.
- An ineligible discount is an **error with a reason** ("that promo code is
  not valid for this event"), never a silent fall-back to full price.
- **The amount is the fee times the seats.** One method computes it and
  both the live quote and the charge call it, so the price shown and the
  price taken cannot disagree — the single worst bug this module could
  have. A percentage discount comes off the party total, which is what
  "15% off" means to the person reading it.
- **There are two ways to settle: card, or on arrival.** The choice is
  `payments.method`, and it changes what happens rather than only what
  the page looks like.
- **Charging goes through a `PaymentGateway` interface**, not a hard-coded
  `status = COMPLETED`. The shipped implementation is `MockPaymentGateway`
  and it says so in its own name: nothing leaves the machine and no money
  moves. What it is *not* is a gateway that approves everything — that
  version made the declined-card path unreachable, so "we handle declines"
  was a claim nobody could check. It now decides **from the card number,
  deterministically**:

  | Number | Outcome |
  |---|---|
  | `4111 1111 1111 1111` | approves |
  | `4000 0000 0000 0002` | declined — insufficient funds |
  | `4000 0000 0000 9995` | declined — reported lost or stolen |
  | `5105 1051 0510 5100` | declined — issuing bank unreachable |
  | any other checksum-valid number | approves |

  All three declining numbers pass the Luhn check on purpose: a decline
  and a typo have to be distinguishable, or a demo would be showing
  validation rather than a decline. The list is read from the gateway
  class into the checkout's help panel, so a number that approves in the
  panel and declines in the code is impossible. A decline records the
  attempt as `FAILED`, **leaves the seat held** so the attendee can try
  another card, issues no ticket, and shows them the reason.
- **A mistyped card is not a decline.** The number is checked before the
  gateway is troubled — length, the Luhn checksum, the holder name, the
  security code, and an expiry that is valid to the *end* of its month
  (treating the 1st as expired would reject a good card for up to thirty
  days). A failed checksum is reported as "that card number is not valid,
  please check it", and **nothing is recorded**: the money was never asked
  for, so telling somebody their card was refused would send them to their
  bank over a typing mistake.
- **No card detail is stored anywhere.** No number, no expiry, no security
  code, not even a masked fragment. The card is used to reach a decision
  and discarded; the log line on a decline carries only the last four
  digits, and a re-rendered error page does not put the number back into
  the HTML. There is no column for any of it.
- **Paying on arrival is a promise, not a payment.** Choosing it does not
  confirm the booking — that would be the unpaid-confirmation bug arriving
  through a side door. The payment row is `PENDING` for the full party
  amount, the seat hold is extended from the usual few minutes to the
  **event's own start** so the release sweep does not take the seats back
  that evening, and a code is issued immediately because the attendee
  needs something to present and the door needs something to look up. The
  ticket page renders it as an **unpaid reservation**, saying so in as
  many words, with "Due at the door" where "Amount paid" would be. An
  unclaimed promise still releases its seats when the event starts rather
  than holding them forever.
- **The door completes it.** `/events/{id}/door` lists everyone who still
  owes money, with the amount and the code; the organizer records the
  payment with a free-text note ("cash", "card machine", a receipt number)
  that goes into the audit log. That completes the payment and confirms
  the booking — through the same method the card path uses, so there is
  exactly one place in the system where a registration becomes `CONFIRMED`
  by being paid for. Restricted to the event's own organizer and
  administrators. A card payment that was *declined* cannot be turned into
  a sale at the door: otherwise the gateway's verdict would be advisory,
  which is the opposite of what it is.
- **A declined payment survives the exception that reports it.** `pay()`
  is transactional and the exception it throws to tell the attendee is
  unchecked, so by default Spring rolled the `FAILED` row straight back
  out again — for as long as this method had existed, no decline left any
  trace, and the test passed throughout because it verified that `save`
  had been *called*, which it had. The fix is `noRollbackFor` on `pay()`,
  which is safe only because every other throw in that method happens
  before anything is written; the card is validated at the very top for
  exactly that reason. (A separate `REQUIRES_NEW` transaction was tried
  first and **deadlocks**: `pay()` holds a write lock on the registration
  row and inserting a payment needs a shared lock on that same row for the
  foreign key, so the inner transaction waits for the outer one, which is
  waiting for it.) There is a test asserting the annotation itself,
  because removing it silently restores the bug and no mocked repository
  could ever show it.
- **A free event is confirmed AND ticketed at registration** — it never
  reaches the payment flow at all. The ticket is carried by a zero-value
  `COMPLETED` payment, so free and paid bookings produce the same shape of
  record and every page that reads a ticket works identically for both.
  Being worth 0.00 keeps it out of revenue and out of refunds. That leaves
  the payment gate saying exactly one thing, with no exception:

  ```
  free event  ->  confirmed + ticket, at registration
  paid event  ->  held  ->  paid  ->  confirmed + ticket
  ```
- `payments.registration_id` is `UNIQUE`, so a duplicate payment is
  impossible even if two requests race past the service check.
- The ticket is a **code**, not a stored image. The QR square on the
  ticket page is drawn in the browser from that code, and the code encodes
  only the event and registration ids — nothing about the attendee.

**Discounts** (Strategy pattern)

| Type | Eligibility |
|---|---|
| Early bird | inside the discount's validity window |
| Student | account marked **verified student** by an administrator |
| Promo code | the code the attendee typed matches, case-insensitively |
| Group | group size of at least 3 |

Shared checks (active, in-window, percentage 0–100, result clamped to the
original price and rounded to 2dp) live once in
`DiscountCalculationService`, so no strategy can forget one.

**Invitations** (restricted events)

- A person makes **one request per event**, always for themselves — a
  caller-supplied user id is ignored. `UNIQUE (event_id, user_id)` in the
  schema is the real guarantee.
- A **rejection is final**: re-requesting is refused with a message that
  says so and points the person at the organizer, instead of the old
  "you have already requested" wording, which reads as a
  duplicate-submission complaint and leaves a rejected applicant waiting
  for a decision that has already been made. An organizer who changes
  their mind approves the existing request; there is no second request to
  make.

**Concurrency**

- **Registering** locks the event row (`PESSIMISTIC_WRITE`) for the
  "check seats, then take one" sequence.
- **Paying** locks the registration row, so two simultaneous payments for
  one held seat cannot both pass the "no completed payment exists" check
  and leave the second to fail on a database constraint.
- **Consuming a password-reset token** locks the token row, so the same
  link submitted twice at once cannot set two passwords.
- **One lock order everywhere: event, then registration.** The hold-release
  sweep discovers expired holds *without* locking and then takes both locks
  in that order, re-checking each candidate afterwards — because between the
  unlocked read and the lock the attendee may have paid, and acting on the
  stale row would cancel a booking somebody was just charged for. Taking the
  locks in the discovery query was the earlier design, and it inverted the
  order against cancellation: two transactions could wait on each other.

**Refunds**

- **A refund is what the platform owes when the platform takes the seat
  away.** Which of three things happened decides whether money goes back:

  | What happened | Refund? |
  |---|---|
  | The organizer cancels the event | **Yes**, always |
  | An administrator cancels one booking | **Yes** — the place was taken from somebody who still wanted it |
  | The attendee cancels their own booking | **No** — they chose to give the place up, and the organizer is left holding a seat they can no longer sell |

  **The third row is not configurable.** It was a property once and should
  not have been: the requirement states the rule flatly, and a property
  meant an environment variable could quietly run a system that broke it.
  One `RefundPolicy` component holds the rule in code, read by both the
  refund service (which decides) and the booking pages (which **warn the
  attendee before they press cancel** that they will not get their money
  back) — so the warning and the outcome cannot drift apart.
- Cancelling an event automatically creates a `PENDING` refund for every
  completed, non-zero payment on a registration **that held a seat at the
  moment of cancellation** — for the exact amount paid, never more. The
  domain event carries that list, including the status each registration
  held; a listener that re-queried for `CONFIRMED` rows would find none,
  because the cancellation has already cancelled them, and would silently
  refund nobody.
- A waitlisted place is never refunded — it was never charged for.
- One refund per payment (`refunds.payment_id` is `UNIQUE`), and refund
  creation is idempotent, so a repeated cancellation cannot double-pay.
- `PENDING → PROCESSED → COMPLETED`, one step at a time, by an
  administrator or the organizer who owns the event — not by every
  organizer on the platform. The queue is at `/admin/refunds`.

## 6. Security model

**Two authentication architectures, kept apart on purpose**

| | `/api/**` | everything else |
|---|---|---|
| Authentication | HTTP Basic | session cookie + form login |
| Session | stateless | stateful |
| CSRF | disabled | **enabled** |

Disabling CSRF is only safe on a chain with no ambient cookie
credentials, which is why the two chains exist. The checkout page's price
preview therefore posts to `/registrations/{id}/quote` — a
session-authenticated page endpoint that sends the CSRF token — rather
than to `/api/**`.

**Authorization is enforced in the service layer**

URL rules and `@PreAuthorize` are a coarse first filter: they can say
"some organizer", never "*this* organizer". Ownership is checked in the
services, against the loaded entity, through one shared
`EventAccessPolicy`. Both layers are kept.

Nothing that identifies a user is ever taken from the request. Services
receive an `ActingUser` built from the authenticated principal, so
`{"userId": 2}` or `?userId=2` cannot make one account act as another.

**Other guarantees**

- Passwords: BCrypt, minimum 8 characters with a letter and a digit,
  enforced identically at signup, reset and profile change.
- A suspended account (`is_active = false`) cannot authenticate — **and
  the sessions it already holds are ended immediately**. Blocking only the
  next sign-in would have left a suspended user working normally until
  their cookie happened to expire. The same applies to a role change: the
  new role takes effect at once rather than whenever the user next logs
  out. Implemented with Spring's `SessionRegistry` plus
  `HttpSessionEventPublisher`; see `security/ActiveSessionService`.
- The last active administrator cannot be demoted or suspended.
- Nobody can change their own role.
- **An organizer who still owns live events** (`PENDING_APPROVAL` or
  `PUBLISHED`) cannot be demoted or suspended. Allowing it would leave
  those events with an owner who can no longer manage them — silently
  admin-only. The refusal names the number of events and what to do about
  them. Cancelled and completed events do not block anything.
- Emails are normalised to lower case everywhere, so sign-in and password
  reset are case-insensitive and duplicates are impossible.
- Error responses never include a stack trace, exception class name, or
  database constraint name; the detail is logged server-side instead.
- Role changes, suspensions, student verification, event
  create/update/approve/cancel/complete, registrations, cancellations and
  payments are all written to `audit_logs`.

## 7. Password reset

There is **no email sender in this system**. Rather than pretend
otherwise, the reset link is returned to the browser *only* when dev-mode
is switched on:

```properties
# The default when nothing is set. Token created, never displayed, never logged.
app.password-reset.dev-mode=${APP_PASSWORD_RESET_DEV_MODE:false}
```

```bash
export APP_PASSWORD_RESET_DEV_MODE=true   # local testing only: link shown on screen
```

The default is `false` **deliberately**: the safe behaviour has to be the
one you get by forgetting to set anything, so a deployment that ships the
file unchanged never hands out reset links.

Either way:

- the raw token is 32 bytes of `SecureRandom`, and only its **SHA-256
  hash** is stored. Lookup is by hash, so a leaked database dump contains
  no usable reset links — the same reasoning that applies to
  `password_hash`. Plain SHA-256 rather than BCrypt is right here: the
  input is 256 bits of randomness, so there is no dictionary to attack and
  no work factor worth paying on every lookup;
- the token expires in 30 minutes and is single-use, and a successful
  reset invalidates every other outstanding link for that account;
- an unknown email address produces exactly the same response as a known
  one, so the page cannot be used to discover which addresses have
  accounts.

## 8. Notifications

**`IN_APP` is the channel of record.** `NotificationService.notify(...)`
always writes a row the recipient sees at `/notifications`. That row is
what persists, and it is what the reminder sweep checks against to avoid
sending the same reminder twice.

**`EMAIL` is delivered as well — when, and only when, a mail server has
been configured.** With `MAIL_HOST` unset, email is off and the system
behaves exactly as it did when in-app was the only channel: nothing is
queued, nothing is dropped, nothing fails. Set it and the same
notifications also reach the inbox:

```bash
export MAIL_HOST=smtp.example.com
export MAIL_PORT=587
export MAIL_USERNAME=no-reply@example.com
export MAIL_PASSWORD='...'          # never committed; see example.env
```

Mail is sent on a background thread and a failure is logged, never raised
— a mail server being unreachable must not roll back the registration
that produced the notification. The implementation lives in
`notification/`: a `NotificationSender` interface with an in-app sender
and an email sender behind it, so adding a third channel is one new class
and no edit to the service.

**`SMS` remains an enum value with nothing behind it**, and that is said
here rather than left to be discovered. The requirement list names EMAIL,
and EMAIL is what has been built.

**Honest limitation.** The SMTP client (`notification/SmtpClient`) is
written directly against sockets rather than using
`spring-boot-starter-mail`, because the build environment had no access
to a package registry and an undeclared dependency would have been worse
than a small implementation. It is tested against an in-process fake SMTP
server that speaks the protocol — command order, multi-line EHLO,
message termination, header-injection refusal — but it has **not** been
tested against a live mail provider. `NotificationSender` is the seam: a
`JavaMailSender`-backed implementation drops in behind the same interface
whenever the dependency becomes available.

Password-reset links are **not** emailed even when mail is configured —
see §7 for why.

Notifications are created for: registration confirmed, seat held awaiting
payment, payment hold released, waitlisted, promoted from the waitlist,
payment confirmed, ticket issued, invitation approved, invitation
rejected, event published, event cancelled, event reminder (7 days and 24
hours ahead), booking cancelled by an administrator, and refund completed.

### Event reminders

Two reminders go out, not one: **seven days ahead**, while there is still
time to arrange to attend, and **24 hours ahead**, so it is not forgotten.
One reminder cannot do both jobs.

```properties
app.reminders.lead-times-hours=${APP_REMINDER_LEAD_HOURS:168,24}
```

Each lead time owns a band that ends where the next smaller one begins, so
an attendee gets each reminder exactly once and is never told "starts in 7
days" about something happening tomorrow. A malformed value stops startup
rather than silently reducing how many reminders go out — a
misconfiguration that shows up as "nobody was reminded" three weeks later
is the worst possible failure mode for this job.

## 9. Design patterns

### Strategy — discount calculation

`service/discount/`: the `DiscountStrategy` interface, four concrete
strategies, and `DiscountCalculationService` as the context, which Spring
hands every strategy as a `List<DiscountStrategy>` and which indexes them
into a `Map<DiscountType, DiscountStrategy>`.

**Why:** the four types have genuinely different eligibility logic — a
date window, an account flag, a typed code, a headcount threshold — not
one formula with four labels. Adding a fifth type is one new
`@Component`.

**Demo:** the checkout page's live price preview, `POST
/registrations/{id}/quote`.

### Observer — event lifecycle

`events/` (`EventPublishedEvent`, `EventCancelledEvent`) and `listener/`
(`NotificationEventListener`, `RefundTriggerListener`), wired by Spring's
`ApplicationEventPublisher`.

**Why:** `EventServiceImpl` announces "this event was cancelled" and has
no dependency on either notifications or payments. Two unrelated
subscribers react independently.

**Demo:** cancel a paid event and watch a `Notification` row *and* a
`Refund` row appear, with `EventServiceImpl` importing neither service.
The status guard on `cancelEvent` means the event fires at most once per
event, so the listeners cannot produce duplicate side effects.

## 10. The interface

### The Verdant Events design pass

The public site was rebuilt around the Verdant Events identity: forest
green (`#14432C`) and muted gold (`#B08D57`) on ivory (`#F7F5F0`) and
obsidian (`#111111`), Playfair Display for headings against DM Sans for
everything else, near-square corners (2–8px), and no blue anywhere on any
surface — buttons, links, focus rings, charts, badges and alerts
included, which a check asserts on every run.

| Area | What it is now |
|---|---|
| Home | Twelve sections: cinematic hero, brand statement, statistics, a numbered editorial list of what the platform does, the featured grid, the discover/search bar, how it works, why Verdant Events, the story, testimonials, a closing call to action, footer |
| Navigation | One bar for the whole public site. Transparent over the hero, ivory and solid once scrolled, with the active section marked on the server rather than guessed by a script. The wordmark is a real link to `/` from every page |
| New pages | `/services`, `/about` and `/contact` — real routes with real controllers, not anchors that scroll somebody halfway down the home page |
| Imagery | Ten art-directed SVGs generated by `tools/make_imagery.py`, bundled rather than hot-linked. Each is designed to be swapped for a photograph of the same name |
| Dashboards | Deliberately left workmanlike. The editorial serif stops at the application shell: an organizer reading a table of registrations is working, not browsing |

**What the public pages are allowed to claim**

Every number on the marketing pages is counted from the database by
`PublicStatsService`. If the platform has held no events, the statistics
row does not render; if nobody has been checked in anywhere, the
attendance figure is left out rather than printed as a damning `0%`. The
featured grid is built from the categories that published events actually
use, ordered by how many each holds, so no tile can send a visitor to an
empty listing — and it disappears entirely when there is nothing
published. The testimonials are labelled as illustrative and attributed to
nobody. The footer says there are no social accounts instead of linking to
invented ones. The contact form composes an email in the visitor's own
client and says on the page that nothing is submitted to the website,
because nothing is.

### The earlier pass

The table below records the pass *before* the Verdant Events redesign —
what the application's own screens gained over the original submission.
Those screens are still what it describes; the public pages above have
since been rebuilt.

**One design system, not twenty-seven styled pages**

`static/css/theme.css` is the whole visual language: colour, spacing,
radius, elevation and a fixed type scale as CSS custom properties, then
the components built from them — page and section headers, cards, event
cards, stat cards, status pills, buttons, forms, tables, action menus,
empty states, toasts, steppers, the ticket, the filter panel and the
dashboard shell. Pages compose those classes. No page picks its own font
size or its own grey, and `check_ui.py` fails the build if one starts to.

The shared markup lives in `templates/fragments/components.html`: the
event card, the status pills, the capacity meter, the category icons and
the empty state. An event therefore looks identical on the home page and
the listing, and `PUBLISHED` looks identical on the admin table and the
event hero, because in each case it is the same fragment.

**What changed, and why**

| Area | Before | Now |
|---|---|---|
| Home | Generic hero, search below the fold, the same star icon on all six categories | Search is the main interaction; each category has its own glyph and tint; featured and upcoming both use the shared card |
| Listing | Three filters, plus a "Sort" dropdown that submitted nothing | Six filters that all reach the server (see §5), a real empty state that distinguishes "no matches" from "nothing published", and no dead controls |
| Event detail | Details stacked in a column | Cover hero, a five-fact row (date, time, venue, capacity, price), sticky booking panel that goes static on mobile, plus the schedule, registration window, instructions and organizer sections that the model already had and the page never showed |
| Checkout | One long form | Four-step indicator, event summary, price breakdown with the total set at display size, busy state on the pay button |
| Ticket | A dark card | A ticket — perforation, stub, QR, ticket code, paid badge, print stylesheet, and an `.ics` download built in the browser from the values already on the page |
| Dashboards | Tables | A stat row counted from the rows already on the page, then the table; five or six buttons per row replaced by one `⋮` menu |
| Admin reports | Two Chart.js canvases | The same two charts drawn in CSS from the same data, with no third-party charting script |
| Tables | Multiple buttons per row | One action menu, keyboard-dismissable, that escapes its scroll container |
| Mobile | Sidebar reflowed into a strip of pills across the top | Off-canvas drawer; filters become a bottom sheet; no horizontal scroll at any width down to 320px |

**Things deliberately not built**

- **No favourite/save button.** There is no table behind it. A heart that
  forgets what you clicked is worse than no heart.
- **No "registrations over time" or "revenue over time" chart.** Nothing
  in the reporting model is time-stamped in a way that would let the page
  draw a trend, and the page says so rather than drawing a plausible
  line. §11 covers what the admin dashboard does show.
- **No per-organizer revenue KPI.** Revenue is exposed platform-wide to
  administrators, not per organizer, so an organizer-dashboard revenue
  card would be a number with nothing behind it.
- **The three testimonials and the "50,000+ users" counter on the signup
  page are gone.** They were written for people who do not exist. A
  fabricated endorsement is a claim, not decoration; the panel now says
  what the account actually gets you.

**Accessibility**

Every input has a label or an `aria-label`, every image an `alt`, every
page a skip link, and focus outlines are styled rather than removed.
Colour is never the only signal — a status pill carries its own word.
All text meets WCAG AA contrast, which required darkening two things the
original palette got wrong: the secondary grey (`#94A3B8` was 2.56:1 on
white) and white-on-mid-tone success and danger buttons (2.54:1 and
3.76:1). Those are checked in a browser on every run, not assumed.

`prefers-reduced-motion` removes every transition and animation.

## 11. API reference

Full interactive docs at `/swagger-ui.html`. All `/api/**` endpoints use
HTTP Basic.

| Method | Path | Access |
|---|---|---|
| `GET` | `/api/events/published` | public |
| `GET` | `/api/events/{id}` | public |
| `GET` | `/api/events/{id}/sessions` | public |
| `POST` | `/api/events` | organizer, admin |
| `PUT` | `/api/events/{id}` | owning organizer, admin |
| `DELETE` | `/api/events/{id}` (cancel) | owning organizer, admin |
| `POST` | `/api/events/{id}/approve` | admin |
| `POST` | `/api/events/{id}/complete` | owning organizer, admin |
| `GET` | `/api/events` | admin |
| `GET` | `/api/events/mine` | organizer, admin |
| `POST` | `/api/events/{id}/sessions` | owning organizer, admin |
| `DELETE` | `/api/events/{id}/sessions/{sessionId}` | owning organizer, admin |
| `POST` | `/api/events/{id}/registrations?seats=N` | any signed-in user (registers **themselves**; `seats` 1–10, default 1) |
| `DELETE` | `/api/events/{id}/registrations` | any signed-in user (cancels **their own**) |
| `GET` | `/api/events/{id}/registrations` | owning organizer, admin |
| `GET` | `/api/registrations/mine` | any signed-in user |
| `GET` | `/api/registrations/{id}` | owner, owning organizer, admin |
| `POST` | `/api/events/{id}/invitations` | any signed-in user (requests for **themselves**) |
| `GET` | `/api/events/{id}/invitations` | owning organizer, admin |
| `POST` | `/api/events/{id}/invitations/{invId}/approve` \| `/reject` | owning organizer, admin |
| `POST` | `/registrations/{id}/quote` | owner of the registration (page chain, CSRF-protected) |
| `POST` | `/api/payments` | owner of the registration |
| `GET` | `/api/payments/registration/{id}` | owner of the registration |
| `POST` | `/api/refunds/{id}/advance` | admin, owning organizer |
| `GET` | `/api/notifications`, `/unread-count` | own notifications only |
| `POST` | `/api/notifications/{id}/read`, `/read-all` | own notifications only |
| `GET` | `/api/reports/summary` | admin |

**Status codes.** `400` validation or business-rule failure · `401` not
authenticated · `403` authenticated but not allowed · `404` not found ·
`409` conflict (duplicate registration, duplicate payment, venue clash,
duplicate account) · `500` unexpected. Errors are JSON for `/api/**` and a
rendered error page for browser requests:

```json
{ "timestamp": "…", "status": 409, "error": "Conflict",
  "message": "This registration has already been paid for" }
```

Permanent deletes are page routes rather than API ones, and all three are
Administrator-only: `POST /admin/events/{id}/delete`,
`POST /admin/bookings/{id}/delete`, `POST /admin/users/{id}/delete`. Each
takes an optional `reason` recorded in the audit log.

### Pages

| Page | Route | Access |
|---|---|---|
| Home | `/` | public |
| Event list (search/filter) | `/events` | public |
| Event detail | `/events/{id}` | public to view; sign-in to act |
| Login / Register / Forgot / Reset password | `/login`, `/register`, `/forgot-password`, `/reset-password` | public |
| My registrations | `/my-registrations` | signed in |
| Notifications | `/notifications` | signed in (own only) |
| Profile | `/profile` | signed in (own only) |
| Pay | `/registrations/{id}/pay` | owner of that registration |
| E-ticket | `/registrations/{id}/ticket` | owner of that registration |
| My events | `/my-events` | organizer, admin |
| Create / edit event | `/events/new`, `/events/{id}/edit` | organizer (own), admin |
| Attendee roster **and door check-in** | `/events/{id}/attendees` | owning organizer, admin |
| **At the door** (who still owes money) | `/events/{id}/door` | owning organizer, admin |
| Discounts | `/discounts` | organizer (own events), admin |
| Invitation requests | `/events/{id}/invitations` | owning organizer, admin |
| Event approvals | `/admin/events` | admin |
| Bookings (platform-wide) | `/admin/bookings` | admin |
| Users | `/admin/users` | admin |
| Refund queue | `/admin/refunds` | admin |
| Audit log | `/admin/audit` | admin |
| Reports | `/admin/reports` | admin |

CSV exports sit beside three of these — `/admin/reports.csv`,
`/admin/bookings.csv` and `/admin/audit.csv` — and each carries the
filters currently applied rather than the whole table: what you are
looking at is what you download. They are RFC 4180, UTF-8 with a BOM so
Excel opens them correctly, and every field that begins `=`, `+`, `-` or
`@` is prefixed so a spreadsheet treats it as text rather than a formula.

## 12. What was verified, and how

`mvn test` runs the unit tests; they need **no database**.

```bash
mvn clean test
mvn spring-boot:run
```

The tests are JUnit 5 + Mockito and cover: authentication state
(suspended accounts), event creation/validation/status transitions/venue
conflicts/ownership, registration windows, waitlist and promotion,
re-registration after cancelling, the 48-hour rule, multi-seat booking and
partial attendance, payment ownership, the card checks and the gateway's
decisions, paying on arrival and settling at the door, every discount
rule, refund creation and idempotency, permanent deletion and everything
it refuses, password reset, user management guards, notification
ownership, the access policy, and the cancellation listeners.

Every rule added in the latest pass was checked by **fault injection** —
the guard is removed, the suite is run, and the test that should catch it
is confirmed to fail. Two tests passed the first time for the wrong reason
and were rewritten; without that step they would have been decoration.

Regression tests were added for each fix in the second pass, so a
reintroduced bug fails a test rather than a review:

| Fix | What the test pins down |
|---|---|
| Cancellation race (event) | the row lock is taken, and a second cancellation publishes nothing, saves nothing and re-reads nothing |
| Cancellation race (registration) | the event is locked **before** the registration, and a second cancellation releases no further seat |
| Cancelled-event state | every registration ends `CANCELLED`, seats go to zero, and the DTO reports the cancelled event to the ticket page |
| Observer payload | the notification and the refund still happen *after* the registrations have been cancelled — the case the old status re-query got silently wrong |
| Waitlist vs seat | a waitlisted place is never refunded and gets a different message |
| Session invalidation | sessions are ended on role change and on suspension, and *not* on reactivation or a no-op role change |
| Organizer lifecycle | demoting or suspending an organizer with live events is refused, and only `PENDING_APPROVAL`/`PUBLISHED` count as live |
| Token storage | the raw token never reaches the repository, the stored form is a 64-char hash, and a fresh token is issued each time |
| Pricing scope | a quote for someone else's registration is refused, as is a discount belonging to another event |
| Field limits | description and special-instruction caps hold on create *and* update, with the boundary value accepted |
| Invitation rule | the rejected case is refused with a message that says the decision is final |
| Reporting | one grouped query rather than one per event, and an event with no registrations still appears |
| Multi-seat booking | every seat figure moves by the party size, a party is never split across the capacity line, and freeing seats can promote more than one waiting booking |
| Partial attendance | three of four arriving is stored as three, more arrivals than seats is refused, and undoing a check-in clears the head count with the timestamp |
| Per-seat pricing | the charge is fee × seats, the quote shows the same total, and a percentage discount comes off the party total |
| The mock gateway | each test card gives the same answer every time, a decline carries its reason, and a failed checksum is reported as a typo rather than a decline |
| Declined payments | the `FAILED` row survives the exception that reports the decline — asserted on the `noRollbackFor` annotation itself, because no mocked repository can show it |
| Pay on arrival | the booking is **not** confirmed, the hold is extended to the event start, a code is issued, and only the door completes it |
| Permanent delete | who is refused (organizers, self, the last administrator, an account still owning events), children before parents, seats returned, and the audit row written *before* anything goes |

`ArchitectureSmokeTest` additionally fails the build if a `TODO`/`FIXME`
comes back into production code, if a controller imports a repository, if
a template regains a dead `href="#"` control, if a credential is
hard-coded, if `open-in-view` is turned on, or if the password-reset
dev-mode default is flipped to `true`.

**Tests never touch a developer's database.** The default run is plain
JUnit + Mockito and opens no connection at all;
`src/test/resources/application-test.properties` exists so that anything
which *does* boot a Spring context gets an in-memory H2 (test scope only),
not whichever MySQL happens to be running locally. There is no personal
username or password anywhere in the test configuration.

### What was run, and what was not

Maven Central is unreachable from the environment this project was last
worked in, so **`mvn` was never run there**. Everything else was: the
sources were compiled with `javac` against a local copy of the same
dependencies `pom.xml` declares, the application was started against a
real MySQL server with the schema and seed data in this repository, and
the pages were driven through a real headless browser.

**Observed, not assumed:**

- **The code compiles.** 177 main classes and 30 test classes, no errors.
- **The tests pass.** 597 assertions-bearing test methods, 0 failures,
  run against the compiled classes.
- **The application starts** against a live MySQL and serves every route.
- **Event CRUD works end to end through the UI** — an event created in the
  organizer's form appears in the listing, its detail page shows what was
  entered, the edit form pre-loads the stored values, an edit is written
  back to the database, and an administrator's permanent delete removes it
  and leaves no orphaned registration or payment rows behind.
- **Every public route returns 200**, the navigation and its active states
  route correctly, and no page scrolls sideways at 992–1920px for a signed
  out visitor or for any of the three roles signed in.
- **No uncaught JavaScript errors** across a full pass of the public site.

**Still only your build can show:**

- **That the Maven build works.** `pom.xml` is unchanged from the version
  this project was developed with, but it has not been resolved against
  Maven Central here. If a dependency or plugin version is wrong, `mvn
  clean test` is what will say so.
- **Concurrency under real parallel load.** The pessimistic locks are
  covered by tests and argued from the isolation semantics; they have not
  been measured with genuinely simultaneous requests.
- **MySQL-specific schema behaviour at scale.** The generated column and
  the `CHECK` constraints were exercised by the running application, but
  not stress-tested.

Instead, everything below was checked statically before hand-off: every
Java file parses and has balanced braces; every method call on a project
type resolves to a declared method with a matching argument count; there
are no unused or dangling imports; every Thymeleaf template is
tag-balanced, every fragment reference resolves, and every view name a
controller returns exists; both SQL files parse as MySQL; every column an
entity maps exists in the schema and no schema column is silently
unmapped; there are no unfinished-work leftovers in the Java or the
templates; and every seed row satisfies every constraint the schema
declares (foreign keys, the one-active-registration rule, `seats_taken`
against the registrations, payment arithmetic, refund uniqueness). Each
checker was fault-injected to confirm it actually fails when it should.

Two of those checks are about the interface specifically, and one of them
runs a browser:

- `surface.py` snapshots every form action, method and field name, every
  URL, every `sec:authorize` expression, every fragment include and every
  id the page's JavaScript looks up — taken **before** the redesign
  started — and diffs it afterwards. A renamed field, a POST quietly
  turned into a GET, or an authorization check dropped while restyling a
  page all show up as a removal. Fourteen removals are listed in the file
  with the reason each one was deliberate; anything else fails.
- `check_ui.py` enforces the design system: no hard-coded brand colour or
  font size in a template, no dead `href="#"` or unnamed form control,
  every class defined in `theme.css` or Bootstrap, every input labelled,
  every image with `alt`, every page with a skip link.
- `check_render.py` opens Chromium, lays the components out at 320, 390,
  768, 1024 and 1440px, and checks the things that only exist once
  something is painted: no horizontal scroll at any width, every action
  menu opening fully on screen without being clipped, and every piece of
  text meeting WCAG AA contrast. **This one earned its keep**: it found
  that `.table-responsive` is a scroll container, so the action menu in
  every table in the application was being clipped at the table's bottom
  edge — invisible to any check that reads the markup as text. It also
  found five contrast failures inherited from the original palette.
  It skips cleanly if Playwright is not installed.

This is a real check and it caught real mistakes, but it is **not a
compiler and not a test run**. Static analysis cannot tell you that
generics line up, that an overload resolves the way you expect, or that a
Mockito stub matches at runtime. Run `mvn clean test` yourself before
submitting.

### Integration testing against a real database

There is deliberately no `@SpringBootTest` in the default run: the old one
needed a live MySQL matching whoever's `application.properties` happened
to be checked out, so it failed on any machine without that database. To
exercise the whole context, point the environment variables at a scratch
database and write the integration test against that explicitly.

## 13. Administration screens

Four screens exist only for an Administrator, plus one shared with
organizers. All of them are protected three times over — the URL rule in
`SecurityConfig`, a class-level `@PreAuthorize`, and a role check inside
the service before it queries anything — so typing the URL by hand is
refused before any data is read.

| Screen | Path | Who |
|---|---|---|
| Approvals | `/admin/events` | Admin |
| Users | `/admin/users` | Admin |
| **Bookings** | `/admin/bookings` | Admin |
| **Discounts** | `/discounts`, `/admin/discounts` | Organizer (own events), Admin (all) |
| Refund queue | `/admin/refunds` | Admin |
| **Audit log** | `/admin/audit` | Admin |
| Reports | `/admin/reports` | Admin |
| **At the door** | `/events/{id}/door` | Owning organizer, Admin |

### Bookings

Every registration on the platform with its payment and refund state,
filterable by attendee, event, booking status and payment status. An
Administrator can cancel a booking on an attendee's behalf: the seat is
released, the waitlist is promoted, the attendee is notified, and **if the
place had been paid for a refund is raised automatically**.

Two deliberate differences from an attendee cancelling their own booking:
the 48-hour cutoff does not apply (staff acting on a support request is
the escape hatch that rule's own error message points people at), and the
attendee *is* notified, because unlike a self-service cancellation they
did not press the button.

A page of bookings is assembled in **three queries regardless of page
size** — the page of registrations, then all of its payments, then all of
their refunds — not one lookup per row.

### Permanent delete

Everything else in this product cancels, suspends or archives, and that is
the right default: a cancelled booking is a fact about what happened, and
a suspended account is one an administrator can put back. `PurgeService`
is the escape hatch for the record that should never have existed at all —
a test event on a live platform, a duplicate account, a booking created
against the wrong person — where leaving a cancelled husk behind is worse
than removing it. **Administrator only**, on all three.

Three rules hold everywhere in it.

**The audit row is written BEFORE anything is deleted**, and it describes
what is about to go — not "event 14 deleted" but the event's name, its
organizer, and how many registrations, payments and refunds went with it.
Written afterwards it could not say any of that, because the rows it
describes would no longer exist to be counted. That is what keeps a
permanent delete traceable: the thing itself is gone, the fact that
somebody removed it is not.

**Children are deleted explicitly, in dependency order, in one
transaction** — not left to the database's own `ON DELETE` rules. Half the
foreign keys in this schema are `RESTRICT`, so relying on cascade would
fail on some paths and silently work on others, and doing it in the
service is what lets the audit row count what went. If any step fails the
whole thing rolls back, so a purge cannot half-happen and leave orphans.

**Seats are settled before the rows go.** A booking deleted without
returning its seats leaves the event short for the rest of its life, and
the booking that accounted for them no longer exists to explain the
discrepancy.

| Target | What goes with it |
|---|---|
| Event | its registrations, their payments, those payments' refunds, announcements, saved reports, discounts, sessions, invitations |
| Booking | its payment and its refund; the seats go back to the event |
| Account | its bookings, payments, refunds, notifications, saved reports, invitations, authored announcements, reset tokens; seats returned; signed-in sessions ended |

Purging an event does **not** notify its attendees. Cancelling tells
everyone, because a cancelled event is a thing that happened to them;
purging says it never existed, and a message about an event nobody can
look up is worse than silence. Purging a booking does **not** promote the
waitlist either — moving the queue on the strength of a row that should
never have been there would be acting on a fiction. The seats simply
become available again.

What it **refuses**:

- an **organizer cannot purge their own event** — the widest blast radius
  in the system must not be reachable by the person with the narrowest
  view of it;
- an administrator **cannot delete their own account**;
- the **last active administrator** cannot be deleted, checked under the
  same lock the suspension path uses, because an unlocked count lets two
  administrators each delete the other's colleague and leave the platform
  with none;
- an account that **still owns events**, counting events in *every* status
  — those events carry other people's bookings and other people's money,
  and `events.organizer_id` is `ON DELETE RESTRICT` so it would fail at
  the database anyway, after the rest of the purge had already run.

What **survives**: the audit trail. Rows naming the deleted person are
kept and their user reference nulled — a trail that disappears along with
the account is not a trail, and the details column names them in prose.
Likewise a check-in they performed: `checked_in_at` stays and
`checked_in_by` is cleared, because the attendance record is a fact about
somebody else.

The control on each screen is a collapsed panel requiring the name to be
typed back and a reason recorded. That is a **guard against a slip, not a
security control** — nothing on the server asks for the typed name, and it
could not, because anybody can edit the page. What protects those
endpoints is that they are administrator-only and audited.

### Discounts

Discounts used to be creatable only by writing SQL directly into the
table, which meant the four discount strategies had no supported way of
ever being exercised on a new event. The screen enforces:

- one discount of each type per event (two active `EARLY_BIRD` rows would
  make "which one applies?" a question about row order);
- a percentage strictly between 0 and 100;
- a promo code is required for `PROMO_CODE` and ignored for every other
  type, and is stored trimmed and upper-cased so an attendee typing it off
  a poster need not match the organizer's capitalisation;
- the **type cannot change after creation** — changing it would silently
  change which strategy produced the price a completed payment already
  recorded;
- a discount already applied to a payment **cannot be deleted**. The
  foreign key is `ON DELETE SET NULL`, so the database would allow it and
  quietly erase why that payment was cheaper. Switch it off instead.

### Audit log

`AuditLogService` had written a row on every state change since the first
version of this system, and nothing ever read one back — which meant the
requirement was satisfied on paper and not in practice. `/admin/audit` is
the read side: paged, filterable by actor, action, record type and record
id, exportable, and **read-only**. There is no edit and no delete, because
a trail somebody can amend answers nothing.

A row whose actor has since been deleted still shows what was done and to
what, and says "Account deleted" instead of the name — `audit_logs.user_id`
is `ON DELETE SET NULL` precisely so the trail outlives the account.

### CSV export

`/admin/bookings.csv`, `/admin/audit.csv` and `/admin/reports.csv` return
real RFC 4180 CSV, written straight to the response so a large export does
not have to fit in memory twice, and carrying whatever filters the screen
currently has applied. Three details make it a real export rather than a
string-join (see `CsvWriterTest`):

- a field containing a comma, a quote or a newline is quoted, and inner
  quotes are doubled — one comma in an event name otherwise shifts every
  later column of that row;
- a field starting with `=`, `+`, `-` or `@` is prefixed with `'`, because
  a spreadsheet treats such a cell as a formula and the data is
  user-supplied;
- a UTF-8 BOM is emitted, because Excel on Windows otherwise reads the
  file as the system code page and mangles every non-ASCII name in it.

---

## 14. Deliberately out of scope

These are **not** implemented, and nothing in the UI or the code pretends
otherwise:

- **Email and SMS delivery.** Notifications are in-app rows. The password
  reset flow has the same gap and is gated behind a development flag. The
  `channel` column keeps `EMAIL` and `SMS` as legal values because the
  requirement list names them, but nothing writes either.
- **Attendance / check-in.** There is no way to record who actually turned
  up, so the reporting dashboard does not show an attendance figure — that
  would just be the registration count under a different name.
- **A real payment gateway.** Payment is a mock that always succeeds; the
  `PaymentStatus` enum already models `PENDING`/`FAILED` for a real one.
- **File upload for event banners.** A banner is a URL.
- **PDF export.** CSV export is implemented (§13); PDF is not.
- **Trend charts.** The reporting model has no time-stamped measure, so
  the dashboard shows current totals and says so, rather than drawing a
  plausible-looking line from invented data.
- **Student verification documents.** An administrator sets the verified
  flag manually; checking the student ID happens outside the system.
- **An admin change-request workflow for locked event fields.** Locked
  fields are simply locked once registrations exist.
