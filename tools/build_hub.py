import gzip
import io
import json
import math
import os
import struct
import sys

DATA_VERSION = 5023
W = 61
C = 30
ROAD = 3

blocks_report = json.load(open(sys.argv[1]))
out_path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..", "target", "hub.nbt")

grid = {}


def state(name, **props):
    key = "minecraft:" + name
    if key not in blocks_report:
        raise SystemExit("unknown block " + name)
    info = blocks_report[key]
    allowed = info.get("properties", {})
    default = next((s.get("properties", {}) for s in info["states"] if s.get("default")), {})
    full = dict(default)
    for k, v in props.items():
        v = str(v).lower()
        if k not in allowed or v not in allowed[k]:
            raise SystemExit("bad property %s=%s on %s" % (k, v, name))
        full[k] = v
    return (key, tuple(sorted(full.items())))


def put(x, y, z, name, **props):
    if 0 <= x < W and 0 <= z < W and y >= 0:
        grid[(x, y, z)] = state(name, **props)


def get(x, y, z):
    return grid.get((x, y, z))


def fill(x0, y0, z0, x1, y1, z1, name, **props):
    for x in range(min(x0, x1), max(x0, x1) + 1):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                put(x, y, z, name, **props)


def in_walkway(x, z):
    return abs(x - C) <= ROAD or abs(z - C) <= ROAD


