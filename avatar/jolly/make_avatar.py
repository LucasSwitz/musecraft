#!/usr/bin/env python3
"""Write the Jolly Figura avatar: one smooth, rigged, furry mesh shaped
after the original reference art, as measured in shape.py.

Shape. The hood and belly are one mesh: round rings through the hood, then
oval rings through the belly, which is wider than deep and bulges forward
from a flatter back. The arms are tubes swept along the reference's curved
outline (bowing out from high on the shoulders into round mitts); the legs
are short stumps with soft toes.

Rig. Each part sits in a group named for the vanilla part it follows (Body,
RightArm, LeftArm, RightLeg, LeftLeg), so every vanilla animation drives it;
item pivots sit in the mitts. He has no neck, so the hood stays with the body
and only the face turns: a thin layer over the hood (the face window and a
puffy fur rim around it) in a Face group the script turns toward where the
player looks.

Fur. A soft, tileable fur pattern is laid continuously around and down each
part; per face the UVs shift by whole tiles into one padded tile.

Blockbench space: y up, feet at y=0, the face toward -z, the player's right
side at +x. Shapes are written in model space (y down, neck at 0, right side
at -x) and converted by to_bb().
Usage: make_avatar.py OUT_DIR"""
import base64
import json
import math
import os
import random
import struct
import sys
import uuid
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shape  # noqa: E402

TEX, TEX_H = 256, 304
FACE_S = 16                          # texels per model pixel in the face projection
FACE_X0, FACE_Y0 = -7.0, -2.3        # model-space corner of the face projection
FACE_W, FACE_H = 224, 160            # its size in texels, at the top of the sheet
FUR_U0, FUR_V0 = 0, 160              # the fur tile, below it
FUR_T, FUR_PAD = 96, 48              # tile period and padding, in texels
FUR_D = 16                           # fur texels per model pixel
RIM = 0.45                           # width of the fur rim around the face window
BLOB_TILES = 8                       # fur tiles around the belly
RADIAL_TILES = 5                     # fur tiles around the face, on the hood
NORMAL_LIFT = 0.9                    # how far the script tilts normals toward the sky

def to_bb(p):
    x, y, z = p
    return [round(-x, 4), round(24 - y, 4), round(z, 4)]


def ids(prefix):
    n = 0
    while True:
        yield f'{prefix}{n}'
        n += 1


def sample(radius, y0, y1, tol=0.03, max_len=0.9):
    """Profile points along r(y), denser where it curves."""
    out = [(y0, radius(y0))]
    for (a, b) in shape.rings(radius, y0, y1, tol=tol, max_len=max_len, max_ratio=1.3, step=0.01):
        out.append(b)
    return out


window_edge = shape.window_edge


def rim_height(e):
    """The soft fur edge around the window: rises from the window's edge, falls gently outward."""
    if e >= 0 or e <= -RIM:
        return 0.0
    d = -e
    if d < 0.15:
        return 0.09 * math.sin(math.pi / 2 * d / 0.15)
    return 0.09 * math.cos(math.pi / 2 * (d - 0.15) / (RIM - 0.15))


class Mesh:
    def __init__(self, name, texture=0):
        self.name, self.vertices, self.faces, self.texture = name, {}, {}, texture
        self._v, self._f = ids('v'), ids('f')

    def vertex(self, p):
        key = next(self._v)
        self.vertices[key] = to_bb(p)
        return key

    def face(self, keys, inside, uvs):
        """keys: 3-4 vertex ids; inside: a model point inside the solid; uvs: one
        [u, v] per key. Figura's normal is (v2-v1) x (v0-v1): wind it outward."""
        pts = [self.vertices[k] for k in keys]
        a = [pts[2][i] - pts[1][i] for i in range(3)]
        b = [pts[0][i] - pts[1][i] for i in range(3)]
        n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
        c = [sum(p[i] for p in pts) / len(pts) for i in range(3)]
        o = to_bb(inside)
        if sum(n[i] * (c[i] - o[i]) for i in range(3)) < 0:
            keys, uvs = keys[::-1], uvs[::-1]
        self.faces[next(self._f)] = {'uv': {k: [round(u, 3), round(v, 3)] for k, (u, v) in zip(keys, uvs)},
                                     'vertices': keys, 'texture': self.texture}

    def element(self):
        return {'name': self.name, 'color': 0, 'origin': [0, 0, 0], 'rotation': [0, 0, 0], 'export': True,
                'visibility': True, 'locked': False, 'render_order': 'default', 'allow_mirror_modeling': True,
                'shading': 'smooth', 'vertices': self.vertices, 'faces': self.faces, 'type': 'mesh',
                'uuid': str(uuid.uuid4())}


def fur_uvs(uvs):
    """Shift a face's continuous fur coordinates by whole tiles into the padded
    tile; None if the face spans more than the padding."""
    su = min(u for u, _ in uvs) // FUR_T * FUR_T
    sv = min(v for _, v in uvs) // FUR_T * FUR_T
    out = [(u - su + FUR_U0, v - sv + FUR_V0) for u, v in uvs]
    if any(u - FUR_U0 > FUR_T + FUR_PAD or v - FUR_V0 > FUR_T + FUR_PAD for u, v in out):
        return None
    return out


# The fur flows away from the face. Measured from the body's center along a
# line out through the face, so it rises from the face over the head, runs down
# the back, hangs down the belly, and only bunches low on the back by the legs.
FUR_CENTER = (0.0, 12.0, 0.0)


def fur_axis():
    face = (0.0, shape.FACE_CY, -shape.outer_radius(shape.FACE_CY))
    a = [f - c for f, c in zip(face, FUR_CENTER)]
    n = math.sqrt(sum(v * v for v in a))
    a = [v / n for v in a]
    e1 = (1.0, 0.0, 0.0)
    e2 = (a[1] * e1[2] - a[2] * e1[1], a[2] * e1[0] - a[0] * e1[2], a[0] * e1[1] - a[1] * e1[0])
    return a, e1, e2


def radial_fur(pts):
    """Fur coordinates growing away from the face: v is the angle out from the
    face (scaled to a typical radius, so strands keep one length), u runs around it. None near the point
    opposite the face, where the strands would bunch."""
    a, e1, e2 = fur_axis()
    out = []
    for p in pts:
        d = [p[i] - FUR_CENTER[i] for i in range(3)]
        dist = math.sqrt(sum(v * v for v in d))
        alpha = math.acos(max(-1.0, min(1.0, sum(d[i] * a[i] for i in range(3)) / dist)))
        if alpha > math.radians(165):
            return None
        out.append([math.atan2(sum(d[i] * e1[i] for i in range(3)), sum(d[i] * e2[i] for i in range(3))), alpha * 8.0])
    if max(b for b, _ in out) - min(b for b, _ in out) > math.pi:     # straddles the wrap
        out = [[b + 2 * math.pi if b < 0 else b, v] for b, v in out]
    return [(b / (2 * math.pi) * RADIAL_TILES * FUR_T, v * FUR_D) for b, v in out]


