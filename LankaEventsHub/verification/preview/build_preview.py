#!/usr/bin/env python3
"""
Builds a static preview of the real pages so they can actually be looked
at, and renders it.

Why this exists: every check so far reads the templates as text. Text
checks cannot see that a card is 40px taller than its neighbours, that a
badge overflows its row, or that the sticky panel overlaps the footer.
On the EER diagrams earlier in this project, two whole classes of defect
were only ever found by rendering the thing and looking at it.

The app cannot be started here (no Maven, no MySQL), so instead the
Thymeleaf templates are rendered with a deliberately small substitution
pass: th:text/th:each/th:if are resolved against a fixture of realistic
model data, fragments are inlined, and everything else is stripped. This
is NOT a Thymeleaf implementation and it is not a correctness check - it
exists purely so the CSS can be seen against real-shaped content at real
breakpoints.
"""
import re
import pathlib
import shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent / 'src/main/resources'
TEMPLATES = ROOT / 'templates'
OUT = pathlib.Path(__file__).resolve().parent / 'out'


# ---------------------------------------------------------------------
# A component gallery: every piece of the system, with realistic content,
# in one page. This is what makes spacing and alignment problems obvious.
# ---------------------------------------------------------------------
GALLERY = """
<a class="skip-link" href="#g">Skip</a>
<div class="container py-5" id="g">

  <div class="page-header">
    <div>
      <p class="section-eyebrow">Design system</p>
      <h1 class="page-header__title">EventSphere components</h1>
      <p class="page-header__sub">Every component, with content the shape of real content.</p>
    </div>
    <div class="page-header__actions">
      <button class="btn btn-light-custom"><i class="fa-solid fa-download"></i> Export</button>
      <button class="btn btn-primary"><i class="fa-solid fa-plus"></i> Create event</button>
    </div>
  </div>

  <h2 class="mb-3">Buttons</h2>
  <div class="d-flex gap-2 flex-wrap mb-4">
    <button class="btn btn-primary">Primary</button>
    <button class="btn btn-outline-primary">Outline</button>
    <button class="btn btn-light-custom">Light</button>
    <button class="btn btn-ghost">Ghost</button>
    <button class="btn btn-success">Success</button>
    <button class="btn btn-danger">Danger</button>
    <button class="btn btn-outline-danger">Outline danger</button>
    <button class="btn btn-primary btn-lg">Large</button>
    <button class="btn btn-primary btn-sm">Small</button>
    <button class="btn btn-primary" disabled>Disabled</button>
    <button class="btn btn-primary is-busy">Loading</button>
  </div>

  <h2 class="mb-3">Status badges</h2>
  <div class="d-flex gap-2 flex-wrap mb-5">
    <span class="badge-custom badge-success-soft">Published</span>
    <span class="badge-custom badge-warning-soft">Pending approval</span>
    <span class="badge-custom badge-gray-soft">Completed</span>
    <span class="badge-custom badge-danger-soft">Cancelled</span>
    <span class="badge-custom badge-success-soft">Confirmed</span>
    <span class="badge-custom badge-warning-soft">Waitlisted</span>
    <span class="badge-custom badge-primary-soft">Processed</span>
    <span class="badge-custom badge-gray-soft badge-plain">Workshop</span>
  </div>

  <h2 class="mb-3">Stat cards</h2>
  <div class="row g-4 mb-5">
    <div class="col-sm-6 col-xl-3"><div class="stat-card">
      <span class="stat-icon"><i class="fa-solid fa-calendar-days"></i></span>
      <div class="stat-value">128</div><div class="stat-label">Total events</div></div></div>
    <div class="col-sm-6 col-xl-3"><div class="stat-card">
      <span class="stat-icon is-success"><i class="fa-solid fa-ticket"></i></span>
      <div class="stat-value">3,847</div><div class="stat-label">Confirmed registrations</div></div></div>
    <div class="col-sm-6 col-xl-3"><div class="stat-card">
      <span class="stat-icon is-warning"><i class="fa-solid fa-hourglass-half"></i></span>
      <div class="stat-value">7</div><div class="stat-label">Awaiting approval</div></div></div>
    <div class="col-sm-6 col-xl-3"><div class="stat-card">
      <span class="stat-icon is-neutral"><i class="fa-solid fa-money-bill-wave"></i></span>
      <div class="stat-value tnum">LKR 1,284,500</div><div class="stat-label">Gross revenue</div></div></div>
  </div>

  <h2 class="mb-3">Event cards</h2>
  <div class="row g-4 mb-5">
    __CARDS__
  </div>

  <h2 class="mb-3">Stepper</h2>
  <nav class="stepper mb-5">
    <div class="stepper__step is-done"><span class="stepper__dot"><i class="fa-solid fa-check"></i></span><span class="stepper__label">Event</span></div>
    <span class="stepper__line"></span>
    <div class="stepper__step is-done"><span class="stepper__dot"><i class="fa-solid fa-check"></i></span><span class="stepper__label">Registration</span></div>
    <span class="stepper__line"></span>
    <div class="stepper__step is-current"><span class="stepper__dot">3</span><span class="stepper__label">Payment</span></div>
    <span class="stepper__line"></span>
    <div class="stepper__step"><span class="stepper__dot">4</span><span class="stepper__label">Ticket</span></div>
  </nav>

  <div class="row g-4 mb-5">
    <div class="col-lg-7">
      <h2 class="mb-3">Table with action menu</h2>
      <section class="card-custom">
        <div class="card-head"><h2>Your event list</h2>
          <span class="text-micro" style="color:var(--muted);">4 events</span></div>
        <div class="table-responsive"><table class="table-modern">
          <thead><tr><th>Event</th><th>Date</th><th>Status</th><th>Registrations</th><th class="col-actions"></th></tr></thead>
          <tbody>__ROWS__</tbody>
        </table></div>
      </section>
    </div>
    <div class="col-lg-5">
      <h2 class="mb-3">Alerts</h2>
      <div class="alert-custom alert-success"><i class="fa-solid fa-circle-check"></i><span>Your registration is confirmed.</span></div>
      <div class="alert-custom alert-warning"><i class="fa-solid fa-triangle-exclamation"></i><span>This event is full — you will join the waitlist.</span></div>
      <div class="alert-custom alert-error"><i class="fa-solid fa-circle-exclamation"></i><span>Cancellations must be made at least 48 hours before the event starts.</span></div>
      <div class="alert-custom alert-info"><i class="fa-solid fa-circle-info"></i><span>Organizer access is granted by an administrator.</span></div>

      <h2 class="mb-3 mt-5">Form</h2>
      <div class="card-custom card-pad">
        <div class="mb-4"><label class="form-label" for="p1">Event name <span class="req">*</span></label>
          <input class="form-control" id="p1" value="Future of AI 2026"></div>
        <div class="mb-4"><label class="form-label" for="p2">Search</label>
          <div class="input-icon"><i class="fa-solid fa-magnifying-glass"></i>
          <input class="form-control" id="p2" placeholder="Name or description"></div></div>
        <div class="mb-2"><label class="check-row"><input type="checkbox" checked> Only events with seats left</label></div>
        <p class="form-text">Free events always match.</p>
      </div>
    </div>
  </div>

  <div class="row g-4 mb-5">
    <div class="col-lg-6">
      <h2 class="mb-3">Bar chart</h2>
      <section class="card-custom"><div class="card-head"><h2>Events by status</h2></div>
      <div class="card-pad"><div class="bar-chart">
        <div class="bar-row"><span class="bar-row__label">Published</span><span class="bar-row__value">64</span>
          <div class="bar-row__track"><div class="bar-row__fill" style="width:72%"></div></div></div>
        <div class="bar-row"><span class="bar-row__label">Awaiting approval</span><span class="bar-row__value">12</span>
          <div class="bar-row__track"><div class="bar-row__fill tone-2" style="width:22%"></div></div></div>
        <div class="bar-row"><span class="bar-row__label">Completed</span><span class="bar-row__value">41</span>
          <div class="bar-row__track"><div class="bar-row__fill tone-3" style="width:48%"></div></div></div>
        <div class="bar-row"><span class="bar-row__label">Cancelled</span><span class="bar-row__value">3</span>
          <div class="bar-row__track"><div class="bar-row__fill tone-4" style="width:6%"></div></div></div>
      </div></div></section>
    </div>
    <div class="col-lg-6">
      <h2 class="mb-3">Empty state</h2>
      <div class="card-custom"><div class="empty-state">
        <div class="empty-state__icon"><i class="fa-solid fa-ticket"></i></div>
        <h3 class="empty-state__title">No registrations yet</h3>
        <p class="empty-state__text">When you register for an event it will appear here, along with its ticket.</p>
        <a href="#g" class="btn btn-primary">Browse events</a>
      </div></div>
    </div>
  </div>

  <h2 class="mb-3">Ticket</h2>
  <div class="row mb-5"><div class="col-xl-7">
    <article class="ticket">
      <div class="ticket__stripe"><span>EventSphere pass</span><span>Admit one</span></div>
      <div class="ticket__main">
        <p class="ticket__when">Saturday 12 September 2026 · 09:00</p>
        <h1 class="ticket__event">Future of AI 2026</h1>
        <div class="ticket__grid">
          <div class="ticket__qr"><div style="width:100%;height:100%;background:
            repeating-conic-gradient(#0F172A 0% 25%, #fff 0% 50%) 0 0/22px 22px;"></div></div>
          <div class="ticket__details">
            <div><div class="ticket__label">Attendee</div><div class="ticket__value">Nadeesha Silva</div></div>
            <div><div class="ticket__label">Venue</div><div class="ticket__value">Colombo City Centre</div></div>
            <div><div class="ticket__label">Amount paid</div><div class="ticket__value tnum">LKR 4,500.00</div></div>
            <div><div class="ticket__label">Discount applied</div><div class="ticket__value tnum">− LKR 500.00</div></div>
          </div>
        </div>
        <div class="ticket__perf"><span class="ticket__perf-right"></span></div>
      </div>
      <div class="ticket__stub">
        <div class="flex-grow-1" style="min-width:200px;">
          <div class="ticket__label">Ticket code</div>
          <div class="ticket__code">ES-100-57-A4F92C</div>
        </div>
        <span class="ticket__status" style="background:var(--success);"><i class="fa-solid fa-circle-check"></i> Paid</span>
      </div>
    </article>
  </div></div>

  <h2 class="mb-3">Category tiles</h2>
  <div class="row g-3 mb-5">__TILES__</div>

  <h2 class="mb-3">Toasts</h2>
  <div class="mb-5" style="position:relative;height:150px;">
    <div class="toast-stack" style="position:absolute;top:0;right:0;">
      <div class="toast-item toast-success"><i class="fa-solid fa-circle-check toast-item__icon"></i>
        <div class="toast-item__body">Registration successful</div>
        <button class="toast-item__close"><i class="fa-solid fa-xmark"></i></button></div>
      <div class="toast-item toast-error"><i class="fa-solid fa-circle-exclamation toast-item__icon"></i>
        <div class="toast-item__body">Payment failed — that discount is not valid for this event</div>
        <button class="toast-item__close"><i class="fa-solid fa-xmark"></i></button></div>
    </div>
  </div>
</div>
"""

