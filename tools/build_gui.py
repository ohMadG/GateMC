"""
Builds the /store dialog assets into resourcepack/ and the art index into the plugin resources.

    python tools/build_gui.py <minecraft client jar>

The client jar is only read to measure the vanilla item textures the store uses as art (their visible width sets
the glyph advance); the pack refers to those textures by name and never copies them.

Writes:
  resourcepack/assets/genscore/font/shop.json        panels, tabs, cards, buttons and spacing
  resourcepack/assets/genscore/font/shop_art.json    vanilla item textures at every size and row the store uses
  resourcepack/assets/genscore/font/shop_t<Y>.json   the vanilla ASCII sheet with its top at dialog row Y
  resourcepack/assets/genscore/textures/gui/shop/*.png
  src/main/resources/modules/store/art.txt           art name, first code point, visible width, frame height

Coordinates are dialog pixels from the top-left of the first body line; they must match StoreDialog.java.
"""
import json
import os
import struct
import sys
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_pack import png, PACK, NS, write, ROOT  # noqa: E402

TEX = "%s:gui/shop/" % NS
TEXT_MAX = 430

ASCII_ROWS = [
    "\u0000" * 16, "\u0000" * 16,
    " !\"#$%&'()*+,-./", "0123456789:;<=>?", "@ABCDEFGHIJKLMNO", "PQRSTUVWXYZ[\\]^_", "`abcdefghijklmno",
    "pqrstuvwxyz{|}~\u0000",
    "\u0000" * 16, "\u0000" * 16, "\u0000" * 16, "\u0000" * 16, "\u0000" * 16, "\u0000" * 16, "\u0000" * 16,
    "\u0000" * 16,
]

ART = [
    "item/iron_ingot", "item/gold_ingot", "item/copper_ingot", "item/diamond", "item/emerald", "item/netherite_ingot",
    "item/nether_star", "block/tripwire_hook", "item/name_tag", "item/totem_of_undying", "item/experience_bottle",
    "item/blaze_rod", "item/fishing_rod", "item/ender_eye", "item/ender_pearl", "item/amethyst_shard",
    "item/echo_shard", "item/heart_of_the_sea", "item/trial_key", "item/ominous_trial_key", "item/golden_apple",
    "item/enchanted_book", "item/book", "item/paper", "item/clock_00", "item/compass_00", "item/bundle",
    "item/chest_minecart", "item/minecart", "item/firework_rocket", "item/cake", "item/spyglass", "item/mace",
    "item/wind_charge", "item/breeze_rod", "item/netherite_upgrade_smithing_template", "block/gold_block",
    "block/emerald_block", "block/diamond_block", "block/beacon", "item/end_crystal", "item/goat_horn",
    "item/music_disc_pigstep", "item/elytra", "item/shulker_shell", "item/dragon_breath", "item/written_book",
    "item/map", "item/golden_horse_armor", "item/diamond_sword", "item/netherite_pickaxe", "item/golden_hoe",
    "item/netherite_hoe", "item/nautilus_shell", "item/sniffer_egg", "item/ghast_tear", "item/magma_cream",
    "item/blaze_powder", "item/gold_nugget", "item/raw_gold", "item/lapis_lazuli", "item/redstone", "item/quartz",
    "item/prismarine_crystals", "item/rabbit_foot", "item/honeycomb", "item/resin_brick", "item/glow_ink_sac",
    "item/slime_ball", "item/phantom_membrane",
]

ART_BASE = 0xE800
ART_SLOTS = [(96, 69)]
ART_SLOTS += [(32, 4)]
ART_SLOTS += [(16, 345)]
ART_SLOTS += [(16, 235 + 18 * i) for i in range(7)]
ART_SLOTS += [(24, 114 + 50 * i) for i in range(2)]
ART_SLOTS += [(12, 69)]


