#!/usr/bin/env python3
"""Rasterized previews of a Figura avatar's model.bbmodel: smooth-shaded mesh,
lit like Minecraft entities, in several views plus a nod pose, and a
side-by-side with the reference art.
Usage: uv run --with numpy --with pillow python preview_mesh.py AVATAR_DIR OUT.png [REF.jpg]"""
import base64
import io
import json
import math
import os
import re
import sys

import numpy as np
from PIL import Image

BG = np.array([242, 242, 242], float)
L0 = np.array([0.2, 1.0, -0.7]); L0 /= np.linalg.norm(L0)
L1 = np.array([-0.2, 1.0, 0.7]); L1 /= np.linalg.norm(L1)


def rot(rx, ry, rz):
    rx, ry, rz = (math.radians(a) for a in (rx, ry, rz))
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    return (np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]) @ np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
            @ np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]]))


ARMOR_DIR = os.environ.get('ARMOR_DIR', '')   # vanilla textures/models/armor, to dress the armor shells


def load(avatar_dir, pose, armor=None):
    """armor: a material (e.g. 'diamond') to dress the armor shells in, else they're left out."""
    m = json.load(open(f'{avatar_dir}/model.bbmodel'))
    lift = re.search(r'local LIFT = ([0-9.]+)', open(f'{avatar_dir}/script.lua').read())
    up = np.array([0.0, float(lift.group(1)) if lift else 0.0, 0.0])
    src = m['textures'][0]['source'].split(',', 1)[1]
    tex = np.asarray(Image.open(io.BytesIO(base64.b64decode(src))).convert('RGBA'), float)
    by_uuid = {e['uuid']: e for e in m['elements']}
    tris = []   # (positions 3x3, normals 3x3, uvs 3x2)

    armor_tex = {}
    if armor:
        for layer in (1, 2):
            armor_tex[layer] = np.asarray(Image.open(f'{ARMOR_DIR}/{armor}_layer_{layer}.png').convert('RGBA'), float)

    def add(e, R, pivot):
        if any(f.get('texture', 0) for f in e['faces'].values()):
            if not armor:
                return
            t = armor_tex[2 if e['name'] in ('belt', 'legging') else 1]
        else:
            t = tex
        keys = list(e['vertices'])
        idx = {k: i for i, k in enumerate(keys)}
        P = (np.array([e['vertices'][k] for k in keys], float) - pivot) @ R.T + pivot
        N = np.zeros_like(P)
        for f in e['faces'].values():
            p = [P[idx[k]] for k in f['vertices']]
            n = np.cross(p[2] - p[1], p[0] - p[1])          # Figura's face normal
            n /= max(np.linalg.norm(n), 1e-9)
            for k in f['vertices']:
                N[idx[k]] += n
        N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-9)
        N += up                       # the avatar script's soft fill
        N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-9)
        for f in e['faces'].values():
            vs = f['vertices']
            for a, b, c in ((0, 1, 2), (0, 2, 3))[:len(vs) - 2]:
                k3 = [vs[a], vs[b], vs[c]]
                tris.append((P[[idx[k] for k in k3]], N[[idx[k] for k in k3]],
                             np.array([f['uv'][k] for k in k3], float), t))

    def walk(node, R, pivot):
        if isinstance(node, str):
            add(by_uuid[node], R, pivot)
            return
        if node['name'] in pose:      # posed groups turn about their own origin
            R, pivot = rot(*pose[node['name']]), np.array(node['origin'], float)
        for ch in node['children']:
            walk(ch, R, pivot)

    for node in m['outliner']:
        walk(node, np.eye(3), np.zeros(3))
    return tris, tex