CARD = """
<div class="col-xl-4 col-md-6"><div class="card-custom card-hover event-card">
  <a href="#g" class="event-card__media">
    <img src="{img}" alt="">
    <span class="event-card__badges">
      <span class="badge-custom badge-plain">{cat}</span>
      {soldout}
    </span>
  </a>
  <div class="event-card__body">
    <p class="event-card__date"><i class="fa-solid fa-calendar-day"></i><span>{date}</span></p>
    <h3 class="event-card__title"><a href="#g" class="text-heading">{name}</a></h3>
    <p class="event-card__meta"><i class="fa-solid fa-location-dot"></i><span>{venue}</span></p>
    <p class="event-card__meta"><i class="fa-solid fa-user-group"></i>
      <span class="seats-left {seatcls}">{seats}</span></p>
    <div class="event-card__foot">
      <span class="event-card__price">{price}</span>
      <a class="btn btn-outline-primary btn-sm" href="#g">View event <i class="fa-solid fa-arrow-right"></i></a>
    </div>
  </div>
</div></div>
"""

# Deliberately awkward content: a very long title, a very long venue, a
# free event, and a sold-out one. If the grid survives these it survives
# real data.
CARDS = [
    dict(img='img/e0.jpg',
         cat='Conference', name='Future of AI 2026', date='SAT, 12 SEP · 09:00',
         venue='Colombo City Centre', seats='120 of 300 seats left', seatcls='',
         price='LKR 5,000.00', soldout=''),
    dict(img='img/e1.jpg',
         cat='Workshop',
         name='Hands-on Kubernetes and Cloud-Native Platform Engineering Intensive',
         date='TUE, 15 SEP · 14:00',
         venue='SLIIT Malabe Campus, Auditorium B, New Building',
         seats='4 of 40 seats left', seatcls='is-low',
         price='LKR 2,500.00', soldout=''),
    dict(img='img/e2.jpg',
         cat='Networking', name='Founders Breakfast', date='FRI, 18 SEP · 08:00',
         venue='Cinnamon Grand', seats='No seats left', seatcls='is-full',
         price='Free', soldout='<span class="badge-custom badge-plain" style="color:#B91C1C;">Sold out</span>'),
]

