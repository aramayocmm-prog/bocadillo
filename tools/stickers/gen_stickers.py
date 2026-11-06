#!/usr/bin/env python3
"""Generates the original Bocadillo sticker catalogue (vector, recolourable).

All artwork is drawn here from geometric primitives; nothing is derived from third-party art.
Output: app/src/main/assets/stickers/catalog.json

Colour slots: 0 = main fill, 1 = secondary/details, 2 = outline. Layers reference slots by index
(users can recolour them) or use fixed "#RRGGBB" colours for details that should not change.
Compound shapes use an "outline pass" (fat stroke in slot 2) under a "fill pass", so overlapping
sub-shapes never show interior seams.
"""
import json
import math
import os
import sys

K = 0.5522847498


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def circle(cx, cy, r):
    k = r * K
    return (f"M{f(cx + r)} {f(cy)} C{f(cx + r)} {f(cy + k)} {f(cx + k)} {f(cy + r)} {f(cx)} {f(cy + r)} "
            f"C{f(cx - k)} {f(cy + r)} {f(cx - r)} {f(cy + k)} {f(cx - r)} {f(cy)} "
            f"C{f(cx - r)} {f(cy - k)} {f(cx - k)} {f(cy - r)} {f(cx)} {f(cy - r)} "
            f"C{f(cx + k)} {f(cy - r)} {f(cx + r)} {f(cy - k)} {f(cx + r)} {f(cy)} Z")


def ellipse(cx, cy, rx, ry):
    kx, ky = rx * K, ry * K
    return (f"M{f(cx + rx)} {f(cy)} C{f(cx + rx)} {f(cy + ky)} {f(cx + kx)} {f(cy + ry)} {f(cx)} {f(cy + ry)} "
            f"C{f(cx - kx)} {f(cy + ry)} {f(cx - rx)} {f(cy + ky)} {f(cx - rx)} {f(cy)} "
            f"C{f(cx - rx)} {f(cy - ky)} {f(cx - kx)} {f(cy - ry)} {f(cx)} {f(cy - ry)} "
            f"C{f(cx + kx)} {f(cy - ry)} {f(cx + rx)} {f(cy - ky)} {f(cx + rx)} {f(cy)} Z")


def rrect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    k = r * (1 - K)
    return (f"M{f(x + r)} {f(y)} L{f(x + w - r)} {f(y)} C{f(x + w - k)} {f(y)} {f(x + w)} {f(y + k)} {f(x + w)} {f(y + r)} "
            f"L{f(x + w)} {f(y + h - r)} C{f(x + w)} {f(y + h - k)} {f(x + w - k)} {f(y + h)} {f(x + w - r)} {f(y + h)} "
            f"L{f(x + r)} {f(y + h)} C{f(x + k)} {f(y + h)} {f(x)} {f(y + h - k)} {f(x)} {f(y + h - r)} "
            f"L{f(x)} {f(y + r)} C{f(x)} {f(y + k)} {f(x + k)} {f(y)} {f(x + r)} {f(y)} Z")


def poly(pts, close=True):
    s = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in pts)
    return s + (" Z" if close else "")


def star(cx, cy, n, ro, ri, rot=-90):
    pts = []
    for i in range(n * 2):
        a = math.radians(rot + i * 180 / n)
        r = ro if i % 2 == 0 else ri
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts)


def arc_pts(cx, cy, rx, ry, a0, a1, n=24):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def arc(cx, cy, rx, ry, a0, a1, n=24):
    return poly(arc_pts(cx, cy, rx, ry, a0, a1, n), close=False)


HEART = "M50 88 C22 68 6 50 6 32 C6 18 17 8 30 8 C39 8 46 13 50 21 C54 13 61 8 70 8 C83 8 94 18 94 32 C94 50 78 68 50 88 Z"


def heart_at(cx, cy, s):
    """Heart path scaled by s (1 = 88 units tall), centred at (cx, cy)."""
    import re
    out, n = [], 0
    for p in re.findall(r"[MCZ]|-?\d+\.?\d*", HEART):
        if p in "MCZ":
            out.append(p)
            n = 0
        else:
            v = float(p)
            out.append(f(cx + (v - 50) * s) if n % 2 == 0 else f(cy + (v - 48) * s))
            n += 1
    return " ".join(out).replace("M ", "M").replace("C ", "C")


