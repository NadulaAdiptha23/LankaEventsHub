#!/usr/bin/env python3
"""
Generates the art-directed imagery for the Verdant Events public site.

WHY THIS EXISTS
---------------
The build environment has no route to any image host, so no photograph
could be fetched, bundled or even verified to load. Referencing remote
photo URLs would have meant shipping images nobody had seen. These are
built instead: atmospheric, brand-coloured compositions that are correct
by construction and cannot 404.

They are DESIGNED TO BE REPLACED. Every one writes to a fixed filename
at a fixed aspect ratio, and every template references that filename
through a single fragment. Dropping `hero.jpg` next to `hero.svg` and
changing one line in `fragments/media.html` swaps the whole site over to
real photography with no other edit.

Each image is the same scene language - a dark venue, one warm light
source, architecture, a crowd - rendered at different crops so the set
reads as one shoot rather than as eight unrelated pictures.

Run:  python3 tools/make_imagery.py
"""

import math
import pathlib
import random

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/static/img"
OUT.mkdir(parents=True, exist_ok=True)

# The brand, as the imagery uses it.
OBSIDIAN = "#0B0B0B"
CHARCOAL = "#161616"
FOREST = "#14432C"
FOREST_LIGHT = "#1E6340"
GOLD = "#B08D57"
GOLD_WARM = "#D8B87F"
IVORY = "#F7F5F0"


def defs(seed_id, warm=GOLD_WARM, cool=FOREST, grain=0.55):
    """Shared gradients and the grain filter, namespaced per file."""
    return f"""
  <defs>
    <linearGradient id="sky{seed_id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"   stop-color="{CHARCOAL}"/>
      <stop offset="55%"  stop-color="{OBSIDIAN}"/>
      <stop offset="100%" stop-color="#050505"/>
    </linearGradient>
    <radialGradient id="glow{seed_id}" cx="50%" cy="50%" r="50%">
      <stop offset="0%"   stop-color="{warm}" stop-opacity="0.85"/>
      <stop offset="45%"  stop-color="{warm}" stop-opacity="0.20"/>
      <stop offset="100%" stop-color="{warm}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="cool{seed_id}" cx="50%" cy="50%" r="50%">
      <stop offset="0%"   stop-color="{cool}" stop-opacity="0.55"/>
      <stop offset="100%" stop-color="{cool}" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="beam{seed_id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"   stop-color="{warm}" stop-opacity="0.34"/>
      <stop offset="100%" stop-color="{warm}" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="vig{seed_id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"   stop-color="#000" stop-opacity="0.45"/>
      <stop offset="40%"  stop-color="#000" stop-opacity="0"/>
      <stop offset="100%" stop-color="#000" stop-opacity="0.72"/>
    </linearGradient>
    <filter id="grain{seed_id}" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="3" seed="{seed_id}"/>
      <feColorMatrix type="saturate" values="0"/>
      <feComponentTransfer><feFuncA type="linear" slope="{grain}"/></feComponentTransfer>
      <feBlend mode="overlay" in2="SourceGraphic"/>
    </filter>
    <filter id="soft{seed_id}" x="-30%" y="-30%" width="160%" height="160%">
      <feGaussianBlur stdDeviation="14"/>
    </filter>
  </defs>"""


def bokeh(rng, w, h, n, y0, y1, colour=GOLD_WARM, rmin=3, rmax=17):
    """Out-of-focus highlights. The single strongest 'photograph' cue."""
    out = []
    for _ in range(n):
        cx = rng.uniform(-0.03 * w, 1.03 * w)
        cy = rng.uniform(y0 * h, y1 * h)
        r = rng.uniform(rmin, rmax)
        o = rng.uniform(0.05, 0.42)
        out.append(
            f'<circle cx="{cx:.0f}" cy="{cy:.0f}" r="{r:.1f}" fill="{colour}" opacity="{o:.2f}"/>'
        )
    return "\n    ".join(out)


def crowd(rng, w, h, base, scale, colour="#000", opacity=0.92, n=54):
    """A band of heads and shoulders, drawn as overlapping capsules."""
    out = []
    x = -0.04 * w
    while x < 1.06 * w:
        head_r = rng.uniform(0.9, 1.35) * scale
        bh = head_r * rng.uniform(2.6, 3.6)
        y = base * h + rng.uniform(-0.4, 0.4) * scale
        out.append(
            f'<circle cx="{x:.0f}" cy="{y - bh:.0f}" r="{head_r:.1f}" fill="{colour}" opacity="{opacity}"/>'
            f'<rect x="{x - head_r * 1.55:.0f}" y="{y - bh + head_r * 0.7:.0f}" '
            f'width="{head_r * 3.1:.1f}" height="{bh * 1.9:.0f}" rx="{head_r:.1f}" '
            f'fill="{colour}" opacity="{opacity}"/>'
        )
        x += head_r * rng.uniform(2.1, 3.4)
    return "\n    ".join(out)


