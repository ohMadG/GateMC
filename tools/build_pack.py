"""
Builds the GensCore resource pack: conveyor belt models, their textures, and Blockbench project files.

    python tools/build_pack.py

Writes the pack sources to resourcepack/ (checked in), the Blockbench projects to resourcepack/blockbench/ (open and
edit them in Blockbench, then File > Export > Java Block/Item over the matching file in
resourcepack/assets/genscore/models/item/), and the zipped pack plus its SHA-1 to target/.

Models are made for item_display entities with the NONE transform: model space 0..16 is the block the entity sits
in. A straight belt runs along Z and items flow toward +Z (south); the plugin turns each segment with the entity's
rotation. Corners enter from -Z (north) and leave toward -X (west, "left") or +X (east, "right").

Pack format: Minecraft 26.3 resource pack 97.1 (from the server jar's version.json).
"""
import base64
import hashlib
import json
import os
import struct
import uuid
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "resourcepack")
NS = "genscore"
RESOURCE_MAJOR, RESOURCE_MINOR = 97, 1
OUT = 0.06  # how far side panels stand outside the block (in 1/16 block), to avoid z-fighting with the slab


# ---------------------------------------------------------------------- PNG writing (no Pillow needed)

def png(width, height, pixels):
    """pixels: list of rows, each a list of (r, g, b, a)."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in row) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (255,)


def noise(x, y, seed):
    h = (x * 374761393 + y * 668265263 + seed * 1442695040888963407) & 0xffffffff
    h = (h ^ (h >> 13)) * 1274126177 & 0xffffffff
    return ((h ^ (h >> 16)) & 0xff) / 255.0


# ---------------------------------------------------------------------- textures

def belt_frames(frames=8):
    """Animated rubber belt: dark rubber with raised cleats every 4 px, scrolling 2 px per frame toward +V."""
    rubber = (38, 38, 42, 255)
    cleat = (70, 70, 76, 255)
    rows = []
    for f in range(frames):
        for y in range(16):
            row = []
            for x in range(16):
                v = (y - f * 2) % 16
                if v % 4 == 0:
                    c = cleat if 1 <= x <= 14 else shade(cleat, 0.8)
                elif v % 4 == 1:
                    c = shade(rubber, 0.75)  # shadow under the cleat
                else:
                    c = shade(rubber, 0.9 + 0.2 * noise(x, v, 3))
                if x == 0 or x == 15:
                    c = shade(rubber, 0.6)  # belt edges
                row.append(c)
            rows.append(row)
    return 16, 16 * frames, rows


def steel():
    """Brushed steel side panel with a darker border and rivets."""
    base = (150, 156, 162, 255)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            c = shade(base, 0.92 + 0.12 * noise(x // 3, y, 7))  # brushed streaks run horizontally
            if x in (0, 15) or y in (0, 15):
                c = shade(base, 0.7)
            if (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13), (7, 2), (7, 13)):
                c = (205, 210, 215, 255)  # rivet
            row.append(c)
        rows.append(row)
    return 16, 16, rows


def hazard():
    """Yellow and black diagonal hazard stripes for the rail tops."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            stripe = ((x + y) // 4) % 2 == 0
            c = (232, 182, 28, 255) if stripe else (30, 30, 30, 255)
            row.append(shade(c, 0.93 + 0.1 * noise(x, y, 11)))
        rows.append(row)
    return 16, 16, rows


def dark():
    """Dark painted steel for legs, rollers and the return run."""
    base = (58, 62, 68, 255)
    rows = [[shade(base, 0.9 + 0.2 * noise(x, y, 5)) for x in range(16)] for y in range(16)]
    for i in range(16):
        rows[0][i] = rows[15][i] = shade(base, 0.7)
    return 16, 16, rows


def roller():
    """Polished roller: light band in the middle, darker edges."""
    rows = []
    for y in range(16):
        f = 0.65 + 0.55 * (1 - abs(y - 7.5) / 7.5)
        rows.append([shade((175, 180, 186, 255), f * (0.95 + 0.1 * noise(x, y, 13))) for x in range(16)])
    return 16, 16, rows