def L(d, fill=None, stroke=None, sw=0, evenodd=False):
    layer = {"d": d}
    if fill is not None:
        layer["fill"] = fill
    if stroke is not None:
        layer["stroke"] = stroke
        layer["sw"] = sw
    if evenodd:
        layer["evenodd"] = "true"
    return layer


def compound(d, sw=6):
    """Outline pass + fill pass for shapes made of overlapping parts."""
    return [L(d, fill=2, stroke=2, sw=sw), L(d, fill=0)]


DARK = "#2B2B2B"
YELLOW = "#FFD23F"
STICKERS = {}
CATS = []


def add(cat, sid, slots, layers, w=100, h=100):
    STICKERS.setdefault(cat, []).append({"id": sid, "w": w, "h": h, "slots": slots, "layers": layers})


# ----------------------------------------------------------------------------- faces
FACE = [YELLOW, DARK, DARK]


def head():
    return L(circle(50, 50, 44), fill=0, stroke=2, sw=4)


def eyes_dots(y=40, rx=5, ry=7):
    return L(ellipse(36, y, rx, ry) + " " + ellipse(64, y, rx, ry), fill=1)


add("faces", "face_smile", FACE, [head(), eyes_dots(), L(arc(50, 52, 22, 18, 20, 160), stroke=1, sw=5)])
add("faces", "face_grin", FACE, [head(), eyes_dots(38),
    L("M24 56 L76 56 C76 72 64 82 50 82 C36 82 24 72 24 56 Z", fill=1),
    L("M30 57 L70 57 L68 64 L32 64 Z", fill="#FFFFFF"),
    L("M38 75 C44 70 56 70 62 75 C58 79 54 81 50 81 C46 81 42 79 38 75 Z", fill="#FF6B81")])
add("faces", "face_wink", FACE, [head(), L(ellipse(36, 40, 5, 7), fill=1),
    L(arc(64, 44, 8, 6, 200, 340), stroke=1, sw=4.5), L(arc(50, 54, 20, 16, 20, 160), stroke=1, sw=5)])
add("faces", "face_surprised", FACE, [head(), L(circle(36, 38, 6) + " " + circle(64, 38, 6), fill=1),
    L(arc(36, 26, 8, 5, 200, 340), stroke=1, sw=3.5), L(arc(64, 26, 8, 5, 200, 340), stroke=1, sw=3.5),
    L(ellipse(50, 66, 9, 12), fill=1)])
add("faces", "face_sad", FACE, [head(), eyes_dots(40), L(arc(50, 76, 18, 12, 200, 340), stroke=1, sw=5),
    L("M66 50 C66 50 72 58 72 62 C72 66 69 68 66 68 C63 68 60 66 60 62 C60 58 66 50 66 50 Z", fill="#4FC3F7")])
add("faces", "face_angry", ["#FF7043", DARK, DARK], [head(), eyes_dots(44, 5, 6),
    L("M24 30 L44 38", stroke=1, sw=5), L("M76 30 L56 38", stroke=1, sw=5),
    L(arc(50, 80, 16, 10, 200, 340), stroke=1, sw=5)])
add("faces", "face_cool", FACE, [head(),
    L("M18 34 L82 34 L80 42 C78 52 70 54 62 52 C56 50 54 44 50 42 C46 44 44 50 38 52 C30 54 22 52 20 42 Z", fill=1),
    L("M26 38 L34 38", stroke="#FFFFFF", sw=2.5), L(arc(50, 58, 18, 14, 20, 160), stroke=1, sw=5)])
add("faces", "face_love", FACE, [head(), L(heart_at(35, 40, 0.16) + " " + heart_at(65, 40, 0.16), fill="#E53935"),
    L(arc(50, 54, 20, 18, 20, 160), stroke=1, sw=5)])
add("faces", "face_laugh", FACE, [head(),
    L(arc(36, 42, 8, 7, 200, 340), stroke=1, sw=5), L(arc(64, 42, 8, 7, 200, 340), stroke=1, sw=5),
    L("M26 54 L74 54 C74 72 62 82 50 82 C38 82 26 72 26 54 Z", fill=1),
    L("M14 46 C10 52 10 58 14 60 C18 58 18 52 14 46 Z M86 46 C82 52 82 58 86 60 C90 58 90 52 86 46 Z", fill="#4FC3F7")])
