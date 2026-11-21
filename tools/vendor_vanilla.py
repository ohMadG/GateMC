"""
Copies every vanilla texture a font in the pack draws into the pack itself and points the font at the copy.

    python tools/vendor_vanilla.py <minecraft client jar>

Fonts that name minecraft: textures pick up whatever the player's own resource packs put there (animated or HD
item textures), which changes the glyph widths and breaks the drawn menus. Copies live under genscore:vanilla/.
"""
import glob
import json
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_pack import PACK, NS, write  # noqa: E402


def vendor(jar):
    copied = set()
    changed = 0
    for path in sorted(glob.glob(os.path.join(PACK, "assets", "*", "font", "*.json"))):
        with open(path, encoding="utf-8") as f:
            font = json.load(f)
        touched = False
        for p in font.get("providers", []):
            ref = p.get("file", "")
            if not ref.startswith("minecraft:"):
                continue
            rel = ref[len("minecraft:"):]
            if rel not in copied:
                data = jar.read("assets/minecraft/textures/" + rel)
                write(os.path.join(PACK, "assets", NS, "textures", "vanilla", rel), data, binary=True)
                copied.add(rel)
            p["file"] = "%s:vanilla/%s" % (NS, rel)
            touched = True
        if touched:
            write(path, json.dumps(font) + "\n")
            changed += 1
    print("vendored %d vanilla textures into %d fonts" % (len(copied), changed))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("usage: vendor_vanilla.py <minecraft client jar>")
    vendor(zipfile.ZipFile(sys.argv[1]))