def glow(frames=6):
    """Hot orange core that pulses."""
    rows = []
    for f in range(frames):
        k = 0.8 + 0.2 * abs(f - frames / 2) / (frames / 2)
        for y in range(16):
            row = []
            for x in range(16):
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 / 10.6
                c = (255, int(150 + 90 * (1 - d)), int(40 + 60 * (1 - d)), 255)
                row.append(shade(c, k * (0.9 + 0.15 * noise(x, y, 17 + f))))
            rows.append(row)
    return 16, 16 * frames, rows


def vent():
    """Dark grille: slots with a lighter rim."""
    base = (48, 52, 58, 255)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            c = shade(base, 0.95 + 0.1 * noise(x, y, 19))
            if y % 3 == 1 and 2 <= x <= 13:
                c = (14, 14, 16, 255)
            if x in (0, 15) or y in (0, 15):
                c = shade(base, 1.3)
            row.append(c)
        rows.append(row)
    return 16, 16, rows


def coin():
    """Gold token face: a rim, a raised star, a little shine."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            c = (236, 186, 46, 255)
            if d > 6.2:
                c = (196, 140, 24, 255)   # rim
            # five-pointed star-ish emblem in the middle
            ax, ay = abs(x - 7.5), abs(y - 7.5)
            if (ax < 1.2 and ay < 4.5) or (ay < 1.2 and ax < 4.5) or (abs(ax - ay) < 1.0 and ax < 3.2):
                c = (255, 236, 140, 255)
            row.append(shade(c, 0.92 + 0.12 * noise(x, y, 23)))
        rows.append(row)
    return 16, 16, rows


def tinted_glow(color):
    def make():
        rows = []
        for y in range(16):
            row = []
            for x in range(16):
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 / 10.6
                f = 0.75 + 0.35 * (1 - d)
                row.append(shade(color + (255,), f * (0.92 + 0.12 * noise(x, y, 29))))
            rows.append(row)
        return 16, 16, rows
    return make


TEXTURES = {
    "belt": belt_frames,
    "steel": steel,
    "hazard": hazard,
    "dark": dark,
    "roller": roller,
    "glow": glow,
    "vent": vent,
    "coin": coin,
    "glow_common": tinted_glow((235, 235, 235)),
    "glow_rare": tinted_glow((70, 210, 255)),
    "glow_epic": tinted_glow((220, 90, 255)),
    "glow_legendary": tinted_glow((255, 190, 40)),
    "glow_red": tinted_glow((255, 50, 40)),
}


# ---------------------------------------------------------------------- geometry

def box(name, frm, to, tex, faces=("north", "east", "south", "west", "up", "down"), uv=None, light=0):
    """A cuboid using one texture on the given faces; uv per face is worked out from the box size."""
    out = {}
    x1, y1, z1 = frm
    x2, y2, z2 = to
    auto = {
        "north": [16 - x2, 16 - y2, 16 - x1, 16 - y1],
        "south": [x1, 16 - y2, x2, 16 - y1],
        "east": [16 - z2, 16 - y2, 16 - z1, 16 - y1],
        "west": [z1, 16 - y2, z2, 16 - y1],
        "up": [x1, z1, x2, z2],
        "down": [x1, 16 - z2, x2, 16 - z1],
    }
    for f in faces:
        out[f] = {"uv": (uv or {}).get(f, auto[f]), "texture": "#" + tex}
    el = {"name": name, "from": list(frm), "to": list(to), "faces": out}
    if light:
        el["light_emission"] = light
    return el


def straight():
    e = []
    # Side frames: full-height steel panels, so the slab under the belt is hidden.
    # Side frames: full-height steel panels set a hair outside the block (OUT), so they never sit in the same plane
    # as the slab under the belt (that made the sides flicker).
    e.append(box("frame_west", (-OUT, 0, 0), (2, 10, 16), "steel", faces=("north", "east", "south", "west", "down")))
    e.append(box("frame_east", (14, 0, 0), (16 + OUT, 10, 16), "steel", faces=("north", "east", "south", "west", "down")))
    # Hazard-striped tops of the frames.
    e.append(box("rail_west", (0, 10, 0), (2, 10.5, 16), "hazard", faces=("up", "north", "south", "east", "west")))
    e.append(box("rail_east", (14, 10, 0), (16, 10.5, 16), "hazard", faces=("up", "north", "south", "east", "west")))
    # Belt top run (animated) and the return run underneath.
    e.append(box("belt_top", (2, 8, 0), (14, 9, 16), "belt", faces=("up", "north", "south"),
                 uv={"up": [2, 0, 14, 16]}))
    e.append(box("belt_return", (2, 2, 0), (14, 3, 16), "dark", faces=("up", "down", "north", "south")))
    # Rollers between the runs.
    for i, z in enumerate((1, 7, 13)):
        e.append(box("roller_%d" % i, (2, 4, z), (14, 8, z + 2), "roller", faces=("north", "south", "up", "down")))
    # Cross braces at both ends.
    e.append(box("brace_north", (2, 0, 0.5), (14, 1.5, 1.5), "dark"))
    e.append(box("brace_south", (2, 0, 14.5), (14, 1.5, 15.5), "dark"))
    return e


def corner(right):
    """Belt entering from -Z (north). Left turn leaves toward -X (west), right toward +X (east)."""
    e = []
    if right:
        outer_side = box("frame_west", (-OUT, 0, 0), (2, 10, 16 + OUT), "steel")   # outside of the bend
        outer_back = box("frame_south", (2, 0, 14), (16, 10, 16 + OUT), "steel")
        post = box("post_ne", (14, 0, -OUT), (16 + OUT, 10, 2), "steel")            # inner corner post
        rails = [box("rail_w", (0, 10, 0), (2, 10.5, 16), "hazard", faces=("up", "north", "south", "east", "west")),
                 box("rail_s", (2, 10, 14), (16, 10.5, 16), "hazard", faces=("up", "north", "south", "east", "west")),
                 box("rail_post", (14, 10, 0), (16, 10.5, 2), "hazard", faces=("up", "north", "south", "east", "west"))]
        belt = box("belt_top", (2, 8, 0), (16, 9, 14), "belt", faces=("up",), uv={"up": [2, 0, 14, 16]})
        rollers = [box("roller_0", (2, 4, 1), (14, 8, 3), "roller", faces=("north", "south", "up", "down")),
                   box("roller_1", (13, 4, 2), (15, 8, 14), "roller", faces=("east", "west", "up", "down"))]
    else:
        outer_side = box("frame_east", (14, 0, 0), (16 + OUT, 10, 16 + OUT), "steel")
        outer_back = box("frame_south", (0, 0, 14), (14, 10, 16 + OUT), "steel")
        post = box("post_nw", (-OUT, 0, -OUT), (2, 10, 2), "steel")
        rails = [box("rail_e", (14, 10, 0), (16, 10.5, 16), "hazard", faces=("up", "north", "south", "east", "west")),
                 box("rail_s", (0, 10, 14), (14, 10.5, 16), "hazard", faces=("up", "north", "south", "east", "west")),
                 box("rail_post", (0, 10, 0), (2, 10.5, 2), "hazard", faces=("up", "north", "south", "east", "west"))]
        belt = box("belt_top", (0, 8, 0), (14, 9, 14), "belt", faces=("up",), uv={"up": [2, 0, 14, 16]})
        rollers = [box("roller_0", (2, 4, 1), (14, 8, 3), "roller", faces=("north", "south", "up", "down")),
                   box("roller_1", (1, 4, 2), (3, 8, 14), "roller", faces=("east", "west", "up", "down"))]
    e += [outer_side, outer_back, post] + rails + [belt] + rollers
    e.append(box("belt_return", (2, 2, 2), (14, 3, 14), "dark", faces=("up", "down")))
    return e


def gen_machine():
    """The gen: a machine with an output chute on its +Z side at belt height. Items come out of the chute."""
    e = []
    e.append(box("base", (0, 0, 0), (16, 2, 16), "dark"))
    e.append(box("body", (1, 2, 1), (15, 14, 13), "steel"))
    e.append(box("band", (0.9, 4, 0.9), (15.1, 5, 13.1), "hazard", faces=("north", "east", "south", "west")))
    e.append(box("vent_top", (3, 14, 3), (13, 15.5, 11), "vent"))
    e.append(box("core_west", (0.4, 6, 3), (1, 12, 10), "glow", faces=("west",), light=15))
    e.append(box("core_east", (15, 6, 3), (15.6, 12, 10), "glow", faces=("east",), light=15))
    # Output chute: floor at belt height (8..10), open at the +Z end.
    e.append(box("chute_floor", (3, 8, 13), (13, 9, 16), "dark"))
    e.append(box("chute_west", (3, 9, 13), (4, 13, 16), "steel"))
    e.append(box("chute_east", (12, 9, 13), (13, 13, 16), "steel"))
    e.append(box("chute_roof", (3, 13, 13), (13, 14, 16), "steel"))
    e.append(box("chute_glow", (4, 9, 13.05), (12, 13, 13.1), "glow", faces=("south",), light=12))
    # Pipes on the back.
    e.append(box("pipe_a", (3, 3, 0), (5, 13, 1), "roller"))
    e.append(box("pipe_b", (11, 3, 0), (13, 13, 1), "roller"))
    return e


def token():
    """A gold coin standing in the XY plane (it is laid flat on the belt by the plugin)."""
    e = []
    e.append(box("face", (4, 3, 7.25), (12, 13, 8.75), "coin"))
    e.append(box("round_a", (3, 4, 7.3), (13, 12, 8.7), "coin", faces=("east", "west", "up", "down")))
    e.append(box("round_b", (3.5, 3.5, 7.35), (12.5, 12.5, 8.65), "coin", faces=("east", "west", "up", "down")))
    e.append(box("emblem", (6.5, 6.5, 6.9), (9.5, 9.5, 9.1), "glow_legendary", faces=("north", "south"), light=10))
    return e


def cache(rarity):
    """A small strongbox with glowing seams in the rarity's colour."""
    glow = "glow_" + rarity
    e = []
    e.append(box("body", (3, 3, 3), (13, 13, 13), "steel"))
    e.append(box("lid", (2.6, 11, 2.6), (13.4, 13.4, 13.4), "dark"))
    e.append(box("seam", (2.8, 7, 2.8), (13.2, 8.5, 13.2), glow, faces=("north", "east", "south", "west"), light=15))
    e.append(box("lock", (6.5, 5.5, 2.4), (9.5, 9.5, 3), glow, faces=("north", "east", "west", "up", "down"), light=15))
    for i, (x, z) in enumerate(((2.6, 2.6), (12.4, 2.6), (2.6, 12.4), (12.4, 12.4))):
        e.append(box("corner_%d" % i, (x, 3, z), (x + 1, 13.4, z + 1), "dark"))
    return e