add("faces", "face_sleepy", FACE, [head(),
    L("M28 42 L44 42 M56 42 L72 42", stroke=1, sw=5), L(ellipse(50, 66, 6, 5), fill=1),
    L("M70 14 L80 14 L70 24 L80 24", stroke="#1E88E5", sw=3.5)])
add("faces", "face_tongue", FACE, [head(), eyes_dots(38),
    L(arc(50, 54, 22, 14, 15, 165), stroke=1, sw=5),
    L("M42 66 L58 66 L58 76 C58 82 54 86 50 86 C46 86 42 82 42 76 Z", fill="#FF6B81", stroke=1, sw=3)])

# ----------------------------------------------------------------------------- accessories
add("accessories", "sunglasses", [DARK, "#555555", "#000000"], [
    L("M6 20 L94 20 L94 26 L90 26 C88 42 80 48 68 48 C58 48 54 40 52 30 L48 30 C46 40 42 48 32 48 C20 48 12 42 10 26 L6 26 Z", fill=0, stroke=2, sw=2),
    L("M16 28 L24 28 M62 28 L70 28", stroke="#FFFFFF", sw=3)], w=100, h=60)
add("accessories", "round_glasses", [DARK, "#BBDEFB", DARK], [
    L(circle(28, 30, 18) + " " + circle(72, 30, 18), fill="#66BBDEFB", stroke=0, sw=5),
    L("M46 28 C48 24 52 24 54 28 M10 26 L2 22 M90 26 L98 22", stroke=0, sw=4)], w=100, h=60)
add("accessories", "mustache", ["#4E342E", "#4E342E", "#2B1B17"], [
    L("M50 22 C44 12 30 10 22 18 C16 24 8 22 6 16 C6 30 16 38 28 36 C38 34 46 30 50 28 C54 30 62 34 72 36 C84 38 94 30 94 16 C92 22 84 24 78 18 C70 10 56 12 50 22 Z", fill=0, stroke=2, sw=2)],
    w=100, h=44)
add("accessories", "bow_tie", ["#E53935", "#B71C1C", DARK], [
    L("M50 30 L12 8 C6 16 6 44 12 52 Z M50 30 L88 8 C94 16 94 44 88 52 Z", fill=0, stroke=2, sw=3),
    L(rrect(42, 20, 16, 20, 5), fill=1, stroke=2, sw=3)], w=100, h=60)
stripes = []
for (y1, y2) in [(30, 42), (56, 68), (82, 92)]:
    def xr(y):
        t = (y - 8) / 84.0
        return 50 - 30 * t, 50 + 30 * t
    a, b = xr(y1)
    c, d = xr(y2)
    stripes.append(poly([(a, y1), (b, y1), (d, y2), (c, y2)]))
add("accessories", "party_hat", ["#AB47BC", YELLOW, DARK], [
    L(poly([(50, 8), (80, 92), (20, 92)]), fill=0, stroke=2, sw=4), L(" ".join(stripes), fill=1),
    L(poly([(50, 8), (80, 92), (20, 92)]), stroke=2, sw=4), L(circle(50, 8, 7), fill="#FF7043", stroke=2, sw=3)])
add("accessories", "crown", ["#FFC107", "#E53935", DARK], [
    L(poly([(10, 30), (30, 52), (50, 18), (70, 52), (90, 30), (84, 78), (16, 78)]), fill=0, stroke=2, sw=4),
    L(rrect(16, 70, 68, 10, 3), fill="#FFA000", stroke=2, sw=3),
    L(circle(30, 62, 5) + " " + circle(70, 62, 5), fill="#1E88E5"), L(circle(50, 58, 6), fill=1),
    L(circle(10, 30, 4) + " " + circle(50, 18, 4) + " " + circle(90, 30, 4), fill=0, stroke=2, sw=2.5)], w=100, h=90)
add("accessories", "top_hat", [DARK, "#E53935", "#000000"], [
    L(ellipse(50, 80, 46, 10), fill=0, stroke=2, sw=3), L(rrect(24, 12, 52, 68, 4), fill=0, stroke=2, sw=3),
    L("M24 62 L76 62 L76 72 L24 72 Z", fill=1)], w=100, h=92)