def beams(rng, w, h, n, origin_y=0.0):
    """Stage light shafts, fanning from a point above the frame."""
    out = []
    ox = w * 0.5
    for i in range(n):
        a = math.radians(rng.uniform(-46, 46))
        spread = rng.uniform(0.02, 0.055) * w
        x1 = ox + math.tan(a) * h * 1.25
        out.append(
            f'<path d="M{ox - spread * 0.18:.0f},{origin_y:.0f} '
            f'L{x1 - spread:.0f},{h * 1.05:.0f} L{x1 + spread:.0f},{h * 1.05:.0f} '
            f'L{ox + spread * 0.18:.0f},{origin_y:.0f} Z" '
            f'fill="url(#beamB)" opacity="{rng.uniform(0.25, 0.7):.2f}"/>'
        )
    return "\n    ".join(out)


def arches(w, h, n, y_base, height, colour="#000", opacity=0.9):
    """Venue architecture: a colonnade of arches, in silhouette."""
    out = []
    span = w / n
    for i in range(n):
        x = i * span
        cw = span * 0.74
        cx = x + span * 0.5
        top = y_base - height
        out.append(
            f'<path d="M{cx - cw / 2:.0f},{y_base:.0f} L{cx - cw / 2:.0f},{top + cw / 2:.0f} '
            f'A{cw / 2:.0f},{cw / 2:.0f} 0 0 1 {cx + cw / 2:.0f},{top + cw / 2:.0f} '
            f'L{cx + cw / 2:.0f},{y_base:.0f} Z" fill="{colour}" opacity="{opacity}"/>'
        )
    return "\n    ".join(out)


def wrap(w, h, body, sid):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
        f'width="{w}" height="{h}" preserveAspectRatio="xMidYMid slice" role="img">'
        f"{defs(sid)}\n  <g filter=\"url(#grain{sid})\">\n    {body}\n  </g>\n</svg>\n"
    )


# ---------------------------------------------------------------------
# HERO — a stage seen from the back of a full room.
# ---------------------------------------------------------------------
def hero(path, w=1920, h=1080):
    rng = random.Random(7)
    sid = "B"
    body = f"""<rect width="{w}" height="{h}" fill="url(#sky{sid})"/>

    <!-- the room glow behind the stage -->
    <ellipse cx="{w*0.52:.0f}" cy="{h*0.46:.0f}" rx="{w*0.46:.0f}" ry="{h*0.44:.0f}" fill="url(#glow{sid})"/>
    <ellipse cx="{w*0.17:.0f}" cy="{h*0.30:.0f}" rx="{w*0.26:.0f}" ry="{h*0.30:.0f}" fill="url(#cool{sid})"/>

    <!-- light shafts from the rig -->
    <g>{beams(rng, w, h, 9)}</g>

    <!-- the truss -->
    <rect x="0" y="{h*0.085:.0f}" width="{w}" height="{h*0.012:.0f}" fill="#000" opacity="0.85"/>
    {"".join(f'<rect x="{x}" y="{h*0.085:.0f}" width="7" height="{h*0.055:.0f}" fill="#000" opacity="0.8"/>' for x in range(60, w, 150))}

    <!-- back wall architecture -->
    <g opacity="0.55">{arches(w, h, 7, h*0.66, h*0.40, "#000", 0.62)}</g>

    <!-- the stage edge -->
    <rect x="0" y="{h*0.655:.0f}" width="{w}" height="{h*0.02:.0f}" fill="{GOLD}" opacity="0.30"/>
    <rect x="0" y="{h*0.675:.0f}" width="{w}" height="{h*0.10:.0f}" fill="#000" opacity="0.55"/>

    <!-- a speaker, centre stage -->
    <g opacity="0.95" transform="translate({w*0.5:.0f},{h*0.655:.0f})">
      <circle cx="0" cy="-118" r="30" fill="#000"/>
      <path d="M-46,0 L-38,-84 A46,46 0 0 1 38,-84 L46,0 Z" fill="#000"/>
    </g>

    <!-- depth: three crowd bands, each darker and larger than the last -->
    <g>{crowd(rng, w, h, 0.885, h*0.020, "#000", 0.55)}</g>
    <g>{crowd(rng, w, h, 0.955, h*0.028, "#000", 0.78)}</g>
    <g>{crowd(rng, w, h, 1.045, h*0.040, "#000", 0.95)}</g>

    <!-- held-up highlights in the crowd, and lens bokeh over everything -->
    <g filter="url(#soft{sid})">{bokeh(rng, w, h, 46, 0.30, 0.92, GOLD_WARM, 4, 15)}</g>
    <g>{bokeh(rng, w, h, 26, 0.12, 0.55, IVORY, 1.5, 4)}</g>

    <rect width="{w}" height="{h}" fill="url(#vig{sid})"/>"""
    path.write_text(wrap(w, h, body, sid))


