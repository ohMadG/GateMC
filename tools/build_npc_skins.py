import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from build_pack import png, noise

OUT = os.path.join(os.path.dirname(__file__), "..", "resourcepack", "assets", "genscore", "textures", "entity", "npc")
CLEAR = (0, 0, 0, 0)


def rgb(h):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def tone(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (255,)


class Skin:
    def __init__(self, seed):
        self.px = [[CLEAR] * 64 for _ in range(64)]
        self.seed = seed

    def set(self, x, y, c, grain=0.0):
        if c is None:
            return
        if grain and c[3]:
            c = tone(c, 1 + (noise(x, y, self.seed) - 0.5) * grain)
        self.px[y][x] = c

    def box(self, u, v, w, h, d, fn, grain=0.08):
        faces = {
            "top": (u + d, v, w, d),
            "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h),
            "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h),
            "back": (u + 2 * d + w, v + d, w, h),
        }
        for face, (fx, fy, fw, fh) in faces.items():
            for y in range(fh):
                for x in range(fw):
                    c = fn(face, x, y, fw, fh)
                    if c is not None:
                        self.set(fx + x, fy + y, c, grain)

    def save(self, name):
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, name + ".png"), "wb") as f:
            f.write(png(64, 64, self.px))


HEAD = (0, 0, 8, 8, 8)
HAT = (32, 0, 8, 8, 8)
BODY = (16, 16, 8, 12, 4)
JACKET = (16, 32, 8, 12, 4)
RARM = (40, 16, 4, 12, 4)
RSLEEVE = (40, 32, 4, 12, 4)
LARM = (32, 48, 4, 12, 4)
LSLEEVE = (48, 48, 4, 12, 4)
RLEG = (0, 16, 4, 12, 4)
RPANTS = (0, 32, 4, 12, 4)
LLEG = (16, 48, 4, 12, 4)
LPANTS = (0, 48, 4, 12, 4)


def person(spec):
    s = Skin(spec["seed"])
    skin = rgb(spec["skin"])
    hair = rgb(spec["hair"])
    shirt = rgb(spec["shirt"])
    pants = rgb(spec["pants"])
    shoes = rgb(spec["shoes"])
    eye = rgb(spec.get("eye", "#3B2A1E"))
    sleeve_len = spec.get("sleeve", 12)
    hair_len = spec.get("hair_len", 3)

    def head(face, x, y, w, h):
        if face == "top":
            return hair
        if face == "bottom":
            return tone(skin, 0.85)
        if face == "back":
            return hair if y < hair_len + 3 else tone(skin, 0.92)
        if face in ("left", "right"):
            edge = x >= 6 if face == "right" else x <= 1
            if y < 2 or (y < hair_len + 2 and not edge):
                return hair
            return tone(skin, 0.95)
        if y < 2:
            return hair
        if y == 2 and spec.get("fringe", True) and x in (0, 1, 6, 7):
            return hair
        if y == 3 and x in (1, 2, 5, 6):
            return tone(hair, 0.85) if spec.get("brows", True) else skin
        if y == 4 and x in (1, 6):
            return rgb("#F2F2F2")
        if y == 4 and x in (2, 5):
            return eye
        if y == 5 and x in (3, 4):
            return tone(skin, 0.86)
        if y == 6 and x in (3, 4):
            return rgb(spec.get("mouth", "#8E4B3E"))
        return skin

    s.box(*HEAD, head, 0.04)

    def body(face, x, y, w, h):
        if face == "top":
            return tone(shirt, 1.05)
        if face == "bottom":
            return tone(pants, 0.9)
        if y >= 10:
            return pants if y == 11 else tone(shirt, 0.82) if spec.get("belt") is None else rgb(spec["belt"])
        return tone(shirt, 0.88) if face in ("left", "right", "back") and x in (0, w - 1) else shirt

    s.box(*BODY, body)

    def arm(face, x, y, w, h):
        if face == "top":
            return shirt
        if face == "bottom":
            return skin
        if y >= sleeve_len or y == 11:
            return skin if y >= sleeve_len else skin
        if y == sleeve_len - 1 and spec.get("cuff"):
            return rgb(spec["cuff"])
        return tone(shirt, 0.9) if face == "back" else shirt

    s.box(*RARM, arm)
    s.box(*LARM, arm)

    def leg(face, x, y, w, h):
        if face == "top":
            return pants
        if face == "bottom":
            return tone(shoes, 0.8)
        if y >= 10:
            return shoes
        return tone(pants, 0.88) if face in ("left", "right") else pants

    s.box(*RLEG, leg)
    s.box(*LLEG, leg)
    return s


