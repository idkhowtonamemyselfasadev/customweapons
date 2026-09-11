#!/usr/bin/env python3
"""World effects for the abilities, built from block-display entities.

A vanilla client can draw any block anywhere, at any size and angle, as a block_display
entity, and it interpolates between the transformations the server sends. So an ice cube
closing around a frozen player, debris arcing out of a ground slam, or a cage of lightning
rods around a shocked target are all just a handful of these entities moved and scaled
each tick. Nothing here needs the resource pack; it works for every player.

Every effect is a list of parts. A part is one block and a list of keyframes: at tick t
the block is centred at c (blocks, relative to the target's feet or to the point the
effect was played at), has size s and Euler rotation r in degrees. The mod interpolates
between keys once a tick; the preview plays the same data in the browser.

    python3 effects.py   -> ../src/main/resources/assets/customweapons/effects.json
"""
import json
import math
import os
import random

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "customweapons", "effects.json")


def key(t, c, s, r=(0, 0, 0)):
    return {"t": t, "c": [round(v, 3) for v in c], "s": [round(v, 3) for v in s], "r": [round(v, 1) for v in r]}


def part(block, keys, follow=True, bright=False, ease="smooth", glow=None):
    p = {"block": block, "follow": follow, "bright": bright, "ease": ease, "keys": keys}
    if glow is not None:
        p["glow"] = glow
    return p


def cube(s):
    return (s, s, s)


def arc_out(rng, block, count, t0, t1, r0, r1, y0, peak, size, spin=True, follow=False, bright=False, drop=None):
    """`count` chunks flung outward in a ring, rising to `peak` and landing (or dropping to
    `drop`) by t1, each on its own angle and with a little scatter."""
    parts = []
    for i in range(count):
        a = (2 * math.pi * i / count) + rng.uniform(-0.25, 0.25)
        rr1 = r1 * rng.uniform(0.75, 1.15)
        sz = size * rng.uniform(0.7, 1.3)
        keys = []
        steps = 6
        for k in range(steps + 1):
            u = k / steps
            t = t0 + (t1 - t0) * u
            r = r0 + (rr1 - r0) * u
            y = y0 + peak * 4 * u * (1 - u) if drop is None else y0 + peak * 4 * u * (1 - u) - (drop * u * u)
            rot = (u * 360 * rng.choice((-1, 1)), a * 57.3, u * 180) if spin else (0, 0, 0)
            s = sz if k < steps else sz * 0.2
            keys.append(key(t, (math.cos(a) * r, max(y, 0.05), math.sin(a) * r), cube(s), rot))
        parts.append(part(block, keys, follow=follow, bright=bright, ease="linear"))
    return parts


def ring(block, count, t0, t1, r0, r1, y, size, thick, fade_from=0.6, follow=False, bright=False):
    """A flat expanding ring of slabs, thinning out as it goes."""
    parts = []
    for i in range(count):
        a = 2 * math.pi * i / count
        keys = []
        steps = 5
        for k in range(steps + 1):
            u = k / steps
            t = t0 + (t1 - t0) * u
            r = r0 + (r1 - r0) * u
            f = 1.0 if u < fade_from else max(0.0, 1 - (u - fade_from) / (1 - fade_from))
            w = size * (0.6 + r / max(r1, 0.1)) * f
            keys.append(key(t, (math.cos(a) * r, y, math.sin(a) * r), (w, thick * f + 0.01, size * 0.5 * f + 0.01),
                            (0, -math.degrees(a), 0)))
        parts.append(part(block, keys, follow=follow, bright=bright, ease="linear"))
    return parts


