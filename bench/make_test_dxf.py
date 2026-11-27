"""Generates a heavy-but-small test drawing similar to an apartment-building power layout:
furniture and people blocks (splines, arcs, solid hatches) inserted many times, walls, doors,
electrical symbols, circuits and labels. Usage: python make_test_dxf.py out.dxf [apartments]"""
import math
import random
import sys

import ezdxf

random.seed(1)
out = sys.argv[1] if len(sys.argv) > 1 else "test.dxf"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 120

doc = ezdxf.new("R2018", setup=True)
msp = doc.modelspace()
for name, color in [("A-WALL", 8), ("A-FURN", 9), ("A-DOOR", 252), ("E-POWR", 3), ("E-LITE", 2),
                    ("E-SYMB", 4), ("E-TEXT", 2), ("A-TEXT", 7), ("A-HATCH", 253)]:
    doc.layers.add(name, color=color)


def person(blk):
    """Top-view person: many spline curves plus a solid-filled head."""
    for k in range(6):
        pts = [(math.cos(a) * (0.25 + 0.05 * k), math.sin(a) * 0.18 + 0.02 * k)
               for a in [i * math.pi / 6 for i in range(13)]]
        blk.add_spline(pts, dxfattribs={"layer": "0"})
    for side in (-1, 1):
        blk.add_spline([(0.2 * side, 0), (0.35 * side, 0.25), (0.3 * side, 0.5), (0.2 * side, 0.65)])
        blk.add_spline([(0.1 * side, -0.1), (0.15 * side, -0.4), (0.12 * side, -0.7), (0.08 * side, -0.9)])
    h = blk.add_hatch(color=256)
    h.paths.add_polyline_path([(math.cos(a) * 0.1, 0.1 + math.sin(a) * 0.12)
                               for a in [i * math.pi / 16 for i in range(32)]], is_closed=True)


def sofa(blk, w):
    blk.add_lwpolyline([(0, 0, 0.3), (w, 0, 0.3), (w, 0.9, 0), (0, 0.9, 0)], format="xyb", close=True)
    for i in range(int(w / 0.6)):
        blk.add_lwpolyline([(0.1 + i * 0.6, 0.15, 0.2), (0.65 + i * 0.6, 0.15, 0.2),
                            (0.65 + i * 0.6, 0.75, 0.2), (0.1 + i * 0.6, 0.75, 0.2)], format="xyb", close=True)
    blk.add_blockref("PERSON", (w / 2, 0.45))


def bed(blk):
    blk.add_lwpolyline([(0, 0), (1.8, 0), (1.8, 2.0), (0, 2.0)], close=True)
    for x in (0.1, 0.95):
        blk.add_spline([(x, 1.55), (x + 0.2, 1.9), (x + 0.55, 1.88), (x + 0.75, 1.55), (x + 0.4, 1.5), (x, 1.55)])
    for i in range(12):
        blk.add_spline([(0.05, 1.3 - i * 0.1), (0.6, 1.35 - i * 0.1), (1.2, 1.25 - i * 0.1), (1.75, 1.3 - i * 0.1)])


def socket(blk):
    blk.add_circle((0, 0), 0.12)
    blk.add_line((-0.12, 0), (0.12, 0))
    blk.add_line((0, 0.12), (0, 0.3))
    h = blk.add_hatch(color=0)
    h.paths.add_edge_path().add_arc((0, 0), 0.12, 0, 180)
    blk.add_text("13A", dxfattribs={"height": 0.08, "insert": (0.15, 0.15)})


def light(blk):
    blk.add_circle((0, 0), 0.2)
    blk.add_line((-0.14, -0.14), (0.14, 0.14))
    blk.add_line((-0.14, 0.14), (0.14, -0.14))


def stove(blk):
    blk.add_lwpolyline([(0, 0), (0.6, 0), (0.6, 0.6), (0, 0.6)], close=True)
    for cx, cy in ((0.15, 0.15), (0.45, 0.15), (0.15, 0.45), (0.45, 0.45)):
        for r in (0.03, 0.08, 0.1):
            blk.add_circle((cx, cy), r)


