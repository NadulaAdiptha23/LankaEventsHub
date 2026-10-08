#!/usr/bin/env python3
"""
Extracts the *functional surface* of the Thymeleaf templates.

The UI redesign is allowed to change every pixel and must change no
behaviour. "Behaviour" here is a concrete, checkable list:

  - form action + method + the name of every field submitted
  - every th:href / th:src / th:action URL expression
  - every sec:authorize expression
  - every model attribute the page reads
  - every fragment it includes and every id JavaScript reaches for

Run with --save before touching anything, then --check afterwards. The
diff is the evidence that a purely visual change stayed purely visual.
A field silently renamed, a POST turned into a GET, or an authorization
check dropped while restyling a page all show up here as a removal.
"""
import json
import re
import sys
import pathlib

PROJECT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATES = PROJECT / 'src/main/resources/templates'
SNAPSHOT = pathlib.Path(__file__).resolve().parent / 'surface.json'

SINGULAR = {
    'urls': 'url', 'authorize': 'authorization rule', 'fields': 'field',
    'fragments': 'fragment', 'js_ids': 'js_id', 'model': 'model',
}

TAG_RE = re.compile(r'<(\w+)([^>]*)>', re.S)
ATTR_RE = re.compile(r'([\w:.-]+)\s*=\s*"([^"]*)"', re.S)


def tags(html: str):
    for m in TAG_RE.finditer(html):
        yield m.group(1).lower(), dict(ATTR_RE.findall(m.group(2)))


def surface_of(html: str) -> dict:
    forms = []
    urls = set()
    authorize = set()
    fields = set()
    fragments = set()
    js_ids = set()

    # Forms, with the fields that sit inside them. Templates here never
    # nest forms, so a simple span scan is accurate.
    for fm in re.finditer(r'<form([^>]*)>(.*?)</form>', html, re.S):
        attrs = dict(ATTR_RE.findall(fm.group(1)))
        body = fm.group(2)
        names = sorted({
            a.get('name') for _t, a in tags(body) if a.get('name')
        })
        forms.append({
            'action': attrs.get('th:action') or attrs.get('action') or '',
            'method': (attrs.get('method') or 'get').lower(),
            'fields': names,
        })

    for _t, attrs in tags(html):
        for key in ('th:href', 'th:action', 'th:src', 'href', 'src'):
            val = attrs.get(key)
            # Only Thymeleaf link expressions and site-relative links are
            # behaviour; a CDN stylesheet is decoration.
            if val and (val.startswith('@{') or val.startswith('/')):
                urls.add(val)
        if 'sec:authorize' in attrs:
            authorize.add(attrs['sec:authorize'])
        if attrs.get('name'):
            fields.add(attrs['name'])
        for key in ('th:replace', 'th:insert'):
            if attrs.get(key):
                fragments.add(attrs[key])

    # Ids that the page's own JavaScript looks up - renaming one of these
    # while restyling silently breaks the script.
    for m in re.finditer(r'getElementById\([\'"]([\w-]+)[\'"]\)', html):
        js_ids.add(m.group(1))
    for m in re.finditer(r'querySelector\([\'"]([^\'"]+)[\'"]\)', html):
        js_ids.add(m.group(1))

    # Model attributes: ${foo...} and ${foo.bar}
    model = set()
    for m in re.finditer(r'\$\{\s*#?([a-zA-Z_]\w*)', html):
        name = m.group(1)
        if name not in ('_csrf',):
            model.add(name)

    return {
        'forms': sorted(forms, key=lambda f: (f['action'], f['method'])),
        'urls': sorted(urls),
        'authorize': sorted(authorize),
        'fields': sorted(fields),
        'fragments': sorted(fragments),
        'js_ids': sorted(js_ids),
        'model': sorted(model),
    }


def collect() -> dict:
    out = {}
    for f in sorted(TEMPLATES.rglob('*.html')):
        rel = str(f.relative_to(TEMPLATES))
        out[rel] = surface_of(f.read_text())
    return out