def build():
    fx = {}
    rng = random.Random("customweapons-fx")

    # ---------------------------------------------------------------- Frostbrand
    # A hit: three ice shards burst out of the chest and melt away.
    shards = []
    for i in range(3):
        a = rng.uniform(0, 2 * math.pi)
        d0 = (math.cos(a) * 0.3, 1.1 + rng.uniform(-0.2, 0.3), math.sin(a) * 0.3)
        d1 = (math.cos(a) * 0.9, d0[1] + 0.4, math.sin(a) * 0.9)
        shards.append(part("minecraft:packed_ice", [
            key(0, d0, cube(0.05), (0, 0, 0)),
            key(2, d1, cube(0.22), (45, a * 57.3, 45)),
            key(7, (d1[0], d1[1] - 0.3, d1[2]), cube(0.01), (90, a * 57.3, 90)),
        ], bright=True))
    fx["frost_hit"] = {"ticks": 8, "parts": shards}

    # Frozen solid: the ice closes around them from a point, holds while they are rooted,
    # then bursts. Four crystal spikes grow out of the ground around the block.
    cube_keys = [
        key(0, (0, 1.0, 0), cube(0.2)),
        key(3, (0, 1.05, 0), (1.45, 2.45, 1.45)),
        key(6, (0, 1.0, 0), (1.25, 2.25, 1.25)),
        key(38, (0, 1.0, 0), (1.25, 2.25, 1.25)),
        key(41, (0, 1.05, 0), (1.4, 2.4, 1.4)),
        key(44, (0, 1.0, 0), cube(0.01)),
    ]
    parts = [part("minecraft:ice", cube_keys, bright=True)]
    for i in range(4):
        a = math.pi / 4 + i * math.pi / 2
        x, z = math.cos(a) * 0.95, math.sin(a) * 0.95
        lean = (math.degrees(-math.sin(a)) * 0.35, 0, math.degrees(math.cos(a)) * 0.35)
        parts.append(part("minecraft:packed_ice", [
            key(2, (x, 0.0, z), (0.01, 0.01, 0.01), lean),
            key(8, (x, 0.55, z), (0.28, 1.1, 0.28), lean),
            key(38, (x, 0.55, z), (0.28, 1.1, 0.28), lean),
            key(44, (x, 0.2, z), (0.01, 0.01, 0.01), lean),
        ], bright=True))
    fx["frost_shatter"] = {"ticks": 45, "parts": parts}

    # The Ice Beam's freeze: the same ice, held for three seconds before it bursts.
    def stretch(part, hold_from, hold_to, end):
        keys = []
        for k in part["keys"]:
            t = k["t"]
            if t >= 38:
                t = end - (45 - t)          # the burst keeps its shape, moved to the end
            keys.append(dict(k, t=t))
        return dict(part, keys=keys)
    fx["frost_beam"] = {"ticks": 65, "parts": [stretch(p, 38, 58, 65) for p in parts]}

    # ---------------------------------------------------------------- Bloodletter
    # A crescent cut across the chest, blade after blade, then three drops fall.
    parts = []
    n = 6
    for i in range(n):
        a = math.radians(-55 + 110 * i / (n - 1))
        c = (math.sin(a) * 0.75, 1.15 + math.cos(a) * 0.35 - 0.2, 0.45)
        parts.append(part("minecraft:red_stained_glass", [
            key(i * 0.5, c, (0.01, 0.01, 0.01), (0, 0, -math.degrees(a))),
            key(i * 0.5 + 1.5, c, (0.5, 0.08, 0.1), (0, 0, -math.degrees(a))),
            key(i * 0.5 + 4.5, (c[0], c[1] - 0.1, c[2]), (0.01, 0.01, 0.01), (0, 0, -math.degrees(a))),
        ], bright=True))
    for i in range(3):
        x = rng.uniform(-0.4, 0.4)
        parts.append(part("minecraft:redstone_block", [
            key(2, (x, 1.0, 0.4), cube(0.01)),
            key(4, (x, 0.9, 0.45), cube(0.1)),
            key(9, (x, 0.05, 0.5), cube(0.08)),
            key(11, (x, 0.02, 0.5), cube(0.01)),
        ], ease="linear"))
    fx["blood_slash"] = {"ticks": 12, "parts": parts}

    # ------------------------------------------------------------------ Gale Edge
    # The dash: a ring of air bursts out from where you stood.
    parts = ring("minecraft:white_stained_glass", 10, 0, 8, 0.4, 2.6, 0.9, 0.35, 0.12, fade_from=0.4)
    parts += ring("minecraft:light_blue_stained_glass", 6, 1, 7, 0.2, 1.6, 1.3, 0.25, 0.08, fade_from=0.5)
    fx["wind_dash"] = {"ticks": 9, "parts": parts}
    # The Momentum Strike: shards of air thrown off the target.
    fx["wind_hit"] = {"ticks": 8, "parts": arc_out(rng, "minecraft:white_stained_glass", 7, 0, 7, 0.2, 1.4, 1.1, 0.4,
                                                     0.16, follow=True, bright=True)}

    # --------------------------------------------------------------- Aegis Hammer
    # The slam: a purple flash at the head, a shockwave ring, and debris flung out.
    parts = [part("minecraft:crying_obsidian", [
        key(0, (0, 0.3, 0.9), cube(0.2)),
        key(2, (0, 0.5, 0.9), cube(1.1), (0, 45, 0)),
        key(6, (0, 0.4, 0.9), cube(0.01), (0, 135, 0)),
    ], follow=False, bright=True)]
    parts += ring("minecraft:stone", 14, 1, 11, 0.6, 5.2, 0.06, 0.5, 0.1, fade_from=0.5)
    parts += arc_out(rng, "minecraft:cobbled_deepslate", 12, 1, 13, 0.6, 3.8, 0.1, 1.1, 0.3)
    parts += arc_out(rng, "minecraft:gilded_blackstone", 4, 1, 10, 0.4, 2.4, 0.2, 1.4, 0.18)
    fx["slam_wave"] = {"ticks": 14, "parts": parts}

    # ----------------------------------------------------------------- Tidecaller
    # Water thrown up around the target, on a wet hit and when the harpoon lands.
    parts = arc_out(rng, "minecraft:light_blue_stained_glass", 9, 0, 9, 0.2, 1.3, 0.6, 1.2, 0.18, follow=True,
                    bright=True, drop=0.8)
    parts += [part("minecraft:blue_stained_glass", [
        key(0, (0, 0.3, 0), (0.6, 0.05, 0.6)),
        key(3, (0, 1.2, 0), (0.5, 1.8, 0.5), (0, 30, 0)),
        key(8, (0, 2.0, 0), (0.01, 0.01, 0.01), (0, 90, 0)),
    ], bright=True)]
    fx["tide_splash"] = {"ticks": 10, "parts": parts}

    # --------------------------------------------------------------- Stormpiercer
    # The shock: four glowing rods drop from the sky and circle the target, and a spark
    # pulses at the chest, for as long as the mark lasts.
    parts = []
    for i in range(4):
        keys = []
        for t in range(0, 25, 3):
            a = i * math.pi / 2 + t * 0.22
            y = 1.0 if t >= 3 else 4.5
            h = 2.2 if 3 <= t <= 21 else 0.01
            keys.append(key(t, (math.cos(a) * 1.1, y, math.sin(a) * 1.1), (0.6, h, 0.6), (0, -math.degrees(a), 0)))
        parts.append(part("minecraft:end_rod", keys, bright=True, ease="linear"))
    parts.append(part("minecraft:light_blue_stained_glass", [
        key(0, (0, 1.1, 0), cube(0.01)),
        key(2, (0, 1.1, 0), cube(0.9), (45, 45, 0)),
        key(5, (0, 1.1, 0), cube(0.3), (45, 135, 0)),
        key(9, (0, 1.1, 0), cube(0.7), (45, 225, 0)),
        key(14, (0, 1.1, 0), cube(0.25), (45, 315, 0)),
        key(20, (0, 1.1, 0), cube(0.5), (45, 405, 0)),
        key(24, (0, 1.1, 0), cube(0.01), (45, 495, 0)),
    ], bright=True))
    fx["storm_cage"] = {"ticks": 25, "parts": parts}

    # ------------------------------------------------------------------- Hellfire
    # The blast: fire flares out of the point of impact, embers arc away, and the ground
    # is scorched for a while.
    parts = []
    for i in range(6):
        a = i * math.pi / 3
        x, z = math.cos(a) * 0.5, math.sin(a) * 0.5
        parts.append(part("minecraft:fire", [
            key(0, (x, 0.2, z), cube(0.2)),
            key(2, (x * 1.8, 0.8, z * 1.8), (1.2, 1.6, 1.2), (0, -math.degrees(a), 0)),
            key(7, (x * 2.4, 1.6, z * 2.4), cube(0.01), (0, -math.degrees(a), 0)),
        ], follow=False, bright=True))
    parts += arc_out(rng, "minecraft:magma_block", 10, 0, 12, 0.3, 3.2, 0.3, 1.6, 0.22, bright=True)
    # A scorch mark: a thin dark slab lying on the ground, gone after five seconds.
    parts.append(part("minecraft:blackstone", [
        key(0, (0, 0.03, 0), cube(0.01), (0, 45, 0)),
        key(3, (0, 0.03, 0), (2.8, 0.06, 2.8), (0, 45, 0)),
        key(80, (0, 0.03, 0), (2.8, 0.06, 2.8), (0, 45, 0)),
        key(100, (0, 0.03, 0), cube(0.01), (0, 45, 0)),
    ], follow=False))
    fx["hell_burst"] = {"ticks": 100, "parts": parts}

    # ---------------------------------------------------------------- Dawnbreaker
    # The mark: a thin gold ring rises off the ground and tightens on the point, with a
    # faint shaft of light standing over it, for the second before the strike.
    parts = []
    n = 10
    for i in range(n):
        a = 2 * math.pi * i / n
        keys = []
        for t, r, y, w in ((0, 2.8, 0.05, 0.9), (10, 1.8, 0.6, 0.7), (18, 0.7, 1.3, 0.45), (20, 0.3, 1.5, 0.01)):
            keys.append(key(t, (math.cos(a) * r, y, math.sin(a) * r), (w, 0.08, 0.16), (0, -math.degrees(a), 0)))
        parts.append(part("minecraft:gold_block", keys, follow=False, bright=True, ease="linear"))
    parts.append(part("minecraft:yellow_stained_glass", [
        key(0, (0, 3.0, 0), (0.01, 0.01, 0.01)),
        key(4, (0, 3.0, 0), (0.35, 6.0, 0.35), (0, 20, 0)),
        key(16, (0, 3.0, 0), (0.25, 6.0, 0.25), (0, 120, 0)),
        key(20, (0, 3.0, 0), (0.01, 0.01, 0.01), (0, 160, 0)),
    ], follow=False, bright=True))
    fx["sun_telegraph"] = {"ticks": 20, "parts": parts}

    # The strike: a column of sunlight drops from fourteen blocks up, hits the ground and
    # flattens into a ring of gold that expands and fades; a few embers arc away.
    parts = [
        part("minecraft:glowstone", [
            key(0, (0, 14.0, 0), (0.8, 0.01, 0.8)),
            key(2, (0, 7.5, 0), (1.4, 13.0, 1.4)),
            key(5, (0, 3.0, 0), (2.2, 6.0, 2.2), (0, 30, 0)),
            key(9, (0, 1.0, 0), (3.4, 2.0, 3.4), (0, 60, 0)),
            key(14, (0, 0.3, 0), (4.6, 0.4, 4.6), (0, 90, 0)),
            key(18, (0, 0.1, 0), (0.01, 0.01, 0.01), (0, 110, 0)),
        ], follow=False, bright=True),
        part("minecraft:yellow_stained_glass", [
            key(0, (0, 14.0, 0), (1.6, 0.01, 1.6)),
            key(3, (0, 7.0, 0), (2.4, 14.0, 2.4), (0, 45, 0)),
            key(7, (0, 5.0, 0), (2.0, 10.0, 2.0), (0, 65, 0)),
            key(12, (0, 2.5, 0), (1.2, 5.0, 1.2), (0, 90, 0)),
            key(16, (0, 1.0, 0), (0.01, 0.01, 0.01), (0, 100, 0)),
        ], follow=False, bright=True),
    ]
    parts += ring("minecraft:gold_block", 12, 4, 22, 0.5, 4.2, 0.08, 0.55, 0.12, fade_from=0.45)
    parts += arc_out(rng, "minecraft:ochre_froglight", 6, 4, 28, 0.4, 3.4, 0.4, 1.6, 0.22, bright=True)
    fx["sunstrike"] = {"ticks": 30, "parts": parts}

    # ----------------------------------------------------------------- Voidreaper
    # The rift: a tear of crying-obsidian and purpur shards spiralling inward and closing
    # around a slit of void light, played both where the wielder left and where they land.
    parts = [part("minecraft:purple_stained_glass", [
        key(0, (0, 1.1, 0), (0.15, 2.2, 0.15)),
        key(3, (0, 1.1, 0), (0.45, 2.5, 0.45), (0, 45, 0)),
        key(9, (0, 1.1, 0), (0.12, 2.2, 0.12), (0, 135, 0)),
        key(12, (0, 1.1, 0), (0.01, 0.01, 0.01), (0, 180, 0)),
    ], follow=False, bright=True)]
    n = 8
    for i in range(n):
        a0 = 2 * math.pi * i / n
        y = 1.0 + rng.uniform(-0.5, 0.6)
        block = "minecraft:crying_obsidian" if i % 2 == 0 else "minecraft:purpur_block"
        keys = []
        for t, r, turn, s in ((0, 1.8, 0.0, 0.22), (4, 1.1, 1.4, 0.24), (8, 0.45, 2.8, 0.18), (11, 0.05, 3.6, 0.01)):
            a = a0 + turn
            keys.append(key(t, (math.cos(a) * r, y, math.sin(a) * r), cube(s), (turn * 60, math.degrees(a), turn * 40)))
        parts.append(part(block, keys, follow=False, bright=True, ease="linear"))
    fx["rift_open"] = {"ticks": 12, "parts": parts}

    # The harvest: four pale soul wisps rise out of the victim and drift up, fading.
    parts = [part("minecraft:soul_fire", [
        key(0, (0, 0.05, 0), (0.6, 0.01, 0.6)),
        key(3, (0, 0.5, 0), (0.9, 1.0, 0.9), (0, 30, 0)),
        key(8, (0, 0.9, 0), (0.01, 0.01, 0.01), (0, 60, 0)),
    ], follow=False, bright=True)]
    for i in range(4):
        a = math.pi / 4 + i * math.pi / 2
        x0, z0 = math.cos(a) * 0.35, math.sin(a) * 0.35
        keys = []
        for t in range(0, 17, 4):
            u = t / 16
            drift = math.sin(u * math.pi * 1.5 + i) * 0.35
            s = 0.28 * (1 - u * 0.9) if t < 16 else 0.01
            keys.append(key(t, (x0 + drift, 0.9 + u * 2.4, z0 - drift * 0.5), cube(s), (0, u * 180, u * 90)))
        parts.append(part("minecraft:soul_lantern", keys, follow=False, bright=True, ease="linear"))
    fx["soul_harvest"] = {"ticks": 16, "parts": parts}

    # ------------------------------------------------------------------- Starfall
    # The launch: magma and blackstone dust blown out in a ring at ground level, and a
    # short burst of embers straight up after the wielder.
    parts = ring("minecraft:magma_block", 8, 0, 8, 0.3, 2.8, 0.08, 0.4, 0.12, fade_from=0.4, bright=True)
    parts += ring("minecraft:blackstone", 10, 1, 9, 0.5, 3.2, 0.06, 0.35, 0.08, fade_from=0.5)
    for i in range(6):
        a = 2 * math.pi * i / 6
        x, z = math.cos(a) * 0.35, math.sin(a) * 0.35
        parts.append(part("minecraft:magma_block", [
            key(0, (x, 0.2, z), cube(0.05)),
            key(3, (x * 1.5, 1.8, z * 1.5), cube(0.2), (a * 57.3, 90, 0)),
            key(9, (x * 2.0, 4.0, z * 2.0), cube(0.01), (a * 57.3, 270, 0)),
        ], follow=False, bright=True))
    fx["comet_launch"] = {"ticks": 10, "parts": parts}

    # The impact: a flash of orange that expands and vanishes in four ticks, magma and
    # blackstone chunks arcing out, a flat ring of basalt, and amethyst shards flung up.
    parts = [part("minecraft:orange_stained_glass", [
        key(0, (0, 0.6, 0), cube(0.3)),
        key(2, (0, 1.0, 0), cube(3.2), (0, 45, 0)),
        key(4, (0, 1.0, 0), cube(0.01), (0, 90, 0)),
    ], follow=False, bright=True)]
    parts += arc_out(rng, "minecraft:magma_block", 8, 1, 16, 0.5, 4.6, 0.2, 1.8, 0.3, bright=True)
    parts += arc_out(rng, "minecraft:blackstone", 8, 1, 18, 0.5, 4.0, 0.2, 1.4, 0.36)
    parts += ring("minecraft:basalt", 12, 2, 16, 0.6, 5.2, 0.06, 0.5, 0.1, fade_from=0.5)
    for i in range(5):
        a = 2 * math.pi * i / 5 + rng.uniform(-0.3, 0.3)
        r = rng.uniform(0.3, 1.2)
        x, z = math.cos(a) * r, math.sin(a) * r
        h = rng.uniform(2.4, 3.6)
        parts.append(part("minecraft:amethyst_block", [
            key(1, (x, 0.3, z), cube(0.05)),
            key(10, (x * 1.4, h, z * 1.4), cube(0.22), (120, a * 57.3, 60)),
            key(26, (x * 1.8, 0.1, z * 1.8), cube(0.2), (300, a * 57.3, 200)),
            key(30, (x * 1.8, 0.05, z * 1.8), cube(0.01), (300, a * 57.3, 200)),
        ], follow=False, bright=True, ease="linear"))
    fx["meteor_impact"] = {"ticks": 30, "parts": parts}

    # ---------------------------------------------------------------- Bloodletter
    # Exsanguinate: every bleed bursts at once - shards of red flung out and down, and a
    # dark-red ring at chest height that expands and fades.
    parts = arc_out(rng, "minecraft:redstone_block", 8, 0, 10, 0.2, 1.6, 1.0, 0.5, 0.16, follow=True, drop=1.0)
    parts += ring("minecraft:nether_wart_block", 8, 1, 11, 0.3, 1.9, 1.1, 0.3, 0.08, fade_from=0.45, follow=True)
    fx["blood_burst"] = {"ticks": 12, "parts": parts}

    # --------------------------------------------------------------- Aegis Hammer
    # Stagger: three gold sparks orbit the head for the second the stun lasts, shrinking away.
    parts = []
    for i in range(3):
        keys = []
        for t in range(0, 21, 2):
            a = i * 2 * math.pi / 3 + t * 0.35
            s = 0.18 * (1 - t / 20) + 0.01
            keys.append(key(t, (math.cos(a) * 0.5, 2.2, math.sin(a) * 0.5), cube(s), (t * 20, -math.degrees(a), t * 20)))
        parts.append(part("minecraft:gold_block", keys, bright=True, ease="linear"))
    fx["stagger"] = {"ticks": 20, "parts": parts}

    return fx


def main():
    fx = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(fx, f, separators=(",", ":"))
    for name, e in fx.items():
        print(f"{name:16s} {len(e['parts']):3d} parts  {e['ticks']:3d} ticks")
    print(OUT)


if __name__ == "__main__":
    main()
