# Rendered preview

`check_render.py` builds `out/gallery.html` from the current
`theme.css` and opens it in Chromium to check the things that only exist
once something is painted — horizontal overflow, clipped dropdowns and
text contrast.

`out/vendor/` holds Bootstrap 5.3.3 and Font Awesome 6.5.1, vendored
locally because the sandbox this was built in cannot reach a CDN and a
preview rendered *without* Bootstrap would have quietly lied about the
layout. They are the same versions the templates load at runtime.

To look at the gallery yourself, open `out/gallery.html` in a browser
after running `python3 check_render.py` (or `build_preview.py`) once.

Requires `pip install playwright && playwright install chromium`. If
Playwright is missing the check skips rather than failing.
