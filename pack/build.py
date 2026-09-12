#!/usr/bin/env python3
"""Builds the CustomWeapons resource pack and a browser preview of every model.

    python3 build.py            -> customweapons-models/ (the pack folder),
                                   ../release/CustomWeapons-Models.zip,
                                   preview.html (open it in a browser)

The pack overrides the item-model definition of each base item so that a stack carrying
custom_model_data string "cw:<weapon>" (which the mod stamps on every weapon) picks the
weapon's model, and everything else falls through to the vanilla definition unchanged.
"""
import base64
import io
import json
import os
import shutil
import zipfile

import weapons

ROOT = os.path.dirname(os.path.abspath(__file__))
NS = "customweapons"
PACK = os.path.join(ROOT, "customweapons-models")
ZIP = os.path.join(os.path.dirname(ROOT), "release", "CustomWeapons-Models.zip")
PREVIEW = os.path.join(ROOT, "preview.html")
VENDOR = os.path.join(ROOT, "vendor", "beyond-pack")

# The vanilla 1.21.11 model trees these definitions fall back to, copied from the client.
VANILLA = {
    "netherite_sword": {"type": "minecraft:model", "model": "minecraft:item/netherite_sword"},
    "diamond_sword": {"type": "minecraft:model", "model": "minecraft:item/diamond_sword"},
    "iron_sword": {"type": "minecraft:model", "model": "minecraft:item/iron_sword"},
    "netherite_axe": {"type": "minecraft:model", "model": "minecraft:item/netherite_axe"},
    "golden_sword": {"type": "minecraft:model", "model": "minecraft:item/golden_sword"},
    "netherite_hoe": {"type": "minecraft:model", "model": "minecraft:item/netherite_hoe"},
    "mace": {"type": "minecraft:model", "model": "minecraft:item/mace"},
    "trident": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": ["gui", "ground", "fixed", "on_shelf"],
                   "model": {"type": "minecraft:model", "model": "minecraft:item/trident"}}],
        "fallback": {
            "type": "minecraft:condition", "property": "minecraft:using_item",
            "on_false": {"type": "minecraft:special", "base": "minecraft:item/trident_in_hand",
                         "model": {"type": "minecraft:trident"}},
            "on_true": {"type": "minecraft:special", "base": "minecraft:item/trident_throwing",
                        "model": {"type": "minecraft:trident"}}}},
    "bow": {
        "type": "minecraft:condition", "property": "minecraft:using_item",
        "on_false": {"type": "minecraft:model", "model": "minecraft:item/bow"},
        "on_true": {
            "type": "minecraft:range_dispatch", "property": "minecraft:use_duration", "scale": 0.05,
            "entries": [{"threshold": 0.65, "model": {"type": "minecraft:model", "model": "minecraft:item/bow_pulling_1"}},
                        {"threshold": 0.9, "model": {"type": "minecraft:model", "model": "minecraft:item/bow_pulling_2"}}],
            "fallback": {"type": "minecraft:model", "model": "minecraft:item/bow_pulling_0"}}},
    "crossbow": {
        "type": "minecraft:select", "property": "minecraft:charge_type",
        "cases": [{"when": "arrow", "model": {"type": "minecraft:model", "model": "minecraft:item/crossbow_arrow"}},
                  {"when": "rocket", "model": {"type": "minecraft:model", "model": "minecraft:item/crossbow_firework"}}],
        "fallback": {
            "type": "minecraft:condition", "property": "minecraft:using_item",
            "on_false": {"type": "minecraft:model", "model": "minecraft:item/crossbow"},
            "on_true": {
                "type": "minecraft:range_dispatch", "property": "minecraft:crossbow/pull",
                "entries": [{"threshold": 0.58, "model": {"type": "minecraft:model", "model": "minecraft:item/crossbow_pulling_1"}},
                            {"threshold": 1.0, "model": {"type": "minecraft:model", "model": "minecraft:item/crossbow_pulling_2"}}],
                "fallback": {"type": "minecraft:model", "model": "minecraft:item/crossbow_pulling_0"}}}},
}

BASE_ITEM = {
    "bloodletter": "netherite_sword", "gale_edge": "diamond_sword", "frostbrand": "iron_sword",
    "aegis_hammer": "netherite_axe", "tidecaller": "trident", "stormpiercer": "bow", "hellfire": "crossbow",
    "dawnbreaker": "golden_sword", "voidreaper": "netherite_hoe", "starfall": "mace",
}

DISPLAY_NAME = {
    "bloodletter": "Bloodletter", "gale_edge": "Gale Edge", "frostbrand": "Frostbrand",
    "aegis_hammer": "Aegis Hammer", "tidecaller": "Tidecaller", "stormpiercer": "Stormpiercer", "hellfire": "Hellfire",
    "dawnbreaker": "Dawnbreaker", "voidreaper": "Voidreaper", "starfall": "Starfall",
}


def m(name):
    return {"type": "minecraft:model", "model": f"{NS}:item/{name}"}


