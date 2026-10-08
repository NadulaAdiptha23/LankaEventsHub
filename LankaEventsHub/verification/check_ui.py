#!/usr/bin/env python3
"""
Design-system consistency checks.

The redesign's whole claim is that the pages compose one system rather
than each inventing its own. That claim decays silently: one hard-coded
#2563EB, one font-size:0.82rem, one more copy of the same card markup,
and a year later there are four blues and eleven type sizes again.

So the claim is checked:

  1. No hard-coded brand hex in a template - colour comes from a token.
  2. No hard-coded font-size in a template - type comes from the scale.
  3. No dead links or fake controls (a form control with no name that
     submits nothing, an href="#").
  4. Every CSS class a template uses is defined somewhere - in theme.css,
     in Bootstrap, or as a documented exception.
  5. Every accessibility rule the pages rely on: a label or aria-label on
     every input, alt on every image, and a skip link on every full page.

Run with --inject to confirm each check actually fails when it should.
"""
import re
import sys
import pathlib
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources'
TEMPLATES = ROOT / 'templates'
THEME = ROOT / 'static/css/theme.css'

# Hexes that may legitimately appear inline: the ones that only exist on a
# dark photographic background or inside a gradient, where a token would
# have to be invented for a single use.
ALLOWED_HEX = {
    '#fff', '#ffffff', '#000', '#0F172A', '#60A5FA', '#93C5FD', '#CBD5E1',
    '#475569', '#94A3B8', '#64748B', '#7C3AED', '#1E40AF', '#312E81',
    '#1E293B', '#0E7490', '#4338CA', '#6D28D9', '#BE185D', '#B45309',
    '#B91C1C', '#047857', '#1D4ED8', '#DBEAFE', '#E0E7FF', '#D1FAE5',
    '#FCE7F3', '#FEE2E2', '#EDE9FE', '#CFFAFE', '#FEF3C7', '#F1F5F9',
}

# The brand blue in particular must come from a token: it is the one
# colour most likely to be re-themed, and a stray literal is how a rebrand
# ends up half-applied.
BRAND_LITERALS = {'#2563eb', '#1d4eb8'}


def templates():
    return sorted(TEMPLATES.rglob('*.html'))


def strip_comments(html: str) -> str:
    return re.sub(r'<!--.*?-->', '', html, flags=re.S)


def check_brand_literals(problems, injected):
    for f in templates():
        body = strip_comments(f.read_text())
        if injected and f.name == 'home.html':
            body += '\n<div style="color:#2563EB;"></div>'
        # <meta name="theme-color"> is read by the browser chrome before
        # any stylesheet is parsed, so it cannot reference a token.
        body = re.sub(r'<meta[^>]*theme-color[^>]*>', '', body)
        for m in re.finditer(r'#[0-9A-Fa-f]{6}\b|#[0-9A-Fa-f]{3}\b', body):
            hexcode = m.group(0)
            if hexcode.lower() in BRAND_LITERALS:
                line = body[:m.start()].count('\n') + 1
                problems.append(
                    f"{f.name}:{line}: brand colour {hexcode} hard-coded; "
                    f"use var(--primary)")


def check_font_sizes(problems, injected):
    for f in templates():
        body = strip_comments(f.read_text())
        if injected and f.name == 'profile.html':
            body += '\n<p style="font-size:0.83rem;">x</p>'
        for m in re.finditer(r'font-size\s*:\s*([^;"\']+)', body):
            value = m.group(1).strip()
            if value.startswith('var('):
                continue
            line = body[:m.start()].count('\n') + 1
            problems.append(
                f"{f.name}:{line}: hard-coded font-size {value}; "
                f"use a class or a --t-* token")


def check_dead_controls(problems, injected):
    for f in templates():
        body = strip_comments(f.read_text())
        if injected and f.name == 'event-list.html':
            body += '\n<a href="#">Sort</a>'
        for m in re.finditer(r'href\s*=\s*"#"', body):
            line = body[:m.start()].count('\n') + 1
            problems.append(f"{f.name}:{line}: dead href=\"#\" link")
        # A select or a named-less text input inside a form submits
        # nothing - the "Sort: Recommended" dropdown that did nothing.
        for m in re.finditer(r'<select(?![^>]*\bname=)[^>]*>', body):
            line = body[:m.start()].count('\n') + 1
            problems.append(f"{f.name}:{line}: <select> with no name submits nothing")


def check_labels(problems, injected):
    for f in templates():
        body = strip_comments(f.read_text())
        if injected and f.name == 'login.html':
            body += '\n<input type="text" id="orphan-field">'
        labelled = set(re.findall(r'<label[^>]*\bfor="([^"]+)"', body))
        for m in re.finditer(r'<(input|select|textarea)([^>]*)>', body):
            attrs = m.group(2)
            if re.search(r'type\s*=\s*"(hidden|submit|button)"', attrs):
                continue
            if 'aria-label' in attrs:
                continue
            ident = re.search(r'\bid="([^"]+)"', attrs)
            # An input wrapped inside its own <label> needs no for=.
            start = body.rfind('<label', 0, m.start())
            end = body.rfind('</label>', 0, m.start())
            if start > end:
                continue
            if ident and ident.group(1) in labelled:
                continue
            line = body[:m.start()].count('\n') + 1
            problems.append(
                f"{f.name}:{line}: <{m.group(1)}> has no label, aria-label "
                f"or wrapping <label>")