def planar_fur(pts):
    return [(x * FUR_D, y * FUR_D) for x, y, z in pts]


def skin(mesh, rings, centers, tiles_around, grows=None, place=None):
    """Join rings of model points (one point = a pole) into a furred surface.
    centers[i] is a point on the axis at ring i; fur runs around and down,
    unless grows(face points) gives other fur coordinates for a face."""
    keys = [[mesh.vertex(p) for p in ring] for ring in rings]
    seg = max(len(r) for r in rings)
    down = [0.0]
    for i in range(1, len(rings)):
        down.append(down[-1] + math.dist(centers[i], centers[i - 1]))
    for i in range(len(rings) - 1):
        a, b = keys[i], keys[i + 1]
        inside = [(centers[i][j] + centers[i + 1][j]) / 2 for j in range(3)]
        va, vb = down[i] * FUR_D, down[i + 1] * FUR_D
        for k in range(seg):
            u0, u1 = k / seg * tiles_around * FUR_T, (k + 1) / seg * tiles_around * FUR_T
            if len(a) == 1 and len(b) == 1:
                continue
            if len(a) == 1:
                ks, uv = [a[0], b[k], b[(k + 1) % seg]], [((u0 + u1) / 2, va), (u0, vb), (u1, vb)]
            elif len(b) == 1:
                ks, uv = [a[k], a[(k + 1) % seg], b[0]], [(u0, va), (u1, va), ((u0 + u1) / 2, vb)]
            else:
                ks, uv = [a[k], a[(k + 1) % seg], b[(k + 1) % seg], b[k]], [(u0, va), (u1, va), (u1, vb), (u0, vb)]
            if place:
                mesh.face(ks, inside, place(uv))
                continue
            pts = [(-mesh.vertices[k][0], 24 - mesh.vertices[k][1], mesh.vertices[k][2]) for k in ks]
            mapped = grows(pts) if grows else None
            mesh.face(ks, inside, (mapped and fur_uvs(mapped)) or fur_uvs(uv) or fur_uvs(planar_fur(pts)))


def mesh_y(mesh, key):
    return 24 - mesh.vertices[key][1]


def superellipse(theta, a, front, back, n):
    """Point on an oval cross-section: theta 0 faces front (-z), half-width a,
    front and back depths, squareness n (2 = ellipse)."""
    s, c = math.sin(theta), math.cos(theta)
    f = lambda t: math.copysign(abs(t) ** (2 / n), t)
    return a * f(s), -(front if c >= 0 else back) * f(c)

# ------------------------------------------------------------------ parts --

# The side profile below the round hood, as front and back depth over the
# half-width (the front view is traced off the reference). Behind, a soft
# S-curve: the nape recesses under the hood, the back rounds out to its
# fullest at mid-body, and a round rump tucks under above the legs, whose backs
# show beneath it (fuller any lower and it kicks out like a duck tail, or sags). In front, the
# chest swells into a small pot belly that peaks mid-body and tucks back above
# the legs, which rise into it.
#               y     front  back
SIDE_PROFILE = [(6.0, 1.0, 1.0), (7.5, 0.97, 0.925), (9.0, 0.96, 0.87), (11.0, 0.97, 0.895), (13.0, 0.99, 0.92),
                (15.0, 0.985, 0.925), (17.0, 0.95, 0.91), (18.5, 0.86, 0.86), (19.5, 0.78, 0.775), (20.0, 0.73, 0.685),
                (20.5, 0.68, 0.49), (21.0, 0.62, 0.37), (21.15, 0.6, 0.44)]
# Extra fullness at the back of the hood, so the head reads as its own round
# volume above the nape (only behind the sides, where the turning face never goes).
HEAD_BACK = [(-3.0, 0.0), (0.0, 0.055), (2.5, 0.085), (4.5, 0.055), (6.0, 0.0)]


def belly_depths(y, a):
    """Front and back depth and squareness of the body at height y."""
    front = shape.table([(p[0], p[1]) for p in SIDE_PROFILE])(y)
    back = shape.table([(p[0], p[2]) for p in SIDE_PROFILE])(y)
    return a * front, a * back, 2 + 0.3 * shape.smoothstep(6.0, 9.0, y)


def blob(segments=22):
    """Hood and belly as one closed mesh."""
    mesh = Mesh('blob')
    prof = [(-4.0, 0.0)] + sample(shape.head_outline, -3.995, shape.NECK, tol=0.04, max_len=1.2)[1:]
    prof += sample(shape.table(shape.BODY), shape.NECK, shape.BODY[-1][0], tol=0.04, max_len=1.4)[1:]
    prof += [(21.22, 3.0), (21.26, 1.3), (21.28, 0.0)]   # the base: only the arch between the legs shows
    rings, centers = [], []
    for y, a in prof:
        centers.append((0.0, y, 0.0))
        if a < 0.03:
            rings.append([(0.0, y, 0.0)])
            continue
        ring = []
        if y > shape.NECK:
            front, back, n = belly_depths(y, a)
            for k in range(segments):
                x, z = superellipse(math.pi + 2 * math.pi * k / segments, a, front, back, n)
                ring.append((x, y, z))
        else:
            bump = shape.table(HEAD_BACK)(y)
            for k in range(segments):
                t = math.pi + 2 * math.pi * k / segments
                behind = shape.smoothstep(math.radians(100), math.pi, abs(math.atan2(math.sin(t), math.cos(t))))
                r = a * (1 + bump * behind)
                ring.append((r * math.sin(t), y, -r * math.cos(t)))
        rings.append(ring)
    skin(mesh, rings, centers, tiles_around=BLOB_TILES, grows=radial_fur)
    return mesh


# The right arm's centerline (model space, x < 0), traced from the reference's
# outer outline minus the arm's radius: high on the shoulder, bowing out, then
# hanging into the mitt.
ARM_LINE = [(-5.6, 6.8, -0.2), (-6.15, 7.8, -0.25), (-6.75, 9.0, -0.3), (-7.45, 10.2, -0.35), (-8.15, 11.4, -0.4),
            (-8.75, 12.6, -0.45), (-9.25, 13.8, -0.5), (-9.6, 15.0, -0.55), (-9.8, 16.2, -0.6), (-9.85, 17.5, -0.6),
            (-9.75, 18.6, -0.55), (-9.6, 19.5, -0.5)]