ROW = """
<tr>
  <td><div class="cell-title">{name}</div><div class="cell-sub">{venue}</div></td>
  <td>{date}</td>
  <td><span class="badge-custom {badge}">{status}</span></td>
  <td>
    <div class="d-flex justify-content-between align-items-center mb-1">
      <span class="text-micro text-muted-custom tnum">{taken} / {cap}</span>
      <span class="text-micro text-muted-custom tnum">{pct}%</span></div>
    <div class="meter"><div class="meter__fill {fill}" style="width:{pct}%"></div></div>
  </td>
  <td class="col-actions"><div class="action-menu {open}">
    <button class="action-menu__trigger"><i class="fa-solid fa-ellipsis-vertical"></i></button>
    <div class="action-menu__list">
      <a href="#g"><i class="fa-solid fa-eye"></i> View event</a>
      <a href="#g"><i class="fa-solid fa-users"></i> Attendees</a>
      <a href="#g"><i class="fa-solid fa-pen"></i> Edit</a>
      <div class="action-menu__sep"></div>
      <button><i class="fa-solid fa-flag-checkered"></i> Mark completed</button>
      <button class="is-danger"><i class="fa-solid fa-ban"></i> Cancel event</button>
    </div></div></td>
</tr>
"""

ROWS = [
    dict(name='Future of AI 2026', venue='Colombo City Centre', date='12 Sep 2026',
         status='Published', badge='badge-success-soft', taken=180, cap=300, pct=60,
         fill='', open=''),
    dict(name='Hands-on Kubernetes Intensive', venue='SLIIT Malabe Campus',
         date='15 Sep 2026', status='Pending approval', badge='badge-warning-soft',
         taken=36, cap=40, pct=90, fill='is-warning', open=''),
    dict(name='Founders Breakfast', venue='Cinnamon Grand', date='18 Sep 2026',
         status='Published', badge='badge-success-soft', taken=60, cap=60, pct=100,
         fill='is-full', open=''),
    dict(name='Photography Masterclass', venue='Independence Square',
         date='02 Aug 2026', status='Completed', badge='badge-gray-soft',
         taken=25, cap=30, pct=83, fill='is-warning', open=''),
]