add("accessories", "hair_bow", ["#EC407A", "#AD1457", DARK], [
    L("M50 34 C40 14 14 6 10 20 C6 34 8 50 14 58 C24 58 40 44 50 34 Z M50 34 C60 14 86 6 90 20 C94 34 92 50 86 58 C76 58 60 44 50 34 Z", fill=0, stroke=2, sw=3),
    L(ellipse(50, 34, 9, 11), fill=1, stroke=2, sw=3)], w=100, h=66)
add("accessories", "kiss_lips", ["#E53935", "#B71C1C", "#7F0000"], [
    L("M6 30 C18 12 36 8 50 18 C64 8 82 12 94 30 C80 52 62 60 50 60 C38 60 20 52 6 30 Z", fill=0, stroke=2, sw=3),
    L("M10 30 C26 34 40 34 50 30 C60 34 74 34 90 30", stroke=1, sw=3)], w=100, h=66)
add("accessories", "halo", ["#FFE082", "#FFC107", "#F9A825"], [
    L(ellipse(50, 25, 44, 16) + " " + ellipse(50, 25, 32, 9), fill=0, stroke=2, sw=2, evenodd=True)], w=100, h=50)
add("accessories", "devil_horns", ["#E53935", "#B71C1C", "#7F0000"], [
    L("M10 60 C6 40 10 20 22 6 C22 22 28 36 38 46 C32 54 22 60 10 60 Z M90 60 C94 40 90 20 78 6 C78 22 72 36 62 46 C68 54 78 60 90 60 Z",
      fill=0, stroke=2, sw=3)], w=100, h=66)

# ----------------------------------------------------------------------------- symbols
add("symbols", "heart", ["#E53935", "#FFFFFF", "#7F0000"], [L(HEART, fill=0, stroke=2, sw=4),
    L("M24 26 C26 20 32 18 36 20", stroke=1, sw=5)])
add("symbols", "star", [YELLOW, "#FFFFFF", "#E65100"], [L(star(50, 54, 5, 46, 20), fill=0, stroke=2, sw=4)])
add("symbols", "sparkle", ["#FFFFFF", YELLOW, "#90A4AE"], [
    L("M50 4 C54 34 66 46 96 50 C66 54 54 66 50 96 C46 66 34 54 4 50 C34 46 46 34 50 4 Z", fill=0, stroke=2, sw=3),
    L("M82 10 C83 18 86 21 94 22 C86 23 83 26 82 34 C81 26 78 23 70 22 C78 21 81 18 82 10 Z", fill=1)])
add("symbols", "lightning", [YELLOW, "#FFFFFF", "#E65100"], [L(poly([(58, 4), (20, 56), (46, 56), (36, 96), (80, 38), (54, 38), (66, 4)]), fill=0, stroke=2, sw=4)])
add("symbols", "exclamation", ["#E53935", "#FFFFFF", "#7F0000"], [
    L("M38 10 L62 10 L56 66 L44 66 Z", fill=0, stroke=2, sw=4), L(circle(50, 84, 9), fill=0, stroke=2, sw=4)])
add("symbols", "question", ["#1E88E5", "#FFFFFF", "#0D47A1"], [
    L("M28 34 C28 18 40 8 52 8 C66 8 76 18 76 30 C76 42 68 48 60 52 C54 55 52 60 52 66", stroke=2, sw=20),
    L("M28 34 C28 18 40 8 52 8 C66 8 76 18 76 30 C76 42 68 48 60 52 C54 55 52 60 52 66", stroke=0, sw=12),
    L(circle(52, 86, 9), fill=0, stroke=2, sw=4)])
add("symbols", "check", ["#43A047", "#FFFFFF", "#1B5E20"], [
    L("M12 52 L38 78 L88 22", stroke=2, sw=22), L("M12 52 L38 78 L88 22", stroke=0, sw=14)])
add("symbols", "cross", ["#E53935", "#FFFFFF", "#7F0000"], [
    L("M18 18 L82 82 M82 18 L18 82", stroke=2, sw=22), L("M18 18 L82 82 M82 18 L18 82", stroke=0, sw=14)])
add("symbols", "arrow", ["#FF7043", "#FFFFFF", DARK], [L(poly([(6, 38), (56, 38), (56, 16), (94, 50), (56, 84), (56, 62), (6, 62)]), fill=0, stroke=2, sw=4)])
add("symbols", "curved_arrow", ["#1E88E5", "#FFFFFF", "#0D47A1"], [
    L("M14 86 C14 46 40 28 66 28", stroke=2, sw=20), L("M14 86 C14 46 40 28 66 28", stroke=0, sw=12),
    L(poly([(60, 8), (94, 28), (60, 48)]), fill=0, stroke=2, sw=4)])
