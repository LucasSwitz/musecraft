"""Jolly's shape and face, measured off the original reference art (not included) at
28 model pixels tall: the hood and belly outlines, the face window, and the
face's colors and features. make_avatar.py builds the Figura avatar from these.

Model units are pixels (16 per block); y points down, vanilla's neck is y=0,
the ground is y=24, and the face looks toward -z."""
import math

FUR = 'dbcbb1'
FUR_RGB = (219, 203, 177)       # sampled off the reference, for Figura's soft fill
FACE_RGB = (238, 217, 181)

# The hood: a superellipsoid of revolution, top at y=-4.
HOOD_C, HOOD_R, HOOD_N = 3.3, 7.3, 2.2
# The belly below it, (y, radius) pairs off the reference outline.
BODY = [(3.8, 7.18), (4.4, 7.33), (4.92, 7.36), (5.41, 7.39), (5.91, 7.45), (6.40, 7.51), (6.90, 7.57), (7.93, 7.67),
        (9.91, 7.85), (11.89, 7.95), (13.87, 8.0), (15.86, 8.02), (16.81, 8.01), (17.30, 7.96), (17.80, 7.86),
        (18.29, 7.70), (18.79, 7.51), (19.28, 7.31), (19.78, 7.06), (20.27, 6.85), (20.77, 6.62),
        (21.0, 6.1), (21.15, 5.0)]
# The hood flares into the belly's outline down to here; below, the belly takes over.
NECK = 6.0
# Legs: short round stumps with soft soles, mostly hidden in the belly.
LEG_PIVOT = (3.55, 19.6)
# The face window in front view, traced off the reference: radius from the
# window's center by angle (degrees clockwise from straight up; the left half
# mirrors the right). A broad arch on top, widest just below the middle, a
# rounder, flatter bottom. Centered lower than the reference to show more hood.
FACE_CX, FACE_CY = 0.0, 3.15
FACE_REF_CY = 2.242            # where the reference's window is centered (the features are laid out around it)
FACE_OUTLINE = [(0, 3.636), (6, 3.676), (12, 3.735), (18, 3.82), (24, 3.889), (30, 4.057), (36, 4.171), (42, 4.33),
                (48, 4.473), (54, 4.597), (60, 4.711), (66, 4.795), (72, 4.89), (78, 4.979), (84, 5.058), (90, 5.127),
                (96, 5.217), (102, 5.306), (108, 5.37), (111, 5.41), (114, 5.395), (120, 5.326), (126, 5.162),
                (132, 4.87), (138, 4.597), (144, 4.32), (150, 4.107), (156, 3.958), (162, 3.839), (168, 3.715),
                (174, 3.696), (180, 3.636)]
FACE_TOP = FACE_CY - FACE_OUTLINE[0][1]
FACE_BOTTOM = FACE_CY + FACE_OUTLINE[-1][1]


def hood_radius(y):
    t = min(1.0, abs(y - HOOD_C) / HOOD_R)
    return HOOD_R * (1 - t ** HOOD_N) ** (1 / HOOD_N)


def table(points):
    def radius(y):
        if y <= points[0][0]:
            return points[0][1]
        for (y0, r0), (y1, r1) in zip(points, points[1:]):
            if y <= y1:
                return r0 + (r1 - r0) * (y - y0) / (y1 - y0)
        return points[-1][1]
    return radius


def outer_radius(y):
    """The outline above the neck: the hood, or the belly where it bulges past it."""
    return max(hood_radius(y), table(BODY)(y)) if y >= BODY[0][0] else hood_radius(y)


def head_outline(y):
    # The hood carries the outline down to the neck, then tucks back into a
    # sphere inside the belly so nodding never opens a hole.
    return outer_radius(y) if y <= NECK else hood_radius(y)


def blend(dst, rgb, a):
    a = max(0.0, min(1.0, a))
    return [round(d * (1 - a) + s * a) for d, s in zip(dst[:3], rgb)] + [255]