def overlay(s, part, fn, grain=0.08):
    s.box(*part, fn, grain)


def hat(s, crown, brim=None, band=None, crown_rows=4, brim_row=None):
    def fn(face, x, y, w, h):
        if face == "top":
            return crown
        if face == "bottom":
            return None
        if y < crown_rows - 1:
            return crown
        if y == crown_rows - 1:
            return band or crown
        if brim and brim_row is not None and y == brim_row and face == "front":
            return brim
        return None

    overlay(s, HAT, fn, 0.06)


def guide():
    s = person({"seed": 1, "skin": "#E0AC84", "hair": "#6B4426", "shirt": "#3FA34D", "pants": "#C8B48C",
                "shoes": "#5A3B22", "eye": "#2E6BB0", "belt": "#7A5A34"})
    green = rgb("#2F8A3C")

    def hood(face, x, y, w, h):
        if face == "front" and 3 <= x <= 4 and y <= 6:
            return rgb("#E8E8E8") if y in (1, 2, 3, 4, 5) and x == 3 else None
        if face == "front" and x == 0 and y < 10:
            return tone(green, 0.85)
        if face == "back" and y < 3:
            return tone(green, 0.8)
        return None

    overlay(s, JACKET, hood)

    def cap(face, x, y, w, h):
        if face == "top":
            return green
        if y < 2:
            return green
        if y == 2 and face == "front":
            return tone(green, 0.7)
        return None

    overlay(s, HAT, cap)
    return s