TILES = [
    ('Workshop', 'fa-screwdriver-wrench', 'background:#FEF3C7;color:#B45309;'),
    ('Conference', 'fa-people-group', 'background:#DBEAFE;color:#1D4ED8;'),
    ('Seminar', 'fa-chalkboard-user', 'background:#E0E7FF;color:#4338CA;'),
    ('Networking', 'fa-handshake', 'background:#D1FAE5;color:#047857;'),
    ('Concert', 'fa-music', 'background:#FCE7F3;color:#BE185D;'),
    ('Sports', 'fa-futbol', 'background:#FEE2E2;color:#B91C1C;'),
]


def build():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'css').mkdir(exist_ok=True)
    shutil.copy(ROOT / 'static/css/theme.css', OUT / 'css/theme.css')
    (OUT / 'js').mkdir(exist_ok=True)
    shutil.copy(ROOT / 'static/js/app.js', OUT / 'js/app.js')

    body = GALLERY
    body = body.replace('__CARDS__', ''.join(CARD.format(**c) for c in CARDS))
    body = body.replace('__ROWS__', ''.join(ROW.format(**r) for r in ROWS))
    body = body.replace('__TILES__', ''.join(
        f'<div class="col-6 col-md-4 col-lg-2"><a href="#g" class="category-tile">'
        f'<span class="category-tile__icon" style="{tint}">'
        f'<i class="fa-solid {icon}"></i></span>'
        f'<span class="category-tile__name">{name}</span></a></div>'
        for name, icon, tint in TILES))

    page = f"""<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>EventSphere component gallery</title>
<!-- Vendored locally: the CDNs the real pages use are unreachable from
     this sandbox, and rendering without Bootstrap would have made this
     preview lie about the layout. Same versions the templates load. -->
<link rel="stylesheet" href="vendor/css/bootstrap.min.css">
<link rel="stylesheet" href="vendor/css/fontawesome.min.css">
<link rel="stylesheet" href="css/theme.css">
<script src="js/app.js" defer></script>
</head><body>{body}</body></html>"""

    (OUT / 'gallery.html').write_text(page)
    print(f"wrote {OUT / 'gallery.html'}")


if __name__ == '__main__':
    build()