# ------------------------------------------------------------------ animations
# One motion per weapon, as keyframes of (t 0..1, rotation delta, translation delta, scale
# factor) applied on top of the weapon's resting first-person pose. First-person axes:
# +x right, +y up, +z towards the camera, so a thrust is -z. Third person reuses the same
# keys with the translation halved. The mod counts frames 1..FRAMES over as many ticks.
FRAMES = 10

ANIMS = {
    # The game already swings the arm on every attack, so these stay small: a tilt and a
    # short push, enough to read as a wind-up and a cut without leaving the screen.
    "slash": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),
              (0.2, (-18, 10, 28), (-0.5, 0.9, -0.4), 1.0),
              (0.55, (22, -18, -40), (1.0, -1.0, -1.4), 1.04),
              (0.75, (12, -9, -22), (0.5, -0.6, -0.9), 1.0),
              (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "thrust": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),
               (0.25, (-8, 0, 8), (0.3, 0.3, 0.9), 1.0),
               (0.55, (10, 0, -12), (-0.5, -0.3, -2.8), 1.04),
               (0.8, (4, 0, -4), (-0.2, 0, -1.0), 1.0),
               (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "slam": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),
             (0.3, (-38, 0, 8), (0, 2.4, 0.5), 1.0),
             (0.55, (42, 0, -14), (0.3, -2.2, -1.8), 1.08),
             (0.7, (32, 0, -11), (0.3, -1.8, -1.4), 1.0),
             (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "shot": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),
             (0.15, (-14, 4, 0), (0.3, 0.7, 1.2), 1.08),
             (0.35, (6, -3, 0), (-0.2, 0.15, 0.4), 1.03),
             (0.55, (-4, 2, 0), (0.15, 0.2, 0.2), 1.01),
             (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "blast": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),
              (0.15, (-20, 0, 6), (0.3, 1.0, 2.0), 1.1),
              (0.45, (-6, 0, 2), (0.15, 0.4, 0.7), 1.03),
              (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
}
# The ultimates (sneak + left-click, frames 11..20): bigger motions than the ability ones,
# each a different shape - a full spin, a raise-and-slam, a sky-point, a sweep.
ULT_ANIMS = {
    "spin": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),            # the blade whipped round once
             (0.2, (-25, 60, 30), (-0.6, 0.8, -0.3), 1.05),
             (0.5, (10, 200, -40), (0.8, 0.2, -1.6), 1.12),
             (0.75, (20, 320, -20), (0.3, -0.5, -1.0), 1.05),
             (1.0, (0, 360, 0), (0, 0, 0), 1.0)],
    "raise_slam": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),      # lifted high, driven into the ground
                   (0.35, (-70, 0, 12), (0, 3.5, 0.8), 1.08),
                   (0.55, (55, 0, -20), (0.4, -3.0, -2.2), 1.18),
                   (0.75, (45, 0, -16), (0.4, -2.6, -1.8), 1.08),
                   (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "sky": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),             # pointed at the sky and held there
            (0.25, (-90, 0, 0), (0.2, 3.0, -0.5), 1.05),
            (0.65, (-95, 0, 0), (0.2, 3.4, -0.5), 1.15),
            (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "sweep": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),           # a wide horizontal sweep
              (0.2, (0, 70, 20), (-1.6, 0.5, 0.4), 1.05),
              (0.55, (0, -80, -20), (1.8, -0.3, -1.2), 1.12),
              (0.8, (0, -30, -8), (0.6, 0, -0.5), 1.03),
              (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
    "recoil": [(0.0, (0, 0, 0), (0, 0, 0), 1.0),          # a huge shot: the weapon kicks back hard
               (0.12, (-35, 8, 0), (0.6, 1.4, 2.6), 1.2),
               (0.4, (-12, 3, 0), (0.3, 0.6, 1.0), 1.08),
               (0.7, (4, -1, 0), (-0.1, 0.1, 0.3), 1.02),
               (1.0, (0, 0, 0), (0, 0, 0), 1.0)],
}
WEAPON_ULT = {
    "bloodletter": "spin", "frostbrand": "sky", "gale_edge": "spin", "tidecaller": "sweep",
    "aegis_hammer": "raise_slam", "stormpiercer": "sky", "hellfire": "recoil",
    "dawnbreaker": "sky", "voidreaper": "sweep", "starfall": "raise_slam",
}
WEAPON_ANIM = {
    "bloodletter": "slash", "frostbrand": "slash", "gale_edge": "thrust", "tidecaller": "thrust",
    "aegis_hammer": "slam", "stormpiercer": "shot", "hellfire": "blast",
    "dawnbreaker": "slash", "voidreaper": "slash", "starfall": "slam",
}