add("symbols", "music_note", [DARK, "#FFFFFF", "#000000"], [
    L(ellipse(30, 78, 16, 12) + " " + ellipse(76, 68, 16, 12), fill=0),
    L("M42 78 L42 18 L88 8 L88 68", stroke=0, sw=7), L("M42 30 L88 20", stroke=0, sw=10)])
add("symbols", "plus", ["#43A047", "#FFFFFF", "#1B5E20"], [L(poly([(38, 8), (62, 8), (62, 38), (92, 38), (92, 62), (62, 62), (62, 92), (38, 92), (38, 62), (8, 62), (8, 38), (38, 38)]), fill=0, stroke=2, sw=4)])
add("symbols", "fire", ["#FF7043", YELLOW, "#BF360C"], [
    L("M50 96 C26 96 12 80 12 60 C12 40 28 30 34 8 C42 22 46 30 48 40 C54 30 58 24 58 14 C76 30 88 46 88 62 C88 82 74 96 50 96 Z", fill=0, stroke=2, sw=4),
    L("M50 92 C38 92 30 84 30 72 C30 60 40 54 44 42 C50 52 54 58 56 64 C60 60 62 56 62 50 C70 58 72 66 72 74 C72 86 62 92 50 92 Z", fill=1)])
add("symbols", "drop", ["#29B6F6", "#FFFFFF", "#01579B"], [
    L("M50 6 C50 6 82 46 82 64 C82 82 68 94 50 94 C32 94 18 82 18 64 C18 46 50 6 50 6 Z", fill=0, stroke=2, sw=4),
    L("M32 64 C32 74 38 80 44 82", stroke=1, sw=5)])
add("symbols", "zzz", ["#5C6BC0", "#FFFFFF", "#1A237E"], [
    L("M10 40 L40 40 L10 70 L40 70", stroke=2, sw=12), L("M10 40 L40 40 L10 70 L40 70", stroke=0, sw=6),
    L("M52 22 L72 22 L52 44 L72 44", stroke=2, sw=10), L("M52 22 L72 22 L52 44 L72 44", stroke=0, sw=5),
    L("M80 6 L94 6 L80 20 L94 20", stroke=2, sw=8), L("M80 6 L94 6 L80 20 L94 20", stroke=0, sw=4)], w=100, h=76)

# ----------------------------------------------------------------------------- nature
rays = []
for i in range(12):
    a = math.radians(i * 30)
    a1, a2 = a - math.radians(7), a + math.radians(7)
    rays.append(poly([(50 + 30 * math.cos(a1), 50 + 30 * math.sin(a1)), (50 + 48 * math.cos(a), 50 + 48 * math.sin(a)), (50 + 30 * math.cos(a2), 50 + 30 * math.sin(a2))]))
add("nature", "sun", ["#FFC107", "#FF9800", "#E65100"], [L(" ".join(rays), fill=1, stroke=2, sw=3), L(circle(50, 50, 28), fill=0, stroke=2, sw=4)])
CLOUD = " ".join([circle(30, 60, 20), circle(50, 46, 26), circle(72, 58, 20), rrect(14, 56, 74, 26, 13)])
add("nature", "cloud", ["#FFFFFF", "#ECEFF1", "#78909C"], compound(CLOUD, 7))
add("nature", "rain_cloud", ["#CFD8DC", "#29B6F6", "#546E7A"], compound(CLOUD.replace("82", "70").replace(" 60 20", " 52 18"), 7) + [
    L("M26 82 L22 94 M46 82 L42 96 M66 82 L62 94", stroke=1, sw=5)])
add("nature", "moon", ["#FFE082", "#FFFFFF", "#F9A825"], [
    L("M62 8 C36 10 16 30 16 54 C16 78 36 94 60 94 C72 94 82 90 88 84 C64 84 44 68 44 46 C44 30 52 16 62 8 Z", fill=0, stroke=2, sw=4)])