for x in range(W):
    for z in range(W):
        edge = min(x, z, W - 1 - x, W - 1 - z)
        dx, dz = abs(x - C), abs(z - C)
        if edge <= 1:
            name = "yellow_concrete" if ((x + z) // 2) % 2 == 0 else "black_concrete"
        elif dx <= ROAD or dz <= ROAD:
            name = "smooth_stone"
            if (dx == ROAD and dz > 8 and dz % 6 == 0) or (dz == ROAD and dx > 8 and dx % 6 == 0):
                name = "sea_lantern"
        elif dx == ROAD + 1 or dz == ROAD + 1:
            along = z if dx == ROAD + 1 else x
            name = "yellow_concrete" if (along // 2) % 2 == 0 else "black_concrete"
        elif x % 6 == 0 or z % 6 == 0:
            name = "gray_concrete"
        else:
            name = "light_gray_concrete"
            if (x % 6 in (2, 3)) and (z % 6 in (2, 3)) and ((x // 6) + (z // 6)) % 3 == 0:
                name = "waxed_oxidized_copper_grate"
        put(x, 0, z, name)

for a in range(C - ROAD, C + ROAD + 1):
    for (x, z) in ((a, 0), (a, W - 1), (0, a), (W - 1, a)):
        put(x, 0, z, "smooth_stone_slab", type="bottom")
    for (x, z) in ((a, 1), (a, W - 2), (1, a), (W - 2, a)):
        put(x, 0, z, "smooth_stone")


def side_xy(side, u, v):
    if side == 0:
        return u, v
    if side == 1:
        return W - 1 - v, u
    if side == 2:
        return W - 1 - u, W - 1 - v
    return v, W - 1 - u


for side in range(4):
    for u in range(W):
        if C - 7 <= u <= C + 7:
            continue
        x, z = side_xy(side, u, 0)
        if u % 5 == 0 or u in (0, W - 1):
            fill(x, 1, z, x, 2, z, "polished_deepslate")
            put(x, 3, z, "lantern")
        else:
            fill(x, 1, z, x, 2, z, "iron_bars")
    for u in (C - 6, C - 5, C + 5, C + 6):
        for v in (0, 1):
            x, z = side_xy(side, u, v)
            for y in range(1, 10):
                if y <= 2:
                    put(x, y, z, "yellow_concrete" if (y + u) % 2 == 0 else "black_concrete")
                else:
                    put(x, y, z, "polished_deepslate")
    for u in range(C - 7, C + 8):
        for v in (0, 1):
            x, z = side_xy(side, u, v)
            put(x, 10, z, "yellow_concrete" if (u // 2) % 2 == 0 else "black_concrete")
            put(x, 11, z, "polished_deepslate")
    for u in (C - 7, C + 7):
        x, z = side_xy(side, u, 0)
        put(x, 12, z, "lightning_rod")
    for u in (C - 3, C + 3):
        x, z = side_xy(side, u, 1)
        put(x, 9, z, "iron_chain", axis="y")
        put(x, 8, z, "lantern", hanging="true")


P0, P1 = C - 7, C + 7
for x in range(P0, P1 + 1):
    for z in range(P0, P1 + 1):
        edge = x in (P0, P1) or z in (P0, P1)
        put(x, 1, z, "polished_deepslate" if edge else "deepslate_tiles")
for a in range(C - 2, C + 3):
    put(a, 1, P0 - 1, "polished_deepslate_stairs", facing="south")
    put(a, 1, P1 + 1, "polished_deepslate_stairs", facing="north")
    put(P0 - 1, 1, a, "polished_deepslate_stairs", facing="east")
    put(P1 + 1, 1, a, "polished_deepslate_stairs", facing="west")

B0, B1 = C - 2, C + 2
for y in range(2, 13):
    for x in range(B0, B1 + 1):
        for z in range(B0, B1 + 1):
            shell = x in (B0, B1) or z in (B0, B1)
            corner = x in (B0, B1) and z in (B0, B1)
            if not shell:
                put(x, y, z, "shroomlight" if y % 2 else "ochre_froglight")
                continue
            if corner:
                name = "polished_deepslate"
            elif y == 2:
                name = "deepslate_tiles"
            elif y == 7:
                name = "iron_block"
            else:
                name = "orange_stained_glass"
            put(x, y, z, name)
fill(B0, 13, B0, B1, 13, B1, "polished_deepslate")
fill(B0 + 1, 14, B0 + 1, B1 - 1, 14, B1 - 1, "waxed_cut_copper")
put(C, 15, C, "iron_block")
put(C, 16, C, "lightning_rod")
for (x, z) in ((B0, B0), (B1, B0), (B0, B1), (B1, B1)):
    put(x, 14, z, "campfire", lit="true")

CRATES = [
    ("vote", C - 5, C - 5, "lime_concrete", "emerald_block", "north"),
    ("cosmetic", C + 5, C - 5, "light_blue_concrete", "diamond_block", "north"),
    ("amethyst", C - 5, C + 5, "purple_concrete", "amethyst_block", "south"),
    ("op", C + 5, C + 5, "red_nether_bricks", "redstone_block", "south"),
]
crate_spots = []
for crate, px, pz, trim, core, facing in CRATES:
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            put(x, 2, z, core if (x, z) == (px, pz) else trim)
    put(px, 3, pz, "barrier")
    crate_spots.append((crate, px, 3, pz))


for sx, sz in ((C - 9, C - 9), (C + 9, C - 9), (C - 9, C + 9), (C + 9, C + 9)):
    for y in range(1, 25):
        for x in range(sx - 1, sx + 2):
            for z in range(sz - 1, sz + 2):
                if y <= 3:
                    name = "polished_deepslate"
                elif 18 <= y <= 23:
                    name = "red_concrete" if (y // 2) % 2 == 0 else "white_concrete"
                else:
                    name = "bricks"
                put(x, y, z, name)
    for x in range(sx - 1, sx + 2):
        for z in range(sz - 1, sz + 2):
            if (x, z) != (sx, sz):
                put(x, 25, z, "polished_deepslate")
    put(sx, 24, sz, "hay_block")
    put(sx, 25, sz, "campfire", lit="true", signal_fire="true")
    ux = 1 if sx < C else -1
    uz = 1 if sz < C else -1
    for k in range(2, 8):
        put(sx + ux * k, 9, sz, "waxed_copper_block")
    for k in range(0, 7):
        put(sx + ux * 7, 9, sz + uz * k, "waxed_copper_block")
    put(sx + ux * 7, 9, sz, "iron_block")


for pz in (12, W - 13):
    for x in range(16, W - 16):
        put(x, 7, pz, "waxed_copper_block")
        put(x, 7, pz + 1, "iron_block")
    for x in (16, W - 17):
        fill(x, 1, pz, x, 6, pz + 1, "polished_deepslate")
    for x in (C - 5, C + 5):
        fill(x, 1, pz, x, 6, pz, "polished_deepslate")
        put(x, 1, pz + 1, "yellow_concrete")
    for x in range(C - 3, C + 4, 3):
        put(x, 6, pz, "lantern", hanging="true")


def tank(cx, cz, r, top):
    for x in range(cx - r - 1, cx + r + 2):
        for z in range(cz - r - 1, cz + r + 2):
            d = math.hypot(x - cx, z - cz)
            if d <= r + 0.5:
                put(x, 1, z, "polished_deepslate")
                put(x, top + 1, z, "light_gray_concrete")
                if d <= r - 1:
                    put(x, top + 2, z, "smooth_stone_slab", type="bottom")
            if r - 0.5 <= d <= r + 0.5:
                for y in range(2, top + 1):
                    put(x, y, z, "waxed_copper_block" if y in (4, top - 2) else "white_concrete")
    put(cx, top + 2, cz, "iron_bars")
    for y in range(2, top + 2):
        put(cx, y, cz + r + 1, "ladder", facing="south")


tank(7, 7, 3, 11)
tank(15, 7, 3, 9)
tank(7, 16, 3, 9)


COLORS = [("orange_concrete", "orange_terracotta"), ("blue_concrete", "blue_terracotta"),
          ("red_concrete", "red_terracotta"), ("green_concrete", "green_terracotta"),
          ("white_concrete", "light_gray_terracotta")]


def container(x0, y0, z0, length, along_x, color):
    main, rib = color
    for i in range(length):
        for j in range(3):
            for y in range(y0, y0 + 3):
                x, z = (x0 + i, z0 + j) if along_x else (x0 + j, z0 + i)
                end = i in (0, length - 1)
                put(x, y, z, "light_gray_terracotta" if end else (rib if i % 2 else main))




GZ0, GZ1 = W - 19, W - 4
for gx in (4, 17):
    for gz in (GZ0, GZ1):
        for y in range(1, 12):
            put(gx, y, gz, "yellow_concrete" if (y // 2) % 2 == 0 else "black_concrete")
    for z in range(GZ0, GZ1 + 1):
        put(gx, 12, z, "yellow_concrete")
        if z not in (GZ0, GZ1):
            put(gx, 1, z, "rail", shape="north_south")
mid = (GZ0 + GZ1) // 2
for x in range(4, 18):
    put(x, 13, mid, "yellow_concrete" if (x // 2) % 2 == 0 else "black_concrete")
fill(10, 12, mid - 1, 11, 12, mid + 1, "gray_concrete")
for y in range(6, 12):
    put(10, y, mid, "iron_chain", axis="y")
put(10, 5, mid, "lightning_rod", facing="down")
container(7, 2, mid - 1, 7, True, COLORS[2])
put(4, 9, GZ0 + 1, "glass")
fill(4, 8, GZ0 + 1, 4, 10, GZ0 + 2, "light_gray_concrete")
put(4, 9, GZ0 + 2, "glass")


S0, S1 = W - 18, W - 4
for x in range(S0, S1 + 1):
    for z in range(S0, S1 + 1):
        sky = (x - S0) % 5 == 2 and (z - S0) % 5 == 2
        put(x, 8, z, "tinted_glass" if sky else "deepslate_tile_slab", **({} if sky else {"type": "bottom"}))
for x in range(S0, S1 + 1, 7):
    for z in range(S0, S1 + 1, 7):
        fill(x, 1, z, x, 7, z, "polished_deepslate")
for x in range(S0 + 2, S1 - 1, 2):
    put(x, 1, S0 + 3, "blast_furnace", facing="south")
    put(x, 2, S0 + 3, "hopper")
    if (x - S0) % 4 == 2:
        fill(x, 3, S0 + 3, x, 9, S0 + 3, "bricks")
        put(x, 10, S0 + 3, "campfire", lit="true")
for x in range(S0 + 3, S1 - 1, 3):
    put(x, 1, S0 + 7, "lava_cauldron")
put(S0 + 3, 1, S1 - 3, "anvil", facing="east")
put(S0 + 5, 1, S1 - 3, "smithing_table")
put(S0 + 7, 1, S1 - 3, "grindstone", face="floor", facing="north")
put(S0 + 9, 1, S1 - 3, "stonecutter")
for x in range(S0 + 2, S1, 4):
    for z in range(S0 + 2, S1, 4):
        if get(x, 7, z) is None and get(x, 8, z) and "slab" in get(x, 8, z)[0]:
            put(x, 7, z, "iron_chain", axis="y")
            put(x, 6, z, "lantern", hanging="true")


for side in range(4):
    for v in (6, 18):
        for du in (-5, 5):
            x, z = side_xy(side, C + du, v)
            if get(x, 1, z) is None:
                fill(x, 1, z, x, 3, z, "polished_basalt")
                put(x, 4, z, "lantern")


import random
rng = random.Random(5023)


def in_stats(x, z):
    return 39 <= x <= W - 2 and 1 <= z <= 20


def walk_zone(x, z):
    return abs(x - C) <= ROAD + 1 or abs(z - C) <= ROAD + 1


def free(x, y, z):
    return 0 <= x < W and 0 <= z < W and get(x, y, z) is None


def column_free(x, z, top=6):
    return all(free(x, y, z) for y in range(1, top + 1))


for x in range(2, W - 2):
    for z in range(2, W - 2):
        cur = get(x, 0, z)
        if cur and cur[0] == "minecraft:light_gray_concrete":
            r = rng.random()
            if r < 0.10:
                put(x, 0, z, "tuff")
            elif r < 0.16:
                put(x, 0, z, "andesite")
            elif r < 0.18:
                put(x, 0, z, "gray_concrete")
        if cur and cur[0] == "minecraft:smooth_stone" and rng.random() < 0.05:
            put(x, 0, z, "polished_andesite")

for side in range(4):
    for d in range(10, 27, 5):
        v = C - d
        for du, dv in ((0, 0), (-1, -1), (1, -1)):
            x, z = side_xy(side, C + du, v + dv)
            put(x, 0, z, "white_concrete")

for x in range(C - 2, C + 3):
    for z in range(C - 13, C - 8):
        edge = x in (C - 2, C + 2) or z in (C - 13, C - 9)
        put(x, 0, z, "yellow_concrete" if edge else "polished_tuff")
put(C, 0, C - 11, "sea_lantern")
for (x, z) in ((C - 2, C - 13), (C + 2, C - 13), (C - 2, C - 9), (C + 2, C - 9)):
    put(x, 0, z, "sea_lantern")


def conveyor(z, x0, x1, supports, cargo):
    for x in range(x0, x1 + 1):
        put(x, 4, z, "black_concrete")
        put(x, 4, z - 1, "iron_bars")
        put(x, 4, z + 1, "iron_bars")
        if (x - x0) % 4 == 2:
            put(x, 5, z, cargo[((x - x0) // 4) % len(cargo)])
    for x in supports:
        fill(x, 1, z, x, 3, z, "polished_deepslate")
        put(x, 3, z - 1, "polished_deepslate_stairs", facing="south", half="top")
        put(x, 3, z + 1, "polished_deepslate_stairs", facing="north", half="top")
    for x in (x0 - 1, x1 + 1):
        put(x, 1, z, "blast_furnace", facing="south")
        put(x, 2, z, "observer", facing="up")
        put(x, 3, z, "hopper")
        put(x, 4, z, "iron_block")
        put(x, 5, z, "lightning_rod")


ORES = ["raw_iron_block", "coal_block", "raw_copper_block", "raw_gold_block"]
conveyor(16, 20, 38, (20, 24, 36), ORES)
conveyor(W - 10, 20, 40, (20, 24, 36, 40), ORES[::-1])

for gx in (12, W - 13):
    for gz in (C - 5, C + 5):
        fill(gx, 1, gz, gx, 6, gz, "polished_deepslate")
        put(gx, 1, gz, "yellow_concrete")
    for gz in range(C - 5, C + 6):
        put(gx, 7, gz, "yellow_concrete" if (gz // 2) % 2 == 0 else "black_concrete")
    for gz in (C - 2, C + 2):
        put(gx, 6, gz, "iron_chain", axis="y")
        put(gx, 5, gz, "copper_lantern", hanging="true")
    put(gx, 8, C, "end_rod", facing="up")

for sx, sz in ((C - 9, C - 9), (C + 9, C - 9), (C - 9, C + 9), (C + 9, C + 9)):
    for x in range(sx - 2, sx + 3):
        for z in range(sz - 2, sz + 3):
            if max(abs(x - sx), abs(z - sz)) == 2:
                put(x, 14, z, "waxed_cut_copper_slab", type="top")
                put(x, 15, z, "iron_bars")
    for x in range(sx - 1, sx + 2):
        for z in range(sz - 1, sz + 2):
            if (x, z) != (sx, sz):
                put(x, 13, z, "polished_deepslate")
    for (x, z) in ((sx - 2, sz - 2), (sx + 2, sz - 2), (sx - 2, sz + 2), (sx + 2, sz + 2)):
        put(x, 16, z, "copper_lantern")
    ox = -1 if sx < C else 1
    for y in range(4, 14):
        put(sx + ox * 2, y, sz, "ladder", facing="west" if ox < 0 else "east")
    put(sx + ox * 2, 14, sz, "air")
    put(sx + ox * 2, 15, sz, "air")

for (x, z) in ((B0, B0), (B1, B0), (B0, B1), (B1, B1)):
    put(x, 5, z, "waxed_copper_bulb", lit="true")
    put(x, 10, z, "waxed_copper_bulb", lit="true")

for crate, px, py, pz in crate_spots:
    for x in range(px - 2, px + 3):
        for z in range(pz - 2, pz + 3):
            if max(abs(x - px), abs(z - pz)) == 2 and get(x, 1, z) and "deepslate" in get(x, 1, z)[0]:
                put(x, 1, z, "yellow_concrete" if (x + z) % 2 == 0 else "black_concrete")

for (cx, cz, r) in ((7, 7, 3), (15, 7, 3), (7, 16, 3)):
    put(cx + r + 1, 3, cz, "grindstone", face="wall", facing="east")
    put(cx - r - 1, 3, cz, "grindstone", face="wall", facing="west")
    for y in range(1, 6):
        put(cx + r + 1, y, cz - 2, "waxed_copper_block")
put(11, 6, 7, "waxed_copper_block")
put(11, 5, 7, "iron_block")
put(7, 6, 11, "waxed_copper_block")
put(7, 5, 11, "iron_block")

for x in range(6, 15):
    for z in range(W - 14, W - 9):
        if x in (6, 14) or z in (W - 14, W - 10):
            put(x, 0, z, "yellow_concrete" if (x + z) % 2 == 0 else "black_concrete")
for gx in (4, 17):
    for gz in (GZ0 + 1, GZ1 - 1):
        put(gx, 1, gz, "red_concrete")
for x in (7, 14):
    put(x, 11, mid, "iron_chain", axis="y")
    put(x, 10, mid, "lantern", hanging="true")

for x in range(S0 + 2, S1 - 1, 3):
    put(x, 1, S1 - 1, "chest", facing="north")
for (x, z) in ((S1 - 2, S0 + 9), (S1 - 2, S0 + 10), (S1 - 3, S0 + 9)):
    put(x, 1, z, "spruce_slab", type="bottom")
put(S1 - 2, 2, S0 + 9, "iron_block")
put(S1 - 2, 2, S0 + 10, "gold_block")
put(S1 - 3, 2, S0 + 9, "waxed_copper_block")
put(S1 - 2, 3, S0 + 9, "iron_block")
put(S0 + 11, 1, S1 - 3, "crafting_table")
put(S0 + 12, 1, S1 - 3, "cauldron")
put(S0 + 2, 1, S0 + 10, "decorated_pot")
for x in range(S0, S1 + 1):
    for z in (S0, S1):
        if get(x, 8, z) and "slab" in get(x, 8, z)[0]:
            put(x, 8, z, "polished_deepslate")
for z in range(S0, S1 + 1):
    for x in (S0, S1):
        if get(x, 8, z) and "slab" in get(x, 8, z)[0]:
            put(x, 8, z, "polished_deepslate")

PROPS = [
    [((0, 0, 0), "barrel", {"facing": "up"}), ((1, 0, 0), "barrel", {"facing": "up"}), ((0, 1, 0), "barrel", {"facing": "up"})],
    [((0, 0, 0), "stripped_spruce_log", {"axis": "x"}), ((1, 0, 0), "stripped_spruce_log", {"axis": "x"})],
    [((0, 0, 0), "spruce_slab", {"type": "bottom"}), ((1, 0, 0), "spruce_slab", {"type": "bottom"}),
     ((0, 1, 0), "waxed_copper_block", {}), ((1, 1, 0), "iron_block", {})],
    [((0, 0, 0), "waxed_copper_block", {}), ((0, 1, 0), "waxed_copper_block", {}), ((0, 2, 0), "campfire", {"lit": "true"})],
    [((0, 0, 0), "light_gray_concrete", {}), ((0, 1, 0), "light_gray_concrete", {}), ((0, 1, -1), "lever", {"face": "wall", "facing": "north"})],
    [((0, 0, 0), "composter", {}), ((1, 0, 0), "barrel", {"facing": "north"})],
]
placed = 0
tries = 0
while placed < 34 and tries < 4000:
    tries += 1
    x, z = rng.randrange(3, W - 4), rng.randrange(3, W - 4)
    if walk_zone(x, z) or in_stats(x, z) or max(abs(x - C), abs(z - C)) <= 11:
        continue
    prop = PROPS[rng.randrange(len(PROPS))]
    cells = [(x + dx, 1 + dy, z + dz) for (dx, dy, dz), _, _ in prop]
    if not all(free(cx, cy, cz) and column_free(cx, cz, 4) and not walk_zone(cx, cz) and not in_stats(cx, cz) for cx, cy, cz in cells):
        continue
    ring = [(cx + ax, cz + az) for cx, _, cz in cells for ax in (-1, 0, 1) for az in (-1, 0, 1)]
    if not all(column_free(a, b, 3) or (a, b) in [(c[0], c[2]) for c in cells] for a, b in ring):
        continue
    for ((dx, dy, dz), name, props) in prop:
        put(x + dx, 1 + dy, z + dz, name, **props)
    placed += 1

for side in range(4):
    for u in range(5, W - 5, 10):
        if C - 7 <= u <= C + 7:
            continue
        x, z = side_xy(side, u, 0)
        if get(x, 3, z) and "lantern" in get(x, 3, z)[0]:
            put(x, 3, z, "copper_lantern")


for (x, y, z), st in sorted(list(grid.items()), key=lambda kv: kv[0][1]):
    if grid.get((x, y, z)) != st or not st[0].endswith("_slab") or ("type", "bottom") not in st[1]:
        continue
    above = grid.get((x, y + 1, z))
    if above is None or above[0].endswith("_slab"):
        continue
    if not (st[0].startswith("minecraft:spruce") or st[0].startswith("minecraft:oak")):
        continue
    top = y + 1
    while (x, top + 1, z) in grid:
        top += 1
    del grid[(x, y, z)]
    for yy in range(y + 1, top + 1):
        grid[(x, yy - 1, z)] = grid.pop((x, yy, z))


for x in range(40, W - 2):
    for z in range(2, 20):
        edge = x in (40, W - 3) or z in (2, 19)
        if edge:
            put(x, 0, z, "yellow_concrete" if (x + z) % 2 == 0 else "black_concrete")
for cz in (5, 10, 15):
    for z in range(cz - 2, cz + 3):
        put(W - 4, 1, z, "polished_deepslate_slab", type="bottom")
    put(W - 4, 0, cz, "sea_lantern")
    for z in (cz - 2, cz + 2):
        put(W - 4, 1, z, "polished_deepslate")
        put(W - 4, 2, z, "lantern")
for x in range(45, 53):
    put(x, 1, 3, "polished_deepslate_slab", type="bottom")
for x in (45, 52):
    put(x, 1, 3, "polished_deepslate")
    put(x, 2, 3, "lantern")
put(48, 0, 3, "sea_lantern")
put(49, 0, 3, "sea_lantern")


def backboard(cells, y0, y1):
    n = len(cells)
    for i, (x, z) in enumerate(cells):
        for y in range(y0, y1 + 1):
            edge = i in (0, n - 1) or y in (y0, y1)
            put(x, y, z, "polished_deepslate" if edge else "black_concrete")
        put(x, y1 + 1, z, "yellow_concrete" if i % 2 == 0 else "black_concrete")
    for (x, z) in (cells[0], cells[-1]):
        put(x, y1 + 2, z, "lantern")


for cz in (5, 10, 15):
    backboard([(W - 3, z) for z in range(cz - 2, cz + 3)], 1, 7)
backboard([(x, 2) for x in range(45, 53)], 1, 6)


lounges = []
for radius in range(12, 27):
    for x in range(3, W - 6):
        for z in range(3, W - 6):
            if len(lounges) >= 8:
                break
            if max(abs(x + 1 - C), abs(z + 1 - C)) != radius:
                continue
            cells = [(x + i, z + j) for i in range(-1, 4) for j in range(-1, 4)]
            if any(walk_zone(a, b) or in_stats(a, b) or not (2 <= a < W - 2 and 2 <= b < W - 2) for a, b in cells):
                continue
            if not all(column_free(a, b, 4) for a, b in cells):
                continue
            if any(abs(x - lx) < 7 and abs(z - lz) < 7 for lx, lz in lounges):
                continue
            lounges.append((x, z))
for x, z in lounges:
    for i in range(3):
        put(x + i, 1, z, "dark_oak_stairs", facing="north")
        put(x + i, 1, z + 2, "dark_oak_stairs", facing="south")
    put(x + 1, 1, z + 1, "dark_oak_fence")
    put(x + 1, 2, z + 1, "dark_oak_pressure_plate")
    put(x, 1, z + 1, "potted_fern")
    put(x + 2, 1, z + 1, "potted_azalea_bush")
    put(x - 1, 1, z + 1, "dark_oak_fence")
    put(x - 1, 2, z + 1, "dark_oak_fence")
    put(x - 1, 3, z + 1, "lantern")


put(C, 1, C - 11, "lime_glazed_terracotta")


FULL_EXCLUDE = ("slab", "stairs", "lantern", "chain", "rail", "campfire", "ladder", "lightning_rod", "end_rod", "lever", "grindstone", "trapdoor", "air")
for (x, y, z), st in list(grid.items()):
    if st[0] != "minecraft:iron_bars":
        continue
    props = {}
    for name, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("west", (-1, 0)), ("east", (1, 0))):
        n = get(x + dx, y, z + dz)
        props[name] = "true" if n and not any(s in n[0] for s in FULL_EXCLUDE) else "false"
    grid[(x, y, z)] = state("iron_bars", **props)


def tag_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def named(t, name, payload):
    return bytes([t]) + tag_string(name) + payload


def compound(items):
    return b"".join(items) + b"\x00"


def int_list(values):
    return bytes([3]) + struct.pack(">i", len(values)) + b"".join(struct.pack(">i", v) for v in values)


def compound_list(payloads):
    return bytes([10]) + struct.pack(">i", len(payloads)) + b"".join(payloads)


palette = []
index = {}
for st in sorted(set(grid.values())):
    index[st] = len(palette)
    palette.append(st)

palette_payloads = []
for name, props in palette:
    items = [named(8, "id", tag_string(name))]
    if props:
        items.append(named(10, "properties", compound([named(8, k, tag_string(v)) for k, v in props])))
    palette_payloads.append(compound(items))

block_payloads = []
for (x, y, z), st in sorted(grid.items(), key=lambda kv: (kv[0][1], kv[0][0], kv[0][2])):
    block_payloads.append(compound([named(9, "pos", int_list([x, y, z])), named(3, "state", struct.pack(">i", index[st]))]))

height = max(y for (_, y, _) in grid) + 1
root = named(10, "", compound([
    named(3, "DataVersion", struct.pack(">i", DATA_VERSION)),
    named(9, "size", int_list([W, height, W])),
    named(9, "palette", compound_list(palette_payloads)),
    named(9, "blocks", compound_list(block_payloads)),
    named(9, "entities", bytes([10]) + struct.pack(">i", 0)),
]))
os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
with gzip.open(out_path, "wb") as f:
    f.write(root)
for crate, x, y, z in crate_spots:
    print("crate", crate, x, y, z)
print("hub:", out_path, len(grid), "blocks,", len(palette), "states, size", W, height, W)