# Radius by arc position (0..1): a round shoulder cap, a full arm, a mitt.
ARM_R = [(0.0, 0.0), (0.02, 1.2), (0.05, 1.65), (0.12, 1.85), (0.5, 1.88), (0.78, 2.1), (0.86, 2.05),
         (0.92, 1.75), (0.96, 1.2), (0.99, 0.45), (1.0, 0.0)]


def lerp_table(table, t):
    for (t0, v0), (t1, v1) in zip(table, table[1:]):
        if t <= t1:
            return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    return table[-1][1]


def catmull(points, n):
    """n+1 points along a Catmull-Rom spline through the points."""
    p = [points[0]] + points + [points[-1]]
    out = []
    for i in range(n + 1):
        t = i / n * (len(points) - 1)
        k = min(int(t), len(points) - 2)
        f = t - k
        a, b, c, d = p[k], p[k + 1], p[k + 2], p[k + 3]
        out.append(tuple(0.5 * (2 * b[j] + (-a[j] + c[j]) * f + (2 * a[j] - 5 * b[j] + 4 * c[j] - d[j]) * f * f
                                + (-a[j] + 3 * b[j] - 3 * c[j] + d[j]) * f ** 3) for j in range(3)))
    return out


def arm(side, segments=12):
    """side -1 = right arm (model -x), +1 = left. Returns (shoulder pivot, mesh, mitt center)."""
    pts = catmull([(-side * x, y, z) if side > 0 else (x, y, z) for x, y, z in ARM_LINE], 120)
    lengths = [0.0]
    for i in range(1, len(pts)):
        lengths.append(lengths[-1] + math.dist(pts[i], pts[i - 1]))
    total = lengths[-1]
    # Rings: dense at the rounded ends, sparse along the arm.
    ts = [0.0, 0.02, 0.05, 0.09, 0.14, 0.25, 0.37, 0.5, 0.62, 0.72, 0.8, 0.86, 0.91, 0.95, 0.98, 1.0]
    rings, centers, ref = [], [], (0.0, 0.0, -1.0)
    for t in ts:
        i = min(range(len(pts)), key=lambda j: abs(lengths[j] - t * total))
        c = pts[i]
        tan = [pts[min(i + 1, len(pts) - 1)][j] - pts[max(i - 1, 0)][j] for j in range(3)]
        tl = math.sqrt(sum(v * v for v in tan))
        tan = [v / tl for v in tan]
        # A frame around the tangent, carried along so the rings don't twist.
        d = sum(ref[j] * tan[j] for j in range(3))
        nrm = [ref[j] - d * tan[j] for j in range(3)]
        nl = math.sqrt(sum(v * v for v in nrm))
        nrm = [v / nl for v in nrm]
        ref = nrm
        bi = [tan[1] * nrm[2] - tan[2] * nrm[1], tan[2] * nrm[0] - tan[0] * nrm[2], tan[0] * nrm[1] - tan[1] * nrm[0]]
        r = lerp_table(ARM_R, t)
        centers.append(c)
        if r < 0.03:
            rings.append([c])
            continue
        rings.append([tuple(c[j] + r * (math.cos(2 * math.pi * k / segments + math.pi) * nrm[j]
                                        + math.sin(2 * math.pi * k / segments + math.pi) * bi[j]) for j in range(3))
                      for k in range(segments)])
    mesh = Mesh('arm')
    skin(mesh, rings, centers, tiles_around=2)
    mitt = min(pts, key=lambda p: abs(p[1] - 17.5))
    return pts[0], mesh, mitt


# Legs: (y, half-width, front depth, back depth, forward shift) down to soft toes.
# Fat, short legs whose outsides carry on the body's outline, whose fronts
# lean back into the belly and whose backs swell into the bottom (as in the
# reference), with soft, bulging feet.
LEG_RINGS = [(17.6, 0, 0, 0, 0), (17.8, 2.8, 1.9, 2.3, 0), (18.6, 3.15, 2.15, 2.9, 0), (19.6, 3.15, 2.5, 3.5, 0),
             (20.4, 3.1, 2.82, 3.45, 0), (21.0, 3.02, 2.98, 3.2, 0), (21.6, 2.9, 3.0, 2.9, -0.02),
             (22.6, 2.8, 2.95, 2.72, -0.06),
             (23.15, 2.98, 3.22, 2.82, -0.12), (23.55, 2.9, 3.15, 2.72, -0.15), (23.82, 2.62, 2.88, 2.46, -0.15),
             (23.96, 2.05, 2.3, 1.92, -0.15), (24.0, 0, 0, 0, -0.15)]


LEG_FORWARD = -1.4     # forward, so the legs rise into the belly and the feet show beneath it


# The legs' own fur strip, beside the tile: the same fur, wrapping around, but
# running once down the leg so it can carry the soft shadow under the belly.
LEG_U0, LEG_V0 = 144, 160


def leg_fur(uvs):
    su = min(u for u, _ in uvs) // FUR_T * FUR_T
    return [(u - su + LEG_U0, v + LEG_V0) for u, v in uvs]


def leg(side, segments=16):
    x0, z0 = side * shape.LEG_PIVOT[0], LEG_FORWARD
    rings, centers = [], []
    for y, a, front, back, dz in LEG_RINGS:
        centers.append((x0, y, z0 + dz))
        if a == 0:
            rings.append([(x0, y, z0 + dz)])
            continue
        ring = []
        for k in range(segments):
            x, z = superellipse(math.pi + 2 * math.pi * k / segments, a, front, back, 2.0)
            ring.append((x0 + x, y, z0 + z + dz))
        rings.append(ring)
    mesh = Mesh('leg')
    skin(mesh, rings, centers, tiles_around=3, place=leg_fur)
    return (x0, shape.LEG_PIVOT[1], z0), mesh


