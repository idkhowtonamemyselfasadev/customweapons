#!/usr/bin/env python3
"""Flat front-view thumbnails of the idle weapon models, for eyeballing shapes without a
browser: every merged box is drawn as an x/y rectangle (z ignored, back boxes first) in
its role's palette colour, 8 px per voxel.

    python3 thumbs.py [weapon ...]   -> thumbs/<weapon>.png (all ten weapons by default)
"""
import os
import sys

from PIL import Image, ImageDraw

import weapons

PX = 8
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "thumbs")


def render(weapon, builder):
    v = weapons.Volume()
    builder(v)
    boxes = weapons.mesh(v)
    palette = weapons.PALETTES[weapon]
    img = Image.new("RGBA", (weapons.G * PX, weapons.G * PX), (28, 30, 36, 255))
    d = ImageDraw.Draw(img, "RGBA")
    for gx in range(0, weapons.G * PX, PX * 4):                   # faint 4-voxel grid
        d.line([(gx, 0), (gx, weapons.G * PX)], fill=(40, 42, 50, 255))
        d.line([(0, gx), (weapons.G * PX, gx)], fill=(40, 42, 50, 255))
    # Boxes far from the viewer first, so anything sticking out of the front lands on top.
    for (x0, y0, z0), (x1, y1, z1), role in sorted(boxes, key=lambda b: -b[0][2]):
        c = palette[role]
        rgb = tuple(c[:3]) + ((c[3],) if len(c) == 4 else (255,))
        top, bottom = (weapons.G - y1) * PX, (weapons.G - y0) * PX - 1
        d.rectangle([x0 * PX, top, x1 * PX - 1, bottom], fill=rgb,
                    outline=tuple(int(k * 0.75) for k in rgb[:3]) + (rgb[3],))
    return img, len(boxes)


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    idle = {w: b for w, variant, b, _ in weapons.VARIANTS if w == variant}
    names = argv or list(idle)
    for name in names:
        img, n = render(name, idle[name])
        path = os.path.join(OUT, name + ".png")
        img.save(path)
        print(f"{name:14s} {n:4d} boxes  {path}")


if __name__ == "__main__":
    main(sys.argv[1:])
