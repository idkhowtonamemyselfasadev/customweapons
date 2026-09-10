#!/usr/bin/env python3
"""Voxel-sculpted 3D models for the seven custom weapons.

Every weapon is built upright on a 32x32x32 grid of half-unit voxels (so the model spans
the 16-unit item box): grip at the bottom, business end at the top, one unit thick in Z.
The voxels are greedily merged into cuboids and written out as a Minecraft model where
every element is turned 45 degrees about Z, which lays the weapon along the same diagonal
as a vanilla sword sprite - so vanilla's own hand and inventory transforms work unchanged.

Colours are painted as roles; each role is a dithered 16x16 swatch in the texture and
every face maps onto its swatch. Detail comes from geometry, the way vanilla's 3D trident
and the other 3D item packs do it.
"""
import math
import random

from PIL import Image

G = 32
SCALE = 16.0 / G
SWATCH = 16
C = 16          # centre column
ZF, ZB = 15, 16  # the one-unit-thick main plane (front, back voxel)


class Volume:
    def __init__(self):
        self.v = {}

    def put(self, x, y, z, role):
        if 0 <= x < G and 0 <= y < G and 0 <= z < G:
            self.v[(x, y, z)] = role

    def box(self, x0, y0, x1, y1, role, z0=ZF, z1=ZB):
        """Inclusive integer voxel box."""
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.put(x, y, z, role)

    def each(self, fn, role, z0=ZF, z1=ZB):
        for x in range(G):
            for y in range(G):
                if fn(x + 0.5, y + 0.5):
                    for z in range(z0, z1 + 1):
                        self.put(x, y, z, role)

    def taper(self, cx, y0, y1, w0, w1, role, z0=ZF, z1=ZB, skew=0.0):
        """A blade: half-width w0 at y0 narrowing to w1 at y1; skew bends the centre line."""
        def f(x, y):
            if not (y0 <= y <= y1 + 1):
                return False
            t = (y - y0) / max(y1 + 1 - y0, 1e-6)
            w = w0 + (w1 - w0) * t
            c = cx + skew * t * t
            return abs(x - c) <= w
        self.each(f, role, z0, z1)

    def line(self, x0, y0, x1, y1, role, z0=ZF, z1=ZB):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) * 2 + 1
        for i in range(n + 1):
            t = i / n
            self.box(int(round(x0 + (x1 - x0) * t)), int(round(y0 + (y1 - y0) * t)),
                     int(round(x0 + (x1 - x0) * t)), int(round(y0 + (y1 - y0) * t)), role, z0, z1)

    def edge(self, of, role, z0=ZF, z1=ZB):
        """Recolour the outermost voxels (empty to the left or right) of a role."""
        for (x, y, z), r in list(self.v.items()):
            if r == of and ((x - 1, y, z) not in self.v or (x + 1, y, z) not in self.v):
                self.v[(x, y, z)] = role

    def carve(self, fn):
        for k in [k for k in self.v if fn(k[0] + 0.5, k[1] + 0.5, k[2] + 0.5)]:
            del self.v[k]

    def rows(self, of, role, ys):
        for (x, y, z), r in list(self.v.items()):
            if r == of and y in ys:
                self.v[(x, y, z)] = role


# --------------------------------------------------------------------- meshing
def mesh(volume):
    v = volume.v
    done = set()
    boxes = []
    for key in sorted(v):
        if key in done:
            continue
        x, y, z = key
        role = v[key]

        def ok(px, py, pz):
            return (px, py, pz) in v and v[(px, py, pz)] == role and (px, py, pz) not in done

        x1 = x
        while ok(x1 + 1, y, z):
            x1 += 1
        z1 = z
        while all(ok(px, y, z1 + 1) for px in range(x, x1 + 1)):
            z1 += 1
        y1 = y
        while all(ok(px, y1 + 1, pz) for px in range(x, x1 + 1) for pz in range(z, z1 + 1)):
            y1 += 1
        for px in range(x, x1 + 1):
            for py in range(y, y1 + 1):
                for pz in range(z, z1 + 1):
                    done.add((px, py, pz))
        boxes.append(((x, y, z), (x1 + 1, y1 + 1, z1 + 1), role))
    return boxes