def turret():
    """A wall turret: base, pedestal, head and a barrel pointing +Z with a red lens."""
    e = []
    e.append(box("base", (2, 0, 2), (14, 2, 14), "dark"))
    e.append(box("pedestal", (5, 2, 5), (11, 6, 11), "steel"))
    e.append(box("head", (3.5, 6, 3), (12.5, 12, 12), "steel"))
    e.append(box("stripe", (3.4, 9, 2.9), (12.6, 10, 12.1), "hazard", faces=("north", "east", "south", "west")))
    e.append(box("barrel", (6.5, 7.5, 12), (9.5, 10.5, 18), "dark"))
    e.append(box("lens", (7, 8, 17.95), (9, 10, 18.1), "glow_red", faces=("south",), light=15))
    e.append(box("sensor", (6, 12, 6), (10, 13.5, 10), "glow_red", faces=("up", "north", "south", "east", "west"), light=8))
    return e


MODELS = {
    "conveyor_belt": straight,
    "conveyor_corner_left": lambda: corner(False),
    "conveyor_corner_right": lambda: corner(True),
    "gen_machine": gen_machine,
    "token": token,
    "cache_common": lambda: cache("common"),
    "cache_rare": lambda: cache("rare"),
    "cache_epic": lambda: cache("epic"),
    "cache_legendary": lambda: cache("legendary"),
    "turret": turret,
}