snow = []
for i in range(6):
    a = math.radians(i * 60 - 90)
    x2, y2 = 50 + 42 * math.cos(a), 50 + 42 * math.sin(a)
    snow.append(f"M50 50 L{f(x2)} {f(y2)}")
    for t, l in [(0.55, 12), (0.8, 8)]:
        bx, by = 50 + 42 * t * math.cos(a), 50 + 42 * t * math.sin(a)
        for s in (-1, 1):
            b = a + s * math.radians(40)
            snow.append(f"M{f(bx)} {f(by)} L{f(bx + l * math.cos(b))} {f(by + l * math.sin(b))}")
add("nature", "snowflake", ["#4FC3F7", "#FFFFFF", "#01579B"], [L(" ".join(snow), stroke=2, sw=9), L(" ".join(snow), stroke=0, sw=4.5)])
petals = " ".join(circle(50 + 24 * math.cos(math.radians(a)), 50 + 24 * math.sin(math.radians(a)), 17) for a in range(0, 360, 60))
add("nature", "flower", ["#F48FB1", YELLOW, "#AD1457"], compound(petals, 6) + [L(circle(50, 50, 15), fill=1, stroke=2, sw=3)])
add("nature", "leaf", ["#66BB6A", "#2E7D32", "#1B5E20"], [
    L("M14 86 C10 46 34 14 88 10 C92 62 62 90 14 86 Z", fill=0, stroke=2, sw=4), L("M14 86 C38 62 56 42 80 18", stroke=1, sw=4)])
bands = []
for r, c in [(44, "#E53935"), (38, "#FF9800"), (32, "#FFEB3B"), (26, "#43A047"), (20, "#1E88E5"), (14, "#8E24AA")]:
    bands.append(L(arc(50, 56, r, r, 180, 360, 36), stroke=c, sw=6.4))
add("nature", "rainbow", ["#FFFFFF", "#FFFFFF", "#FFFFFF"], bands + compound(circle(12, 58, 10) + " " + circle(22, 60, 9) + " " + circle(80, 60, 9) + " " + circle(90, 58, 10), 4), w=100, h=70)
add("nature", "tree", ["#43A047", "#6D4C41", "#1B5E20"], [L(rrect(42, 62, 16, 32, 3), fill=1, stroke=2, sw=3)] +
    compound(circle(50, 34, 26) + " " + circle(30, 50, 18) + " " + circle(70, 50, 18), 6))

# ----------------------------------------------------------------------------- party
add("party", "balloon", ["#E53935", "#FFFFFF", "#7F0000"], [
    L("M50 66 C48 76 56 84 50 98", stroke="#777777", sw=2.5),
    L(ellipse(50, 34, 28, 32), fill=0, stroke=2, sw=3), L(poly([(46, 70), (54, 70), (50, 64)]), fill=0, stroke=2, sw=2.5),
    L("M34 22 C36 16 40 12 46 10", stroke=1, sw=4)], w=100, h=100)
add("party", "gift", ["#42A5F5", "#FFEB3B", "#0D47A1"], [
    L(rrect(12, 40, 76, 54, 4), fill=0, stroke=2, sw=4), L(rrect(6, 28, 88, 16, 4), fill=0, stroke=2, sw=4),
    L("M44 28 L56 28 L56 94 L44 94 Z", fill=1),
    L("M50 28 C40 10 22 10 24 20 C26 28 40 28 50 28 C60 28 74 28 76 20 C78 10 60 10 50 28 Z", fill=1, stroke=2, sw=3)])
conf = []
import random
rng = random.Random(7)
cols = ["#E53935", "#1E88E5", "#43A047", "#FFC107", "#AB47BC", "#FF7043"]
conf_layers = []
for i in range(22):
    x, y = rng.uniform(8, 92), rng.uniform(8, 92)
    a = rng.uniform(0, math.pi)
    w2, h2 = rng.uniform(3, 6), rng.uniform(1.5, 2.5)
    pts = [(x + dx * math.cos(a) - dy * math.sin(a), y + dx * math.sin(a) + dy * math.cos(a)) for dx, dy in [(-w2, -h2), (w2, -h2), (w2, h2), (-w2, h2)]]
    conf_layers.append(L(poly(pts), fill=cols[i % len(cols)]))
