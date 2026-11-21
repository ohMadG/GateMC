import json
import os
import struct
import sys
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resourcepack")
W = 176

BG = (31, 34, 41, 255)
HEADER = (40, 44, 54, 255)
EDGE_DARK = (12, 13, 17, 255)
EDGE_LIGHT = (66, 72, 86, 255)
SLOT_FILL = (24, 26, 32, 255)
SLOT_SHADOW = (10, 11, 14, 255)
SLOT_SHINE = (62, 67, 80, 255)
INV_BG = (26, 28, 35, 255)
ACCENT_A = (255, 122, 24)
ACCENT_B = (255, 213, 79)


def png(width, height, pixels):
    raw = b"".join(b"\x00" + bytes(v for px in pixels[y] for v in px) for y in range(height))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def accent(x):
    f = x / (W - 1)
    return tuple(int(ACCENT_A[i] + (ACCENT_B[i] - ACCENT_A[i]) * f) for i in range(3)) + (255,)


def rect(img, x0, y0, x1, y1, color):
    for y in range(max(0, y0), min(len(img), y1 + 1)):
        for x in range(max(0, x0), min(W, x1 + 1)):
            img[y][x] = color


def slot(img, x, y):
    rect(img, x, y, x + 17, y + 17, SLOT_FILL)
    rect(img, x, y, x + 17, y, SLOT_SHADOW)
    rect(img, x, y, x, y + 17, SLOT_SHADOW)
    rect(img, x + 1, y + 17, x + 17, y + 17, SLOT_SHINE)
    rect(img, x + 17, y + 1, x + 17, y + 17, SLOT_SHINE)


def menu(rows):
    h = 114 + 18 * rows
    img = [[(0, 0, 0, 0)] * W for _ in range(h)]
    rect(img, 0, 0, W - 1, h - 1, BG)
    rect(img, 1, 1, W - 2, 15, HEADER)
    for x in range(2, W - 2):
        img[15][x] = accent(x)
        img[16][x] = tuple(int(c * 0.45) for c in accent(x)[:3]) + (255,)
    top = 17
    for r in range(rows):
        for c in range(9):
            slot(img, 7 + 18 * c, top + 18 * r)
    sep = top + 18 * rows + 3
    for x in range(7, W - 7):
        a = accent(x)
        img[sep][x] = (a[0] // 2, a[1] // 2, a[2] // 2, 255)
    inv_top = 18 * rows + 17 + 12
    rect(img, 3, inv_top - 12 + 6, W - 4, h - 4, INV_BG)
    for r in range(3):
        for c in range(9):
            slot(img, 7 + 18 * c, 18 * rows + 30 + 18 * r)
    for c in range(9):
        slot(img, 7 + 18 * c, 18 * rows + 88)
    for x in range(W):
        img[0][x] = EDGE_DARK
        img[h - 1][x] = EDGE_DARK
    for y in range(h):
        img[y][0] = EDGE_DARK
        img[y][W - 1] = EDGE_DARK
    for x in range(1, W - 1):
        img[1][x] = EDGE_LIGHT if img[1][x] == HEADER else img[1][x]
    for (cx, cy) in ((0, 0), (W - 1, 0), (0, h - 1), (W - 1, h - 1)):
        img[cy][cx] = (0, 0, 0, 0)
    for (cx, cy, dx, dy) in ((1, 0, 0, 1), (W - 2, 0, W - 1, 1), (1, h - 1, 0, h - 2), (W - 2, h - 1, W - 1, h - 2)):
        img[cy][cx] = (0, 0, 0, 0)
        img[dy][dx] = (0, 0, 0, 0)
    return h, img


def main():
    tex = os.path.join(ROOT, "assets", "genscore", "textures", "gui")
    font_dir = os.path.join(ROOT, "assets", "genscore", "font")
    os.makedirs(tex, exist_ok=True)
    os.makedirs(font_dir, exist_ok=True)
    providers = [{"type": "space", "advances": {"": -8, "": -169}}]
    for rows in range(1, 7):
        h, img = menu(rows)
        with open(os.path.join(tex, "menu_%d.png" % rows), "wb") as f:
            f.write(png(W, h, img))
        providers.append({"type": "bitmap", "file": "genscore:gui/menu_%d.png" % rows, "ascent": 13, "height": h,
                          "chars": [chr(0xE100 + rows)]})
    with open(os.path.join(font_dir, "gui.json"), "w", encoding="utf-8") as f:
        json.dump({"providers": providers}, f, indent=2, ensure_ascii=True)
        f.write("\n")
    print("menu backgrounds: 6 sheets and font genscore:gui")


if __name__ == "__main__":
    main()