# ---------------------------------------------------------------------- writing

def write(path, data, binary=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb" if binary else "w", encoding=None if binary else "utf-8", newline=None if binary else "\n") as f:
        f.write(data)


def java_model(elements):
    used = {f["texture"][1:] for el in elements for f in el["faces"].values()}
    tex = {name: "%s:item/conveyor/%s" % (NS, name) for name in TEXTURES if name in used}
    tex["particle"] = tex.get("steel") or next(iter(tex.values()))
    return {
        "credit": "GensCore conveyor belt (tools/build_pack.py)",
        "texture_size": [16, 16],
        "textures": tex,
        "elements": elements,
        "display": {
            "gui": {"rotation": [30, 225, 0], "scale": [0.625, 0.625, 0.625]},
            "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
            "fixed": {"scale": [0.5, 0.5, 0.5]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]},
        },
    }


def bbmodel(name, elements, pngs):
    """A Blockbench project (Java Block/Item format) with the textures embedded."""
    tex_ids = {}
    textures = []
    for i, (tname, data) in enumerate(pngs.items()):
        tex_ids[tname] = str(i)
        w, h = 16, len(data["rows"])
        textures.append({
            "path": "", "name": tname + ".png", "folder": "item/conveyor", "namespace": NS, "id": str(i),
            "width": w, "height": h, "uv_width": 16, "uv_height": 16, "particle": tname == "steel",
            "render_mode": "default", "visible": True, "saved": True, "uuid": str(uuid.uuid4()),
            "relative_path": "../assets/genscore/textures/item/conveyor/" + tname + ".png",
            "source": "data:image/png;base64," + base64.b64encode(data["png"]).decode(),
            "frame_time": {"belt": 2, "glow": 3}.get(tname, 1),
        })
    els = []
    for el in elements:
        faces = {}
        for fname, face in el["faces"].items():
            faces[fname] = {"uv": face["uv"], "texture": int(tex_ids[face["texture"][1:]])}
        els.append({"name": el["name"], "box_uv": False, "rescale": False, "locked": False, "from": el["from"],
                    "to": el["to"], "autouv": 0, "color": 0, "origin": [8, 8, 8], "faces": faces, "type": "cube",
                    "light_emission": el.get("light_emission", 0), "uuid": str(uuid.uuid4())})
    return {
        "meta": {"format_version": "4.10", "model_format": "java_block", "box_uv": False},
        "name": name, "parent": "", "ambientocclusion": True, "front_gui_light": False,
        "visible_box": [1, 1, 0], "variable_placeholders": "", "variable_placeholder_buttons": [],
        "unhandled_root_fields": {},
        "resolution": {"width": 16, "height": 16},
        "elements": els,
        "outliner": [e["uuid"] for e in els],
        "textures": textures,
    }


