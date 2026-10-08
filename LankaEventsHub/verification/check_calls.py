#!/usr/bin/env python3
"""
Static cross-check for a project that cannot be compiled here.

1. Collects every type declared in the project and the methods it declares
   (name -> set of arities, including varargs-free overloads).
2. Collects every field declaration `private final Foo bar;` so a call
   `bar.method(...)` can be resolved to type Foo.
3. Flags call sites where the method name is unknown on that type, or the
   argument count matches no declared overload.

Also flags imports of project classes that do not exist.

This is not a compiler; it only catches the class of mistake that matters
most here - call sites left behind after a signature change.
"""
import re, sys, pathlib
from collections import defaultdict

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'src'
files = sorted(ROOT.rglob('*.java'))

TYPE_RE = re.compile(r'^\s*(?:public\s+|abstract\s+|final\s+)*(?:class|interface|enum|record)\s+(\w+)', re.M)
# class/interface X extends/implements Y (first supertype only - this
# project has no multi-interface implements list that matters for method
# resolution, and the common case, an abstract base class, is exactly
# what this exists to catch).
EXTENDS_RE = re.compile(r'\b(?:class|interface)\s+(\w+)\b[^{;]*?\b(?:extends|implements)\s+(\w+)', re.M)
# method declarations: modifiers returnType name(...)
METHOD_RE = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public|protected|private)\s+(?:static\s+|final\s+|abstract\s+|default\s+|synchronized\s+)*'
    r'(?:<[^>]+>\s+)?'
    r'[\w.<>\[\],? ]+?\s+(\w+)\s*\(([^)]*)\)\s*(?:throws [\w., ]+)?\s*[{;]', re.M)
# interface methods without modifier
IFACE_METHOD_RE = re.compile(
    r'^\s{4}(?!//|\*|@)(?:<[^>]+>\s+)?[\w.<>\[\],? ]+?\s+(\w+)\s*\(([^)]*)\)\s*;', re.M)
FIELD_RE = re.compile(r'^\s*private\s+(?:final\s+)?([A-Z]\w*)(?:<[^>]*>)?\s+(\w+)\s*(?:=|;)', re.M)
LOCAL_RE = re.compile(r'^\s+(?:final\s+)?([A-Z]\w*)(?:<[^>]*>)?\s+(\w+)\s*=\s*', re.M)
IMPORT_RE = re.compile(r'^import\s+(com\.sliit\.eventsphere\.[\w.]+);', re.M)

# Methods every project class inherits from Object / Throwable / Enum and
# therefore never declares itself.
INHERITED = {
    'getMessage', 'getLocalizedMessage', 'getCause', 'getStackTrace', 'printStackTrace',
    'toString', 'equals', 'hashCode', 'getClass', 'name', 'ordinal', 'compareTo', 'values',
    'valueOf', 'wait', 'notify', 'notifyAll',
}


def strip_literals(src: str) -> str:
    """Blank out comments and string/char literals so commas and braces
    inside them cannot be mistaken for code."""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i)); i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(''.join('\n' if ch == '\n' else ' ' for ch in src[i:j])); i = j
        elif c in '"\'':
            quote = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2; continue
                if src[j] == quote:
                    j += 1; break
                j += 1
            out.append('"' + ' ' * max(0, j - i - 2) + '"'); i = j
        else:
            out.append(c); i += 1
    return ''.join(out)


def arity(params: str) -> int:
    params = params.strip()
    if not params:
        return 0
    depth = 0
    n = 1
    for ch in params:
        if ch in '<([':
            depth += 1
        elif ch in '>)]':
            depth -= 1
        elif ch == ',' and depth == 0:
            n += 1
    return n


types = {}                        # simple name -> file
methods = defaultdict(lambda: defaultdict(set))   # type -> method -> {arity}
supers = defaultdict(list)        # type -> [direct supertypes declared in this project]
texts = {}