def face_layer():
    """The face window and its fur rim: a band of the hood's surface lifted just
    off it, the rim raised, with the face texture projected straight on. Faces
    entirely outside the rim are left out."""
    mesh = Mesh('face')
    lift = 0.08
    # Rows: close together across the window's top and bottom edges, where the rim is.
    top, bottom = shape.FACE_TOP, shape.FACE_BOTTOM
    ys = [top - RIM - 0.35 + i * 0.4 for i in range(int((RIM + 1.2) / 0.4) + 1)]
    ys += [ys[-1] + (bottom - 0.85 - ys[-1]) * i / 4 for i in range(1, 4)]
    ys += [min(bottom - 0.85 + i * 0.4, bottom + RIM + 0.15) for i in range(int((RIM + 1.2) / 0.4) + 2)]
    ys = sorted(set(round(y, 3) for y in ys))
    azs = list(range(-64, -36, 4)) + list(range(-36, 37, 6)) + list(range(40, 65, 4))
    grid, edge = [], []
    for y in ys:
        r = shape.outer_radius(y) + lift
        row, erow = [], []
        for az in azs:
            e = window_edge(r * math.sin(math.radians(az)), y)
            h = r + rim_height(e)
            row.append((h * math.sin(math.radians(az)), y, -h * math.cos(math.radians(az))))
            erow.append(e)
        grid.append(row)
        edge.append(erow)
    keys = [[mesh.vertex(p) for p in row] for row in grid]
    for i in range(len(ys) - 1):
        for k in range(len(azs) - 1):
            if max(edge[i][k], edge[i][k + 1], edge[i + 1][k], edge[i + 1][k + 1]) < -RIM - 0.05:
                continue
            ks = [keys[i][k], keys[i][k + 1], keys[i + 1][k + 1], keys[i + 1][k]]
            pts = [grid[i][k], grid[i][k + 1], grid[i + 1][k + 1], grid[i + 1][k]]
            # Project the hood-surface position straight on, so the texture lines up head-on.
            uv = []
            for (px, py, pz) in pts:
                fx = (shape.outer_radius(py) + lift) * px / math.hypot(px, pz)
                uv.append(((fx - FACE_X0) * FACE_S, (py - FACE_Y0) * FACE_S))
            mesh.face(ks, (0.0, ys[i], 0.0), uv)
    return mesh


# ------------------------------------------------------------------ armor --
# Armor shells fitted over him, hidden until a piece is worn; the script skins
# each with the worn material's vanilla armor texture (64x32, laid out like the
# vanilla armor models), so they sit on his round body instead of boxing it.
ARMOR_TEX = 1
Y_HELM = 5.4             # the helmet comes down to the shoulders beside the face
Y_HELM_S = -1.86         # where the hood turns from crown to side (45 degrees)
HELM_MARGIN = RIM + 0.15
Y_CHEST = (7.4, 17.0)    # below the chin all around, since the face slides with the head
Y_BELT = (16.8, 21.05)
Y_LEGGING = (19.4, 22.8)
Y_BOOT = (21.8, 23.97)
# Head items, centered on the hood and just enclosing it: a block (a pumpkin: 10px,
# centered 4px above the pivot) or a mob head (9.5px tall, standing on the pivot).
# The face layer hides under them, and they sit back a little over the hood's fuller back.
HEAD_BLOCK_SCALE, HEAD_SKULL_SCALE = 1.55, 1.632
HEAD_ITEM_CENTER = shape.HOOD_C - 0.2
HEAD_ITEM_PIVOT = (0.0, HEAD_ITEM_CENTER + 4 * HEAD_BLOCK_SCALE, 0.21)
HEAD_SKULL_DROP = 4.75 * HEAD_SKULL_SCALE - 4 * HEAD_BLOCK_SCALE   # mob heads sit this much lower
ELYTRA_PIVOT = (0.0, 7.0, 5.9)   # just inside his back: the wings hang 2px behind it, flaring out below
ELYTRA_SCALE = 0.65      # vanilla wings are 20px long; his back is shorter
ELYTRA_TILT = 14


def wrap180(a):
    return (a + 180) % 360 - 180


def box_u(psi, region, u0, wf, ws):
    """u along a vanilla box unwrap (right side, front, left side, back) for the
    angle psi from the front toward +x, using the linear map of region (the
    face's own region, so each face maps without tearing)."""
    if region == 0:      # the seam at the back right: stay off the neighbouring texture
        return max(u0 + 0.02, u0 + (psi + 135) / 90 * ws)
    if region == 1:
        return u0 + ws + (psi + 45) / 90 * wf
    if region == 2:
        return u0 + ws + wf + (psi - 45) / 90 * ws
    return min(u0 + 2 * ws + 2 * wf - 0.02, u0 + 2 * ws + wf + (psi - 135) / 90 * wf)


def around(pts, angle, mirror=False):
    """The points' angles (degrees from the front toward +x, from the angle
    map they were built at), unwrapped next to the face's center angle, plus
    the face's region."""
    angs = [(-1 if mirror else 1) * angle[p] if p in angle else None for p in pts]   # None: a pole
    cx = sum(math.sin(math.radians(a)) for a in angs if a is not None)
    cz = sum(math.cos(math.radians(a)) for a in angs if a is not None)
    center = (math.degrees(math.atan2(cx, cz)) + 135) % 360 - 135
    region = 0 if center < -45 else 1 if center < 45 else 2 if center < 135 else 3
    return [center if a is None else center + wrap180(a - center) for a in angs], region


def offset(surf, a, phi, off, center):
    """surf(a, phi) pushed off along its outward normal."""
    p = surf(a, phi)
    da = [x - y for x, y in zip(surf(a + 1e-3, phi), surf(a - 1e-3, phi))]
    dp = [x - y for x, y in zip(surf(a, phi + 1e-3), surf(a, phi - 1e-3))]
    n = [da[1] * dp[2] - da[2] * dp[1], da[2] * dp[0] - da[0] * dp[2], da[0] * dp[1] - da[1] * dp[0]]
    nl = math.sqrt(sum(v * v for v in n))
    if nl < 1e-9:
        n, nl = [0.0, -1.0, 0.0], 1.0
    n = [v / nl for v in n]
    if sum(n[i] * (p[i] - center[i]) for i in range(3)) < 0:
        n = [-v for v in n]
    return tuple(p[i] + off * n[i] for i in range(3))


