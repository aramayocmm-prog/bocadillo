#!/usr/bin/env python3
"""Builds the Noto Emoji sticker pack bundled in the app.

Sources (pinned, see fetch_noto.sh):
  - googlefonts/noto-emoji  2D/svg            Apache License 2.0 (2D/svg/LICENSE)
  - googlefonts/noto-emoji  third_party/region-flags/svg   public domain / exempt (its LICENSE)
  - googlefonts/emoji-metadata  emoji_18_0_ordering.json   Apache License 2.0 (groups, order, shortcodes)

Output (app/src/main/assets/noto/):
  index.json        groups (en/es names) → items {id, k (search keywords)}
  svg/<id>.svg      minified SVG, one per emoji (no skin-tone variants)
  LICENSE-APACHE-2.0.txt, LICENSE-region-flags.txt, NOTICE.txt

Usage: build_noto_pack.py <noto-emoji checkout> <emoji-metadata checkout> <out dir>
"""
import json
import os
import re
import shutil
import sys
import xml.dom.minidom

GROUP_NAMES = {
    "Smileys and emotions": ("smileys", "Smileys", "Caritas"),
    "People": ("people", "People", "Personas"),
    "Animals and nature": ("animals", "Animals & nature", "Animales y naturaleza"),
    "Food and drink": ("food", "Food & drink", "Comida y bebida"),
    "Travel and places": ("travel", "Travel & places", "Viajes y lugares"),
    "Activities and events": ("activities", "Activities", "Actividades"),
    "Objects": ("objects", "Objects", "Objetos"),
    "Symbols": ("symbols", "Symbols", "Símbolos"),
    "Flags": ("flags", "Flags", "Banderas"),
}

NUM = re.compile(r"-?\d*\.\d{3,}")


def minify(svg: str) -> str:
    svg = re.sub(r"<\?xml[^>]*\?>", "", svg)
    svg = re.sub(r"<!--.*?-->", "", svg, flags=re.S)
    svg = re.sub(r"<metadata.*?</metadata>", "", svg, flags=re.S)
    svg = re.sub(r"<sodipodi:namedview[^>]*/>", "", svg)
    svg = re.sub(r"<sodipodi:namedview[^>]*>.*?</sodipodi:namedview>", "", svg, flags=re.S)
    svg = re.sub(r'\s(inkscape|sodipodi):[\w-]+="[^"]*"', "", svg)
    svg = re.sub(r"<title>.*?</title>", "", svg, flags=re.S)
    # 2 decimals are plenty for 128-unit artwork
    svg = NUM.sub(lambda m: ("%.2f" % float(m.group(0))).rstrip("0").rstrip("."), svg)
    svg = re.sub(r">\s+<", "><", svg)
    svg = re.sub(r"\s{2,}", " ", svg)
    return svg.strip()


def emoji_id(base):
    return "_".join("%04x" % c for c in base if c != 0xFE0F)


def flag_letters(base):
    """Region flag file name: 'AR' for regional-indicator pairs, 'GB-ENG' for subdivision tag sequences."""
    if len(base) == 2 and all(0x1F1E6 <= c <= 0x1F1FF for c in base):
        return "".join(chr(ord("A") + c - 0x1F1E6) for c in base)
    if base and base[0] == 0x1F3F4 and base[-1] == 0xE007F:
        tag = "".join(chr(c - 0xE0000) for c in base[1:-1]).upper()  # e.g. "GBENG"
        return tag[:2] + "-" + tag[2:]
    return None


def main(noto, meta, out):
    svg_dir = os.path.join(noto, "2D", "svg")
    flags_dir = os.path.join(noto, "third_party", "region-flags", "svg")
    ordering = json.load(open(os.path.join(meta, "emoji_18_0_ordering.json"), encoding="utf-8"))
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "svg"))
    groups, total_bytes, skipped, fallbacks = [], 0, [], []
    for g in ordering:
        gid, en, es = GROUP_NAMES[g["group"]]
        items = []
        for e in g["emoji"]:
            eid = emoji_id(e["base"])
            src = os.path.join(svg_dir, "emoji_u%s.svg" % eid)
            if not os.path.exists(src):
                letters = flag_letters(e["base"])
                src = os.path.join(flags_dir, "%s.svg" % letters) if letters else None
            if not src or not os.path.exists(src):
                skipped.append(eid)
                continue
            original = open(src, encoding="utf-8").read()
            data = minify(original)
            try:
                xml.dom.minidom.parseString(data)  # never ship a broken file
            except Exception:
                fallbacks.append(eid)
                data = original
            with open(os.path.join(out, "svg", eid + ".svg"), "w", encoding="utf-8") as fh:
                fh.write(data)
            total_bytes += len(data.encode())
            keys = " ".join(s.strip(":").replace("_", " ") for s in e.get("shortcodes", []))
            items.append({"id": eid, "k": keys})
        groups.append({"id": gid, "names": {"en": en, "es": es}, "items": items})
    with open(os.path.join(out, "index.json"), "w", encoding="utf-8") as fh:
        json.dump({"version": 1, "source": "Noto Emoji (googlefonts/noto-emoji v2026-09-24-unicode18_0)", "groups": groups}, fh, ensure_ascii=False, separators=(",", ":"))
    shutil.copy(os.path.join(svg_dir, "LICENSE"), os.path.join(out, "LICENSE-APACHE-2.0.txt"))
    shutil.copy(os.path.join(noto, "third_party", "region-flags", "LICENSE"), os.path.join(out, "LICENSE-region-flags.txt"))
    with open(os.path.join(out, "NOTICE.txt"), "w", encoding="utf-8") as fh:
        fh.write(
            "Emoji stickers: Noto Emoji, Copyright 2013 Google LLC.\n"
            "Licensed under the Apache License, Version 2.0 (LICENSE-APACHE-2.0.txt).\n"
            "https://github.com/googlefonts/noto-emoji\n"
            "Changes: SVG files were minified (whitespace/metadata removed, coordinates rounded to 2 decimals).\n\n"
            "Country flags: region-flags (via noto-emoji third_party), public domain or exempt from copyright\n"
            "(LICENSE-region-flags.txt).\n\n"
            "Emoji grouping/shortcodes: googlefonts/emoji-metadata, Apache License 2.0.\n"
        )
    n = sum(len(g["items"]) for g in groups)
    print(f"{n} stickers, {total_bytes / 1048576:.1f} MB svg, skipped {len(skipped)}: {skipped[:10]}, unminified {len(fallbacks)}: {fallbacks[:10]}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