for f in files:
    t = strip_literals(f.read_text())
    texts[f] = t
    name = f.stem
    types[name] = f
    body = t
    for m in EXTENDS_RE.finditer(body):
        # Only the type this FILE declares - a nested class's own extends
        # clause would otherwise be attributed to the outer one.
        if m.group(1) == name:
            supers[name].append(m.group(2))
    for m in METHOD_RE.finditer(body):
        methods[name][m.group(1)].add(arity(m.group(2)))
    for m in IFACE_METHOD_RE.finditer(body):
        methods[name][m.group(1)].add(arity(m.group(2)))
    rec = re.search(r'\brecord\s+' + re.escape(name) + r'\s*\(([^)]*)\)', body)
    if rec:
        for comp in rec.group(1).split(','):
            comp = comp.strip()
            if comp:
                methods[name][comp.split()[-1]].add(0)

def effective_methods(typ, _seen=None):
    """Methods declared on typ plus everything it inherits from
    project-local supertypes.

    A supertype that is not one of this project's own types (a framework
    or JDK class) contributes nothing - the same "cannot see into it"
    limit every other framework type already has in this checker - rather
    than being reported as an error. _seen guards against a cycle.
    """
    _seen = _seen if _seen is not None else set()
    if typ in _seen:
        return {}
    _seen.add(typ)
    merged = defaultdict(set)
    for name, arities in methods.get(typ, {}).items():
        merged[name] |= arities
    for parent in supers.get(typ, []):
        if parent in types:
            for name, arities in effective_methods(parent, _seen).items():
                merged[name] |= arities
    return merged


problems = []

for f in files:
    t = texts[f]

    for m in IMPORT_RE.finditer(t):
        simple = m.group(1).rsplit('.', 1)[1]
        if simple not in types and simple != '*':
            problems.append((f, f"import of unknown project class: {m.group(1)}"))

    # variable -> declared type
    var_types = {}
    for m in FIELD_RE.finditer(t):
        var_types[m.group(2)] = m.group(1)
    for m in LOCAL_RE.finditer(t):
        var_types.setdefault(m.group(2), m.group(1))

    for var, typ in var_types.items():
        if typ not in methods:
            continue
        # (?<![\w.]) rather than \b: a variable is never the tail of a
        # longer dotted path. Without the "no preceding dot" half, the
        # package segment in a fully-qualified name - the "registration"
        # in new com.sliit.eventsphere.service.registration.Foo() - was
        # read as a variable of that name calling Foo(...) on it.
        for call in re.finditer(r'(?<![\w.])' + re.escape(var) + r'\.(\w+)\s*\(', t):
            mname = call.group(1)
            known = effective_methods(typ)
            if mname not in known:
                # ignore inherited/JDK methods on framework types
                if typ.endswith('Repository') or typ in ('Model', 'Logger', 'String'):
                    continue
                if mname in INHERITED:
                    continue   # inherited from Throwable/Object/Enum, not declared here
                line = t[:call.start()].count('\n') + 1
                problems.append((f, f"line {line}: {typ}.{mname}(...) is not declared on {typ}"))
                continue
            # arity check
            start = call.end()
            depth = 1
            i = start
            while i < len(t) and depth:
                if t[i] in '([':
                    depth += 1
                elif t[i] in ')]':
                    depth -= 1
                i += 1
            args = t[start:i - 1]
            a = arity(args)
            if a not in known[mname]:
                line = t[:call.start()].count('\n') + 1
                problems.append((f, f"line {line}: {typ}.{mname}() called with {a} arg(s); "
                                    f"declared arities {sorted(known[mname])}"))

if not problems:
    print("PASS - no unresolved calls or imports")
    sys.exit(0)

for f, msg in problems:
    print(f"{f.relative_to(ROOT.parent)}: {msg}")
print(f"\n{len(problems)} issue(s)")
sys.exit(1)