def shell(name, rows, axis, uv_fn):
    """A mesh from rows of points (equal lengths, or one point for a pole),
    closed around; axis[i] is a point inside at row i. uv_fn(face points, face
    axis points, row index) gives the face's UVs."""
    mesh = Mesh(name, ARMOR_TEX)
    keys = [[mesh.vertex(p) for p in row] for row in rows]
    n = max(len(r) for r in rows)
    for i in range(len(rows) - 1):
        a, b = rows[i], rows[i + 1]
        for k in range(n):
            k1 = (k + 1) % n
            if len(a) == 1:
                ks, pts = [keys[i][0], keys[i + 1][k], keys[i + 1][k1]], [a[0], b[k], b[k1]]
            elif len(b) == 1:
                ks, pts = [keys[i][k], keys[i][k1], keys[i + 1][0]], [a[k], a[k1], b[0]]
            else:
                ks, pts = [keys[i][k], keys[i][k1], keys[i + 1][k1], keys[i + 1][k]], [a[k], a[k1], b[k1], b[k]]
            if len(pts) == 4 and math.dist(pts[0], pts[3]) < 1e-6 and math.dist(pts[1], pts[2]) < 1e-6:
                continue              # a column collapsed onto its edge: nothing to cover
            inside = [(axis[i][j] + axis[i + 1][j]) / 2 for j in range(3)]
            mesh.face(ks, inside, uv_fn(pts, [axis[i] if q in a else axis[i + 1] for q in pts], i))
    return mesh


def hood_shell_radius(y, phi):
    """The hood's radius, with the fullness behind it carried from 30 degrees
    back, so the helmet (which turns with the face) still covers it."""
    bump = shape.table(HEAD_BACK)(y)
    behind = shape.smoothstep(math.radians(30), math.radians(100), abs(math.radians(wrap180(math.degrees(phi)))))
    return shape.head_outline(y) * (1 + bump * behind)


def helmet():
    """A cap over the hood: crown and sides down to the shoulders, cut around
    the face window (and its rim) in front."""
    off = 0.45
    surf = lambda y, phi: (hood_shell_radius(y, phi) * math.sin(phi), y, -hood_shell_radius(y, phi) * math.cos(phi))
    phis = [-165, -135, -105, -82, -66, -56, -49, -43, -36, -26, -13, 0, 13, 26, 36, 43, 49, 56, 66, 82, 105, 135, 165]
    y_top = -3.995

    def bottom(phi):
        y = y_top + 0.3
        while y < Y_HELM:
            x = shape.head_outline(y) * math.sin(math.radians(phi))
            if math.cos(math.radians(phi)) > 0 and window_edge(x, y) > -HELM_MARGIN:
                return y
            y += 0.02
        return Y_HELM

    ybs = [bottom(phi) for phi in phis]
    levels = [lambda yb: min(-3.7, yb), lambda yb: min(Y_HELM_S, yb),
              lambda yb: Y_HELM_S + 0.5 * (yb - Y_HELM_S) if yb > Y_HELM_S else yb, lambda yb: yb]
    rows, angle = [[(0.0, y_top - off, 0.0)]], {}
    for lv in levels:
        row = [offset(surf, lv(yb), math.radians(phi), off, (0, lv(yb), 0)) for phi, yb in zip(phis, ybs)]
        angle.update(zip(row, phis))
        rows.append(row)
    axis = [(0.0, row[0][1], 0.0) for row in rows]
    r_s = hood_shell_radius(Y_HELM_S, 0) + off

    def uv(pts, ax, i):
        if i < 2:   # the crown: the helmet's top, seen from above
            return [(min(15.98, max(8.02, 12 + 4 * p[0] / r_s)), min(7.98, max(0.02, 4 - 4 * p[2] / r_s)))
                    for p in pts]
        psis, region = around(pts, angle)
        return [(box_u(a, region, 0, 8, 8), 8 + 3.9 * max(0.0, p[1] - Y_HELM_S) / (Y_HELM - Y_HELM_S))
                for a, p in zip(psis, pts)]
    return shell('helmet', rows, axis, uv)


def body_surf(y, phi):
    a = shape.table(shape.BODY)(y)
    front, back, n = belly_depths(y, a)
    x, z = superellipse(phi, a, front, back, n)
    return (x, y, z)


def body_band(name, ys, off, v0, v1):
    """A band around the body (16 columns, a texture seam at the back right)."""
    degs = [-135 + 22.5 * k for k in range(16)]
    rows = [[offset(body_surf, y, math.radians(d), off, (0, y, 0)) for d in degs] for y in ys]
    angle = {p: d for row in rows for p, d in zip(row, degs)}
    axis = [(0.0, y, 0.0) for y in ys]

    def uv(pts, ax, i):
        psis, region = around(pts, angle)
        return [(box_u(a, region, 16, 8, 4), v0 + (v1 - v0) * (p[1] - ys[0]) / (ys[-1] - ys[0]))
                for a, p in zip(psis, pts)]
    return shell(name, rows, axis, uv)


def leg_params(y):
    """Half-width, front and back depth and forward shift of the leg at y."""
    for r0, r1 in zip(LEG_RINGS, LEG_RINGS[1:]):
        if y <= r1[0]:
            f = (y - r0[0]) / (r1[0] - r0[0])
            return [r0[j] + (r1[j] - r0[j]) * f for j in range(1, 5)]
    return list(LEG_RINGS[-1][1:])


def leg_band(name, side, ys, off, v0, v1, columns, sole=False, flat_below=None):
    """A sleeve around the leg; boots keep the foot's widest outline down to a flat sole."""
    x0, z0 = side * shape.LEG_PIVOT[0], LEG_FORWARD

    def surf(y, phi):
        a, front, back, dz = leg_params(min(y, flat_below) if flat_below else y)
        x, z = superellipse(phi, a, front, back, 2.0)
        return (x0 + x, y, z0 + dz + z)
    degs = [-135 + 360 / columns * k for k in range(columns)]
    rows, axis, angle = [], [], {}
    for y in ys:
        dz = leg_params(min(y, flat_below) if flat_below else y)[3]
        c = (x0, y, z0 + dz)
        rows.append([offset(surf, y, math.radians(d), off, c) for d in degs])
        angle.update(zip(rows[-1], degs))
        axis.append(c)
    if sole:
        rows.append([(axis[-1][0], ys[-1] + 0.05, axis[-1][2])])     # just under the foot's lowest point
        axis.append((axis[-1][0], ys[-1] + 0.05, axis[-1][2]))
    n_band = len(ys) - 1

    def uv(pts, ax, i):
        if i >= n_band:   # the sole, seen from below
            w = max(math.hypot(p[0] - ax[0][0], p[2] - ax[0][2]) for p in pts) or 1.0
            return [(10 + side * 2 * (p[0] - ax[0][0]) / w, 18 + 2 * (p[2] - ax[0][2]) / w) for p in pts]
        psis, region = around(pts, angle, mirror=side > 0)
        return [(box_u(a, region, 0, 4, 4), v0 + (v1 - v0) * (p[1] - ys[0]) / (ys[-1] - ys[0]))
                for a, p in zip(psis, pts)]
    return shell(name, rows, axis, uv)