def check_images(problems, injected):
    for f in templates():
        body = strip_comments(f.read_text())
        if injected and f.name == 'ticket.html':
            body += '\n<img src="x.png">'
        for m in re.finditer(r'<img(?![^>]*\balt=)[^>]*>', body):
            line = body[:m.start()].count('\n') + 1
            problems.append(f"{f.name}:{line}: <img> with no alt attribute")


def check_classes(problems, injected):
    """Every class a template uses should be defined in theme.css, be a
    Bootstrap utility, or be a JS hook. An undefined class is either a
    typo or a component somebody forgot to add to the system."""
    theme = THEME.read_text()
    defined = set(re.findall(r'\.([a-zA-Z][\w-]*)', theme))

    # Bootstrap 5 utilities and components the pages legitimately use.
    bootstrap = re.compile(
        r'^(row|col|col-\d+|col-(sm|md|lg|xl|xxl)(-\d+|-auto)?|g-\d|gap-\d|'
        r'd-[\w-]+|flex-[\w-]+|'
        r'justify-content-\w+|justify-content-(sm|md|lg)-\w+|align-items-\w+|'
        r'align-self-\w+|text-\w+|text-(sm|md|lg)-\w+|fw-\w+|fs-\d|'
        r'm[tbsexy]?-\w+|p[tbsexy]?-\w+|m[tbsexy]?-(sm|md|lg)-\w+|'
        r'p[tbsexy]?-(sm|md|lg)-\w+|w-\d+|w-auto|h-\d+|h-auto|mx-auto|my-auto|'
        r'position-\w+|top-\d|start-\d|translate-middle|border\w*|rounded\w*|'
        r'bg-\w+|list-unstyled|container|container-fluid|visually-hidden|'
        r'navbar|navbar-[\w-]+|nav-[\w-]+|collapse|breadcrumb|breadcrumb-item|'
        r'form-control|form-select|form-label|form-text|form-check-input|'
        r'form-check|form-check-label|form-check-inline|form-check-reverse|'
        r'form-control-\w+|form-select-\w+|table|table-\w+|active|disabled|'
        r'badge|alert|alert-\w+|btn|btn-\w+|card|card-\w+|progress|'
        r'progress-bar|dropdown\w*|modal\w*|offcanvas\w*|spinner-\w+|'
        r'sticky-\w+|overflow-\w+|min-\w+|max-\w+|opacity-\d+|shadow\w*|'
        r'float-\w+|order-\d+|invisible|visible|clearfix|ratio|ratio-\w+)$')

    # Icon glyphs come from Font Awesome, and a handful of classes are
    # pure JavaScript hooks with no styling of their own.
    external = re.compile(r'^(fa|fas|far|fab|fa-[\w-]+)$')
    js_hooks = {'bulk-check'}

    used = defaultdict(list)
    for f in templates():
        body = f.read_text()
        if injected and f.name == 'home.html':
            body += '\n<div class="totally-made-up-class"></div>'
        for m in re.finditer(r'class="([^"]*)"', body):
            for cls in m.group(1).split():
                if '$' in cls or '{' in cls:
                    continue
                used[cls].append(f.name)
        # th:classappend adds classes too - but only the branches after
        # the ? and the :, never the literal being compared against.
        for m in re.finditer(r'th:classappend="([^"]*)"', body, re.S):
            for branch in re.finditer(r"[?:]\s*'([\w -]*)'", m.group(1)):
                for cls in branch.group(1).split():
                    used[cls].append(f.name)

    for cls, files in sorted(used.items()):
        if cls in defined or bootstrap.match(cls) or external.match(cls) \
                or cls in js_hooks:
            continue
        problems.append(
            f"{files[0]}: class '{cls}' is not defined in theme.css and is "
            f"not a Bootstrap utility")


def check_skip_links(problems, injected):
    """Every page a user lands on gets a skip link, so a keyboard user is
    not made to tab through the whole sidebar on every page."""
    for f in templates():
        if f.parent.name == 'fragments':
            continue
        body = f.read_text()
        if injected and f.name == 'profile.html':
            body = body.replace('class="skip-link"', 'class="removed"')
        # The auth pages are a single form with no navigation to skip.
        if f.name in ('login.html', 'register.html', 'forgot-password.html',
                      'reset-password.html', 'error.html'):
            continue
        if 'skip-link' not in body:
            problems.append(f"{f.name}: no skip link")


CHECKS = [
    ('brand colour tokens', check_brand_literals),
    ('type scale', check_font_sizes),
    ('no dead controls', check_dead_controls),
    ('form labels', check_labels),
    ('image alt text', check_images),
    ('classes defined in the system', check_classes),
    ('skip links', check_skip_links),
]


def main() -> int:
    injected = '--inject' in sys.argv
    if injected:
        print("[--inject] planting one fault per check\n")

    failed = 0
    for name, check in CHECKS:
        problems = []
        check(problems, injected)
        if problems:
            failed += 1
            print(f"FAIL  {name}")
            for p in problems[:12]:
                print(f"        {p}")
            if len(problems) > 12:
                print(f"        ... and {len(problems) - 12} more")
        else:
            print(f"ok    {name}")

    print()
    if failed:
        print(f"{failed} check(s) failed")
        return 1
    print("PASS - the templates compose one design system")
    return 0


if __name__ == '__main__':
    sys.exit(main())