class Img:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0) for _ in range(w)] for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c if len(c) == 4 else tuple(c) + (255,)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(max(0, y0), min(self.h - 1, y1) + 1):
            for x in range(max(0, x0), min(self.w - 1, x1) + 1):
                self.set(x, y, c)

    def outline(self, x0, y0, x1, y1, c):
        self.rect(x0, y0, x1, y0, c)
        self.rect(x0, y1, x1, y1, c)
        self.rect(x0, y0, x0, y1, c)
        self.rect(x1, y0, x1, y1, c)

    def anchor(self):
        x = self.w - 1
        if not any(self.px[y][x][3] for y in range(self.h)):
            self.px[0][x] = (0, 0, 0, 1)
        return self

    def data(self):
        return png(self.w, self.h, self.px)


def gray(v, a=255):
    return (v, v, v, a)


EDGE = (12, 12, 14)
PANEL = (34, 34, 38)
PANEL_HI = (52, 52, 58)
PANEL_LO = (26, 26, 30)


def tab(selected):
    img = Img(99, 39)
    img.rect(0, 0, 98, 38, gray(30))
    for y in range(1, 38):
        img.rect(1, y, 97, y, gray(int(235 - 55 * y / 38)))
    img.rect(1, 1, 97, 2, gray(255))
    img.rect(1, 35, 97, 37, gray(140))
    if selected:
        img.outline(0, 0, 98, 38, (255, 255, 255))
        img.outline(1, 1, 97, 37, (255, 255, 255))
    return img.anchor()


def badge_col():
    img = Img(1, 12)
    img.rect(0, 0, 0, 0, gray(30))
    img.rect(0, 1, 0, 10, gray(255))
    img.rect(0, 9, 0, 10, gray(190))
    img.rect(0, 11, 0, 11, gray(30))
    return img


def card_top():
    img = Img(140, 136)
    img.rect(0, 0, 139, 135, EDGE)
    for y in range(1, 136):
        for x in range(1, 139):
            dx = (x - 70) / 70.0
            dy = (y - 60) / 80.0
            d = min(1.0, (dx * dx + dy * dy) ** 0.5)
            img.set(x, y, gray(int(150 - 85 * d)))
    img.rect(1, 1, 138, 1, gray(200))
    return img.anchor()


def card_body():
    img = Img(140, 235)
    img.rect(0, 0, 139, 234, EDGE)
    img.rect(1, 0, 138, 233, PANEL)
    img.rect(1, 0, 138, 0, PANEL_HI)
    img.rect(8, 137, 131, 137, PANEL_HI)
    return img.anchor()


def slot_box():
    img = Img(20, 20)
    img.rect(0, 0, 19, 19, (18, 18, 20))
    img.rect(1, 1, 18, 18, PANEL_LO)
    img.rect(1, 18, 18, 18, PANEL_HI)
    return img.anchor()


def panel(h):
    img = Img(206, h)
    img.rect(0, 0, 205, h - 1, EDGE)
    img.rect(1, 1, 204, h - 2, PANEL)
    img.rect(1, 1, 204, 1, PANEL_HI)
    img.rect(6, 15, 199, 15, PANEL_HI)
    return img.anchor()


def track():
    img = Img(186, 6)
    img.rect(0, 0, 185, 5, (14, 14, 16))
    img.rect(1, 1, 184, 4, (44, 44, 50))
    return img.anchor()


def fill_col():
    img = Img(1, 4)
    img.rect(0, 0, 0, 3, gray(255))
    img.rect(0, 3, 0, 3, gray(170))
    return img


def marker():
    img = Img(18, 18)
    img.rect(0, 0, 17, 17, (14, 14, 16))
    img.rect(1, 1, 16, 16, (58, 58, 64))
    img.rect(1, 16, 16, 16, (40, 40, 44))
    return img.anchor()


def lock():
    img = Img(7, 8)
    img.outline(1, 0, 5, 3, (230, 230, 230))
    img.rect(0, 3, 6, 7, (230, 230, 230))
    img.rect(3, 5, 3, 6, (40, 40, 40))
    img.outline(0, 3, 6, 7, (20, 20, 20))
    return img.anchor()


def reward_box():
    img = Img(36, 36)
    img.rect(0, 0, 35, 35, (14, 14, 16))
    img.rect(1, 1, 34, 34, PANEL_LO)
    img.rect(1, 34, 34, 34, PANEL_HI)
    return img.anchor()


