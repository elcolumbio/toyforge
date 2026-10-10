#!/usr/bin/env python3
"""Renders build/glyphs/<word>.json: left = raw outlines (font units), right = extruded top caps (unit box)."""
import json, sys
from PIL import Image, ImageDraw
for word in sys.argv[2:]:
    d = json.load(open(f"{sys.argv[1]}/{word}.json"))
    W, H = 1600, 420
    img = Image.new("RGB", (W, H * 2), "white"); dr = ImageDraw.Draw(img)
    xs = [p[0] for c in d["contours"] for p in c]; ys = [p[1] for c in d["contours"] for p in c]
    sx = (W - 40) / (max(xs) - min(xs)); sy = (H - 40) / (max(ys) - min(ys)); s = min(sx, sy)
    for c in d["contours"]:
        pts = [(20 + (x - min(xs)) * s, H - 20 - (y - min(ys)) * s) for x, y in c]
        dr.line(pts + [pts[0]], fill="black", width=2)
        for p in pts: dr.ellipse([p[0] - 2, p[1] - 2, p[0] + 2, p[1] + 2], fill="red")
    asp = (max(xs) - min(xs)) / (max(ys) - min(ys)); h2 = H - 40; w2 = h2 * asp
    if w2 > W - 40: w2 = W - 40; h2 = w2 / asp
    for t in d["tris"]:
        pts = [(20 + (t[i] + .5) * w2, H + 20 + (.5 - t[i + 1]) * h2) for i in (0, 2, 4)]
        dr.polygon(pts, fill=(255, 126, 199), outline=(120, 40, 90))
    img.save(f"{sys.argv[1]}/{word}.png"); print(word, len(d["contours"]), "contours", len(d["tris"]), "cap tris")
