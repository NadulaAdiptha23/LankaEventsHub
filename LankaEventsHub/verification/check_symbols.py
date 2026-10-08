#!/usr/bin/env python3
"""
Heuristic "is every type name resolvable?" check.

For each file, collect the capitalised identifiers used in the body and
confirm each one is either imported explicitly, covered by a wildcard
import, declared in the same file, in the same package, in java.lang, or a
known JDK/framework name reachable through one of the wildcard imports.

This is the check that most nearly replaces "does it compile" for the one
mistake that is easy to make by hand: using a class without importing it.
"""
import re, pathlib, sys
from collections import defaultdict

SRC = pathlib.Path(__file__).resolve().parent.parent / 'src'
files = sorted(SRC.rglob('*.java'))

JAVA_LANG = {
    'String','Integer','Long','Double','Float','Boolean','Byte','Short','Character','Object',
    'Math','System','Exception','RuntimeException','Throwable','Error','Override','Deprecated',
    'SuppressWarnings','FunctionalInterface','SafeVarargs','Class','Enum','Iterable','Comparable',
    'Runnable','Thread','StringBuilder','Number','CharSequence','Void','IllegalArgumentException',
    'AssertionError','IllegalStateException','UnsupportedOperationException','NullPointerException',
    'IllegalStateException','NullPointerException','UnsupportedOperationException','Record',
    # Added when the notification and scheduler work used them and the
    # checker reported false positives. Every one is java.lang; the list
    # was simply incomplete, which is a gap in the checker rather than
    # something the code should have been contorted to avoid.
    'AutoCloseable','InterruptedException','NumberFormatException','ClassCastException',
    'ArithmeticException','IndexOutOfBoundsException','ArrayIndexOutOfBoundsException',
    'StringIndexOutOfBoundsException','CloneNotSupportedException','SecurityException',
    'ThreadLocal','Process','ProcessBuilder','Package','StackTraceElement','ClassLoader',
}

# names each wildcard import can legitimately supply
WILDCARD = {
    'com.sliit.eventsphere.entity': None,          # resolved from the project below
    'jakarta.persistence': {'Entity','Table','Id','GeneratedValue','GenerationType','Column',
        'ManyToOne','OneToMany','OneToOne','JoinColumn','FetchType','CascadeType','Enumerated',
        'EnumType','PrePersist','PreUpdate','Transient','UniqueConstraint','LockModeType','Temporal'},
    'jakarta.validation.constraints': {'NotNull','NotBlank','Size','Min','Max','DecimalMin',
        'DecimalMax','Positive','PositiveOrZero','Negative','Email','Pattern','Digits','Past','Future'},
    'org.springframework.web.bind.annotation': {'RestController','Controller','RequestMapping',
        'GetMapping','PostMapping','PutMapping','DeleteMapping','PatchMapping','PathVariable',
        'RequestParam','RequestBody','ResponseBody','RestControllerAdvice','ControllerAdvice',
        'ExceptionHandler','ResponseStatus','RequestHeader','CrossOrigin'},
    'org.junit.jupiter.api.Assertions': set(),
    'org.mockito.Mockito': set(),
    'org.mockito.ArgumentMatchers': set(),
    'com.sliit.eventsphere.dto': None,
    'com.sliit.eventsphere.service.discount': None,
    'java.util': {'List','Map','Set','Optional','ArrayList','HashMap','HashSet','LinkedHashMap',
        'Collection','Arrays','Collections','Comparator','UUID','Objects','Iterator','LinkedList'},
}

project_types = defaultdict(set)   # package -> {simple names}
for f in files:
    pkg = re.search(r'^package\s+([\w.]+);', f.read_text(), re.M).group(1)
    project_types[pkg].add(f.stem)

def strip_literals(src):
    out=[]; i=0; n=len(src)
    while i<n:
        c=src[i]
        if c=='/' and i+1<n and src[i+1]=='/':
            j=src.find('\n',i); j=n if j<0 else j; out.append(' '*(j-i)); i=j
        elif c=='/' and i+1<n and src[i+1]=='*':
            j=src.find('*/',i+2); j=n if j<0 else j+2
            out.append(''.join('\n' if ch=='\n' else ' ' for ch in src[i:j])); i=j
        elif c in '"\'':
            q=c; j=i+1
            while j<n:
                if src[j]=='\\': j+=2; continue
                if src[j]==q: j+=1; break
                j+=1
            out.append('"'+' '*max(0,j-i-2)+'"'); i=j
        else:
            out.append(c); i+=1
    return ''.join(out)

problems=[]
for f in files:
    raw = f.read_text()
    pkg = re.search(r'^package\s+([\w.]+);', raw, re.M).group(1)
    body = strip_literals(raw)
    lines = body.split('\n')
    code = '\n'.join(l for l in lines if not l.startswith('import ') and not l.startswith('package '))

    known = set(JAVA_LANG)
    known |= project_types[pkg]          # same package
    known.add(f.stem)
    # nested classes/enums/records declared inside this file
    known |= set(re.findall(r'\b(?:class|interface|enum|record)\s+([A-Z]\w*)', body))

    for m in re.finditer(r'^import\s+(?:static\s+)?([\w.]+?)(?:\.(\*|\w+))?;', body, re.M):
        full, simple = m.group(1), m.group(2)
        if simple and simple != '*':
            known.add(simple)
        else:
            base = full
            if base in WILDCARD:
                allowed = WILDCARD[base]
                known |= project_types.get(base, set()) if allowed is None else allowed
            known |= project_types.get(base, set())
            # a static wildcard import of a class brings in its members, which
            # are lower-case method names we do not track
            known.add(base.rsplit('.', 1)[-1])

    used = set(re.findall(r'(?<![\w.])([A-Z][A-Za-z0-9]*)\s*(?=[.(<\[]|::|\s+\w)', code))
    # ALL_CAPS names are constants, not types
    used = {u for u in used if not re.fullmatch(r'[A-Z0-9_]+', u)}
    used |= set(re.findall(r'@([A-Z][A-Za-z0-9]*)', code))
    used |= set(re.findall(r'\bnew\s+([A-Z][A-Za-z0-9]*)', code))
    used |= set(re.findall(r'\bimplements\s+([A-Z][A-Za-z0-9]*)', code))
    used |= set(re.findall(r'\bextends\s+([A-Z][A-Za-z0-9]*)', code))

    for name in sorted(used - known):
        problems.append((f.relative_to(SRC.parent), name))

if not problems:
    print("PASS - every referenced type is imported, declared, or in the same package")
    sys.exit(0)
for f, name in problems:
    print(f"{f}: possibly unresolved type '{name}'")
print(f"\n{len(problems)} candidate(s) - review each")
sys.exit(1)
