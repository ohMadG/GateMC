"""
Draws the handheld tool sprites: the Knockback Rod and the three Raid Pick tiers.

    python tools/build_tools.py [preview_dir]

Writes 32x32 textures to resourcepack/assets/genscore/textures/item/tools/, handheld models and item definitions
(genscore:kb_rod, genscore:raid_pick_1..3). With preview_dir it also writes 12x upscaled previews.
"""
import json
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACK = os.path.join(ROOT, "resourcepack", "assets", "genscore")
N = 32


def png(w, h, rows):
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in row) for row in rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def lit(c, f):
    if f >= 0:
        return mix(c, (255, 255, 255), f)
    return mix(c, (0, 0, 0), -f)


class Canvas:
    def __init__(self):
        self.px = [[None] * N for _ in range(N)]

    def put(self, x, y, c):
        if 0 <= x < N and 0 <= y < N:
            self.px[y][x] = c

    def stroke(self, a, b, half, paint):
        ax, ay = a
        bx, by = b
        dx, dy = bx - ax, by - ay
        ln = math.hypot(dx, dy)
        ux, uy = dx / ln, dy / ln
        for y in range(N):
            for x in range(N):
                px, py = x + 0.5 - ax, y + 0.5 - ay
                t = px * ux + py * uy
                u = -px * uy + py * ux
                if -0.3 <= t <= ln + 0.3 and abs(u) <= half:
                    c = paint(t / ln, u / half, x, y)
                    if c:
                        self.put(x, y, c)

    def blob(self, cx, cy, rx, ry, paint, rot=0.0):
        cs, sn = math.cos(rot), math.sin(rot)
        for y in range(N):
            for x in range(N):
                px, py = x + 0.5 - cx, y + 0.5 - cy
                qx, qy = px * cs + py * sn, -px * sn + py * cs
                d = (qx / rx) ** 2 + (qy / ry) ** 2
                if d <= 1:
                    c = paint(qx / rx, qy / ry, d, x, y)
                    if c:
                        self.put(x, y, c)

    def outline(self, dark=(18, 16, 22)):
        add = []
        for y in range(N):
            for x in range(N):
                if self.px[y][x] is not None:
                    continue
                near = [self.px[y + j][x + i] for i, j in ((1, 0), (-1, 0), (0, 1), (0, -1))
                        if 0 <= x + i < N and 0 <= y + j < N and self.px[y + j][x + i] is not None]
                if near:
                    add.append((x, y, mix(near[0], dark, 0.82)))
        for x, y, c in add:
            self.px[y][x] = c

    def rows(self):
        return [[(c[0], c[1], c[2], 255) if c else (0, 0, 0, 0) for c in row] for row in self.px]


def kb_rod():
    cv = Canvas()
    base, tip = (5, 27), (20, 12)
    grip_a, grip_b = (52, 36, 30), (86, 58, 44)
    gold = (232, 178, 52)

    def shaft(t, u, x, y):
        if t < 0.38:
            band = int((x + y) / 2) % 3 == 0
            c = grip_b if band else grip_a
            return lit(c, -0.35 * u)
        if 0.38 <= t < 0.46 or t > 0.93:
            return lit(gold, 0.35 - 0.45 * u)
        return lit((110, 120, 138), 0.25 - 0.5 * u)

    cv.stroke(base, tip, 1.7, shaft)
    cv.blob(4.6, 27.4, 2.3, 2.3, lambda qx, qy, d, x, y: lit(gold, 0.45 - 0.6 * (qx + qy) / 2))
    cv.stroke((18.3, 13.7), (21.7, 10.3), 3.1, lambda t, u, x, y: lit((236, 236, 240), 0.1 - 0.35 * u))
    red = (214, 38, 46)

    def glove(qx, qy, d, x, y):
        f = 0.12 - 0.5 * (qx * 0.7 + qy * 0.7) - 0.25 * d
        if d > 0.78 and qx + qy > 0.4:
            f -= 0.25
        return lit(red, f)

    cv.blob(24.6, 7.4, 6.4, 5.2, glove, rot=-math.pi / 4)
    cv.blob(20.0, 5.6, 2.4, 1.8, lambda qx, qy, d, x, y: lit(red, 0.15 - 0.4 * qy), rot=-math.pi / 4)
    cv.put(23, 4, (255, 178, 182))
    cv.put(24, 4, (255, 150, 156))
    cv.put(22, 5, (255, 150, 156))
    for i in range(3):
        cv.put(25 + i, 9 + i, lit(red, -0.45))
    cv.outline()
    return cv.rows()