def smoothstep(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
    return t * t * (3 - 2 * t)


def window_radius(deg):
    for (a0, r0), (a1, r1) in zip(FACE_OUTLINE, FACE_OUTLINE[1:]):
        if deg <= a1:
            return r0 + (r1 - r0) * (deg - a0) / (a1 - a0)
    return FACE_OUTLINE[-1][1]


def window_edge(x, y):
    """Approximate distance inside the face window (negative outside), front view."""
    dx, dy = abs(x - FACE_CX), y - FACE_CY
    d = math.hypot(dx, dy)
    deg = math.degrees(math.atan2(dx, -dy))
    r = window_radius(deg)
    # Correct for the outline not being square to the radius.
    slope = (window_radius(min(180, deg + 1)) - window_radius(max(0, deg - 1))) / math.radians(2)
    return (r - d) * r / math.hypot(r, slope)


def face_color(x, y, px):
    """RGBA of the face window seen head-on at model point (x, y): clear outside
    the window so the fur shell shows through; px is one texel in model units,
    for antialiasing."""
    edge = window_edge(x, y)
    inside = smoothstep(-px, px, edge)
    if inside <= 0:
        # The fur hugging the window sits in a little shadow, so the face reads as set in.
        return [*blend([*FUR_RGB, 255], (196, 174, 138), 0.55 * (1 - smoothstep(0, 0.7, -edge)))[:3], 0]
    c = [*FACE_RGB, 255]
    # Brightest and palest at the top, darker toward the chin (sampled off the reference).
    c = blend(c, (246, 234, 212), 0.45 * (1 - smoothstep(FACE_TOP, FACE_TOP + 3.0, y)))
    c = blend(c, (222, 196, 162), 0.5 * smoothstep(FACE_CY - 1.0, FACE_BOTTOM, y))
    # A thin warm crease where the fur overhangs the window's edge.
    c = blend(c, (214, 186, 148), 0.6 * (1 - smoothstep(0.0, 0.42, edge)))
    y -= FACE_CY - FACE_REF_CY  # the features below sit where the reference has them
    for side in (-1, 1):
        d = math.hypot((x - side * 3.13) / 1.05, (y - 3.45) / 0.9)
        c = blend(c, (222, 140, 124), 0.9 * (1 - smoothstep(0.1, 1.05, d)))
    # Smile: an arc of radius 1.1 under the eyes, rounded at the ends.
    r = math.hypot(x, y - 2.515)
    ang = abs(math.degrees(math.atan2(x, y - 2.515)))
    if ang <= 41:
        d = abs(r - 1.095)
    else:
        a = math.radians(41)
        d = min(math.hypot(x - s * 1.095 * math.sin(a), y - 2.515 - 1.095 * math.cos(a)) for s in (-1, 1))
    c = blend(c, (40, 30, 28), smoothstep(px, -px, d - 0.09))
    for side in (-1, 1):
        d = math.hypot((x - side * 2.72) / 0.56, (y - 2.31) / 0.59) - 1
        c = blend(c, (22, 18, 18), smoothstep(px / 0.56, -px / 0.56, d))
        d = math.hypot(x - side * 2.72 - 0.2, y - 2.31 + 0.24) - 0.15
        c = blend(c, (252, 252, 252), smoothstep(px, -px, d))
    c[3] = round(255 * inside)
    return c


def rings(radius, y0, y1, tol=0.07, max_len=3.0, max_ratio=1.25, step=0.02):
    """Fit straight facets to the outline r(y) on [y0, y1]: each ring is the
    longest chord that stays within tol of the curve."""
    pts = [(y0 + i * step, radius(y0 + i * step)) for i in range(int((y1 - y0) / step))] + [(y1, radius(y1))]
    out, i = [], 0
    while i < len(pts) - 1:
        best = i + 1
        for j in range(i + 2, len(pts)):
            (ya, ra), (yb, rb) = pts[i], pts[j]
            length = math.hypot(yb - ya, rb - ra)
            if length > max_len or max(ra, rb) > max_ratio * max(min(ra, rb), 1e-3):
                break
            if max(abs((rb - ra) * (y - ya) - (yb - ya) * (r - ra)) / length for y, r in pts[i + 1:j]) > tol:
                break
            best = j
        out.append((pts[i], pts[best]))
        i = best
    return out