# ---------------------------------------------------------------------- output
def texture(palette, seed):
    rng = random.Random(seed)
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = img.load()
    for i, (role, base) in enumerate(palette.items()):
        ox, oy = (i % 4) * SWATCH, (i // 4) * SWATCH
        tones = [tuple(int(c * f) for c in base) for f in (0.9, 0.96, 1.0, 1.0, 1.05)]
        for y in range(SWATCH):
            for x in range(SWATCH):
                t = rng.choice(tones)
                px[ox + x, oy + y] = tuple(min(255, c) for c in t) + (255,)
    return img


HANDHELD = {
    "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}
BOW = {
    "thirdperson_righthand": {"rotation": [-80, 260, -40], "translation": [-1, -2, 2.5], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [-80, -280, 40], "translation": [-1, -2, 2.5], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}
CROSSBOW = {
    "thirdperson_righthand": {"rotation": [-90, 0, -60], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_lefthand": {"rotation": [-90, 0, 30], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
    "firstperson_righthand": {"rotation": [-90, 0, -55], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
    "firstperson_lefthand": {"rotation": [-90, 0, 35], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
}


def model_json(boxes, palette, tex_ref, display):
    order = list(palette)
    elements = []
    u = 16.0 / 64
    for (x0, y0, z0), (x1, y1, z1), role in boxes:
        i = order.index(role)
        ox, oy = (i % 4) * SWATCH, (i // 4) * SWATCH
        dx, dy, dz = (x1 - x0), (y1 - y0), (z1 - z0)
        faces = {}
        for side, (w, h) in {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
                             "up": (dx, dz), "down": (dx, dz)}.items():
            faces[side] = {"uv": [ox * u, oy * u, (ox + min(SWATCH, w)) * u, (oy + min(SWATCH, h)) * u],
                           "texture": "#t"}
        elements.append({
            "from": [round(x0 * SCALE, 3), round(y0 * SCALE, 3), round(z0 * SCALE, 3)],
            "to": [round(x1 * SCALE, 3), round(y1 * SCALE, 3), round(z1 * SCALE, 3)],
            # Upright in the grid, diagonal in the game: the same lean as a vanilla sprite.
            "rotation": {"origin": [8, 8, 8], "axis": "z", "angle": -45},
            "faces": faces,
        })
    return {
        "credit": "CustomWeapons, pack/weapons.py",
        "texture_size": [64, 64],
        "textures": {"t": tex_ref, "particle": tex_ref},
        "gui_light": "front",
        "elements": elements,
        "display": display,
    }


# ---------------------------------------------------------------------- shapes
def bloodletter(v):
    v.box(14, 0, 17, 2, "guard", 14, 17)                  # pommel
    v.box(15, 0, 16, 1, "gem", 14, 17)
    v.box(15, 3, 16, 9, "wrap", 14, 17)                   # grip, 1 unit thick
    v.rows("wrap", "wrapred", {4, 6, 8})
    v.box(11, 10, 20, 11, "guard", 14, 17)                # crossguard
    v.box(10, 9, 10, 10, "guard", 14, 17)                 # down-turned tips
    v.box(21, 9, 21, 10, "guard", 14, 17)
    v.box(15, 10, 16, 12, "bone", 13, 18)                 # skull at the ricasso
    v.taper(C, 12, 31, 3.0, 0.6, "blade")                 # blade
    v.edge("blade", "edge")
    for y in range(14, 27, 3):                            # serrated back edge
        v.carve(lambda x, py, z, y=y: py == y + 0.5 and x < C and (x - 0.5, py, z) not in v.v)
    v.box(15, 13, 16, 26, "vein")                         # blood channel


def gale_edge(v):
    v.box(14, 0, 17, 2, "pommel", 14, 17)
    v.box(15, 3, 16, 9, "grip", 14, 17)
    v.rows("grip", "wrap", {4, 6, 8})
    v.box(12, 10, 19, 11, "guard", 14, 17)
    v.box(11, 9, 11, 12, "guard", 14, 17)                 # swept wings
    v.box(20, 9, 20, 12, "guard", 14, 17)
    v.taper(C, 12, 31, 2.2, 0.5, "blade", skew=4.0)       # slender, curving to the right
    v.edge("blade", "edge")
    for y, x in ((15, 15), (19, 16), (23, 17), (27, 18)):  # wind swirls up the flat
        v.box(x, y, x, y + 1, "swirl")


def frostbrand(v):
    v.box(13, 0, 18, 2, "pommel", 14, 17)
    v.box(15, 0, 16, 2, "guard", 14, 17)                  # snowflake cross
    v.box(13, 1, 18, 1, "guard", 14, 17)
    v.box(15, 3, 16, 9, "grip", 14, 17)
    v.rows("grip", "wrapl", {4, 6, 8})
    v.box(11, 10, 20, 11, "guard", 14, 17)
    v.taper(C, 12, 31, 3.0, 0.7, "ice")
    v.edge("ice", "frost")
    v.box(15, 12, 16, 27, "core")
    v.box(12, 17, 13, 18, "crystal", 14, 17)              # ice crystals growing off the blade
    v.box(11, 18, 12, 18, "crystal", 15, 16)
    v.box(18, 21, 19, 22, "crystal", 14, 17)
    v.box(19, 22, 20, 22, "crystal", 15, 16)
    v.box(12, 24, 13, 24, "crystal", 15, 16)


def aegis_hammer(v):
    v.box(15, 0, 16, 25, "handle", 14, 17)
    v.box(14, 0, 17, 1, "band", 14, 17)                   # butt cap
    v.box(15, 5, 16, 6, "band", 14, 17)
    v.box(15, 13, 16, 14, "band", 14, 17)
    v.box(13, 21, 18, 30, "stone", 13, 18)                # head, 3 units thick
    v.box(7, 22, 12, 29, "stone", 14, 17)                 # axe blade
    v.box(6, 24, 6, 27, "stone", 14, 17)
    v.each(lambda x, y: 5 <= x <= 8 and 22 <= y <= 30 and abs(y - 26) < (8.5 - x) * 1.3, "edge", 14, 17)
    v.box(19, 23, 23, 28, "hammer", 13, 18)               # hammer face
    v.box(24, 24, 24, 27, "band", 13, 18)
    v.box(15, 24, 16, 27, "gem", 12, 19)                  # crying obsidian set through the head
    v.box(13, 21, 18, 21, "shard", 13, 18)                # echo shard bands
    v.box(13, 30, 18, 30, "shard", 13, 18)
    v.box(13, 25, 13, 26, "shard", 13, 18)
    v.box(18, 25, 18, 26, "shard", 13, 18)


def tidecaller(v):
    v.box(15, 0, 16, 23, "shaft", 14, 17)
    v.box(14, 0, 17, 1, "base", 14, 17)
    v.box(15, 5, 16, 6, "lantern", 14, 17)
    v.box(15, 12, 16, 13, "lantern", 14, 17)
    v.box(15, 18, 16, 19, "lantern", 14, 17)
    v.box(11, 23, 20, 24, "base", 14, 17)                 # crossbar
    v.box(14, 22, 17, 24, "shell", 13, 18)                # nautilus shell at the throat
    v.box(15, 25, 16, 31, "prong")                        # centre prong
    v.box(11, 25, 12, 29, "prong")                        # side prongs
    v.box(19, 25, 20, 29, "prong")
    v.box(15, 31, 16, 31, "tip")
    v.box(12, 29, 12, 30, "tip")
    v.box(19, 29, 19, 30, "tip")
    v.box(13, 24, 13, 25, "prong")                        # prong roots
    v.box(18, 24, 18, 25, "prong")


def stormpiercer(v, bend=0, pull=None):
    """bend: how far the limb tips move toward the string; pull: string pull-point x."""
    def limb(y):
        return 16 + bend - (6 + bend) * math.sin(math.pi * y / 32)
    for y in range(32):
        x = int(round(limb(y + 0.5)))
        v.box(x - 1, y, x, y, "wood")
    v.rows("wood", "copper", {6, 7, 24, 25})
    for y in range(13, 20):                               # leather grip
        x = int(round(limb(y + 0.5)))
        v.box(x - 2, y, x + 1, y, "grip", 14, 17)
    v.box(int(round(limb(16))) - 3, 15, int(round(limb(16))) - 3, 16, "gem", 15, 16)   # centre gem
    for ty in (0, 31):                                    # gems at the tips
        x = int(round(limb(ty + 0.5)))
        v.box(x - 1, ty, x, ty, "gem")
        v.box(x - 1, ty + (1 if ty == 0 else -1), x, ty + (1 if ty == 0 else -1), "gemlight")
    tip_x = int(round(limb(0.5))) + 1
    if pull is None:
        v.line(tip_x, 1, tip_x, 30, "string", 16, 16)
    else:
        v.line(tip_x, 1, pull, 16, "string", 16, 16)
        v.line(pull, 16, tip_x, 30, "string", 16, 16)


def hellfire(v, pull=None, loaded=False):
    v.box(14, 0, 17, 30, "stock", 14, 17)                 # stock, 2 units square
    v.box(15, 0, 16, 30, "core", 14, 14)                  # magma seam down the back
    v.box(15, 0, 16, 30, "core", 17, 17)
    v.rows("core", "magma", {3, 4, 10, 11, 17, 18})
    v.box(13, 0, 18, 2, "iron", 13, 18)                   # butt plate
    v.box(18, 8, 19, 10, "iron", 14, 17)                  # trigger
    v.box(5, 22, 26, 23, "limb")                          # limbs
    v.box(3, 24, 6, 25, "limb")
    v.box(25, 24, 28, 25, "limb")
    v.rows("limb", "limbdark", {23})
    v.box(4, 26, 5, 26, "flame")                          # blaze fire at the limb tips
    v.box(26, 26, 27, 26, "flame")
    v.box(14, 21, 17, 24, "iron", 13, 18)                 # limb mount
    v.box(15, 22, 16, 23, "star", 12, 19)                 # nether star set through the mount
    if pull is None:
        v.line(4, 24, 27, 24, "string", 16, 16)
    else:
        v.line(4, 24, 16, pull, "string", 16, 16)
        v.line(16, pull, 27, 24, "string", 16, 16)
    if loaded:
        v.box(15, 9, 16, 30, "bolt", 18, 18)              # bolt riding on top of the stock
        v.box(15, 9, 16, 11, "flame", 18, 19)
        v.box(15, 29, 16, 31, "flame", 18, 19)


# --------------------------------------------------------------------- palettes
PALETTES = {
    "bloodletter": {"blade": (66, 56, 64), "edge": (128, 114, 124), "vein": (178, 22, 28), "wrap": (30, 25, 28),
                    "wrapred": (118, 20, 24), "guard": (44, 40, 46), "bone": (218, 208, 184), "gem": (226, 34, 44)},
    "gale_edge": {"blade": (80, 226, 232), "edge": (226, 250, 252), "swirl": (168, 242, 246), "guard": (204, 214, 222),
                  "grip": (32, 122, 132), "wrap": (64, 172, 182), "pommel": (236, 246, 250)},
    "frostbrand": {"ice": (196, 232, 250), "frost": (246, 251, 255), "core": (104, 178, 240), "crystal": (92, 204, 192),
                   "guard": (192, 202, 214), "grip": (32, 52, 92), "wrapl": (70, 96, 140), "pommel": (240, 248, 255)},
    "aegis_hammer": {"handle": (62, 46, 32), "band": (214, 176, 58), "stone": (38, 36, 42), "edge": (224, 188, 74),
                     "hammer": (202, 166, 62), "gem": (112, 42, 172), "shard": (22, 142, 152)},
    "tidecaller": {"shaft": (42, 92, 90), "lantern": (204, 242, 232), "base": (102, 172, 162), "shell": (222, 192, 152),
                   "prong": (82, 162, 152), "tip": (226, 246, 240)},
    "stormpiercer": {"wood": (82, 60, 42), "copper": (198, 112, 72), "grip": (58, 42, 30), "gem": (162, 92, 222),
                     "gemlight": (204, 152, 242), "string": (232, 232, 238)},
    "hellfire": {"stock": (50, 24, 28), "core": (62, 22, 12), "magma": (242, 122, 32), "iron": (62, 62, 68),
                 "limb": (232, 172, 44), "limbdark": (172, 112, 22), "flame": (255, 150, 22), "string": (202, 202, 202),
                 "star": (250, 250, 232), "bolt": (44, 32, 32)},
}

# Every model variant: (weapon id, variant name, builder, display block)
VARIANTS = [
    ("bloodletter", "bloodletter", bloodletter, HANDHELD),
    ("gale_edge", "gale_edge", gale_edge, HANDHELD),
    ("frostbrand", "frostbrand", frostbrand, HANDHELD),
    ("aegis_hammer", "aegis_hammer", aegis_hammer, HANDHELD),
    ("tidecaller", "tidecaller", tidecaller, HANDHELD),
    ("stormpiercer", "stormpiercer", lambda v: stormpiercer(v), BOW),
    ("stormpiercer", "stormpiercer_pulling_0", lambda v: stormpiercer(v, 2, 22), BOW),
    ("stormpiercer", "stormpiercer_pulling_1", lambda v: stormpiercer(v, 4, 26), BOW),
    ("stormpiercer", "stormpiercer_pulling_2", lambda v: stormpiercer(v, 6, 30), BOW),
    ("hellfire", "hellfire", lambda v: hellfire(v), CROSSBOW),
    ("hellfire", "hellfire_pulling_0", lambda v: hellfire(v, pull=19), CROSSBOW),
    ("hellfire", "hellfire_pulling_1", lambda v: hellfire(v, pull=15), CROSSBOW),
    ("hellfire", "hellfire_pulling_2", lambda v: hellfire(v, pull=12), CROSSBOW),
    ("hellfire", "hellfire_loaded", lambda v: hellfire(v, pull=12, loaded=True), CROSSBOW),
]


def build(weapon, variant, builder, display, namespace="customweapons"):
    v = Volume()
    builder(v)
    boxes = mesh(v)
    palette = PALETTES[weapon]
    tex_ref = f"{namespace}:item/{weapon}"
    return model_json(boxes, palette, tex_ref, display), len(boxes)


def textures(namespace="customweapons"):
    return {weapon: texture(palette, weapon) for weapon, palette in PALETTES.items()}
