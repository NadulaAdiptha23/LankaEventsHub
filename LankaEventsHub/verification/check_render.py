#!/usr/bin/env python3
"""
Renders the design system in a real browser and checks it.

Everything else in this harness reads the source as text. Text cannot see
that a dropdown is clipped by its scroll container, or that a card row
breaks alignment, or that the page scrolls sideways at 320px. This one
opens a browser, lays the components out at five widths, and asserts the
things that only exist once something is painted:

  1. no horizontal page overflow at any breakpoint;
  2. every action menu opens fully on screen and is not clipped by an
     ancestor (this found a real bug - .table-responsive is a scroll
     container, so an absolutely-positioned menu was being cut off at the
     bottom of every table in the app);
  3. body text meets WCAG AA contrast against its background.

Skips cleanly - it does not fail the build - when Playwright or the
vendored CSS is not present, because this is the one check that needs
more than a Python interpreter.
"""
import sys
import pathlib
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
PREVIEW = HERE / 'preview'
OUT = PREVIEW / 'out'
WIDTHS = [(320, 640), (390, 780), (768, 1024), (1024, 800), (1440, 900)]


def skip(reason: str) -> int:
    print(f"SKIP - {reason}")
    print("      (this check needs Playwright and the vendored CSS in "
          "preview/out/vendor; the rest of the harness is unaffected)")
    return 0