add("party", "confetti", ["#FFFFFF", "#FFFFFF", "#FFFFFF"], conf_layers)
add("party", "cake", ["#F8BBD0", "#FFFFFF", "#6D4C41"], [
    L(rrect(14, 50, 72, 42, 6), fill=0, stroke=2, sw=4),
    L("M14 60 C22 70 28 70 34 60 C40 70 46 70 52 60 C58 70 64 70 70 60 C76 70 82 70 86 60 L86 54 C86 52 84 50 80 50 L20 50 C16 50 14 52 14 54 Z", fill=1, stroke=2, sw=3),
    L(rrect(46, 26, 8, 24, 2), fill="#64B5F6", stroke=2, sw=2.5),
    L("M50 8 C46 14 44 18 50 24 C56 18 54 14 50 8 Z", fill="#FFA726")])
add("party", "trophy", ["#FFC107", "#FFE082", "#E65100"], [
    L("M26 10 L74 10 L72 40 C70 56 60 62 50 62 C40 62 30 56 28 40 Z", fill=0, stroke=2, sw=4),
    L("M28 18 C12 18 12 40 30 42 M72 18 C88 18 88 40 70 42", stroke=2, sw=5),
    L(rrect(44, 60, 12, 16, 2), fill=0, stroke=2, sw=3), L(rrect(28, 76, 44, 14, 3), fill=0, stroke=2, sw=4),
    L(star(50, 34, 5, 11, 5), fill=1)])

# ----------------------------------------------------------------------------- shapes & badges
add("shapes", "circle", ["#1E88E5", "#FFFFFF", "#0D47A1"], [L(circle(50, 50, 44), fill=0, stroke=2, sw=4)])
add("shapes", "rounded_square", ["#43A047", "#FFFFFF", "#1B5E20"], [L(rrect(8, 8, 84, 84, 16), fill=0, stroke=2, sw=4)])
add("shapes", "triangle", ["#FFC107", "#FFFFFF", "#E65100"], [L(poly([(50, 8), (94, 88), (6, 88)]), fill=0, stroke=2, sw=4)])
add("shapes", "burst_badge", ["#E53935", YELLOW, "#7F0000"], [L(star(50, 50, 18, 46, 36, -90), fill=0, stroke=2, sw=3),
    L(circle(50, 50, 30), fill=1, stroke=2, sw=2)])
add("shapes", "ribbon_banner", ["#E53935", "#B71C1C", "#7F0000"], [
    L(poly([(2, 30), (18, 30), (18, 54), (2, 54), (9, 42)]) + " " + poly([(98, 30), (82, 30), (82, 54), (98, 54), (91, 42)]), fill=1, stroke=2, sw=3),
    L(rrect(12, 20, 76, 28, 3), fill=0, stroke=2, sw=3)], w=100, h=64)
add("shapes", "frame", ["#FFFFFF", "#FFFFFF", "#9E9E9E"], [L(rrect(4, 4, 92, 92, 6) + " " + rrect(16, 16, 68, 68, 2), fill=0, stroke=2, sw=2, evenodd=True)])
add("shapes", "location_pin", ["#E53935", "#FFFFFF", "#7F0000"], [
    L("M50 96 C50 96 16 58 16 36 C16 16 32 4 50 4 C68 4 84 16 84 36 C84 58 50 96 50 96 Z", fill=0, stroke=2, sw=4),
    L(circle(50, 36, 13), fill=1)])
add("shapes", "speech_tag", ["#FFFFFF", DARK, DARK], [
    L("M14 10 L86 10 C92 10 96 14 96 20 L96 56 C96 62 92 66 86 66 L44 66 L22 88 L26 66 L14 66 C8 66 4 62 4 56 L4 20 C4 14 8 10 14 10 Z", fill=0, stroke=2, sw=4),
    L(circle(30, 38, 5) + " " + circle(50, 38, 5) + " " + circle(70, 38, 5), fill=1)], w=100, h=92)

CAT_NAMES = {
    "faces": {"en": "Faces", "es": "Caras"},
    "accessories": {"en": "Accessories", "es": "Accesorios"},
    "symbols": {"en": "Symbols", "es": "Símbolos"},
    "nature": {"en": "Nature", "es": "Naturaleza"},
    "party": {"en": "Party", "es": "Fiesta"},
    "shapes": {"en": "Shapes & badges", "es": "Formas e insignias"},
}

catalog = {"version": 1, "categories": [{"id": c, "names": CAT_NAMES[c], "stickers": STICKERS[c]} for c in CAT_NAMES]}

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "stickers", "catalog.json")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w") as fh:
        json.dump(catalog, fh, separators=(",", ":"))
    print(sum(len(c["stickers"]) for c in catalog["categories"]), "stickers ->", out)