def shoulder(side):
    """A cap over the top of the arm, down past the shoulder."""
    off = 0.4
    pts = catmull([(-side * x, y, z) if side > 0 else (x, y, z) for x, y, z in ARM_LINE], 120)
    lengths = [0.0]
    for i in range(1, len(pts)):
        lengths.append(lengths[-1] + math.dist(pts[i], pts[i - 1]))
    ts = [0.0, 0.06, 0.16, 0.28, 0.4]
    rows, axis, angle = [], [], {}
    for t in ts:
        i = min(range(len(pts)), key=lambda j: abs(lengths[j] - t * lengths[-1]))
        c = pts[i]
        tan = [pts[min(i + 1, len(pts) - 1)][j] - pts[max(i - 1, 0)][j] for j in range(3)]
        tl = math.sqrt(sum(v * v for v in tan))
        tan = [v / tl for v in tan]
        ref = (0.0, 0.0, -1.0)
        d = sum(ref[j] * tan[j] for j in range(3))
        nrm = [ref[j] - d * tan[j] for j in range(3)]
        nl = math.sqrt(sum(v * v for v in nrm))
        nrm = [v / nl for v in nrm]
        bi = [tan[1] * nrm[2] - tan[2] * nrm[1], tan[2] * nrm[0] - tan[0] * nrm[2], tan[0] * nrm[1] - tan[1] * nrm[0]]
        r = lerp_table(ARM_R, t) + off
        axis.append(c)
        if t == 0.0:
            rows.append([tuple(c[j] - off * tan[j] for j in range(3))])
            continue
        rows.append([tuple(c[j] + r * (math.cos(math.radians(a)) * nrm[j] + math.sin(math.radians(a)) * bi[j])
                           for j in range(3)) for a in range(-135, 225, 45)])
        angle.update((p, wrap180(-a)) for p, a in zip(rows[-1], range(-135, 225, 45)))

    row_t = {p: t for row, t in zip(rows, ts) for p in row}

    def uv(pts, ax, i):
        psis, region = around(pts, angle, mirror=side > 0)
        return [(box_u(a, region, 40, 4, 4), 20 + 3.5 * max(0.0, row_t[p] - 0.06) / 0.34) for a, p in zip(psis, pts)]
    return shell('shoulder', rows, axis, uv)

# ---------------------------------------------------------------- texture --

def fur_tile(rng):
    """A tileable plush fur pattern (period FUR_T, about 6 model pixels): soft,
    fine strands along +v, as many a touch lighter as a touch darker, over a
    barely varying base. Matched to the reference fur's fine contrast (about 2%)
    so it reads creamy rather than streaky."""
    T = FUR_T
    tile = [[0.0] * T for _ in range(T)]
    n = T // 24
    lattice = [[rng.uniform(-1, 1) for _ in range(n)] for _ in range(n)]
    for y in range(T):
        for x in range(T):
            gx, gy = x / 24, y / 24
            x0, y0 = int(gx) % n, int(gy) % n
            fx, fy = gx - int(gx), gy - int(gy)
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            a = lattice[y0][x0] * (1 - fx) + lattice[y0][(x0 + 1) % n] * fx
            b = lattice[(y0 + 1) % n][x0] * (1 - fx) + lattice[(y0 + 1) % n][(x0 + 1) % n] * fx
            tile[y][x] = 0.008 * (a * (1 - fy) + b * fy)
    for _ in range(STRANDS):
        x, y = rng.uniform(0, T), rng.uniform(0, T)
        length, lean = rng.uniform(7, 15), rng.uniform(-0.2, 0.2)
        shade = rng.uniform(0.025, 0.045) if rng.random() < 0.8 else -rng.uniform(0.01, 0.02)
        for s in range(int(length)):
            px, py = int(x + lean * s) % T, int(y + s) % T
            tile[py][px] += STRAND_GAIN * shade * (1 - 0.5 * s / length)
    # Soften: mostly along the strands, a little across them.
    tile = [[(tile[(y - 1) % T][x] + 2 * tile[y][x] + tile[(y + 1) % T][x]) / 4 for x in range(T)] for y in range(T)]
    return [[(tile[y][(x - 1) % T] + 2 * tile[y][x] + tile[y][(x + 1) % T]) / 4 for x in range(T)] for y in range(T)]


STRANDS, STRAND_GAIN = 1700, 0.9


def fur_color(tile, u, v):
    """Lighter strands catch a pale sheen, as the reference fur's highlights do;
    darker ones just deepen the base."""
    k = tile[int(v) % FUR_T][int(u) % FUR_T]
    if k > 0:
        return [round(c + (s - c) * min(1.0, 3 * k)) for c, s in zip(shape.FUR_RGB, (250, 243, 228))]
    return [max(0, round(c * (1 + k))) for c in shape.FUR_RGB]


def texture():
    tile = fur_tile(random.Random(11))
    img = [[[*shape.FUR_RGB, 0] for _ in range(TEX)] for _ in range(TEX_H)]
    for ty in range(FACE_H):
        for tx in range(FACE_W):
            x, y = FACE_X0 + (tx + 0.5) / FACE_S, FACE_Y0 + (ty + 0.5) / FACE_S
            if window_edge(x, y) < -RIM - 0.1:
                continue
            # The rim's fur uses the hood's own mapping, so it runs on without a seam.
            r = shape.outer_radius(y)
            u, v = radial_fur([(x, y, -math.sqrt(max(0.0, r * r - x * x)))])[0]
            fur = fur_color(tile, u, v)
            # The fur right at the window's edge sits in the overhang's shadow.
            e = window_edge(x, y)
            if e < 0:
                fur = [round(v * (0.9 + 0.1 * shape.smoothstep(0, 0.3, -e))) for v in fur]
            c = shape.face_color(x, y, 0.7 / FACE_S)
            a = c[3] / 255
            # The rim's fur fades out where it settles onto the hood, so no edge shows.
            cover = shape.smoothstep(-RIM, -0.25, window_edge(x, y))
            img[ty][tx] = [round(f * (1 - a) + v * a) for f, v in zip(fur, c[:3])] + [round(255 * cover)]
    for ty in range(FUR_T + FUR_PAD):
        for tx in range(FUR_T + FUR_PAD):
            img[FUR_V0 + ty][FUR_U0 + tx] = fur_color(tile, tx, ty) + [255]
    # The legs: soft shadow where they come out from under the belly, a touch at the soles.
    top = LEG_RINGS[0][0]
    for ty in range(TEX_H - LEG_V0):
        y = top + ty / FUR_D
        shade = 0.8 + 0.2 * shape.smoothstep(20.6, 22.2, y) - 0.06 * shape.smoothstep(23.4, 24.0, y)
        for tx in range(TEX - LEG_U0):
            img[LEG_V0 + ty][LEG_U0 + tx] = [round(c * shade) for c in fur_color(tile, tx, ty)] + [255]
    return png(img)