def bullet():
    img = Img(5, 5)
    for y, row in enumerate(["00100", "01110", "11111", "01110", "00100"]):
        for x, b in enumerate(row):
            if b == "1":
                img.set(x, y, gray(255))
    return img.anchor()


def subdot():
    img = Img(2, 2)
    img.rect(0, 0, 1, 1, gray(150))
    return img


def gift():
    img = Img(9, 9)
    red, gold = (220, 40, 50), (255, 200, 60)
    img.rect(0, 3, 8, 8, red)
    img.rect(0, 2, 8, 3, (240, 70, 80))
    img.rect(4, 2, 4, 8, gold)
    img.rect(0, 5, 8, 5, gold)
    img.set(2, 0, gold)
    img.set(3, 1, gold)
    img.set(6, 0, gold)
    img.set(5, 1, gold)
    return img.anchor()


def cart_icon():
    img = Img(9, 8)
    w = (235, 235, 235)
    img.rect(0, 0, 1, 0, w)
    img.rect(1, 1, 8, 1, w)
    img.rect(2, 2, 7, 4, w)
    img.rect(2, 5, 7, 5, w)
    img.set(3, 7, w)
    img.set(6, 7, w)
    return img.anchor()


def button(w, light, mid, dark):
    img = Img(w, 20)
    img.rect(0, 0, w - 1, 19, (10, 10, 12))
    img.rect(1, 1, w - 2, 18, gray(mid))
    img.rect(1, 1, w - 2, 1, gray(light))
    img.rect(1, 17, w - 2, 18, gray(dark))
    return img.anchor()


def plain_button(w):
    img = Img(w, 20)
    img.rect(0, 0, w - 1, 19, (10, 10, 12))
    img.rect(1, 1, w - 2, 18, (58, 58, 64))
    img.rect(1, 1, w - 2, 1, (92, 92, 100))
    img.rect(1, 17, w - 2, 18, (40, 40, 44))
    return img.anchor()


def total_strip():
    img = Img(206, 20)
    img.rect(0, 0, 205, 19, PANEL)
    img.rect(0, 0, 0, 19, EDGE)
    img.rect(205, 0, 205, 19, EDGE)
    img.rect(6, 0, 199, 0, PANEL_HI)
    return img.anchor()


GLYPHS = [
    (0xE200, "tab", lambda: tab(False), 0),
    (0xE201, "tab_selected", lambda: tab(True), 0),
    (0xE202, "badge", badge_col, 44),
    (0xE203, "card_top", card_top, 49),
    (0xE204, "card_body", card_body, 185),
    (0xE205, "slot", slot_box, 343),
    (0xE206, "rewards", lambda: panel(160), 49),
    (0xE207, "cart", lambda: panel(206), 214),
    (0xE208, "track", track, 74),
    (0xE209, "fill", fill_col, 75),
    (0xE20A, "marker", marker, 66),
    (0xE20B, "lock", lock, 77),
    (0xE20C, "gift", gift, 52),
    (0xE20D, "cart_icon", cart_icon, 218),
    (0xE20E, "reward_box", reward_box, 108),
    (0xE20F, "reward_box", reward_box, 158),
    (0xE210, "lock", lock, 135),
    (0xE211, "lock", lock, 185),
    (0xE220, "btn_view", lambda: plain_button(140), -6),
    (0xE221, "btn_buy", lambda: button(140, 255, 205, 150), -6),
    (0xE222, "btn_total", total_strip, -6),
    (0xE223, "btn_checkout", lambda: button(206, 255, 205, 150), -6),
]
BULLET_BASE = 0xE240
SUBDOT_BASE = 0xE260
HIGHLIGHT_ROWS = 10
HIGHLIGHT_TOP = 207
HIGHLIGHT_PITCH = 11

SPACES = {}
for i in range(10):
    SPACES[chr(0xF801 + i)] = -(1 << i)
    SPACES[chr(0xF821 + i)] = 1 << i