def lerp(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


def keyframe_at(keys, t):
    """Smooth (ease in/out) interpolation between the two keys around t."""
    for (t0, r0, p0, s0), (t1, r1, p1, s1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            u = 0.0 if t1 == t0 else (t - t0) / (t1 - t0)
            u = u * u * (3 - 2 * u)
            return lerp(r0, r1, u), lerp(p0, p1, u), s0 + (s1 - s0) * u
    return keys[-1][1], keys[-1][2], keys[-1][3]


def posed(base, rot, pos, scale, mirror):
    """A display entry: the resting pose plus a delta, mirrored for the left hand."""
    rx, ry, rz = rot
    px, py, pz = pos
    if mirror:
        ry, rz, px = -ry, -rz, -px
    return {
        "rotation": [round(base["rotation"][0] + rx, 2), round(base["rotation"][1] + ry, 2),
                     round(base["rotation"][2] + rz, 2)],
        "translation": [round(base["translation"][0] + px, 3), round(base["translation"][1] + py, 3),
                        round(base["translation"][2] + pz, 3)],
        "scale": [round(c * scale, 3) for c in base["scale"]],
    }


def frame_display(display, keys, frame):
    """Display block for one frame, all four hand contexts."""
    t = frame / FRAMES
    rot, pos, scale = keyframe_at(keys, t)
    third = tuple(c * 0.5 for c in pos)
    return {
        "firstperson_righthand": posed(display["firstperson_righthand"], rot, pos, scale, False),
        "firstperson_lefthand": posed(display["firstperson_lefthand"], rot, pos, scale, True),
        "thirdperson_righthand": posed(display["thirdperson_righthand"], rot, third, scale, False),
        "thirdperson_lefthand": posed(display["thirdperson_lefthand"], rot, third, scale, True),
    }


def frame_models(weapon, builder, display):
    """One full model per frame: the weapon, its trail or flash for that frame, and the
    hand pose for that frame."""
    keys = ANIMS[WEAPON_ANIM[weapon]]
    ult = ULT_ANIMS[WEAPON_ULT[weapon]]
    out = {}
    for frame in range(1, FRAMES + 1):
        model, _ = weapons.build_frame(weapon, builder, display, frame, NS)
        model["display"] = display if os.environ.get("CW_NOPOSE") else frame_display(display, keys, frame)
        out[f"{weapon}_f{frame}"] = model
        # The ultimate's frame: the same trail/flash geometry, the bigger pose.
        ult_model, _ = weapons.build_frame(weapon, builder, display, frame, NS)
        ult_model["display"] = display if os.environ.get("CW_NOPOSE") else frame_display(display, ult, frame)
        out[f"{weapon}_f{FRAMES + frame}"] = ult_model
    if os.environ.get("CW_DEBUG_FRAMES"):
        # 11: the resting model re-parented with frame 5's pose; 12: frame 5's geometry, resting pose
        out[f"{weapon}_f21"] = {"parent": f"{NS}:item/{weapon}", "display": frame_display(display, keys, 5)}
        m22, _ = weapons.build_frame(weapon, builder, display, 5, NS)
        out[f"{weapon}_f22"] = m22
    return out


def animated(weapon, idle_tree):
    """Frame k while the mod has written k into the item, the idle tree otherwise."""
    return {
        "type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
        "entries": [{"threshold": k, "model": m(f"{weapon}_f{k}")}
                    for k in range(1, 2 * FRAMES + (3 if os.environ.get("CW_DEBUG_FRAMES") else 1))],
        "fallback": idle_tree,
    }


def weapon_tree(weapon):
    """The model tree used when the stack is this weapon."""
    if weapon == "stormpiercer":
        return {
            "type": "minecraft:condition", "property": "minecraft:using_item",
            "on_false": m("stormpiercer"),
            "on_true": {
                "type": "minecraft:range_dispatch", "property": "minecraft:use_duration", "scale": 0.05,
                "entries": [{"threshold": 0.65, "model": m("stormpiercer_pulling_1")},
                            {"threshold": 0.9, "model": m("stormpiercer_pulling_2")}],
                "fallback": m("stormpiercer_pulling_0")}}
    if weapon == "hellfire":
        return {
            "type": "minecraft:select", "property": "minecraft:charge_type",
            "cases": [{"when": "arrow", "model": m("hellfire_loaded")},
                      {"when": "rocket", "model": m("hellfire_loaded")}],
            "fallback": {
                "type": "minecraft:condition", "property": "minecraft:using_item",
                "on_false": m("hellfire"),
                "on_true": {
                    "type": "minecraft:range_dispatch", "property": "minecraft:crossbow/pull",
                    "entries": [{"threshold": 0.58, "model": m("hellfire_pulling_1")},
                                {"threshold": 1.0, "model": m("hellfire_pulling_2")}],
                    "fallback": m("hellfire_pulling_0")}}}
    return m(weapon)


def item_definitions():
    """One assets/minecraft/items/<base>.json per base item, selecting on custom_model_data."""
    by_base = {}
    for weapon, base in BASE_ITEM.items():
        by_base.setdefault(base, []).append(weapon)
    out = {}
    for base, ws in by_base.items():
        out[base] = {
            # The animation swaps the model ten times in half a second; without this the
            # client would play its "new item" lower-and-raise on every frame.
            "hand_animation_on_swap": False,
            "model": {
                "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
                "cases": [{"when": f"cw:{w}", "model": animated(w, weapon_tree(w))} for w in ws],
                "fallback": VANILLA[base]}}
    return out


def write_pack():
    if os.path.exists(PACK):
        shutil.rmtree(PACK)
    models_dir = os.path.join(PACK, "assets", NS, "models", "item")
    tex_dir = os.path.join(PACK, "assets", NS, "textures", "item")
    items_dir = os.path.join(PACK, "assets", "minecraft", "items")
    for d in (models_dir, tex_dir, items_dir):
        os.makedirs(d)

    with open(os.path.join(PACK, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"pack_format": 75, "min_format": [75, 0], "max_format": [75, 99],
                            "description": "CustomWeapons: 3D models for the ten legendaries"}}, f, indent=2)

    models = {}
    counts = {}
    for weapon, variant, builder, display in weapons.VARIANTS:
        model, n = weapons.build(weapon, variant, builder, display, NS)
        models[variant] = model
        counts[variant] = n
        with open(os.path.join(models_dir, variant + ".json"), "w") as f:
            json.dump(model, f, separators=(",", ":"))
    idle = {w: (b, d) for w, v, b, d in weapons.VARIANTS if w == v}
    frames = {}
    for weapon, (builder, display) in idle.items():
        frames[weapon] = frame_models(weapon, builder, display)
        for name, model in frames[weapon].items():
            with open(os.path.join(models_dir, name + ".json"), "w") as f:
                json.dump(model, f, separators=(",", ":"))
    textures = weapons.textures(NS)
    for weapon, img in textures.items():
        img.save(os.path.join(tex_dir, weapon + ".png"))
    definitions = item_definitions()
    # Beyond the End's item models ride along (a snapshot of its built pack in pack/vendor):
    # both mods override the same five vanilla item files, a client keeps one file per
    # path, so each pack carries the other's cases and the two servers packs agree.
    vendored = 0
    if os.path.isdir(VENDOR):
        for folder, _, files in os.walk(os.path.join(VENDOR, "assets", "beyond")):
            for fn in files:
                src = os.path.join(folder, fn)
                dst = os.path.join(PACK, os.path.relpath(src, VENDOR))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copy(src, dst)
                vendored += 1
        their_items = os.path.join(VENDOR, "assets", "minecraft", "items")
        for fn in sorted(os.listdir(their_items)):
            base = fn[:-5]
            with open(os.path.join(their_items, fn)) as f:
                theirs = json.load(f)
            their_cases = [c for c in theirs["model"]["cases"] if str(c.get("when", "")).startswith("beyond:")]
            if base in definitions:
                definitions[base]["model"]["cases"] += their_cases
            else:
                definitions[base] = theirs
        print(f"folded in {vendored} Beyond the End files")
    for base, definition in definitions.items():
        with open(os.path.join(items_dir, base + ".json"), "w") as f:
            json.dump(definition, f, indent=2)

    os.makedirs(os.path.dirname(ZIP), exist_ok=True)
    # Fixed timestamps: the sha1 the server config carries only changes with content.
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for folder, _, files in sorted(os.walk(PACK)):
            for name in sorted(files):
                path = os.path.join(folder, name)
                info = zipfile.ZipInfo(os.path.relpath(path, PACK), date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(path, "rb") as f:
                    z.writestr(info, f.read())
    return models, textures, counts, frames


# ---------------------------------------------------------------------- preview
HTML = r"""<!doctype html>
<html><head><meta charset="utf-8"><title>CustomWeapons models</title>
<style>
  body{margin:0;background:#1d1f24;color:#e8e8ec;font:14px/1.4 system-ui,sans-serif}
  h1{font-size:18px;margin:16px 20px 4px}
  p.hint{margin:0 20px 12px;color:#9aa}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px;padding:0 20px 24px}
  .card{background:#272a31;border-radius:10px;padding:10px;display:flex;flex-direction:column;gap:6px}
  .card h2{font-size:15px;margin:2px 4px}
  .card canvas{width:100%;height:280px;border-radius:8px;background:radial-gradient(#3a3e48,#20222a);cursor:grab;display:block}
  .row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
  .row canvas.tex{width:64px;height:64px;image-rendering:pixelated;background:#111;border-radius:4px}
  .meta{color:#9aa;font-size:12px}
  select,button{background:#3a3e48;color:#eee;border:0;border-radius:6px;padding:4px 8px;font:inherit}
  .card canvas.gui{width:64px;height:64px;image-rendering:pixelated;background:#8b8b8b;border:2px solid #373737;border-radius:4px;cursor:default}
</style>
<script src="https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js"></script>
</head><body>
<h1>CustomWeapons – 3D weapon models (draft)</h1>
<p class="hint">Drag to rotate, scroll to zoom. The small grey box is how the item shows in the inventory slot.
Bow and crossbow have a dropdown for their draw stages. <b>Attack</b> plays the weapon's attack animation
(the motion the pack adds on top of the hand pose for half a second after an ability fires).</p>
<div class="grid" id="grid"></div>
<h1>World effects</h1>
<p class="hint">What every player sees in the world when an ability lands, pack or not: these are real blocks
placed and moved by the server around the target (the grey figure). Drag to look around; the effect loops.</p>
<div class="grid" id="fxgrid"></div>
<script>
const DATA = __DATA__;
const NAMES = __NAMES__;
const GROUPS = __GROUPS__;

function loadTexture(dataUri){
  const t = new THREE.TextureLoader().load(dataUri);
  t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter;
  t.flipY = false;
  return t;
}

// Minecraft element -> three.js mesh. UVs are 0..16 over the whole texture.
function buildModel(model, texture){
  const group = new THREE.Group();
  const mat = new THREE.MeshLambertMaterial({map: texture, side: THREE.DoubleSide, transparent: true, alphaTest: 0.05});
  for (const el of model.elements){
    const [x0,y0,z0] = el.from, [x1,y1,z1] = el.to;
    const geo = new THREE.BoxGeometry(x1-x0, y1-y0, z1-z0);
    // three.js box face order: +x, -x, +y, -y, +z, -z  == east, west, up, down, south, north
    const order = ['east','west','up','down','south','north'];
    const uv = geo.attributes.uv;
    order.forEach((side, i) => {
      const f = el.faces[side]; if (!f) return;
      const [u0,v0,u1,v1] = f.uv.map(c => c/16);
      const base = i*4;
      // vertices of each face in BoxGeometry: (0,1) top row, (2,3) bottom row
      const coords = [[u0,v0],[u1,v0],[u0,v1],[u1,v1]];
      coords.forEach(([u,v], k) => uv.setXY(base+k, u, v));
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set((x0+x1)/2, (y0+y1)/2, (z0+z1)/2);
    if (el.rotation){
      const r = el.rotation, o = new THREE.Vector3(...r.origin);
      const piv = new THREE.Group();
      piv.position.copy(o);
      mesh.position.sub(o);
      piv.add(mesh);
      const a = THREE.MathUtils.degToRad(r.angle);
      if (r.axis==='x') piv.rotation.x = a; if (r.axis==='y') piv.rotation.y = a; if (r.axis==='z') piv.rotation.z = a;
      group.add(piv);
    } else group.add(mesh);
  }
  const root = new THREE.Group();
  group.position.set(-8,-8,-8);
  root.add(group);
  return root;
}

function makeScene(canvas, model, texture, ortho){
  const renderer = new THREE.WebGLRenderer({canvas, antialias:true, alpha:true});
  renderer.setPixelRatio(window.devicePixelRatio);
  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 0.55));
  const key = new THREE.DirectionalLight(0xffffff, 0.75); key.position.set(3, 6, 8); scene.add(key);
  const fill = new THREE.DirectionalLight(0xffffff, 0.35); fill.position.set(-5, -2, -6); scene.add(fill);
  let camera;
  if (ortho){ camera = new THREE.OrthographicCamera(-9,9,9,-9,0.1,100); camera.position.set(0,0,30); }
  else { camera = new THREE.PerspectiveCamera(35, 1, 0.1, 200); camera.position.set(0, 0, 34); }
  camera.lookAt(0,0,0);
  const holder = new THREE.Group(); scene.add(holder);
  let current = null;
  function set(mdl){ if (current) holder.remove(current); current = buildModel(mdl, texture); holder.add(current); }
  set(model);
  function resize(){
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== w*devicePixelRatio || canvas.height !== h*devicePixelRatio){
      renderer.setSize(w, h, false);
      if (!ortho){ camera.aspect = w/h; camera.updateProjectionMatrix(); }
    }
  }
  return {renderer, scene, camera, holder, set, resize};
}

// ------------------------------------------------------------- world effects
const BLOCK_COLOUR = {
  'minecraft:ice': [0x9fd6ff, 0.55], 'minecraft:packed_ice': [0x8ec8f5, 1], 'minecraft:blue_ice': [0x74b8f0, 1],
  'minecraft:red_stained_glass': [0xd01c22, 0.6], 'minecraft:redstone_block': [0xb01010, 1],
  'minecraft:white_stained_glass': [0xffffff, 0.5], 'minecraft:light_blue_stained_glass': [0x8ad0ff, 0.6],
  'minecraft:blue_stained_glass': [0x4d78d8, 0.6], 'minecraft:crying_obsidian': [0x6a2aa8, 1],
  'minecraft:stone': [0x8d8d8d, 1], 'minecraft:cobbled_deepslate': [0x4c4c50, 1], 'minecraft:gilded_blackstone': [0xd8b04a, 1],
  'minecraft:end_rod': [0xf4f4ff, 1], 'minecraft:fire': [0xff8a1e, 0.85], 'minecraft:magma_block': [0xd9581c, 1],
  'minecraft:blackstone': [0x2a2a2e, 1], 'minecraft:sea_lantern': [0xc8f0e8, 1],
  'minecraft:gold_block': [0xf8d24a, 1], 'minecraft:yellow_stained_glass': [0xf6e04a, 0.5], 'minecraft:glowstone': [0xffe9a0, 1],
  'minecraft:ochre_froglight': [0xf6dc8c, 1], 'minecraft:purpur_block': [0xa878a8, 1], 'minecraft:purple_stained_glass': [0x8a34c8, 0.6],
  'minecraft:soul_lantern': [0x7ff0dc, 1], 'minecraft:soul_fire': [0x4ee0d8, 0.85], 'minecraft:orange_stained_glass': [0xf88a1e, 0.6],
  'minecraft:basalt': [0x55555a, 1], 'minecraft:amethyst_block': [0x9a6ee0, 1], 'minecraft:nether_wart_block': [0x720202, 1],
};
const FX_NAMES = {frost_hit: 'Frostbrand hit', frost_shatter: 'Frostbrand shatter (frozen solid)', blood_slash: 'Bloodletter cut',
  wind_dash: 'Gale Edge dash', wind_hit: 'Gale Edge momentum strike', slam_wave: 'Aegis Hammer ground slam',
  tide_splash: 'Tidecaller wet hit / harpoon', storm_cage: 'Stormpiercer shock', hell_burst: 'Hellfire blast',
  sun_telegraph: 'Dawnbreaker sunstrike mark', sunstrike: 'Dawnbreaker sunstrike', rift_open: 'Voidreaper rift',
  soul_harvest: 'Voidreaper soul harvest', comet_launch: 'Starfall comet launch', meteor_impact: 'Starfall impact',
  blood_burst: 'Bloodletter exsanguinate', stagger: 'Aegis Hammer stagger'};

function dummy(){
  const g = new THREE.Group();
  const skin = new THREE.MeshLambertMaterial({color: 0x777a85});
  const add = (w,h,d,x,y,z) => { const m = new THREE.Mesh(new THREE.BoxGeometry(w,h,d), skin); m.position.set(x,y,z); g.add(m); };
  add(0.25,0.75,0.25,-0.13,0.375,0); add(0.25,0.75,0.25,0.13,0.375,0);   // legs
  add(0.5,0.75,0.25,0,1.125,0);                                           // torso
  add(0.25,0.75,0.25,-0.375,1.125,0); add(0.25,0.75,0.25,0.375,1.125,0); // arms
  add(0.5,0.5,0.5,0,1.75,0);                                              // head
  return g;
}
function fxKeyAt(part, t){
  const keys = part.keys;
  if (t < keys[0].t) return null;
  let a = keys[keys.length-1], b = a;
  for (let i = 0; i+1 < keys.length; i++) if (t >= keys[i].t && t <= keys[i+1].t){ a = keys[i]; b = keys[i+1]; break; }
  let u = (b.t === a.t) ? 0 : (t - a.t)/(b.t - a.t);
  u = Math.max(0, Math.min(1, u));
  if (part.ease === 'out') u = 1-(1-u)*(1-u); else if (part.ease !== 'linear') u = u*u*(3-2*u);
  const L = (x,y)=>x+(y-x)*u;
  return {c: a.c.map((v,k)=>L(v,b.c[k])), s: a.s.map((v,k)=>L(v,b.s[k])), r: a.r.map((v,k)=>L(v,b.r[k]))};
}
// Browsers allow only a handful of WebGL contexts per page, so every effect card shares one
// off-screen renderer and gets its picture copied into a plain 2D canvas each frame.
const fxGl = document.createElement('canvas');
const fxRenderer = new THREE.WebGLRenderer({canvas: fxGl, antialias: true, alpha: true});
fxRenderer.setPixelRatio(1);
const fxCards = [];
for (const [name, fx] of Object.entries(DATA.fx)){
  const card = document.createElement('div'); card.className='card';
  const title = document.createElement('h2'); title.textContent = FX_NAMES[name] || name; card.appendChild(title);
  const canvas = document.createElement('canvas'); card.appendChild(canvas);
  const meta = document.createElement('div'); meta.className='meta'; meta.textContent = fx.parts.length + ' blocks, ' + (fx.ticks/20).toFixed(1) + ' s'; card.appendChild(meta);
  document.getElementById('fxgrid').appendChild(card);
  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 0.6));
  const key = new THREE.DirectionalLight(0xffffff, 0.7); key.position.set(3, 8, 5); scene.add(key);
  const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 100);
  const world = new THREE.Group(); scene.add(world);
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(12,12), new THREE.MeshLambertMaterial({color: 0x3b3f4a}));
  floor.rotation.x = -Math.PI/2; world.add(floor);
  const grid = new THREE.GridHelper(12, 12, 0x555a66, 0x4a4e58); grid.position.y = 0.002; world.add(grid);
  world.add(dummy());
  const cubes = fx.parts.map(p => {
    const [col, op] = BLOCK_COLOUR[p.block] || [0xff00ff, 1];
    const m = new THREE.Mesh(new THREE.BoxGeometry(1,1,1), new THREE.MeshLambertMaterial({color: col, transparent: op < 1, opacity: op}));
    m.visible = false; world.add(m); return m;
  });
  const st = {drag:null, rx:0.5, ry:0.6, dist:7.5, start:performance.now()};
  canvas.onpointerdown = e => { st.drag=[e.clientX,e.clientY]; canvas.setPointerCapture(e.pointerId); };
  canvas.onpointerup = () => st.drag=null;
  canvas.onpointermove = e => { if(!st.drag) return; st.ry += (e.clientX-st.drag[0])*0.01; st.rx = Math.max(0.05, Math.min(1.4, st.rx + (e.clientY-st.drag[1])*0.01)); st.drag=[e.clientX,e.clientY]; };
  canvas.onwheel = e => { e.preventDefault(); st.dist = Math.min(20, Math.max(3, st.dist + e.deltaY*0.01)); };
  fxCards.push({fx, canvas, scene, camera, cubes, st});
}
function fxFrame(){
  for (const c of fxCards){
    const w = c.canvas.clientWidth, h = c.canvas.clientHeight;
    if (!w || !h) continue;
    if (c.canvas.width !== w || c.canvas.height !== h){ c.canvas.width = w; c.canvas.height = h; }
    fxRenderer.setSize(w, h, false);
    c.camera.aspect = w/h; c.camera.updateProjectionMatrix();
    const loop = c.fx.ticks + 20;
    const frozen = (location.hash.match(/t=(\d+)/) || [])[1];
    const t = frozen !== undefined ? Math.min(+frozen, c.fx.ticks) : ((performance.now()-c.st.start)/50) % loop;
    c.fx.parts.forEach((p, i) => {
      const k = t <= c.fx.ticks ? fxKeyAt(p, t) : null;
      const m = c.cubes[i];
      if (!k){ m.visible=false; return; }
      m.visible = true;
      m.position.set(k.c[0], k.c[1], k.c[2]);
      m.scale.set(Math.max(k.s[0],0.001), Math.max(k.s[1],0.001), Math.max(k.s[2],0.001));
      m.rotation.set(THREE.MathUtils.degToRad(k.r[0]), THREE.MathUtils.degToRad(k.r[1]), THREE.MathUtils.degToRad(k.r[2]), 'XYZ');
    });
    const s = c.st;
    c.camera.position.set(Math.sin(s.ry)*Math.cos(s.rx)*s.dist, Math.sin(s.rx)*s.dist + 1, Math.cos(s.ry)*Math.cos(s.rx)*s.dist);
    c.camera.lookAt(0, 1, 0);
    fxRenderer.render(c.scene, c.camera);
    const ctx = c.canvas.getContext('2d');
    ctx.clearRect(0, 0, w, h);
    ctx.drawImage(fxGl, 0, 0, w, h, 0, 0, w, h);
  }
  requestAnimationFrame(fxFrame);
}
fxFrame();

for (const g of GROUPS){
  const card = document.createElement('div'); card.className='card';
  const title = document.createElement('h2'); title.textContent = NAMES[g.weapon]; card.appendChild(title);
  const canvas = document.createElement('canvas'); card.appendChild(canvas);
  const row = document.createElement('div'); row.className='row';
  const gui = document.createElement('canvas'); gui.className='gui'; gui.width=128; gui.height=128; row.appendChild(gui);
  const tex = document.createElement('canvas'); tex.className='tex'; tex.width=64; tex.height=64; row.appendChild(tex);
  const meta = document.createElement('div'); meta.className='meta'; row.appendChild(meta);
  const play = document.createElement('button'); play.textContent = 'Attack'; row.appendChild(play);
  let select = null;
  if (g.variants.length > 1){
    select = document.createElement('select');
    for (const v of g.variants){ const o=document.createElement('option'); o.value=v; o.textContent=v.replace(g.weapon+'_','').replace(g.weapon,'idle'); select.appendChild(o); }
    row.appendChild(select);
  }
  card.appendChild(row);
  document.getElementById('grid').appendChild(card);

  const texture = loadTexture(DATA.textures[g.weapon]);
  const img = new Image(); img.onload = () => tex.getContext('2d').drawImage(img,0,0); img.src = DATA.textures[g.weapon];

  const view = makeScene(canvas, DATA.models[g.variants[0]], texture, false);
  // One pre-built mesh per animation frame, swapped in while the attack plays.
  const frameMeshes = DATA.frames[g.weapon].map(mdl => { const grp = buildModel(mdl, texture); grp.visible = false; view.holder.add(grp); return grp; });
  let idleGroup = null;
  const slot = makeScene(gui, DATA.models[g.variants[0]], texture, true);
  const show = (v) => { view.set(DATA.models[v]); idleGroup = view.holder.children[view.holder.children.length-1]; slot.set(DATA.models[v]); meta.textContent = DATA.counts[v] + ' elements'; };
  show(g.variants[0]);
  if (select) select.onchange = () => show(select.value);

  let drag = null, rx = 0.35, ry = -0.6, auto = true, dist = 34;
  // The attack animation: the pack's per-frame pose deltas, played at 20 fps in a loop
  // of four with a pause, on top of whatever angle the model is turned to.
  const anim = DATA.anims[g.weapon];
  let animStart = -1;
  play.onclick = () => { animStart = performance.now(); };
  canvas.onpointerdown = e => { drag = [e.clientX, e.clientY]; auto = false; canvas.setPointerCapture(e.pointerId); };
  canvas.onpointerup = () => drag = null;
  canvas.onpointermove = e => { if (!drag) return; ry += (e.clientX-drag[0])*0.01; rx += (e.clientY-drag[1])*0.01; drag=[e.clientX,e.clientY]; };
  canvas.onwheel = e => { e.preventDefault(); dist = Math.min(80, Math.max(12, dist + e.deltaY*0.03)); };
  canvas.ondblclick = () => { auto = true; };
  function frame(){
    view.resize(); slot.resize();
    if (auto) ry += 0.008;
    let d = {rot:[0,0,0], pos:[0,0,0], scale:1};
    let activeFrame = -1;
    if (animStart >= 0){
      const elapsed = (performance.now() - animStart) / 1000;
      const cycle = elapsed % 1.2;              // 0.5 s of motion, then a rest
      if (elapsed > 4.8) animStart = -1;
      else if (cycle < 0.5){
        const f = cycle / 0.5 * (anim.frames.length - 1);
        const i = Math.floor(f), u = f - i, a = anim.frames[i], b = anim.frames[Math.min(i+1, anim.frames.length-1)];
        d = {rot: a.rot.map((v,k)=>v+(b.rot[k]-v)*u), pos: a.pos.map((v,k)=>v+(b.pos[k]-v)*u), scale: a.scale+(b.scale-a.scale)*u};
        activeFrame = Math.min(frameMeshes.length-1, Math.max(0, Math.round(f) - 1));
      }
    }
    frameMeshes.forEach((grp, k) => grp.visible = (k === activeFrame));
    if (idleGroup) idleGroup.visible = (activeFrame < 0);
    const r = THREE.MathUtils.degToRad;
    view.holder.rotation.set(rx, ry, 0);
    view.holder.position.set(0,0,0); view.holder.scale.set(1,1,1);
    for (const c of view.holder.children){
      c.rotation.set(r(d.rot[0]), r(d.rot[1]), r(d.rot[2]), 'XYZ');
      c.position.set(d.pos[0], d.pos[1], d.pos[2]);
      c.scale.set(d.scale, d.scale, d.scale);
    }
    view.camera.position.set(0,0,dist);
    view.renderer.render(view.scene, view.camera);
    slot.renderer.render(slot.scene, slot.camera);
    requestAnimationFrame(frame);
  }
  frame();
}
</script>
</body></html>
"""


def write_preview(models, textures, counts, frames):
    tex_data = {}
    for weapon, img in textures.items():
        buf = io.BytesIO()
        img.save(buf, "PNG")
        tex_data[weapon] = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    groups = []
    for weapon in BASE_ITEM:
        groups.append({"weapon": weapon, "variants": [v for w, v, _, _ in weapons.VARIANTS if w == weapon]})
    displays = {w: d for w, v, _, d in weapons.VARIANTS if w == v}
    anims = {}
    for weapon, display in displays.items():
        keys = ANIMS[WEAPON_ANIM[weapon]]
        poses = []
        for frame in range(0, FRAMES + 1):
            rot, pos, scale = keyframe_at(keys, frame / FRAMES)
            poses.append({"rot": rot, "pos": pos, "scale": scale})
        anims[weapon] = {"name": WEAPON_ANIM[weapon], "frames": poses}
    import effects as fxmod
    fx = fxmod.build()
    frame_models_by_weapon = {w: [fm[f"{w}_f{k}"] for k in range(1, FRAMES + 1)] for w, fm in frames.items()}
    html = (HTML.replace("__DATA__", json.dumps({"models": models, "textures": tex_data, "counts": counts,
                                                 "anims": anims, "frames": frame_models_by_weapon, "fx": fx}))
            .replace("__NAMES__", json.dumps(DISPLAY_NAME))
            .replace("__GROUPS__", json.dumps(groups)))
    with open(PREVIEW, "w") as f:
        f.write(html)


def main():
    import effects as fxmod
    fxmod.main()
    models, textures, counts, frames = write_pack()
    write_preview(models, textures, counts, frames)
    for variant, n in counts.items():
        print(f"{variant:28s} {n:4d} elements")
    print(f"pack:    {PACK}")
    print(f"zip:     {ZIP}")
    print(f"preview: {PREVIEW}")


if __name__ == "__main__":
    main()