person(doc.blocks.new("PERSON"))
sofa(doc.blocks.new("SOFA3"), 2.4)
bed(doc.blocks.new("BED"))
socket(doc.blocks.new("SOCKET"))
light(doc.blocks.new("LIGHT"))
stove(doc.blocks.new("STOVE"))

AW, AH = 12.0, 16.0
cols = max(1, int(math.sqrt(N)))
for a in range(N):
    ox, oy = (a % cols) * (AW + 2), (a // cols) * (AH + 2)
    msp.add_lwpolyline([(ox, oy), (ox + AW, oy), (ox + AW, oy + AH), (ox, oy + AH)], close=True,
                       dxfattribs={"layer": "A-WALL", "const_width": 0.2})
    for y in (5, 9, 12):
        msp.add_line((ox, oy + y), (ox + AW * 0.6, oy + y), dxfattribs={"layer": "A-WALL"})
    msp.add_line((ox + AW * 0.6, oy), (ox + AW * 0.6, oy + AH), dxfattribs={"layer": "A-WALL"})
    h = msp.add_hatch(dxfattribs={"layer": "A-HATCH"})
    h.set_pattern_fill("ANSI31", scale=0.05)
    h.paths.add_polyline_path([(ox, oy), (ox + 0.2, oy), (ox + 0.2, oy + AH), (ox, oy + AH)], is_closed=True)
    for d in range(5):
        x, y = ox + 1 + d * 1.5, oy + 5
        msp.add_arc((x, y), 0.9, 0, 90, dxfattribs={"layer": "A-DOOR"})
        msp.add_line((x, y), (x + 0.9, y), dxfattribs={"layer": "A-DOOR"})
    msp.add_blockref("SOFA3", (ox + 1, oy + 13), dxfattribs={"layer": "A-FURN"})
    msp.add_blockref("SOFA3", (ox + 1, oy + 15.5), dxfattribs={"layer": "A-FURN", "rotation": 180})
    msp.add_blockref("PERSON", (ox + 4, oy + 14), dxfattribs={"layer": "A-FURN", "rotation": 30})
    msp.add_blockref("BED", (ox + 1, oy + 1), dxfattribs={"layer": "A-FURN"})
    msp.add_blockref("BED", (ox + 4, oy + 1), dxfattribs={"layer": "A-FURN"})
    msp.add_blockref("STOVE", (ox + 0.5, oy + 9.5), dxfattribs={"layer": "A-FURN"})
    for s in range(14):
        sx, sy = ox + 0.5 + (s % 7) * 1.0, oy + 0.4 + (s // 7) * 6
        msp.add_blockref("SOCKET", (sx, sy), dxfattribs={"layer": "E-SYMB"})
        msp.add_lwpolyline([(sx, sy), (sx, sy + 2), (ox + AW - 1, sy + 2)], dxfattribs={"layer": "E-POWR"})
    for l in range(6):
        msp.add_blockref("LIGHT", (ox + 2 + (l % 3) * 2.5, oy + 3 + (l // 3) * 7), dxfattribs={"layer": "E-SYMB"})
    for t, (tx, ty) in [("LIVING ROOM", (2, 11)), ("BEDROOM 01", (1.5, 4)), ("KITCHEN", (2, 10)),
                        ("BATHROOM", (8, 6)), ("DB-APT%d" % a, (9, 15))]:
        msp.add_text(t, dxfattribs={"layer": "A-TEXT", "height": 0.25, "insert": (ox + tx, oy + ty)})
    for c in range(8):
        msp.add_text("C%d 20A" % c, dxfattribs={"layer": "E-TEXT", "height": 0.12,
                                                "insert": (ox + 8, oy + 1 + c * 0.4), "rotation": 90 * (c % 2)})
    msp.add_mtext("S.P / 2 TR\\P(ASSUMED)", dxfattribs={"layer": "E-TEXT", "char_height": 0.15,
                                                         "insert": (ox + 6, oy + 14)})
doc.saveas(out)
print("written", out)
