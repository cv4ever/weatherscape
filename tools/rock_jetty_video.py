"""Builds Rock Jetty's assets from the phone video VID20260911140508.mp4 (not in the repository).

Steps: lock the rocks to one frame (SIFT matches on the rocks, a RANSAC homography per frame), level
the horizon (0.44 degrees clockwise in the source), crop to the area every frame covers, cross-fade
the last second into the first for a seamless loop, then keep only the sea and rocks: the sky is drawn
by the shader. Also writes the sea mask the shader uses to tell water from rock.

Needs ffmpeg on PATH (or FFMPEG set), opencv-python and numpy.
Usage: python tools/rock_jetty_video.py <source.mp4>
"""
import os
import subprocess
import sys

import cv2
import numpy as np

FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
W, H = 1080, 1920          # source after its rotation flag
REFERENCE = 160            # frame the rocks are locked to
FADE = 30                  # frames of cross-fade that close the loop
SEA_TOP = 1184             # first row kept, 16 rows above the horizon in the stabilised crop


def decode(path):
    raw = subprocess.run([FFMPEG, "-loglevel", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "bgr24", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, H, W, 3)


def encode(frames, path, crf):
    h, w = frames[0].shape[:2]
    p = subprocess.Popen([FFMPEG, "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{w}x{h}",
                          "-r", "30", "-i", "-", "-an", "-c:v", "libx264", "-profile:v", "main", "-crf", str(crf),
                          "-preset", "slow", "-g", "30", "-pix_fmt", "yuv420p", "-movflags", "+faststart", path],
                         stdin=subprocess.PIPE)
    for f in frames:
        p.stdin.write(np.ascontiguousarray(f).tobytes())
    p.stdin.close()
    assert p.wait() == 0


def horizon_angle(img):
    """Tilt in degrees of the sky/sea edge, from the red channel's strongest downward drop per column."""
    r = cv2.GaussianBlur(img[:, :, 2].astype(np.float32), (0, 0), 1.5)
    band = (r[:-6] - r[6:])[1150:1350]
    xs = np.arange(10, 1070, 6).astype(float)
    ys = np.array([np.argmax(band[:, int(x)]) + 1153 for x in xs], float)
    for _ in range(4):
        a, c = np.polyfit(xs, ys, 1)
        err = np.abs(ys - (a * xs + c))
        keep = err < max(1.5, np.percentile(err, 80))
        xs, ys = xs[keep], ys[keep]
    return np.degrees(np.arctan(np.polyfit(xs, ys, 1)[0]))


def main(source):
    frames = decode(source)
    gray = lambda im: cv2.cvtColor(im, cv2.COLOR_BGR2GRAY)
    rocks = np.zeros((H, W), np.uint8)
    rocks[1330:1900] = 255
    sift = cv2.SIFT_create(4000)
    ref_k, ref_d = sift.detectAndCompute(gray(frames[REFERENCE]), rocks)
    matcher = cv2.BFMatcher()
    homographies = []
    for f in frames:
        k, d = sift.detectAndCompute(gray(f), rocks)
        good = [a for a, b in matcher.knnMatch(d, ref_d, k=2) if a.distance < .7 * b.distance]
        src = np.float32([k[m.queryIdx].pt for m in good])
        dst = np.float32([ref_k[m.trainIdx].pt for m in good])
        homographies.append(cv2.findHomography(src, dst, cv2.RANSAC, 2.0)[0])

    steady = cv2.warpPerspective(frames[REFERENCE], homographies[REFERENCE], (W, H))
    level = np.vstack([cv2.getRotationMatrix2D((W / 2, 1243), horizon_angle(steady), 1.0), [0, 0, 1]])
    transforms = [level @ h for h in homographies]

    # Largest rectangle every transformed frame covers.
    valid = np.full((H, W), 255, np.uint8)
    for t in transforms:
        valid &= cv2.warpPerspective(np.full((H, W), 255, np.uint8), t, (W, H), flags=cv2.INTER_NEAREST)
    valid = cv2.erode(valid, np.ones((7, 7), np.uint8))
    top, bottom, left, right = 0, H - 1, 0, W - 1
    while valid[top:bottom + 1, left:right + 1].min() == 0:
        sub = valid[top:bottom + 1, left:right + 1]
        bad = {"t": (sub[0] == 0).mean(), "b": (sub[-1] == 0).mean(), "l": (sub[:, 0] == 0).mean(), "r": (sub[:, -1] == 0).mean()}
        side = max(bad, key=bad.get)
        top, bottom, left, right = top + (side == "t"), bottom - (side == "b"), left + (side == "l"), right - (side == "r")
    w, h = (right - left + 1) // 2 * 2, (bottom - top + 1) // 2 * 2

    fixed = [cv2.warpPerspective(f, t, (W, H), flags=cv2.INTER_LANCZOS4)[top:top + h, left:left + w]
             for f, t in zip(frames, transforms)]
    n = len(fixed)
    loop = [cv2.addWeighted(fixed[n - FADE + i], 1 - i / FADE, fixed[i], i / FADE, 0) if i < FADE else fixed[i]
            for i in range(n - FADE)]
    sea = [f[SEA_TOP:] for f in loop]
    encode(sea, os.path.join(ASSETS, "video", "rock_jetty.mp4"), crf=25)

    # Water is blue and moves; rock is grey and, once stabilised, still. Keep what connects to the open sea.
    sample = np.stack(sea[::3]).astype(np.float32)
    mean = sample.mean(0)
    motion = sample.mean(3).std(0)
    water = np.maximum(np.clip((mean[..., 0] - mean[..., 2] - 20) / 30, 0, 1), np.clip((motion - 16) / 10, 0, 1))
    _, labels = cv2.connectedComponents((water > .5).astype(np.uint8))
    open_sea = np.isin(labels, list(set(np.unique(labels[:60])) - {0})).astype(np.float32)
    open_sea = cv2.morphologyEx(open_sea, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
    cv2.imwrite(os.path.join(ASSETS, "textures", "rock_jetty_sea.png"),
                (cv2.GaussianBlur(open_sea, (0, 0), 1.2) * 255).astype(np.uint8))


if __name__ == "__main__":
    main(sys.argv[1])