def png(img):
    raw = b''.join(b'\x00' + bytes(c for p in row for c in p) for row in img)
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', len(img[0]), len(img), 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def group(name, origin_model, children):
    return {'name': name, 'origin': to_bb(origin_model), 'color': 0, 'uuid': str(uuid.uuid4()), 'export': True,
            'mirror_uv': False, 'isOpen': True, 'locked': False, 'visibility': True, 'autouv': 0, 'children': children}


def build():
    elements, outliner = [], []

    def add(mesh):
        elements.append(mesh.element())
        return elements[-1]['uuid']

    # Head items (pumpkins, mob heads) and elytra wings are vanilla renders at
    # Figura pivots: head items stand on the pivot (the script scales them to
    # enclose the hood, turning with the face), the wings hang from his upper back.
    face = group('Face', (0, shape.HOOD_C, 0), [add(face_layer()), add(helmet()),
                                             group('HelmetItemPivot', HEAD_ITEM_PIVOT, [])])
    chest_ys = [Y_CHEST[0] + (Y_CHEST[1] - Y_CHEST[0]) * k / 4 for k in range(5)]
    outliner.append(group('Body', (0, 0, 0), [
        add(blob()), face,
        add(body_band('chestplate', chest_ys, 0.45, 21, 29)),
        add(body_band('belt', [Y_BELT[0], 19.0, Y_BELT[1]], 0.55, 27, 31.95)),
        group('LeftElytraPivot', ELYTRA_PIVOT, []), group('RightElytraPivot', ELYTRA_PIVOT, [])]))
    for name, side in (('RightArm', -1), ('LeftArm', 1)):
        pivot, mesh, mitt = arm(side)
        outliner.append(group(name, pivot, [add(mesh), group(name.replace('Arm', 'ItemPivot'), mitt, []),
                                            add(shoulder(side))]))
    for name, side in (('RightLeg', -1), ('LeftLeg', 1)):
        hip, mesh = leg(side)
        legging = leg_band('legging', side, [Y_LEGGING[0], 21.1, Y_LEGGING[1]], 0.35, 20, 28, 8)
        boot = leg_band('boot', side, [Y_BOOT[0], 22.9, 23.6, Y_BOOT[1]], 0.5, 26, 31.95, 12, sole=True,
                        flat_below=23.55)
        outliner.append(group(name, hip, [add(mesh), add(legging), add(boot)]))
    faces = sum(len(e['faces']) for e in elements)
    model = {
        'meta': {'format_version': '4.10', 'model_format': 'free', 'box_uv': False},
        'name': 'model', 'model_identifier': '', 'visible_box': [1, 1, 0],
        'resolution': {'width': TEX, 'height': TEX_H},
        'elements': elements, 'outliner': outliner,
        'textures': [{'path': '', 'name': 'jolly.png', 'folder': '', 'namespace': '', 'id': '0',
                      'width': TEX, 'height': TEX_H, 'uv_width': TEX, 'uv_height': TEX_H, 'particle': False,
                      'render_mode': 'default', 'render_sides': 'auto', 'visible': True, 'internal': True,
                      'saved': False, 'uuid': str(uuid.uuid4()), 'relative_path': 'jolly.png',
                      'source': 'data:image/png;base64,' + base64.b64encode(texture()).decode()},
                     {'path': '', 'name': 'armor.png', 'folder': '', 'namespace': '', 'id': '1',
                      'width': 64, 'height': 32, 'uv_width': 64, 'uv_height': 32, 'particle': False,
                      'render_mode': 'default', 'render_sides': 'auto', 'visible': True, 'internal': True,
                      'saved': False, 'uuid': str(uuid.uuid4()), 'relative_path': 'armor.png',
                      'source': 'data:image/png;base64,' + base64.b64encode(png([[(0, 0, 0, 0)] * 64] * 32)).decode()}],
    }
    return model, faces