# ---------------------------------------------------------------------
# Changes that are deliberate.
#
# The point of this file is that an unexplained removal fails. A removal
# that was the whole point of the change belongs here, with the reason -
# so the next person reads why rather than deleting the check because it
# is noisy. Anything not on this list is a regression.
# ---------------------------------------------------------------------
ACCEPTED = {
    ('admin-reports.html', 'LOST js_id: categoryChart'):
        'Chart.js canvas replaced by a CSS bar chart drawn from the same '
        'summary data - one fewer third-party script on every admin page load.',
    ('admin-reports.html', 'LOST js_id: regChart'):
        'Same: the registrations chart is now CSS, not Chart.js.',
    ('admin-reports.html', 'LOST model: e'):
        'False positive - "e" was a lambda parameter inside the old chart script.',
    ('event-detail.html', 'LOST model: iter'):
        'False positive - "iter" was a th:each loop status variable.',
    ('register.html', 'LOST model: t'):
        'False positive - "t" was the loop variable over the removed '
        'fabricated testimonials.',
    ('event-list.html', 'LOST model: keyword'):
        'The three loose request attributes were replaced by one bound '
        'EventSearchCriteria; the query parameter is still named keyword.',
    ('event-list.html', 'LOST model: venue'):
        'Same: now read as criteria.venue. The URL contract is unchanged.',
    ('event-list.html', 'LOST url: @{/events/{id}(id=${event.id})}'):
        'Moved into fragments/components :: eventCard, which this page now '
        'renders for every result.',
    ('home.html', 'LOST url: @{/events/{id}(id=${event.id})}'):
        'Same - the home page uses the shared event card too.',
    ('home.html', 'LOST field: date'):
        'The old hero search submitted date=, which no controller has ever '
        'read. Replaced by dateFrom, which the search now actually filters on.',
    ('home.html', 'form @{/events} LOST field: date'):
        'Same dead parameter as above.',
    ('login.html', "LOST fragment: ~{fragments/head :: head('Login')}"):
        'Page title changed from "Login" to "Sign in". Same fragment.',
    ('register.html', "LOST fragment: ~{fragments/head :: head('Register')}"):
        'Page title changed to "Create an account". Same fragment.',
    ('my-registrations.html', "LOST fragment: ~{fragments/sidebar :: side('registrations')}"):
        'Now passes side(\'dashboard\'); the sidebar treats dashboard and '
        'registrations as the same active item, so the highlight is unchanged.',
}


def diff(before: dict, after: dict) -> int:
    problems = []
    accepted = []
    notes = []

    for page in sorted(set(before) | set(after)):
        if page not in after:
            problems.append((page, "TEMPLATE DELETED"))
            continue
        if page not in before:
            notes.append(f"{page}: new template")
            continue
        b, a = before[page], after[page]

        for key in ('urls', 'authorize', 'fields', 'fragments', 'js_ids', 'model'):
            lost = sorted(set(b[key]) - set(a[key]))
            gained = sorted(set(a[key]) - set(b[key]))
            for item in lost:
                problems.append((page, f"LOST {SINGULAR[key]}: {item}"))
            for item in gained:
                notes.append(f"{page}: added {key[:-1]}: {item}")

        # A form is matched by its action; its method and field set must
        # survive verbatim.
        b_forms = {f['action']: f for f in b['forms']}
        a_forms = {f['action']: f for f in a['forms']}
        for action, bf in b_forms.items():
            af = a_forms.get(action)
            if af is None:
                problems.append((page, f"LOST form action: {action}"))
                continue
            if af['method'] != bf['method']:
                problems.append((page, f"form {action} method changed "
                                       f"{bf['method']} -> {af['method']}"))
            lost_fields = sorted(set(bf['fields']) - set(af['fields']))
            for field in lost_fields:
                problems.append((page, f"form {action} LOST field: {field}"))
        for action in sorted(set(a_forms) - set(b_forms)):
            notes.append(f"{page}: added form action: {action}")

    # Split the deliberate changes out of the failures.
    remaining = []
    for page, message in problems:
        reason = ACCEPTED.get((page, message))
        if reason:
            accepted.append((page, message, reason))
        else:
            remaining.append((page, message))
    problems = remaining

    for note in notes:
        print(f"  + {note}")
    if notes:
        print()

    if accepted:
        print(f"{len(accepted)} accepted change(s):")
        for page, message, reason in accepted:
            print(f"  ~ {page}: {message}")
            print(f"      {reason}")
        print()

    if problems:
        for page, message in problems:
            print(f"  ! {page}: {message}")
        print(f"\n{len(problems)} functional regression(s)")
        return 1

    print("PASS - every form, field, URL, authorization rule, fragment, "
          "script hook and model reference survived the redesign")
    return 0


def main() -> int:
    if '--save' in sys.argv:
        data = collect()
        SNAPSHOT.write_text(json.dumps(data, indent=1, sort_keys=True))
        pages = len(data)
        forms = sum(len(v['forms']) for v in data.values())
        urls = len({u for v in data.values() for u in v['urls']})
        rules = len({r for v in data.values() for r in v['authorize']})
        print(f"saved {SNAPSHOT}: {pages} templates, {forms} forms, "
              f"{urls} distinct URLs, {rules} authorization rules")
        return 0

    if not SNAPSHOT.exists():
        print("no snapshot; run with --save first")
        return 2
    before = json.loads(SNAPSHOT.read_text())
    after = collect()
    if '--inject' in sys.argv:
        # Prove the diff actually fires: drop a field and flip a method.
        page = 'login.html'
        if page in after and after[page]['forms']:
            after[page]['forms'][0]['fields'] = [
                f for f in after[page]['forms'][0]['fields'] if f != 'password']
            after[page]['forms'][0]['method'] = 'get'
        print("[--inject] removed the password field and made login a GET\n")
    return diff(before, after)


if __name__ == '__main__':
    sys.exit(main())
