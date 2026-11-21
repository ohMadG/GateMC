"""
Builds the tile-style /menu for Java (resource pack) and Bedrock (Geyser custom items).

    python tools/build_hub_menu.py <minecraft client jar>

Java: one background glyph for the whole chest (tiles, labels, hotbar frames) plus the vanilla item icons drawn
through font/hubmenu_art.json (the pack only names the vanilla textures; the client jar is read to measure them).
The finished inventory title is written to src/main/resources/modules/hub/menu_title.json.

Bedrock: every tile is cut into 16x16 pieces (the middle top slot shows the real item instead). They are written as a
Bedrock resource pack to target/GateMC-menu.mcpack and a Geyser mapping to target/gatemc_menu.json; the plugin gives
Bedrock players paper items with the item_model genscore:menu/<tile>_<piece>.

Tile ids, colours and slots must match hub/HubTiles.java.
"""
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_pack import png, PACK, NS, write, ROOT  # noqa: E402
from build_gui import Img, measure  # noqa: E402

TILES = [
    ("factory", "FACTORY", (78, 154, 58), "item/hopper", 0, 0),
    ("store", "STORE", (122, 62, 157), "item/emerald", 1, 0),
    ("quests", "QUESTS", (198, 154, 42), "item/writable_book", 2, 0),
    ("powers", "POWERS", (154, 74, 42), "item/blaze_powder", 0, 1),
    ("travel", "TRAVEL", (142, 63, 122), "item/compass_00", 1, 1),
    ("rewards", "REWARDS", (62, 142, 74), "item/chest_minecart", 2, 1),
    ("raiding", "RAID", (59, 110, 143), "item/iron_pickaxe", 0, 2),
    ("style", "STYLE", (46, 125, 114), "item/armor_stand", 1, 2),
    ("leaders", "TOP", (184, 100, 30), "item/gold_ingot", 2, 2),
]