SCRIPT = """-- Jolly: show only the plush mesh; its parts follow the vanilla animations.
vanilla_model.PLAYER:setVisible(false)
vanilla_model.ARMOR:setVisible(false)
vanilla_model.HELMET_ITEM:setVisible(true)    -- pumpkins and mob heads, drawn at HelmetItemPivot

-- Armor: shells fitted to him, shown while a piece is worn and skinned with
-- that material's vanilla armor texture (leather in its dye color).
local m = models.model
local ARMOR = {
  {slot = 6, layer = 1, parts = {m.Body.Face.helmet}},
  {slot = 5, layer = 1, parts = {m.Body.chestplate, m.RightArm.shoulder, m.LeftArm.shoulder}},
  {slot = 4, layer = 2, parts = {m.Body.belt, m.RightLeg.legging, m.LeftLeg.legging}},
  {slot = 3, layer = 1, parts = {m.RightLeg.boot, m.LeftLeg.boot}},
}
local MATERIAL = {leather = "leather", chainmail = "chainmail", iron = "iron", golden = "gold",
                  diamond = "diamond", netherite = "netherite", turtle = "turtle"}
local LEATHER = vectors.hexToRGB("A06540")
local function dye(item)      -- a dyed leather piece's color, if any
  local tag = item:getTag() or {}
  local dyed = tag["minecraft:dyed_color"]
  if type(dyed) == "table" then dyed = dyed.rgb end
  if type(dyed) ~= "number" and type(tag.display) == "table" then dyed = tag.display.color end
  return type(dyed) == "number" and dyed or nil
end
local headItem = m.Body.Face.HelmetItemPivot
local HEAD_BLOCK, HEAD_SKULL, SKULL_DROP = %s, %s, %s
m.Body.LeftElytraPivot:setScale(%s):setRot(%s, 0, 0)    -- tilted in, so the folded wings lie along his back
m.Body.RightElytraPivot:setScale(%s):setRot(%s, 0, 0)
local worn = {}
for _, piece in ipairs(ARMOR) do
  for _, part in ipairs(piece.parts) do part:setVisible(false) end
end
local headId
events.TICK:register(function()
  if not player:isLoaded() then return end
  local id = player:getItem(6).id
  if id ~= headId then        -- mob heads are smaller than blocks and stand on the pivot
    headId = id
    local skull = id:match("_head$") or id:match("_skull$")
    headItem:setScale(skull and HEAD_SKULL or HEAD_BLOCK):setPos(0, skull and -SKULL_DROP or 0, 0)
    local worn_item = id ~= "minecraft:air" and not MATERIAL[id:match("^minecraft:(%%a+)_") or ""]
    m.Body.Face.face:setVisible(not worn_item)     -- a pumpkin or mob head covers the face
  end
  for i, piece in ipairs(ARMOR) do
    local item = player:getItem(piece.slot)
    local color = dye(item)
    local key = item.id .. tostring(color)
    if worn[i] ~= key then
      worn[i] = key
      local material = MATERIAL[item.id:match("^minecraft:(%%a+)_") or ""]
      for _, part in ipairs(piece.parts) do
        part:setVisible(material ~= nil)
        if material then
          part:setPrimaryTexture("RESOURCE", "minecraft:textures/models/armor/" .. material .. "_layer_" .. piece.layer .. ".png")
          part:setColor(material ~= "leather" and vec(1, 1, 1) or color and vectors.intToRGB(color) or LEATHER)
        end
      end
    end
  end
end)

-- He has no neck: the hood stays with the body and the face slides around it
-- toward where the player looks. Only turning, not nodding, so the face stays
-- on the hood's surface.
local face = models.model.Body.Face

-- Animations Musecraft plays with mc.animate (listed by mc.animations). A ping
-- starts each one, so every viewer sees it. The wave lifts the right arm out
-- beside the head, a little forward, and waggles it.
local WAVE_TICKS = 40
local rightArm = models.model.RightArm
local waveStart
function pings.wave() waveStart = world.getTime() end
function musecraft_animations() return "wave" end
function musecraft_animate(name)
  if name ~= "wave" then return false end
  pings.wave()
  return true
end

local function wavePose(t)      -- arm rotation at wave progress t (0..1)
  local lift = math.min(1, t * 5, (1 - t) * 5)       -- up over the first fifth, down over the last
  return lift * 30, lift * (115 + 22 * math.sin(t * math.pi * 8))
end

function events.render(delta, context)
  face:setRot(0, math.clamp(vanilla_model.HEAD:getOriginRot().y, -70, 70), 0)
  local t = waveStart and (world.getTime() + delta - waveStart) / WAVE_TICKS
  if t and t < 1 then
    local rx, rz = wavePose(t)
    rightArm:setRot(rx, 0, rz)
  elseif waveStart then
    waveStart = nil
    rightArm:setRot(0, 0, 0)
  end
end

-- Soft studio fill: tilt every normal toward the sky, so he keeps his shape
-- but no side drops into Minecraft's deep entity shade. A few hundred vertices
-- per tick keeps this inside the default script budget.
local LIFT = %s
local up = vec(0, LIFT, 0)
local queue = {}
for _, part in ipairs({m.Body.blob, m.Body.Face.face, m.RightArm.arm, m.LeftArm.arm, m.RightLeg.leg, m.LeftLeg.leg,
                       m.Body.Face.helmet, m.Body.chestplate, m.Body.belt, m.RightArm.shoulder, m.LeftArm.shoulder,
                       m.RightLeg.legging, m.LeftLeg.legging, m.RightLeg.boot, m.LeftLeg.boot}) do
  for _, verts in pairs(part:getAllVertices()) do queue[#queue + 1] = verts end
end
local list, index = 1, 1

-- A raised arm would carry its sky-tilted normals sideways and go dark, so
-- while it waves its normals are relit toward the sky in its rotated frame.
local armVerts = {}
for _, verts in pairs(m.RightArm.arm:getAllVertices()) do
  for _, v in ipairs(verts) do armVerts[#armVerts + 1] = {v, v:getNormal():copy()} end
end
local armLit = false
events.TICK:register(function()
  local t = waveStart and (world.getTime() - waveStart) / WAVE_TICKS
  if not (t and t < 1) and not armLit then return end
  local rx, rz = 0, 0
  if t and t < 1 then rx, rz = wavePose(t) end
  local sky = matrices.rotation3(rx, 0, rz):invert() * up
  for _, a in ipairs(armVerts) do a[1]:setNormal((a[2] + sky):normalize()) end
  armLit = rx ~= 0 or rz ~= 0
end)

events.TICK:register(function()
  local budget = 300
  while list <= #queue and budget > 0 do
    local v = queue[list][index]
    if v then
      v:setNormal((v:getNormal() + up):normalize())
      index, budget = index + 1, budget - 1
    else
      list, index = list + 1, 1
    end
  end
end)
"""

if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else 'Jolly'
    os.makedirs(out, exist_ok=True)
    model, faces = build()
    with open(os.path.join(out, 'model.bbmodel'), 'w') as f:
        json.dump(model, f, separators=(',', ':'))
    # Figura reads each texture from its file when one is named, else it logs a read error.
    for tex in model['textures']:
        with open(os.path.join(out, tex['relative_path']), 'wb') as f:
            f.write(base64.b64decode(tex['source'].split(',', 1)[1]))
    # Smooth shading for every mesh: Figura averages normals across shared positions.
    meshes = ['Body.blob', 'Body.Face.face', 'RightArm.arm', 'LeftArm.arm', 'RightLeg.leg', 'LeftLeg.leg',
              'Body.Face.helmet', 'Body.chestplate', 'Body.belt', 'RightArm.shoulder', 'LeftArm.shoulder',
              'RightLeg.legging', 'LeftLeg.legging', 'RightLeg.boot', 'LeftLeg.boot']
    with open(os.path.join(out, 'avatar.json'), 'w') as f:
        json.dump({'name': 'Jolly', 'description': 'The default Muse avatar', 'authors': ['Muse'],
                   'color': shape.FUR, 'customizations': {f'model.{m}': {'smooth': True} for m in meshes}},
                  f, indent=2)
    with open(os.path.join(out, 'script.lua'), 'w') as f:
        f.write(SCRIPT % (HEAD_BLOCK_SCALE, HEAD_SKULL_SCALE, round(HEAD_SKULL_DROP, 3), ELYTRA_SCALE, ELYTRA_TILT,
                           ELYTRA_SCALE, ELYTRA_TILT, NORMAL_LIFT))
    print(out, faces, 'faces', os.path.getsize(os.path.join(out, 'model.bbmodel')), 'bytes')
