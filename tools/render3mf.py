#!/usr/bin/env python3
"""Tiny software renderer for ToyForge Bambu 3MF exports (colours per part). Usage: render3mf.py in.3mf out.png
Env: YAW, PITCH (degrees), ZMAX (only triangles below this height, e.g. a nameplate close-up)."""
import os, sys, zipfile, re, numpy as np
from PIL import Image
z = zipfile.ZipFile(sys.argv[1])
sub = z.read('3D/Objects/object_1.model').decode()
cols = re.search(r'filament_colours" value="([^"]+)"', z.read('Metadata/toyforge.config').decode()).group(1).split(';')
tris, colors = [], []
for k, obj in enumerate(re.findall(r'<object id="\d+".*?</object>', sub, re.S)):
    v = np.array([[float(a), float(b), float(c)] for a, b, c in re.findall(r'<vertex x="([^"]+)" y="([^"]+)" z="([^"]+)"', obj)])
    t = np.array([[int(a), int(b), int(c)] for a, b, c in re.findall(r'<triangle v1="(\d+)" v2="(\d+)" v3="(\d+)"', obj)])
    tris.append(v[t]); c = cols[k % len(cols)].lstrip('#'); colors += [tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))] * len(t)
T = np.concatenate(tris); C = np.array(colors, float)
if 'ZMAX' in os.environ:
    keep = T[:, :, 2].max(1) - T[:, :, 2].min() <= float(os.environ['ZMAX']); T, C = T[keep], C[keep]
SS = 2  # supersampling
W, H = 1400 * SS, 1050 * SS
yaw, pitch = np.radians(float(os.environ.get('YAW', -30))), np.radians(float(os.environ.get('PITCH', 25)))
Rz = np.array([[np.cos(yaw), -np.sin(yaw), 0], [np.sin(yaw), np.cos(yaw), 0], [0, 0, 1]])
Rx = np.array([[1, 0, 0], [0, np.cos(pitch), -np.sin(pitch)], [0, np.sin(pitch), np.cos(pitch)]])
P = T @ Rz.T @ Rx.T  # x right, y depth(into screen), z up
n = np.cross(T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]); n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-12
light = np.array([0.4, -0.6, 0.7]); light /= np.linalg.norm(light)
view = (np.array([0, -1, 0]) @ Rx) @ Rz  # direction towards camera in model space
shade = 0.35 + 0.65 * np.clip(n @ light, 0, 1) + 0.15 * np.clip(n @ view, 0, 1)
mn, mx = P.reshape(-1, 3).min(0), P.reshape(-1, 3).max(0)
s = 0.85 * min(W / (mx[0] - mn[0]), H / (mx[2] - mn[2]))
X = (P[:, :, 0] - (mn[0] + mx[0]) / 2) * s + W / 2; Y = H / 2 - (P[:, :, 2] - (mn[2] + mx[2]) / 2) * s; D = P[:, :, 1]
img = np.zeros((H, W, 3)); img[:] = np.linspace([214, 236, 255], [255, 247, 232], H)[:, None, :]
zb = np.full((H, W), np.inf)
for i in range(len(T)):
    x, y, d = X[i], Y[i], D[i]
    x0, x1 = int(max(0, np.floor(x.min()))), int(min(W - 1, np.ceil(x.max()))); y0, y1 = int(max(0, np.floor(y.min()))), int(min(H - 1, np.ceil(y.max())))
    if x1 < x0 or y1 < y0: continue
    gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
    den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
    if abs(den) < 1e-12: continue
    a = ((y[1] - y[2]) * (gx - x[2]) + (x[2] - x[1]) * (gy - y[2])) / den
    b = ((y[2] - y[0]) * (gx - x[2]) + (x[0] - x[2]) * (gy - y[2])) / den
    c = 1 - a - b; m = (a >= 0) & (b >= 0) & (c >= 0)
    if not m.any(): continue
    depth = a * d[0] + b * d[1] + c * d[2]
    sub_z = zb[y0:y1 + 1, x0:x1 + 1]; upd = m & (depth < sub_z)
    sub_z[upd] = depth[upd]; img[y0:y1 + 1, x0:x1 + 1][upd] = np.clip(C[i] * shade[i], 0, 255)
Image.fromarray(img.astype(np.uint8)).resize((W // SS, H // SS), Image.LANCZOS).save(sys.argv[2]); print('ok', len(T), 'triangles')