FONT = {
    "A": ["01110", "10001", "11111", "10001", "10001"],
    "B": ["11110", "10001", "11110", "10001", "11110"],
    "C": ["01111", "10000", "10000", "10000", "01111"],
    "D": ["11110", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "11110", "10000", "11111"],
    "F": ["11111", "10000", "11110", "10000", "10000"],
    "G": ["01111", "10000", "10011", "10001", "01111"],
    "H": ["10001", "10001", "11111", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "11111"],
    "K": ["10001", "10010", "11100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001"],
    "O": ["01110", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "11110", "10000", "10000"],
    "Q": ["01110", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "11110", "10010", "10001"],
    "S": ["01111", "10000", "01110", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10101", "11011", "10001"],
    "Y": ["10001", "01010", "00100", "00100", "00100"],
}

WHITE = (255, 255, 255)
OUTLINE = (24, 20, 28)
BG = (30, 31, 38)
EDGE = (12, 12, 16)
HI = (64, 66, 78)


def text_width(text, font, pitch):
    return sum(pitch if c == " " else len(font[c][0]) + 1 for c in text) - 1


def text(img, s, x, y, font, color=WHITE, outline=OUTLINE):
    def each(fn):
        cx = x
        for c in s:
            if c == " ":
                cx += 3
                continue
            rows = font[c]
            for dy, row in enumerate(rows):
                for dx, b in enumerate(row):
                    if b == "1":
                        fn(cx + dx, y + dy)
            cx += len(rows[0]) + 1

    for ox, oy in ((-1, 0), (1, 0), (0, -1), (0, 1), (1, 1)):
        each(lambda px, py: img.set(px + ox, py + oy, outline))
    each(lambda px, py: img.set(px, py, color))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def tile(img, x0, y0, w, h, color):
    img.rect(x0, y0, x0 + w - 1, y0 + h - 1, EDGE)
    cx, cy = x0 + w / 2.0, y0 + h * 0.42
    import math
    for y in range(y0 + 1, y0 + h - 1):
        for x in range(x0 + 1, x0 + w - 1):
            ang = math.atan2(y - cy, x - cx)
            ray = (int((ang + math.pi) / (math.pi / 8)) % 2) == 0
            dist = math.hypot((x - cx) / w, (y - cy) / h)
            f = 1.15 - 0.55 * min(1.0, dist * 1.6) + (0.10 if ray else 0.0)
            img.set(x, y, shade(color, f))
    img.rect(x0 + 1, y0 + 1, x0 + w - 2, y0 + 1, shade(color, 1.55))
    img.rect(x0 + 1, y0 + h - 2, x0 + w - 2, y0 + h - 2, shade(color, 0.55))
    img.rect(x0 + 1, y0 + 1, x0 + 1, y0 + h - 2, shade(color, 1.25))
    img.rect(x0 + w - 2, y0 + 1, x0 + w - 2, y0 + h - 2, shade(color, 0.7))


def slot(img, x, y):
    img.rect(x, y, x + 17, y + 17, (14, 14, 18))
    img.rect(x + 1, y + 1, x + 16, y + 16, (40, 41, 50))
    img.rect(x + 1, y + 16, x + 16, y + 16, (58, 60, 72))


def java_background():
    img = Img(176, 222)
    img.rect(0, 0, 175, 221, EDGE)
    img.rect(1, 1, 174, 220, BG)
    img.rect(1, 1, 174, 1, HI)
    title = "GATEMC"
    text(img, title, 88 - text_width(title, FONT, 4) // 2, 6, FONT, (255, 196, 70))
    for tid, label, color, art, col, row in TILES:
        x0 = 7 + 54 * col
        if row < 2:
            y0 = 17 + 36 * row
            tile(img, x0 + 1, y0 + 1, 52, 34, color)
            text(img, label, x0 + 27 - text_width(label, FONT, 4) // 2, y0 + 28, FONT)
        else:
            y0 = 89
            tile(img, x0 + 1, y0 + 1, 52, 16, color)
            text(img, label, x0 + 21, y0 + 7, FONT)
    for c in range(9):
        x = 7 + 18 * c
        img.rect(x + 1, 108, x + 16, 123, (46, 48, 58))
        img.rect(x + 1, 108, x + 16, 108, (78, 80, 96))
        img.rect(x + 1, 123, x + 16, 123, (24, 24, 30))
    for r in range(3):
        for c in range(9):
            slot(img, 7 + 18 * c, 139 + 18 * r)
    for c in range(9):
        slot(img, 7 + 18 * c, 197)
    img.anchor()
    return img


def bedrock_pieces():
    pieces = {}
    for tid, label, color, art, col, row in TILES:
        if row < 2:
            img = Img(48, 32)
            tile(img, 0, 0, 48, 32, color)
            text(img, label, 24 - text_width(label, FONT, 4) // 2, 24, FONT)
            for name, (px, py) in {"tl": (0, 0), "tr": (32, 0), "bl": (0, 16), "bm": (16, 16), "br": (32, 16)}.items():
                pieces["%s_%s" % (tid, name)] = crop(img, px, py)
        else:
            img = Img(48, 16)
            tile(img, 0, 0, 48, 16, color)
            pieces["%s_l" % tid] = crop(img, 0, 0)
            pieces["%s_r" % tid] = crop(img, 32, 0)
    return pieces


def crop(img, x0, y0):
    out = Img(16, 16)
    for y in range(16):
        for x in range(16):
            out.px[y][x] = img.px[y0 + y][x0 + x]
    return out


SPACES = {}
for i in range(10):
    SPACES[chr(0xF801 + i)] = -(1 << i)
    SPACES[chr(0xF821 + i)] = 1 << i


def space(delta):
    out = ""
    left = abs(delta)
    base = 0xF801 if delta < 0 else 0xF821
    for bit in range(9, -1, -1):
        while left >= (1 << bit):
            out += chr(base + bit)
            left -= 1 << bit
    return out


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: build_hub_menu.py <minecraft client jar>")
    jar = zipfile.ZipFile(sys.argv[1])
    tex_dir = os.path.join(PACK, "assets", NS, "textures", "gui", "hubmenu")
    font_dir = os.path.join(PACK, "assets", NS, "font")
    bg = java_background()
    write(os.path.join(tex_dir, "background.png"), bg.data(), binary=True)
    write(os.path.join(font_dir, "hubmenu.json"), json.dumps({"providers": [
        {"type": "space", "advances": SPACES},
        {"type": "bitmap", "file": "%s:gui/hubmenu/background.png" % NS, "height": 222, "ascent": 13,
         "chars": [""]},
    ]}, indent=1) + "\n")

    providers = []
    parts = [{"text": space(-8), "font": "%s:hubmenu" % NS},
             {"text": "", "font": "%s:hubmenu" % NS, "color": "white", "shadow_color": 0}]
    cursor = 177
    for i, (tid, label, color, art, col, row) in enumerate(TILES):
        visible, frame, frames = measure(jar, art)
        x0 = 7 + 54 * col
        if row < 2:
            size, x, y = 24, x0 + 15, 17 + 36 * row + 3
        else:
            size, x, y = 16, x0 + 3, 90
        ch = chr(0xE310 + i)
        providers.append({"type": "bitmap", "file": "minecraft:%s.png" % art, "height": size, "ascent": 13 - y,
                          "chars": [ch] + ["\u0000"] * (frames - 1)})
        adv = int(0.5 + visible * size / float(frame)) + 1
        parts.append({"text": space(x - cursor), "font": "%s:hubmenu" % NS})
        parts.append({"text": ch, "font": "%s:hubmenu_art" % NS, "color": "white", "shadow_color": 0})
        cursor = x + adv
    parts.append({"text": space(0 - cursor), "font": "%s:hubmenu" % NS})
    parts = [p for p in parts if p["text"]]
    write(os.path.join(font_dir, "hubmenu_art.json"), json.dumps({"providers": providers}) + "\n")
    write(os.path.join(PACK, "assets", NS, "items", "menu_blank.json"),
          json.dumps({"model": {"type": "minecraft:empty"}}, indent=2) + "\n")
    write(os.path.join(ROOT, "src", "main", "resources", "modules", "hub", "menu_title.json"),
          json.dumps({"text": "", "extra": parts}) + "\n")

    target = os.path.join(ROOT, "target")
    os.makedirs(target, exist_ok=True)
    pieces = bedrock_pieces()
    texture_data = {}
    defs = []
    for name in sorted(pieces):
        key = "gatemc_menu_" + name
        texture_data[key] = {"textures": ["textures/items/gatemc_menu/" + name]}
        defs.append({"type": "definition", "model": "%s:menu/%s" % (NS, name),
                     "bedrock_identifier": "gatemc:menu_" + name,
                     "display_name": name.split("_")[0].capitalize(),
                     "bedrock_options": {"icon": key, "allow_offhand": False}})
    manifest = {
        "format_version": 2,
        "header": {"name": "GateMC Menus", "description": "GateMC menu tiles",
                   "uuid": "4c0f2a51-7d6e-4b8a-9a3c-5e1d2f6b7a01", "version": [1, 0, 0],
                   "min_engine_version": [1, 21, 0]},
        "modules": [{"type": "resources", "uuid": "9b2e6d14-3f8a-4c57-b1d0-6a7e8c9f0b12", "version": [1, 0, 0]}],
    }
    mc = os.path.join(target, "GateMC-menu.mcpack")
    with zipfile.ZipFile(mc, "w", zipfile.ZIP_DEFLATED) as z:
        def add(path, data):
            info = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
        add("manifest.json", json.dumps(manifest, indent=2))
        add("textures/item_texture.json", json.dumps({"resource_pack_name": "gatemc_menu", "texture_name": "atlas.items",
                                                      "texture_data": texture_data}, indent=2))
        for name, img in sorted(pieces.items()):
            add("textures/items/gatemc_menu/%s.png" % name, img.data())
    with open(os.path.join(target, "gatemc_menu.json"), "w") as f:
        json.dump({"format_version": 2, "items": {"minecraft:paper": defs}}, f, indent=2)
    print("hub menu: java title + %d bedrock pieces" % len(pieces))
    from vendor_vanilla import vendor
    vendor(jar)


if __name__ == "__main__":
    main()