def engineer():
    s = person({"seed": 2, "skin": "#C68E62", "hair": "#2B1E16", "shirt": "#6E7378", "pants": "#2C3E66",
                "shoes": "#3A2A1E", "belt": "#5B3A1E", "sleeve": 7})
    vest = rgb("#FF8A1A")
    stripe = rgb("#E9F2F2")

    def hivis(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if y >= 10:
            return None
        if face == "front" and x in (3, 4) and y < 9:
            return None
        if y in (5, 7):
            return stripe
        return vest

    overlay(s, JACKET, hivis)

    def tools(face, x, y, w, h):
        if face == "front" and y in (10, 11) and x in (1, 2, 5, 6):
            return rgb("#A9ADB2") if y == 10 else rgb("#3B3F44")
        return None

    overlay(s, BODY, tools)

    def hardhat(face, x, y, w, h):
        yellow = rgb("#F5C518")
        if face == "top":
            return yellow
        if face == "bottom":
            return None
        if y < 2:
            return yellow
        if y == 2:
            return tone(yellow, 0.8)
        if face == "front" and y == 3 and x in (1, 2, 5, 6):
            return rgb("#3BC4E0")
        if face == "front" and y == 3 and x in (0, 3, 4, 7):
            return rgb("#2A2A2A")
        if face in ("left", "right") and y == 3:
            return rgb("#2A2A2A")
        return None

    overlay(s, HAT, hardhat, 0.04)
    return s


def mystic():
    s = person({"seed": 3, "skin": "#D9B48F", "hair": "#D8D8E0", "shirt": "#5B2C8C", "pants": "#4A2373",
                "shoes": "#2A1840", "eye": "#38E8FF", "mouth": "#B8B8C4", "belt": "#E2B23A"})
    robe = rgb("#5B2C8C")
    gold = rgb("#E2B23A")

    def beard(face, x, y, w, h):
        if face == "front" and y >= 5 and 1 <= x <= 6:
            if y == 5 and x in (3, 4):
                return None
            return rgb("#E4E4EC")
        return None

    def hood(face, x, y, w, h):
        b = beard(face, x, y, w, h)
        if b:
            return b
        if face == "top":
            return robe
        if face == "front":
            if y < 2:
                return robe
            if x in (0, 7):
                return tone(robe, 0.8)
            if y == 1 and 2 <= x <= 5:
                return gold
            return None
        if face == "back":
            return robe
        if face in ("left", "right"):
            return tone(robe, 0.9)
        return None

    overlay(s, HAT, hood, 0.06)

    def trim(face, x, y, w, h):
        if face == "front" and x in (3, 4):
            return gold
        if face == "front" and y >= 10:
            return tone(robe, 0.9)
        if face in ("front", "back", "left", "right") and y >= 10:
            return tone(robe, 0.85)
        return None

    overlay(s, JACKET, trim)

    def robe_legs(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if y < 10:
            return tone(robe, 0.92)
        if y == 10:
            return gold
        return None

    overlay(s, RPANTS, robe_legs)
    overlay(s, LPANTS, robe_legs)
    return s


def trader():
    s = person({"seed": 4, "skin": "#8D5A3B", "hair": "#1E1612", "shirt": "#EFE6D2", "pants": "#B5946A",
                "shoes": "#4A2E1A", "belt": "#3E2816", "sleeve": 11, "cuff": "#D8CDB6"})
    leather = rgb("#7A4A26")

    def vest(face, x, y, w, h):
        if face in ("top", "bottom") or y >= 10:
            return None
        if face == "front" and x in (3, 4):
            return None
        if face == "front" and y == 4 and x in (1, 6):
            return rgb("#E9C44A")
        return leather

    overlay(s, JACKET, vest)

    def bandana(face, x, y, w, h):
        red = rgb("#C62F2F")
        if face == "top":
            return red
        if y < 2:
            return red
        if face == "front" and y == 2 and x in (1, 3, 5):
            return rgb("#F2F2F2")
        if face == "back" and y == 2 and x in (3, 4):
            return red
        if face == "back" and y == 3 and x in (2, 5):
            return tone(red, 0.8)
        if face == "left" and y == 5 and x == 4:
            return rgb("#F5C518")
        return None

    overlay(s, HAT, bandana, 0.05)
    return s


def banker():
    s = person({"seed": 5, "skin": "#E8C2A0", "hair": "#8C8C92", "shirt": "#1D1F26", "pants": "#1D1F26",
                "shoes": "#0E0E10", "belt": "#1D1F26", "cuff": "#F2F2F2", "fringe": False})

    def suit(face, x, y, w, h):
        if face == "front":
            if x in (3, 4) and y < 6:
                return rgb("#E2B23A") if y >= 1 else rgb("#F2F2F2")
            if x in (2, 5) and y < 4:
                return rgb("#F2F2F2")
            if y in (6, 8) and x == 3:
                return rgb("#E2B23A")
        return None

    overlay(s, BODY, suit)

    def face_extra(face, x, y, w, h):
        if face == "front" and y == 5 and 2 <= x <= 5:
            return rgb("#6E6E74")
        if face == "front" and y == 4 and x == 5:
            return rgb("#E2B23A")
        return None

    overlay(s, HEAD, face_extra, 0.0)

    def tophat(face, x, y, w, h):
        black = rgb("#141418")
        if face == "top":
            return black
        if face == "bottom":
            return None
        if y < 2:
            return black
        if y == 2:
            return rgb("#E2B23A")
        return None

    overlay(s, HAT, tophat, 0.03)
    return s


def foreman():
    s = person({"seed": 6, "skin": "#D7A07A", "hair": "#5A3A24", "shirt": "#E9E6DE", "pants": "#2F5DA8",
                "shoes": "#3A2A1E", "belt": "#2F5DA8", "sleeve": 6})
    denim = rgb("#2F5DA8")

    def overalls(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if face == "front":
            if y < 5:
                if x in (1, 6):
                    return denim
                if 2 <= x <= 5 and y >= 2:
                    return denim if not (y == 3 and x in (3, 4)) else rgb("#C9CED6")
                return None
            if y == 5 and x in (1, 6):
                return rgb("#C9CED6")
            return denim
        if face == "back":
            if y < 5:
                return denim if x in (1, 6) or (y == 4 and 1 <= x <= 6) else None
            return denim
        return denim if y >= 5 else None

    overlay(s, JACKET, overalls)

    def moustache(face, x, y, w, h):
        if face == "front" and y == 5 and 2 <= x <= 5:
            return rgb("#4A2E1A")
        return None

    overlay(s, HEAD, moustache, 0.0)

    def hardhat(face, x, y, w, h):
        white = rgb("#F2F2F2")
        if face == "top":
            return white
        if face == "bottom":
            return None
        if y < 2:
            return white
        if y == 2:
            return tone(white, 0.8)
        if face == "front" and y == 1 and x in (3, 4):
            return rgb("#2F5DA8")
        return None

    overlay(s, HAT, hardhat, 0.03)
    return s


def stylist():
    s = person({"seed": 7, "skin": "#F1C7A8", "hair": "#C04BD8", "shirt": "#F2F2F2", "pants": "#16161C",
                "shoes": "#E04A8C", "belt": "#16161C", "eye": "#6A3DB0", "mouth": "#D2477A", "hair_len": 5})
    pink = rgb("#E04A8C")

    def jacket(face, x, y, w, h):
        if face in ("top", "bottom") or y >= 10:
            return None
        if face == "front" and 2 <= x <= 5:
            return None
        return pink

    overlay(s, JACKET, jacket)

    def sleeves(face, x, y, w, h):
        if face in ("top",):
            return pink
        if face == "bottom" or y >= 10:
            return None
        return pink

    overlay(s, RSLEEVE, sleeves)
    overlay(s, LSLEEVE, sleeves)

    def shades(face, x, y, w, h):
        hair = rgb("#C04BD8")
        if face == "front" and y == 4 and x in (1, 2, 5, 6):
            return rgb("#121216")
        if face == "front" and y == 4 and x in (3, 4):
            return rgb("#E2B23A")
        if face == "top":
            return tone(hair, 1.1)
        if face == "front" and y < 2:
            return tone(hair, 1.1)
        if face == "front" and y == 2 and x <= 4:
            return tone(hair, 1.1)
        if face in ("left", "right") and y < 6:
            return hair
        if face == "back" and y < 7:
            return hair
        return None

    overlay(s, HAT, shades, 0.05)
    return s


def merchant():
    s = person({"seed": 8, "skin": "#B57B55", "hair": "#3A2618", "shirt": "#1E7A46", "pants": "#3A2E26",
                "shoes": "#2A1C12", "belt": "#5B3A1E", "cuff": "#E2B23A"})
    coat = rgb("#1E7A46")
    gold = rgb("#E2B23A")

    def coat_fn(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        if face == "front":
            if x in (3, 4):
                return gold if y in (2, 5, 8) else tone(coat, 0.8)
            return coat if y < 12 else None
        return coat

    overlay(s, JACKET, coat_fn)

    def coat_legs(face, x, y, w, h):
        if face in ("top", "bottom"):
            return None
        return coat if y < 4 else None

    overlay(s, RPANTS, coat_legs)
    overlay(s, LPANTS, coat_legs)

    def beard(face, x, y, w, h):
        if face == "front" and y >= 5 and 1 <= x <= 6 and not (y == 6 and x in (3, 4)):
            return rgb("#3A2618")
        return None

    overlay(s, HEAD, beard, 0.0)

    def feather_hat(face, x, y, w, h):
        green = rgb("#145A33")
        if face == "top":
            return green
        if face == "bottom":
            return None
        if y < 2:
            return green
        if y == 2:
            return gold
        if face == "left" and y < 3 and x in (5, 6):
            return rgb("#3BD16F")
        return None

    overlay(s, HAT, feather_hat, 0.05)
    return s


SKINS = {
    "guide": guide,
    "upgrades": engineer,
    "powers": mystic,
    "tokens": trader,
    "daily": banker,
    "tasks": foreman,
    "cosmetics": stylist,
    "store": merchant,
}

if __name__ == "__main__":
    for name, make in SKINS.items():
        make().save(name)
    print("wrote", len(SKINS), "skins to", os.path.normpath(OUT))
