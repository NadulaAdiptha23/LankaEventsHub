#!/usr/bin/env bash
# ---------------------------------------------------------------------
# Everything that can be checked without Maven Central.
#
# This is NOT a substitute for `mvn clean test` - it cannot tell you that
# generics line up or that a Mockito stub matches at runtime. It is the
# set of things that CAN be verified from the source alone, plus one
# check that opens a browser. Every checker here was fault-injected
# during development to confirm it fails when it should; run any of them
# with --inject to see that for yourself.
# ---------------------------------------------------------------------
set -u
cd "$(dirname "$0")"
fail=0
run() { echo; echo "── $1 ──"; shift; "$@" || fail=1; }

run "Java syntax (brace/paren balance)"                 python3 - <<'PY'
import pathlib, sys
def strip(src):
    out=[];i=0;n=len(src)
    while i<n:
        c=src[i]
        if c=='/' and i+1<n and src[i+1]=='/':
            j=src.find('\n',i); j=n if j<0 else j; out.append(' '*(j-i)); i=j
        elif c=='/' and i+1<n and src[i+1]=='*':
            j=src.find('*/',i+2); j=n if j<0 else j+2
            out.append(''.join('\n' if ch=='\n' else ' ' for ch in src[i:j])); i=j
        elif c in '"\'':
            q=c;j=i+1
            while j<n:
                if src[j]=='\\': j+=2; continue
                if src[j]==q: j+=1; break
                j+=1
            out.append('"'+' '*max(0,j-i-2)+'"'); i=j
        else: out.append(c); i+=1
    return ''.join(out)
bad=0
files=sorted((pathlib.Path(__file__).resolve().parent.parent/'src').rglob('*.java'))
for f in files:
    t=strip(f.read_text())
    for o,c,l in (('{','}','braces'),('(',')','parens'),('[',']','brackets')):
        d=0;m=0
        for ch in t:
            if ch==o: d+=1
            elif ch==c: d-=1; m=min(m,d)
        if d or m<0:
            print(f"UNBALANCED {l}: {f}"); bad+=1
print(f"{len(files)} Java files, {bad} unbalanced")
sys.exit(1 if bad else 0)
PY

run "Method calls resolve (name + arity)"               python3 check_calls.py
run "Types are imported or in scope"                    python3 check_symbols.py
run "No unused imports"                                 python3 check_imports.py
run "Thymeleaf templates"                               python3 check_templates.py
run "Entities match the SQL schema"                     python3 check_schema.py
run "UI behaviour unchanged (forms, URLs, auth rules)"  python3 surface.py
run "Design system consistency"                         python3 check_ui.py
run "Rendered UI (overflow, menus, contrast)"           python3 check_render.py

echo
if [ $fail -eq 0 ]; then echo "ALL STATIC CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit $fail