# ---------------------------------------------------------------------
# EXPERIENCE TILES — the same world, different crops.
# ---------------------------------------------------------------------
def experience(path, w, h, seed, mode):
    rng = random.Random(seed)
    sid = "B"
    warm = {"wedding": "#E4CBA0", "music": "#C79A5C", "corporate": "#CBB489",
            "private": "#D8B87F", "exhibition": "#BFA477", "workshop": "#C9AE84"}[mode]

    layers = [f'<rect width="{w}" height="{h}" fill="url(#sky{sid})"/>']

    if mode == "wedding":
        # a lit marquee: catenary string lights over a warm floor
        layers.append(f'<ellipse cx="{w*0.5:.0f}" cy="{h*0.72:.0f}" rx="{w*0.62:.0f}" ry="{h*0.45:.0f}" fill="url(#glow{sid})"/>')
        for k in range(5):
            y = h * (0.16 + k * 0.075)
            sag = h * 0.09
            layers.append(
                f'<path d="M0,{y:.0f} Q{w*0.5:.0f},{y+sag:.0f} {w},{y:.0f}" '
                f'fill="none" stroke="{GOLD}" stroke-width="1" opacity="0.30"/>')
            for i in range(11):
                t = i / 10
                bx = t * w
                by = y + sag * 4 * t * (1 - t) * 0.5
                layers.append(f'<circle cx="{bx:.0f}" cy="{by:.0f}" r="{rng.uniform(2.4,4.6):.1f}" fill="{warm}" opacity="{rng.uniform(0.5,1.0):.2f}"/>')
        layers.append(f'<g opacity="0.92">{arches(w, h, 3, h*0.80, h*0.46, "#000", 0.80)}</g>')
        layers.append(f'<g>{crowd(rng, w, h, 0.99, h*0.050, "#000", 0.93)}</g>')

    elif mode == "music":
        layers.append(f'<ellipse cx="{w*0.5:.0f}" cy="{h*0.34:.0f}" rx="{w*0.55:.0f}" ry="{h*0.40:.0f}" fill="url(#glow{sid})"/>')
        layers.append(f'<g>{beams(rng, w, h, 7)}</g>')
        layers.append(f'<rect x="0" y="{h*0.60:.0f}" width="{w}" height="{h*0.03:.0f}" fill="{GOLD}" opacity="0.32"/>')
        layers.append(f'<g>{crowd(rng, w, h, 0.88, h*0.038, "#000", 0.65)}</g>')
        layers.append(f'<g>{crowd(rng, w, h, 1.00, h*0.055, "#000", 0.95)}</g>')

    elif mode == "corporate":
        # a conference room: a lit screen and rows of seated heads
        layers.append(f'<ellipse cx="{w*0.5:.0f}" cy="{h*0.30:.0f}" rx="{w*0.48:.0f}" ry="{h*0.30:.0f}" fill="url(#cool{sid})"/>')
        layers.append(f'<rect x="{w*0.13:.0f}" y="{h*0.13:.0f}" width="{w*0.74:.0f}" height="{h*0.40:.0f}" fill="{FOREST_LIGHT}" opacity="0.30"/>')
        layers.append(f'<rect x="{w*0.13:.0f}" y="{h*0.13:.0f}" width="{w*0.74:.0f}" height="{h*0.40:.0f}" fill="none" stroke="{GOLD}" stroke-width="1" opacity="0.45"/>')
        layers.append(f'<rect x="{w*0.17:.0f}" y="{h*0.20:.0f}" width="{w*0.40:.0f}" height="{h*0.022:.0f}" fill="{IVORY}" opacity="0.22"/>')
        layers.append(f'<rect x="{w*0.17:.0f}" y="{h*0.26:.0f}" width="{w*0.28:.0f}" height="{h*0.016:.0f}" fill="{IVORY}" opacity="0.14"/>')
        # Seated rows, sized against the frame rather than in absolute
        # pixels - the tall crops otherwise render a row of specks.
        for base, sc, op in [(0.74, h*0.030, 0.55), (0.86, h*0.040, 0.76), (1.00, h*0.052, 0.94)]:
            layers.append(f'<g>{crowd(rng, w, h, base, sc, "#000", op)}</g>')
        # a warm wash off the screen, so the room is not a black slab
        layers.append(f'<ellipse cx="{w*0.5:.0f}" cy="{h*0.52:.0f}" rx="{w*0.60:.0f}" ry="{h*0.26:.0f}" fill="url(#glow{sid})" opacity="0.45"/>')

    elif mode == "private":
        layers.append(f'<ellipse cx="{w*0.62:.0f}" cy="{h*0.40:.0f}" rx="{w*0.50:.0f}" ry="{h*0.44:.0f}" fill="url(#glow{sid})"/>')
        # a long table of candles
        layers.append(f'<rect x="0" y="{h*0.70:.0f}" width="{w}" height="{h*0.30:.0f}" fill="#000" opacity="0.80"/>')
        for i in range(9):
            cx = w * (0.06 + i * 0.11)
            layers.append(f'<rect x="{cx:.0f}" y="{h*0.60:.0f}" width="3" height="{h*0.10:.0f}" fill="{GOLD}" opacity="0.5"/>')
            layers.append(f'<circle cx="{cx+1.5:.0f}" cy="{h*0.59:.0f}" r="{rng.uniform(4,7):.1f}" fill="{warm}" opacity="0.9"/>')
        layers.append(f'<g>{crowd(rng, w, h, 0.66, h*0.042, "#000", 0.85)}</g>')

    elif mode == "exhibition":
        layers.append(f'<ellipse cx="{w*0.5:.0f}" cy="{h*0.28:.0f}" rx="{w*0.52:.0f}" ry="{h*0.34:.0f}" fill="url(#cool{sid})"/>')
        for i in range(4):
            x = w * (0.07 + i * 0.235)
            layers.append(f'<rect x="{x:.0f}" y="{h*0.22:.0f}" width="{w*0.165:.0f}" height="{h*0.42:.0f}" fill="{IVORY}" opacity="{0.06 + i*0.018:.3f}"/>')
            layers.append(f'<rect x="{x:.0f}" y="{h*0.22:.0f}" width="{w*0.165:.0f}" height="{h*0.42:.0f}" fill="none" stroke="{GOLD}" stroke-width="1" opacity="0.35"/>')
            layers.append(f'<path d="M{x+w*0.04:.0f},{h*0.10:.0f} L{x+w*0.125:.0f},{h*0.22:.0f} L{x+w*0.04:.0f},{h*0.22:.0f} Z" fill="{warm}" opacity="0.13"/>')
        layers.append(f'<g>{crowd(rng, w, h, 0.97, h*0.055, "#000", 0.92)}</g>')
        layers.append(f'<ellipse cx="{w*0.5:.0f}" cy="{h*0.55:.0f}" rx="{w*0.55:.0f}" ry="{h*0.25:.0f}" fill="url(#glow{sid})" opacity="0.40"/>')

    else:  # workshop
        layers.append(f'<ellipse cx="{w*0.40:.0f}" cy="{h*0.34:.0f}" rx="{w*0.46:.0f}" ry="{h*0.38:.0f}" fill="url(#glow{sid})"/>')
        layers.append(f'<rect x="{w*0.08:.0f}" y="{h*0.16:.0f}" width="{w*0.56:.0f}" height="{h*0.34:.0f}" fill="#000" opacity="0.55"/>')
        layers.append(f'<rect x="{w*0.08:.0f}" y="{h*0.16:.0f}" width="{w*0.56:.0f}" height="{h*0.34:.0f}" fill="none" stroke="{GOLD}" stroke-width="1" opacity="0.40"/>')
        for i in range(5):
            layers.append(f'<rect x="{w*(0.12+i*0.02):.0f}" y="{h*(0.24+i*0.045):.0f}" width="{w*rng.uniform(0.18,0.40):.0f}" height="{h*0.014:.0f}" fill="{IVORY}" opacity="0.16"/>')
        layers.append(f'<g>{crowd(rng, w, h, 0.98, h*0.052, "#000", 0.93)}</g>')

    layers.append(f'<g filter="url(#soft{sid})">{bokeh(rng, w, h, 22, 0.15, 0.85, warm, 4, 13)}</g>')
    layers.append(f'<rect width="{w}" height="{h}" fill="url(#vig{sid})"/>')
    path.write_text(wrap(w, h, "\n    ".join(layers), sid))


if __name__ == "__main__":
    hero(OUT / "hero.svg")
    experience(OUT / "exp-corporate.svg", 900, 1120, 11, "corporate")
    experience(OUT / "exp-wedding.svg", 1200, 800, 12, "wedding")
    experience(OUT / "exp-music.svg", 900, 700, 13, "music")
    experience(OUT / "exp-private.svg", 900, 700, 14, "private")
    experience(OUT / "exp-exhibition.svg", 1200, 760, 15, "exhibition")
    experience(OUT / "exp-workshop.svg", 900, 900, 16, "workshop")
    experience(OUT / "about.svg", 1000, 1150, 21, "corporate")
    experience(OUT / "cta.svg", 1920, 700, 22, "music")
    experience(OUT / "auth.svg", 900, 1300, 23, "wedding")
    for f in sorted(OUT.glob("*.svg")):
        print(f"{f.name:24} {f.stat().st_size / 1024:6.1f} KB")