def render(tris, tex, yaw, pitch, W, H, scale, target):
    t, p = math.radians(yaw), math.radians(pitch)
    d = np.array([-math.sin(t) * math.cos(p), -math.sin(p), math.cos(t) * math.cos(p)])  # camera looks along d
    right = np.cross(d, [0.0, 1.0, 0.0])
    right /= np.linalg.norm(right)
    up = np.cross(right, d)
    img = np.tile(BG, (H, W, 1))
    zbuf = np.full((H, W), np.inf)
    target = np.asarray(target, float)
    for P, N, UV, tex in tris:
        rel = P - target
        sx = W / 2 + (rel @ right) * scale
        sy = H / 2 - (rel @ up) * scale
        z = rel @ d
        x0, x1 = int(max(0, math.floor(sx.min()))), int(min(W - 1, math.ceil(sx.max())))
        y0, y1 = int(max(0, math.floor(sy.min()))), int(min(H - 1, math.ceil(sy.max())))
        if x0 > x1 or y0 > y1:
            continue
        gy, gx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        gx = gx + 0.5; gy = gy + 0.5
        den = (sy[1] - sy[2]) * (sx[0] - sx[2]) + (sx[2] - sx[1]) * (sy[0] - sy[2])
        if abs(den) < 1e-12:
            continue
        w0 = ((sy[1] - sy[2]) * (gx - sx[2]) + (sx[2] - sx[1]) * (gy - sy[2])) / den
        w1 = ((sy[2] - sy[0]) * (gx - sx[2]) + (sx[0] - sx[2]) * (gy - sy[2])) / den
        w2 = 1 - w0 - w1
        inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        if not inside.any():
            continue
        zz = w0 * z[0] + w1 * z[1] + w2 * z[2]
        sub = zbuf[y0:y1 + 1, x0:x1 + 1]
        m = inside & (zz < sub)
        if not m.any():
            continue
        n = w0[m, None] * N[0] + w1[m, None] * N[1] + w2[m, None] * N[2]
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
        lam = np.minimum(1, 0.4 + 0.6 * (np.maximum(0, n @ L0) + np.maximum(0, n @ L1)))
        uv = w0[m, None] * UV[0] + w1[m, None] * UV[1] + w2[m, None] * UV[2]
        texel = tex[np.clip(uv[:, 1].astype(int), 0, tex.shape[0] - 1), np.clip(uv[:, 0].astype(int), 0, tex.shape[1] - 1)]
        alpha = texel[:, 3:4] / 255
        keep = alpha[:, 0] >= 0.1          # Minecraft's translucent layer discards nearly clear texels
        region = img[y0:y1 + 1, x0:x1 + 1]
        mm = m.copy()
        mm[m] = keep
        under = region[mm]
        region[mm] = texel[keep, :3] * lam[keep, None] * alpha[keep] + under * (1 - alpha[keep])
        sub[mm] = zz[m][keep]
    return img


def main():
    tris, tex = load(sys.argv[1], {})
    nod, _ = load(sys.argv[1], {'Face': (0, 40, 0), 'RightArm': (40, 0, 0), 'LeftArm': (-40, 0, 0),
                                'RightLeg': (-30, 0, 0), 'LeftLeg': (30, 0, 0)})
    c = (0, 14, 0)
    views = [render(tris, tex, 0, 0, 340, 440, 14, c), render(tris, tex, 35, 15, 340, 440, 14, c),
             render(tris, tex, 90, 0, 340, 440, 14, c), render(tris, tex, 160, 15, 340, 440, 14, c),
             render(nod, tex, 30, 10, 340, 440, 14, c), render(tris, tex, 15, 5, 340, 440, 30, (0, 21.4, 0))]
    Image.fromarray(np.concatenate(views, 1).clip(0, 255).astype(np.uint8)).save(sys.argv[2])
    if len(sys.argv) > 3:
        ref = np.asarray(Image.open(sys.argv[3]).convert('RGB').resize((800, 800)), float)
        front = render(tris, tex, 0, 0, 800, 800, 25.2, c)
        Image.fromarray(np.concatenate([ref, front], 1).clip(0, 255).astype(np.uint8)).save(
            sys.argv[2].replace('.png', '-vs-ref.png'))


main()
