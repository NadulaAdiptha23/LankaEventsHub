/* =====================================================================
   Verdant Events — shared UI behaviour
   ---------------------------------------------------------------------
   The small amount of JavaScript the whole site shares: toasts, action
   menus, the mobile navigation drawer, the filter sheet, and busy states
   on buttons that submit.

   Two rules this file follows:
     - It is progressive. Every control it enhances is a real link, form
       or button that already works with JavaScript switched off; nothing
       here is load-bearing for a server round trip.
     - It never changes what gets submitted. It disables a button after
       the browser has begun the submit, never before, and it never
       rewrites an action, a method or a field value.
   ===================================================================== */
(function () {
  'use strict';

  /* ---------------------------------------------------------------
     Toasts
     Successful actions are announced here rather than as a large alert
     box that pushes the page down. Anything the user must read and act
     on stays an inline alert - see fragments/alerts.html.
     --------------------------------------------------------------- */
  var ICONS = {
    success: 'fa-circle-check',
    error: 'fa-circle-exclamation',
    warning: 'fa-triangle-exclamation',
    info: 'fa-circle-info'
  };

  function stack() {
    var el = document.querySelector('.toast-stack');
    if (!el) {
      el = document.createElement('div');
      el.className = 'toast-stack';
      // Announced politely: a confirmation should reach a screen reader
      // without interrupting whatever it is currently reading.
      el.setAttribute('aria-live', 'polite');
      el.setAttribute('aria-atomic', 'false');
      document.body.appendChild(el);
    }
    return el;
  }

  function toast(message, type, timeout) {
    if (!message) { return; }
    var kind = ICONS[type] ? type : 'info';
    var item = document.createElement('div');
    item.className = 'toast-item toast-' + kind;
    item.setAttribute('role', kind === 'error' ? 'alert' : 'status');

    var icon = document.createElement('i');
    icon.className = 'fa-solid ' + ICONS[kind] + ' toast-item__icon';
    icon.setAttribute('aria-hidden', 'true');

    var body = document.createElement('div');
    body.className = 'toast-item__body';
    // textContent, not innerHTML: this text can come from a server
    // message that included user input.
    body.textContent = message;

    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'toast-item__close';
    close.setAttribute('aria-label', 'Dismiss notification');
    close.innerHTML = '<i class="fa-solid fa-xmark" aria-hidden="true"></i>';

    item.appendChild(icon);
    item.appendChild(body);
    item.appendChild(close);
    stack().appendChild(item);

    var timer = setTimeout(dismiss, timeout || (kind === 'error' ? 8000 : 4500));
    close.addEventListener('click', function () { clearTimeout(timer); dismiss(); });

    function dismiss() {
      item.classList.add('is-leaving');
      setTimeout(function () { item.remove(); }, 220);
    }
  }

  window.VerdantEvents = window.VerdantEvents || {};
  window.VerdantEvents.toast = toast;

  /* ---------------------------------------------------------------
     Server-rendered flash messages become toasts.
     The server still renders them into the page, so they are present
     without JavaScript; this just moves them into the stack.
     --------------------------------------------------------------- */
  function promoteFlashes() {
    document.querySelectorAll('[data-flash]').forEach(function (el) {
      toast(el.textContent.trim(), el.getAttribute('data-flash'));
      el.remove();
    });
  }

  /* ---------------------------------------------------------------
     Action menus (the ⋮ in a table row)
     --------------------------------------------------------------- */
  function closeAllMenus(except) {
    document.querySelectorAll('.action-menu.is-open').forEach(function (menu) {
      if (menu === except) { return; }
      menu.classList.remove('is-open');
      unplaceMenu(menu);
      var trigger = menu.querySelector('.action-menu__trigger');
      if (trigger) { trigger.setAttribute('aria-expanded', 'false'); }
    });
  }

  /*
     Every action menu lives inside a table, and .table-responsive is a
     scroll container - overflow-x:auto makes the block clip on BOTH axes,
     so an absolutely-positioned menu gets cut off at the bottom of the
     table. (Found by rendering the page and looking at it; no amount of
     reading the markup shows this.)

     The fix is to take the open menu out of the clipping context: it is
     positioned fixed, against the trigger's viewport rectangle, and
     flipped above the trigger when there is not enough room below. On
     close the inline styles are removed so the CSS is authoritative
     again.
  */
  function placeMenu(menu, trigger) {
    var list = menu.querySelector('.action-menu__list');
    if (!list) { return; }
    var rect = trigger.getBoundingClientRect();
    list.style.position = 'fixed';
    list.style.left = 'auto';
    list.style.right = (window.innerWidth - rect.right) + 'px';
    list.style.top = (rect.bottom + 6) + 'px';

    // Measured after the class is applied, so the menu has a height.
    var height = list.offsetHeight;
    if (rect.bottom + 6 + height > window.innerHeight - 8) {
      list.style.top = Math.max(8, rect.top - 6 - height) + 'px';
    }
  }

  function unplaceMenu(menu) {
    var list = menu.querySelector('.action-menu__list');
    if (!list) { return; }
    list.style.position = '';
    list.style.top = '';
    list.style.left = '';
    list.style.right = '';
  }

  function initActionMenus() {
    document.addEventListener('click', function (event) {
      var trigger = event.target.closest('.action-menu__trigger');
      if (trigger) {
        var menu = trigger.closest('.action-menu');
        var willOpen = !menu.classList.contains('is-open');
        closeAllMenus(menu);
        menu.classList.toggle('is-open', willOpen);
        trigger.setAttribute('aria-expanded', String(willOpen));
        if (willOpen) { placeMenu(menu, trigger); } else { unplaceMenu(menu); }
        event.stopPropagation();
        return;
      }
      // A click anywhere else closes any open menu - including inside
      // one, since every item in a menu navigates or submits.
      closeAllMenus(null);
    });

    // A fixed-position menu cannot follow the page, so any scroll or
    // resize closes it rather than leaving it stranded mid-screen.
    window.addEventListener('scroll', function () { closeAllMenus(null); }, true);
    window.addEventListener('resize', function () { closeAllMenus(null); });

    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') { return; }
      var open = document.querySelector('.action-menu.is-open');
      if (!open) { return; }
      closeAllMenus(null);
      var trigger = open.querySelector('.action-menu__trigger');
      // Focus goes back to the control that opened the menu, or it is
      // lost to the top of the document.
      if (trigger) { trigger.focus(); }
    });
  }

  /* ---------------------------------------------------------------
     Mobile navigation drawer
     --------------------------------------------------------------- */
  function initDrawer() {
    var toggle = document.querySelector('[data-drawer-toggle]');
    if (!toggle) { return; }

    var backdrop = document.querySelector('.drawer-backdrop');
    if (!backdrop) {
      backdrop = document.createElement('div');
      backdrop.className = 'drawer-backdrop';
      document.body.appendChild(backdrop);
    }

    function setOpen(open) {
      document.body.classList.toggle('drawer-open', open);
      toggle.setAttribute('aria-expanded', String(open));
      if (open) {
        var first = document.querySelector('.sidebar__nav a');
        if (first) { first.focus(); }
      } else {
        toggle.focus();
      }
    }

    toggle.addEventListener('click', function () {
      setOpen(!document.body.classList.contains('drawer-open'));
    });
    backdrop.addEventListener('click', function () { setOpen(false); });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && document.body.classList.contains('drawer-open')) {
        setOpen(false);
      }
    });
    // Returning to a desktop width must not leave the page scroll-locked
    // by a drawer that is no longer visible.
    window.addEventListener('resize', function () {
      if (window.innerWidth >= 992) { document.body.classList.remove('drawer-open'); }
    });
  }

  /* ---------------------------------------------------------------
     Filter sheet (event listing, under 992px)
     --------------------------------------------------------------- */
  function initFilterSheet() {
    var open = document.querySelector('[data-filters-open]');
    var close = document.querySelector('[data-filters-close]');
    if (!open) { return; }

    function setOpen(state) {
      document.body.classList.toggle('filters-open', state);
      open.setAttribute('aria-expanded', String(state));
    }
    open.addEventListener('click', function () { setOpen(true); });
    if (close) { close.addEventListener('click', function () { setOpen(false); }); }
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { setOpen(false); }
    });
    window.addEventListener('resize', function () {
      if (window.innerWidth >= 992) { document.body.classList.remove('filters-open'); }
    });
  }

  /* ---------------------------------------------------------------
     Busy state on submit
     Marked with data-busy on the form. The button is disabled only
     after the browser has already started submitting, so its value
     still reaches the server - disabling it any earlier would drop a
     named submit button from the request.
     --------------------------------------------------------------- */
  /* ---------------------------------------------------------------
     Permanent deletes: the submit stays disabled until the name is
     typed back exactly.

     A guard against a slip, NOT a security control - nothing on the
     server asks for the typed name, and it could not, because anybody
     can edit this page. What protects those endpoints is that they are
     administrator-only and audited, in PurgeService. This is here so
     that deleting an event and its four hundred bookings takes more
     than one mis-aimed click.

     With JavaScript off the button is simply enabled from the start,
     which is the same form every other screen submits.
     --------------------------------------------------------------- */
  function initPurgeForms() {
    document.querySelectorAll('form[data-purge]').forEach(function (form) {
      var field = form.querySelector('[data-purge-name]');
      var button = form.querySelector('[data-purge-submit]');
      if (!field || !button) { return; }

      var expected = (field.getAttribute('data-expected') || '').trim();
      function sync() {
        // Case-insensitive and trimmed: the point is to make somebody
        // read the name, not to test their typing.
        button.disabled = field.value.trim().toLowerCase() !== expected.toLowerCase();
      }
      field.addEventListener('input', sync);
      sync();
    });
  }

  function initBusyForms() {
    document.querySelectorAll('form[data-busy]').forEach(function (form) {
      form.addEventListener('submit', function () {
        if (form.getAttribute('novalidate') === null && !form.checkValidity()) {
          return;   // the browser is about to show its own validation
        }
        var button = form.querySelector('[type="submit"]');
        if (!button || button.classList.contains('is-busy')) { return; }
        setTimeout(function () {
          button.classList.add('is-busy');
          button.setAttribute('aria-busy', 'true');
        }, 0);
      });
    });
  }

  /* ---------------------------------------------------------------
     Active navigation state.

     The server already marks the active link from the `active` argument
     passed to the navbar fragment, which is the version that is correct
     on a page whose URL does not match its section. This only fills in
     the gap for any page that did not pass one - it never removes a
     class the server set.
     --------------------------------------------------------------- */
  function initActiveNav() {
    var nav = document.querySelector('.navbar-public');
    if (!nav || nav.querySelector('.nav-link.active')) { return; }
    var here = window.location.pathname;
    nav.querySelectorAll('.nav-link').forEach(function (link) {
      var href = link.getAttribute('href');
      if (!href) { return; }
      var match = href === '/' ? here === '/' : here.indexOf(href) === 0;
      if (match) { link.classList.add('active'); }
    });
  }

  /* ---------------------------------------------------------------
     Navbar scroll state.

     One class, toggled past a threshold. Everything visual lives in
     the stylesheet, so this cannot disagree with the design.

     Three details that matter:

     - the listener is passive and does no layout reads, so it cannot
       make the page scroll janky;
     - the work is deferred to requestAnimationFrame and guarded by a
       `ticking` flag, so a burst of scroll events produces one class
       check per frame rather than sixty;
     - the state is applied ONCE on load as well, because a browser
       that restores a scroll position on refresh would otherwise show
       a transparent bar half way down the page.

     A page rendered with navbar-public--solid has no transparent state
     to leave, so it is skipped entirely.
     --------------------------------------------------------------- */
  function initNavbarScroll() {
    var nav = document.querySelector('.navbar-public');
    if (!nav || nav.classList.contains('navbar-public--solid')) { return; }

    var THRESHOLD = 24;
    var ticking = false;

    function apply() {
      nav.classList.toggle('is-solid', window.scrollY > THRESHOLD);
      ticking = false;
    }
    function onScroll() {
      if (ticking) { return; }
      ticking = true;
      window.requestAnimationFrame(apply);
    }

    apply();
    window.addEventListener('scroll', onScroll, { passive: true });
  }

  /* ---------------------------------------------------------------
     Clicking the wordmark while already on the homepage.

     The anchor points at "/" so it works as a plain link from every
     other page and with JavaScript off. Here - and only here - a click
     on the page it already points at is turned into a scroll to the
     top, because reloading the page somebody is already looking at is
     not what they asked for.

     prefers-reduced-motion is respected: that visitor gets an instant
     jump rather than a smooth one.
     --------------------------------------------------------------- */
  function initBrandToTop() {
    var brand = document.querySelector('.navbar-public .navbar-brand');
    if (!brand) { return; }

    brand.addEventListener('click', function (event) {
      if (window.location.pathname !== '/') { return; }   // let the link do its job
      if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) { return; }
      event.preventDefault();
      var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      window.scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' });
      brand.blur();
    });
  }

  /* ---------------------------------------------------------------
     Section reveal.

     Elements marked data-reveal fade and rise once, the first time
     they come into view. IntersectionObserver rather than a scroll
     handler, and each element is unobserved after it fires, so the
     cost is bounded by the number of sections and not by scrolling.

     Everything is visible with no JavaScript and for anyone who asked
     for reduced motion: the class that hides them is only ever added
     BY this function, so its absence is the safe state.
     --------------------------------------------------------------- */
  function initReveal() {
    var items = document.querySelectorAll('[data-reveal]');
    if (!items.length) { return; }

    var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced || !('IntersectionObserver' in window)) { return; }

    items.forEach(function (el) { el.classList.add('reveal-pending'); });

    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) { return; }
        entry.target.classList.add('reveal-in');
        observer.unobserve(entry.target);
      });
    }, { rootMargin: '0px 0px -12% 0px', threshold: 0.08 });

    items.forEach(function (el) { observer.observe(el); });
  }

  /* ---------------------------------------------------------------
     In-page anchors, offset for the sticky navbar.

     scroll-margin-top in the stylesheet handles this for normal
     navigation; this exists so that a click on a homepage section link
     is smooth rather than instant, and so the URL still updates.
     --------------------------------------------------------------- */
  function initAnchorScroll() {
    document.querySelectorAll('a[href^="#"]').forEach(function (link) {
      var id = link.getAttribute('href');
      if (!id || id === '#') { return; }
      link.addEventListener('click', function (event) {
        var target = document.querySelector(id);
        if (!target) { return; }
        event.preventDefault();
        var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        target.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'start' });
        history.replaceState(null, '', id);
      });
    });
  }

  /* ---------------------------------------------------------------
     Broken images.

     One delegated listener rather than an inline onerror per <img>.
     Thymeleaf refuses to interpolate a variable into an event-handler
     attribute - correctly, it is an injection vector - so the fallback
     travels as data-fallback and is applied here.

     Uses capture, because `error` does not bubble.
     --------------------------------------------------------------- */
  function initImageFallbacks() {
    document.addEventListener('error', function (event) {
      var img = event.target;
      if (!img || img.tagName !== 'IMG') { return; }
      var fallback = img.getAttribute('data-fallback');
      // Guard against a loop if the fallback itself is what failed.
      if (!fallback || img.src.indexOf(fallback) !== -1) { return; }
      img.removeAttribute('data-fallback');
      img.src = fallback;
    }, true);
  }

  document.addEventListener('DOMContentLoaded', function () {
    initImageFallbacks();
    promoteFlashes();
    initActionMenus();
    initDrawer();
    initFilterSheet();
    initBusyForms();
    initPurgeForms();
    initActiveNav();
    initNavbarScroll();
    initBrandToTop();
    initReveal();
    initAnchorScroll();
  });
})();
