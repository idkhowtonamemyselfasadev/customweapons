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

# The vanilla 1.21.11 model trees these definitions fall back to, copied from the client.
VANILLA = {
    "netherite_sword": {"type": "minecraft:model", "model": "minecraft:item/netherite_sword"},
    "diamond_sword": {"type": "minecraft:model", "model": "minecraft:item/diamond_sword"},
    "iron_sword": {"type": "minecraft:model", "model": "minecraft:item/iron_sword"},
    "netherite_axe": {"type": "minecraft:model", "model": "minecraft:item/netherite_axe"},
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
}

DISPLAY_NAME = {
    "bloodletter": "Bloodletter", "gale_edge": "Gale Edge", "frostbrand": "Frostbrand",
    "aegis_hammer": "Aegis Hammer", "tidecaller": "Tidecaller", "stormpiercer": "Stormpiercer", "hellfire": "Hellfire",
}


def m(name):
    return {"type": "minecraft:model", "model": f"{NS}:item/{name}"}


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
        out[base] = {"model": {
            "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
            "cases": [{"when": f"cw:{w}", "model": weapon_tree(w)} for w in ws],
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
                            "description": "CustomWeapons: 3D models for the seven legendaries"}}, f, indent=2)

    models = {}
    counts = {}
    for weapon, variant, builder, display in weapons.VARIANTS:
        model, n = weapons.build(weapon, variant, builder, display, NS)
        models[variant] = model
        counts[variant] = n
        with open(os.path.join(models_dir, variant + ".json"), "w") as f:
            json.dump(model, f, separators=(",", ":"))
    textures = weapons.textures(NS)
    for weapon, img in textures.items():
        img.save(os.path.join(tex_dir, weapon + ".png"))
    for base, definition in item_definitions().items():
        with open(os.path.join(items_dir, base + ".json"), "w") as f:
            json.dump(definition, f, indent=2)

    os.makedirs(os.path.dirname(ZIP), exist_ok=True)
    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for folder, _, files in os.walk(PACK):
            for name in files:
                path = os.path.join(folder, name)
                z.write(path, os.path.relpath(path, PACK))
    return models, textures, counts


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
Bow and crossbow have a dropdown for their draw stages.</p>
<div class="grid" id="grid"></div>
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
  const mat = new THREE.MeshLambertMaterial({map: texture, side: THREE.DoubleSide});
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

for (const g of GROUPS){
  const card = document.createElement('div'); card.className='card';
  const title = document.createElement('h2'); title.textContent = NAMES[g.weapon]; card.appendChild(title);
  const canvas = document.createElement('canvas'); card.appendChild(canvas);
  const row = document.createElement('div'); row.className='row';
  const gui = document.createElement('canvas'); gui.className='gui'; gui.width=128; gui.height=128; row.appendChild(gui);
  const tex = document.createElement('canvas'); tex.className='tex'; tex.width=64; tex.height=64; row.appendChild(tex);
  const meta = document.createElement('div'); meta.className='meta'; row.appendChild(meta);
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
  const slot = makeScene(gui, DATA.models[g.variants[0]], texture, true);
  const show = (v) => { view.set(DATA.models[v]); slot.set(DATA.models[v]); meta.textContent = DATA.counts[v] + ' elements'; };
  show(g.variants[0]);
  if (select) select.onchange = () => show(select.value);

  let drag = null, rx = 0.35, ry = -0.6, auto = true, dist = 34;
  canvas.onpointerdown = e => { drag = [e.clientX, e.clientY]; auto = false; canvas.setPointerCapture(e.pointerId); };
  canvas.onpointerup = () => drag = null;
  canvas.onpointermove = e => { if (!drag) return; ry += (e.clientX-drag[0])*0.01; rx += (e.clientY-drag[1])*0.01; drag=[e.clientX,e.clientY]; };
  canvas.onwheel = e => { e.preventDefault(); dist = Math.min(80, Math.max(12, dist + e.deltaY*0.03)); };
  canvas.ondblclick = () => { auto = true; };
  function frame(){
    view.resize(); slot.resize();
    if (auto) ry += 0.008;
    view.holder.rotation.set(rx, ry, 0);
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


def write_preview(models, textures, counts):
    tex_data = {}
    for weapon, img in textures.items():
        buf = io.BytesIO()
        img.save(buf, "PNG")
        tex_data[weapon] = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    groups = []
    for weapon in BASE_ITEM:
        groups.append({"weapon": weapon, "variants": [v for w, v, _, _ in weapons.VARIANTS if w == weapon]})
    html = (HTML.replace("__DATA__", json.dumps({"models": models, "textures": tex_data, "counts": counts}))
            .replace("__NAMES__", json.dumps(DISPLAY_NAME))
            .replace("__GROUPS__", json.dumps(groups)))
    with open(PREVIEW, "w") as f:
        f.write(html)


def main():
    models, textures, counts = write_pack()
    write_preview(models, textures, counts)
    for variant, n in counts.items():
        print(f"{variant:28s} {n:4d} elements")
    print(f"pack:    {PACK}")
    print(f"zip:     {ZIP}")
    print(f"preview: {PREVIEW}")


if __name__ == "__main__":
    main()