def main():
    pngs = {}
    for name, fn in TEXTURES.items():
        w, h, rows = fn()
        data = png(w, h, rows)
        pngs[name] = {"png": data, "rows": rows}
        write(os.path.join(PACK, "assets", NS, "textures", "item", "conveyor", name + ".png"), data, binary=True)
    write(os.path.join(PACK, "assets", NS, "textures", "item", "conveyor", "belt.png.mcmeta"),
          json.dumps({"animation": {"frametime": 2, "interpolate": False}}, indent=2) + "\n")
    write(os.path.join(PACK, "assets", NS, "textures", "item", "conveyor", "glow.png.mcmeta"),
          json.dumps({"animation": {"frametime": 3, "interpolate": True}}, indent=2) + "\n")

    for name, fn in MODELS.items():
        elements = fn()
        write(os.path.join(PACK, "assets", NS, "models", "item", name + ".json"), json.dumps(java_model(elements), indent=2) + "\n")
        # 26.x item definitions: an item with the item_model component genscore:<name> uses this file.
        write(os.path.join(PACK, "assets", NS, "items", name + ".json"),
              json.dumps({"model": {"type": "minecraft:model", "model": "%s:item/%s" % (NS, name)}}, indent=2) + "\n")
        write(os.path.join(PACK, "blockbench", name + ".bbmodel"), json.dumps(bbmodel(name, elements, pngs)) + "\n")

    write(os.path.join(PACK, "pack.mcmeta"), json.dumps({
        "pack": {
            "description": "GensCore: conveyor belts",
            "min_format": [RESOURCE_MAJOR, 0],
            "max_format": [RESOURCE_MAJOR, RESOURCE_MINOR],
        }
    }, indent=2) + "\n")

    target = os.path.join(ROOT, "target")
    os.makedirs(target, exist_ok=True)
    zpath = os.path.join(target, "GensCore-pack.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, files in os.walk(PACK):
            if "blockbench" in os.path.relpath(base, PACK).split(os.sep):
                continue
            for f in sorted(files):
                full = os.path.join(base, f)
                info = zipfile.ZipInfo(os.path.relpath(full, PACK).replace(os.sep, "/"), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(full, "rb") as fh:
                    z.writestr(info, fh.read())
    sha1 = hashlib.sha1(open(zpath, "rb").read()).hexdigest()
    write(os.path.join(target, "GensCore-pack.sha1"), sha1 + "\n")
    print("pack:", zpath, os.path.getsize(zpath), "bytes, sha1", sha1)


if __name__ == "__main__":
    main()