def read_png(data):
    pos, idat, w, h, ct, bd, trns = 8, b"", 0, 0, 6, 8, b""
    while pos < len(data):
        ln, = struct.unpack(">I", data[pos:pos + 4])
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if kind == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", body[:10])
        elif kind == b"IDAT":
            idat += body
        elif kind == b"tRNS":
            trns = body
    raw = zlib.decompress(idat)
    ch = {6: 4, 2: 3, 3: 1, 0: 1, 4: 2}[ct]
    bpp = max(1, ch * bd // 8)
    stride = (w * ch * bd + 7) // 8
    rows, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]
        i += 1
        line = bytearray(raw[i:i + stride])
        i += stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line)
        prev = line

    def alpha(x, y):
        if ct == 6:
            return rows[y][x * 4 + 3]
        if ct == 4:
            return rows[y][x * 2 + 1]
        if ct == 3:
            idx = rows[y][x] if bd == 8 else (rows[y][(x * bd) >> 3] >> (8 - bd - ((x * bd) & 7))) & ((1 << bd) - 1)
            return trns[idx] if idx < len(trns) else 255
        if ct == 0 and len(trns) == 2:
            return 0 if rows[y][x] == trns[1] else 255
        if ct == 2 and len(trns) == 6:
            return 0 if bytes(rows[y][x * 3:x * 3 + 3]) == bytes(trns[1::2]) else 255
        return 255

    return w, h, alpha


def measure(jar, name):
    w, h, alpha = read_png(jar.read("assets/minecraft/textures/%s.png" % name))
    last = -1
    for x in range(w):
        if any(alpha(x, y) for y in range(w)):
            last = x
    return last + 1, w, h // w


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: build_gui.py <minecraft client jar>")
    jar = zipfile.ZipFile(sys.argv[1])
    providers = [{"type": "space", "advances": SPACES}]
    written = set()
    for cp, name, fn, top in GLYPHS:
        img = fn()
        if name not in written:
            write(os.path.join(PACK, "assets", NS, "textures", "gui", "shop", name + ".png"), img.data(), binary=True)
            written.add(name)
        providers.append({"type": "bitmap", "file": TEX + name + ".png", "height": img.h, "ascent": 7 - top,
                          "chars": [chr(cp)]})
    for name, fn, base, dy in (("bullet", bullet, BULLET_BASE, 2), ("subdot", subdot, SUBDOT_BASE, 3)):
        img = fn()
        write(os.path.join(PACK, "assets", NS, "textures", "gui", "shop", name + ".png"), img.data(), binary=True)
        for row in range(HIGHLIGHT_ROWS):
            providers.append({"type": "bitmap", "file": TEX + name + ".png", "height": img.h,
                              "ascent": 7 - (HIGHLIGHT_TOP + row * HIGHLIGHT_PITCH + dy), "chars": [chr(base + row)]})
    font_dir = os.path.join(PACK, "assets", NS, "font")
    write(os.path.join(font_dir, "shop.json"), json.dumps({"providers": providers}, indent=1) + "\n")

    art_providers = []
    index = []
    for i, name in enumerate(ART):
        visible, frame, frames = measure(jar, name)
        base = ART_BASE + i * 16
        index.append("%s %d %d %d" % (name, base, visible, frame))
        for s, (size, top) in enumerate(ART_SLOTS):
            rows = [chr(base + s)] + ["\u0000"] * (frames - 1)
            art_providers.append({"type": "bitmap", "file": "minecraft:%s.png" % name, "height": size,
                                  "ascent": 7 - top, "chars": rows})
    write(os.path.join(font_dir, "shop_art.json"), json.dumps({"providers": art_providers}) + "\n")
    write(os.path.join(ROOT, "src", "main", "resources", "modules", "store", "art.txt"), "\n".join(index) + "\n")

    for y in range(0, TEXT_MAX + 1):
        write(os.path.join(font_dir, "shop_t%d.json" % y), json.dumps({"providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": "minecraft:font/ascii.png", "ascent": 7 - y, "chars": ASCII_ROWS},
        ]}) + "\n")
    print("store dialog assets written:", len(ART), "art textures")
    from vendor_vanilla import vendor
    vendor(jar)


if __name__ == "__main__":
    main()
