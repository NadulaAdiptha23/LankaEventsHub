#!/usr/bin/env python3
"""Flag imports that are never referenced in the file body (unused
imports compile fine but are noise, and one of them is usually the trace
of a half-finished refactor)."""
import re, pathlib, sys

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
SRC = pathlib.Path(__file__).resolve().parent.parent / 'src'
for f in sorted(SRC.rglob('*.java')):
    raw = f.read_text()
    body = strip_literals(raw)
    # everything after the import block
    lines = body.split('\n')
    import_lines = [(i,l) for i,l in enumerate(lines) if l.startswith('import ')]
    rest = '\n'.join(l for i,l in enumerate(lines) if not l.startswith('import '))
    for i, line in import_lines:
        m = re.match(r'import\s+(?:static\s+)?([\w.]+?)(?:\.(\*|\w+))?;', line)
        if not m: continue
        simple = m.group(2)
        if simple in (None, '*'):
            continue
        if not re.search(r'\b' + re.escape(simple) + r'\b', rest):
            problems.append((f, i+1, line.strip()))

for f, line, text in problems:
    print(f"{f.relative_to(pathlib.Path(__file__).resolve().parent.parent)}:{line}: unused {text}")
print(f"\n{len(problems)} unused import(s)")
sys.exit(1 if problems else 0)