PICK_TIERS = {
    1: {"metal": (176, 182, 192), "core": (255, 64, 44), "edge": (232, 236, 242)},
    2: {"metal": (64, 150, 236), "core": (120, 255, 246), "edge": (170, 226, 255)},
    3: {"metal": (74, 64, 78), "core": (255, 106, 22), "edge": (255, 150, 60)},
}


def raid_pick(tier):
    pal = PICK_TIERS[tier]
    cv = Canvas()
    base, top = (4, 28), (19.5, 12.5)
    steel = (64, 66, 76)

    def handle(t, u, x, y):
        if t < 0.5:
            tape = (178, 32, 34) if int((x + y) / 2) % 3 else (226, 196, 40)
            return lit(tape, -0.4 * u)
        return lit(steel, 0.3 - 0.45 * u)

    cv.stroke(base, top, 1.6, handle)
    cv.blob(3.8, 28.2, 2.0, 2.0, lambda qx, qy, d, x, y: lit(steel, 0.3 - 0.5 * (qx + qy) / 2))
    hx, hy = 20.6, 11.4
    nx, ny = 1 / math.sqrt(2), 1 / math.sqrt(2)
    dx, dy = 1 / math.sqrt(2), -1 / math.sqrt(2)
    span, bend = 14.0, 0.055
    metal = pal["metal"]
    for y in range(N):
        for x in range(N):
            best = None
            for k in range(-140, 141):
                s = k / 10.0
                cx = hx + nx * s - dx * bend * s * s
                cy = hy + ny * s - dy * bend * s * s
                width = 2.9 * (1 - (abs(s) / span) ** 2.2) + 0.25
                dd = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if dd <= width and (best is None or dd / width < best[0]):
                    px, py = x + 0.5 - cx, y + 0.5 - cy
                    side = px * dx + py * dy
                    best = (dd / width, side / width, s)
            if best:
                _, side, s = best
                f = 0.2 + 0.45 * side - 0.1 * abs(s) / span
                c = lit(metal, f)
                if side > 0.55 or abs(s) > span * 0.8:
                    c = lit(pal["edge"], 0.15)
                cv.put(x, y, c)

    def socket(qx, qy, d, x, y):
        if d < 0.38:
            return lit(pal["core"], 0.45 - d)
        stripe = int(x - y + 64) % 4 < 2
        return lit((236, 186, 30) if stripe else (30, 28, 32), 0.1 - 0.3 * qy)

    cv.blob(hx - 0.2, hy + 0.2, 3.5, 3.5, socket)
    if tier == 3:
        for s in (-11.5, -10.0, 10.0, 11.5):
            x = int(hx + nx * s - dx * bend * s * s)
            y = int(hy + ny * s - dy * bend * s * s)
            cv.put(x, y, pal["core"])
    cv.outline()
    return cv.rows()


def upscale(rows, k):
    out = []
    for row in rows:
        big = []
        for p in row:
            big.extend([p if p[3] else (54, 57, 63, 255)] * k)
        out.extend([big] * k)
    return out


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    mode = "wb" if isinstance(data, bytes) else "w"
    with open(path, mode) as f:
        f.write(data)


def main():
    preview = sys.argv[1] if len(sys.argv) > 1 else None
    sprites = {"kb_rod": kb_rod()}
    for t in PICK_TIERS:
        sprites["raid_pick_%d" % t] = raid_pick(t)
    for name, rows in sprites.items():
        write(os.path.join(PACK, "textures", "item", "tools", name + ".png"), png(N, N, rows))
        write(os.path.join(PACK, "models", "item", name + ".json"), json.dumps(
            {"parent": "minecraft:item/handheld", "textures": {"layer0": "genscore:item/tools/" + name}}, indent=2) + "\n")
        write(os.path.join(PACK, "items", name + ".json"), json.dumps(
            {"model": {"type": "minecraft:model", "model": "genscore:item/" + name}}, indent=2) + "\n")
        if preview:
            write(os.path.join(preview, name + ".png"), png(N * 12, N * 12, upscale(rows, 12)))
    print("wrote", ", ".join(sprites))


if __name__ == "__main__":
    main()
