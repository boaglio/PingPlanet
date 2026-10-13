#!/usr/bin/env python3
"""Renders the world map used by PingPlanet as braille characters.

Input: Natural Earth 1:110m land polygons (public domain), from
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson

Usage: python3 tools/make-map.py ne_110m_land.geojson > src/main/resources/pingplanet/world.txt

Each braille character holds 2x4 dots, and a terminal cell is about twice as tall
as it is wide, so every dot covers the same number of degrees in both directions
(an equirectangular projection). The first line of the output stores the
projection so the Java side can place servers on the map.
"""
import json
import sys

COLS = 80                      # characters per line
DEG = 360 / (COLS * 2)         # degrees per dot
LAT_TOP = 84.0                 # north edge
LAT_BOTTOM = -57.0             # south edge (skips Antarctica)
ROWS = round((LAT_TOP - LAT_BOTTOM) / DEG / 4)

# Braille dot bits, indexed [y][x] inside a 2x4 cell.
BITS = [[0x01, 0x08], [0x02, 0x10], [0x04, 0x20], [0x40, 0x80]]


def load_rings(path):
    with open(path) as f:
        data = json.load(f)
    rings = []
    for feature in data["features"]:
        geom = feature["geometry"]
        polys = [geom["coordinates"]] if geom["type"] == "Polygon" else geom["coordinates"]
        for poly in polys:
            for ring in poly:  # holes count too: even-odd rule handles them
                xs = [p[0] for p in ring]
                ys = [p[1] for p in ring]
                rings.append((min(xs), max(xs), min(ys), max(ys), ring))
    return rings


def inside(rings, lon, lat):
    hit = False
    for x0, x1, y0, y1, ring in rings:
        if not (x0 <= lon <= x1 and y0 <= lat <= y1):
            continue
        j = len(ring) - 1
        for i in range(len(ring)):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
                hit = not hit
            j = i
    return hit


def land(rings, dot_x, dot_y):
    # A dot is land when any of 2x2 samples inside it is, so small islands survive.
    for sx in (0.25, 0.75):
        for sy in (0.25, 0.75):
            lon = -180 + (dot_x + sx) * DEG
            lat = LAT_TOP - (dot_y + sy) * DEG
            if inside(rings, lon, lat):
                return True
    return False


def main():
    rings = load_rings(sys.argv[1])
    print(f"# cols={COLS} rows={ROWS} lon_left=-180 lat_top={LAT_TOP} deg_per_dot={DEG}")
    for row in range(ROWS):
        line = []
        for col in range(COLS):
            code = 0
            for y in range(4):
                for x in range(2):
                    if land(rings, col * 2 + x, row * 4 + y):
                        code |= BITS[y][x]
            line.append(chr(0x2800 + code))
        print("".join(line))


if __name__ == "__main__":
    main()
