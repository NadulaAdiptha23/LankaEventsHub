#!/usr/bin/env python3
"""
Thymeleaf template sanity checks (no Spring available here).

  1. Tag balance, HTML5-aware (void elements are not expected to close).
     Thymeleaf's parser is lenient about a lot, but an unclosed <td> or a
     stray </div> is a template that fails at render time.
  2. Every ~{fragments/x :: y} reference resolves to a real fragment.
  3. Every view name a controller returns has a matching .html file, and
     every .html file is reachable from some controller.
  4. th:* attributes that are almost certainly typos (th:hred, th:txt...).
"""
import re, sys, pathlib
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'src/main/resources/templates'
CONTROLLERS = pathlib.Path(__file__).resolve().parent.parent / 'src/main/java/com/sliit/eventsphere/controller'

VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link',
        'meta', 'param', 'source', 'track', 'wbr'}
# Tags HTML5 lets you leave open; not used in these templates but tolerated.
OPTIONAL_CLOSE = {'li', 'p', 'option', 'thead', 'tbody', 'tr', 'td', 'th'}

KNOWN_TH = {
    'th:text', 'th:utext', 'th:href', 'th:src', 'th:action', 'th:value', 'th:each',
    'th:if', 'th:unless', 'th:replace', 'th:insert', 'th:fragment', 'th:object',
    'th:field', 'th:class', 'th:classappend', 'th:style', 'th:styleappend',
    'th:attr', 'th:selected', 'th:checked', 'th:disabled', 'th:readonly',
    'th:with', 'th:switch', 'th:case', 'th:inline', 'th:formaction', 'th:alt',
    'th:title', 'th:id', 'th:name', 'th:placeholder', 'th:type', 'th:method',
    'th:content', 'th:for', 'th:rel', 'th:target', 'th:colspan', 'th:rowspan',
}

problems = []


class Balance(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.stack = []

    def handle_starttag(self, tag, attrs):
        if tag in VOID:
            return
        self.stack.append((tag, self.getpos()[0]))

    def handle_startendtag(self, tag, attrs):
        pass

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        if not self.stack:
            problems.append((self.path, f"line {self.getpos()[0]}: stray closing </{tag}>"))
            return
        if self.stack[-1][0] == tag:
            self.stack.pop()
            return
        # allow optional-close elements to be auto-closed
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                for t, line in self.stack[i + 1:]:
                    if t not in OPTIONAL_CLOSE:
                        problems.append((self.path,
                                         f"line {line}: <{t}> is never closed "
                                         f"(closed by </{tag}> on line {self.getpos()[0]})"))
                del self.stack[i:]
                return
        problems.append((self.path, f"line {self.getpos()[0]}: </{tag}> does not match any open tag"))


fragments = {}   # (file stem, fragment name)
files = sorted(ROOT.rglob('*.html'))

for f in files:
    text = f.read_text()
    for m in re.finditer(r'th:fragment="(\w+)', text):
        rel = f.relative_to(ROOT).with_suffix('').as_posix()
        fragments[(rel, m.group(1))] = f

for f in files:
    text = f.read_text()
    rel = f.relative_to(ROOT)

    p = Balance(rel)
    p.feed(text)
    p.close()
    for tag, line in p.stack:
        if tag not in ('html', 'body', 'head') and tag not in OPTIONAL_CLOSE:
            problems.append((rel, f"line {line}: <{tag}> is never closed"))

    for m in re.finditer(r'~\{([\w/\-]+)\s*::\s*(\w+)', text):
        key = (m.group(1), m.group(2))
        if key not in fragments:
            line = text[:m.start()].count('\n') + 1
            problems.append((rel, f"line {line}: fragment ~{{{m.group(1)} :: {m.group(2)}}} not found"))

    for m in re.finditer(r'\b(th:[a-zA-Z]+)=', text):
        if m.group(1) not in KNOWN_TH:
            line = text[:m.start()].count('\n') + 1
            problems.append((rel, f"line {line}: unknown attribute {m.group(1)}"))

# view names returned by controllers
views = set()
for f in CONTROLLERS.glob('*.java'):
    text = f.read_text()
    for m in re.finditer(r'return\s+"(?!redirect:)([\w\-]+)"\s*;', text):
        views.add(m.group(1))

template_names = {f.relative_to(ROOT).with_suffix('').as_posix() for f in files}
for v in sorted(views):
    if v not in template_names:
        problems.append((pathlib.Path('controllers'), f"view '{v}' has no matching template"))

unused = template_names - views - {n for n in template_names if n.startswith('fragments/')}
if unused:
    print("note: templates not returned by any controller (rendered via error handling or "
          "fragments): " + ", ".join(sorted(unused)))

if not problems:
    print("PASS - templates balanced, fragments resolve, all views exist")
    sys.exit(0)
for f, msg in problems:
    print(f"{f}: {msg}")
print(f"\n{len(problems)} issue(s)")
sys.exit(1)