def main() -> int:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return skip("Playwright is not installed")

    if not (OUT / 'vendor/css/bootstrap.min.css').exists():
        return skip("vendored Bootstrap/Font Awesome not found")

    # Rebuild so the preview always reflects the current theme.css.
    subprocess.run([sys.executable, str(PREVIEW / 'build_preview.py')],
                   check=True, capture_output=True)

    url = 'file://' + str(OUT / 'gallery.html')
    problems = []
    inject = '--inject' in sys.argv

    with sync_playwright() as pw:
        browser = pw.chromium.launch()

        # ---- 1. horizontal overflow ----
        for width, height in WIDTHS:
            page = browser.new_page(viewport={'width': width, 'height': height})
            page.goto(url)
            page.wait_for_timeout(900)
            if inject and width == 768:
                page.evaluate("document.body.insertAdjacentHTML('beforeend',"
                              "'<div style=\"width:3000px;height:10px\"></div>')")
            over = page.evaluate(
                "document.documentElement.scrollWidth - document.documentElement.clientWidth")
            if over > 0:
                problems.append(f"{width}px: page scrolls {over}px sideways")
            page.close()

        # ---- 2. action menus are never clipped ----
        for width, height in ((390, 780), (1440, 900)):
            page = browser.new_page(viewport={'width': width, 'height': height})
            page.goto(url)
            page.wait_for_timeout(900)
            if inject and width == 390:
                # Re-clip them, the way the bug originally behaved.
                page.evaluate("""() => {
                  const s = document.createElement('style');
                  s.textContent = '.action-menu.is-open .action-menu__list'
                      + '{position:absolute!important;top:100%!important;right:0!important}';
                  document.head.appendChild(s);
                }""")
            for index, trigger in enumerate(page.query_selector_all('.action-menu__trigger')):
                trigger.scroll_into_view_if_needed()
                page.wait_for_timeout(120)
                trigger.click()
                page.wait_for_timeout(200)
                verdict = page.evaluate("""(vp) => {
                  const l = document.querySelector('.action-menu.is-open .action-menu__list');
                  if (!l) return 'did not open';
                  const b = l.getBoundingClientRect();
                  if (b.top < -1 || b.left < -1 || b.bottom > vp.h + 1 || b.right > vp.w + 1) {
                    return 'opens off screen';
                  }
                  // Is it actually the topmost thing at its own corners, or
                  // is an ancestor clipping it?
                  const pts = [[b.left + 8, b.top + 8], [b.right - 8, b.bottom - 8]];
                  return pts.some(([x, y]) => !l.contains(document.elementFromPoint(x, y)))
                      ? 'clipped by an ancestor' : 'ok';
                }""", {'w': width, 'h': height})
                if verdict != 'ok':
                    problems.append(f"{width}px: action menu {index} {verdict}")
                page.keyboard.press('Escape')
                page.wait_for_timeout(120)
            page.close()

        # ---- 3. text contrast ----
        page = browser.new_page(viewport={'width': 1440, 'height': 900})
        page.goto(url)
        page.wait_for_timeout(900)
        if inject:
            page.evaluate("""() => {
              const s = document.createElement('style');
              s.textContent = '.stat-label{color:#D8DEE6!important}';
              document.head.appendChild(s);
            }""")
        low = page.evaluate("""() => {
          const lum = (c) => {
            const v = c.map(x => { x /= 255; return x <= 0.03928 ? x/12.92
                                        : Math.pow((x+0.055)/1.055, 2.4); });
            return 0.2126*v[0] + 0.7152*v[1] + 0.0722*v[2];
          };
          const parse = (s) => (s.match(/\\d+(\\.\\d+)?/g) || []).slice(0,4).map(Number);
          // A translucent background does not replace what is behind it,
          // it blends with it. Reading rgba(255,255,255,0.07) on a dark
          // card as "white" reports a contrast failure that is not real,
          // so the stack is composited properly.
          const bgOf = (el) => {
            const layers = [];
            for (let n = el; n; n = n.parentElement) {
              const c = parse(getComputedStyle(n).backgroundColor);
              const a = c.length > 3 ? c[3] : 1;
              if (a > 0) { layers.push([c.slice(0,3), a]); if (a >= 1) break; }
            }
            let out = [255,255,255];
            for (let i = layers.length - 1; i >= 0; i--) {
              const [rgb, a] = layers[i];
              out = out.map((v, k) => rgb[k]*a + v*(1-a));
            }
            return out;
          };
          const out = [];
          document.querySelectorAll('p,span,div,a,label,td,th,h1,h2,h3,li,button')
            .forEach(el => {
              if (!el.textContent.trim() || el.children.length) return;
              const cs = getComputedStyle(el);
              if (cs.visibility === 'hidden' || cs.display === 'none') return;
              const r = el.getBoundingClientRect();
              if (r.width < 4 || r.height < 4) return;
              const fg = parse(cs.color);
              // Text hidden on purpose - the busy button replaces its
              // label with a spinner - is not a contrast failure.
              if ((fg.length > 3 ? fg[3] : 1) < 0.05) return;
              const bg = bgOf(el);
              const l1 = lum(fg), l2 = lum(bg);
              const ratio = (Math.max(l1,l2) + 0.05) / (Math.min(l1,l2) + 0.05);
              const px = parseFloat(cs.fontSize);
              const large = px >= 24 || (px >= 18.66 && parseInt(cs.fontWeight,10) >= 700);
              const need = large ? 3.0 : 4.5;
              if (ratio < need) {
                out.push({t: el.textContent.trim().slice(0,32),
                          cls: (el.className||'').toString().slice(0,34),
                          ratio: Math.round(ratio*100)/100, need});
              }
            });
          return out;
        }""")
        for item in low:
            problems.append(
                f"contrast {item['ratio']}:1 (needs {item['need']}) — "
                f"\"{item['t']}\" .{item['cls']}")
        page.close()
        browser.close()

    if inject:
        print("[--inject] planted an overflow, re-clipped the menus and "
              "faded a label\n")

    if problems:
        for p in problems:
            print(f"  ! {p}")
        print(f"\n{len(problems)} rendering problem(s)")
        return 1

    print(f"PASS - no sideways scroll at {'/'.join(str(w) for w, _ in WIDTHS)}px, "
          f"every action menu opens unclipped, all text meets WCAG AA")
    return 0


if __name__ == '__main__':
    sys.exit(main())
