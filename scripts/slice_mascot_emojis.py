"""Slice the 3x3 mascot sticker sheet into 9 circle-safe emoji PNGs.

The source is a 1024x1024 image arranged as a 3x3 grid of chibi-style stickers
with a Chinese label ribbon under each tile. This script:

  1. grid-cuts the sheet into nine cells;
  2. drops the bottom label ribbon from each cell;
  3. flood-fills the beige paper background from seeds densely sampled along
     every cell edge (so decorative corner marks cannot block the fill);
  4. runs a warm-cream chroma-key pass to clear small beige "islands" that
     the flood fill cannot reach (e.g. suit symbols surrounded by border lines);
  5. keeps the largest connected opaque region (the sticker) plus any smaller
     components whose bounding box sits near the main sticker (sparkles, fire,
     speech bubbles, floating hearts), and drops sheet-border decorations that
     sit outside the sticker's bounding box;
  6. tightly trims to the surviving alpha bounding box; and
  7. pads the sticker into a transparent square whose side is at least the
     bounding-box diagonal, so the sticker always fits inside the inscribed
     circle of the square. This guarantees it is not clipped when rendered
     inside the 45px circular emoji bubble (`border-radius: 50%`).

Usage (run from the repo root or anywhere):

    python scripts/slice_mascot_emojis.py
    python scripts/slice_mascot_emojis.py --src path/to/sheet.png --out path/to/out_dir
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import label


REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SRC = REPO_ROOT / "assets" / "mascot-sheet.png"
DEFAULT_OUT = REPO_ROOT / "poker-frontend" / "public" / "images" / "emojis"

# Order matches the 3x3 grid (left to right, top to bottom).
SLUGS = [
    "mascot_01_xiao",
    "mascot_02_kaixin",
    "mascot_03_wuyu",
    "mascot_04_shengqi",
    "mascot_05_yun",
    "mascot_06_exin",
    "mascot_07_haixiu",
    "mascot_08_shiai",
    "mascot_09_deyi",
]

# Keep the top 78% of each grid cell; bottom 22% is the Chinese label ribbon.
LABEL_CROP_RATIO = 0.78

# Flood-fill tolerance against the beige/cream background. Large enough to
# absorb the subtle shading gradient but small enough that the sticker's white
# outline stops the fill.
FLOOD_TOLERANCE = 42

# Safety multiplier applied to the bounding-box diagonal when computing the
# square canvas side. 1.0 would inscribe the bbox corners on the circle; a bit
# of headroom (1.06) keeps the character visually comfortable inside the bubble.
CIRCLE_SAFETY = 1.06

# Chroma-key residual pass: any still-opaque pixel that is clearly warm-cream
# (R > G > B with noticeable gaps) gets cleared. This catches small beige
# "islands" enclosed by decorative border lines that the edge-seeded flood
# fill cannot reach. The yellow-tint checks intentionally spare pure white and
# any neutral/white highlight on the character.
CREAM_MIN_R = 215
CREAM_MIN_G = 205
CREAM_MIN_B = 185
CREAM_RG_MIN = 2
CREAM_GB_MIN = 3
CREAM_SUM_MIN = 8


def flood_fill_background(tile: Image.Image) -> Image.Image:
    """Replace the beige background around the sticker with transparent pixels.

    Seeds are sampled densely along all four edges so that decorative corner
    marks (hearts, spades, etc. near the sheet border) cannot block the fill
    from reaching the rest of the background.
    """
    rgba = tile.convert("RGBA")
    work = rgba.convert("RGB")

    # Sentinel color that never appears in the source sheet.
    marker = (1, 254, 2)
    w, h = work.size

    seeds: list[tuple[int, int]] = []
    step = 4
    for x in range(0, w, step):
        seeds.append((x, 0))
        seeds.append((x, h - 1))
    for y in range(0, h, step):
        seeds.append((0, y))
        seeds.append((w - 1, y))

    src_pixels = work.load()
    for seed in seeds:
        if src_pixels[seed[0], seed[1]] == marker:
            # Already filled from a neighbouring seed.
            continue
        try:
            ImageDraw.floodfill(work, seed, marker, thresh=FLOOD_TOLERANCE)
        except Exception:
            continue

    out = rgba.copy()
    dst_pixels = out.load()
    for y in range(h):
        for x in range(w):
            if src_pixels[x, y] == marker:
                dst_pixels[x, y] = (0, 0, 0, 0)
    return out


def clear_cream_residuals(img: Image.Image) -> Image.Image:
    """Zero out any pixels still tinted warm-cream after the main flood fill."""
    pixels = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            if r < CREAM_MIN_R or g < CREAM_MIN_G or b < CREAM_MIN_B:
                continue
            if r <= g or g <= b:
                continue
            diff_rg = r - g
            diff_gb = g - b
            if (
                diff_rg >= CREAM_RG_MIN
                and diff_gb >= CREAM_GB_MIN
                and diff_rg + diff_gb >= CREAM_SUM_MIN
            ):
                pixels[x, y] = (0, 0, 0, 0)
    return img


def filter_decoration_components(
    img: Image.Image,
    min_accessory_size: int = 25,
    accessory_margin: int = 15,
) -> Image.Image:
    """Keep the sticker plus any legitimate floating accessories.

    Rules:
      - Always keep the single largest opaque component (the sticker itself).
      - Keep every other component only if (a) it has at least
        ``min_accessory_size`` pixels and (b) its bounding box sits inside
        the main sticker's bounding box expanded by ``accessory_margin``.

    This retains sparkles, fire marks, speech bubbles and similar accessories
    that float around the character, while removing sheet-border decorations
    (suit symbols, divider lines) which sit along the paper margins outside
    the sticker's bounding box.
    """
    arr = np.array(img)
    if arr.ndim != 3 or arr.shape[2] != 4:
        return img
    alpha = arr[:, :, 3]
    mask = alpha > 0
    if not mask.any():
        return img
    labels, n = label(mask, structure=np.ones((3, 3), dtype=int))
    if n == 0:
        return img
    sizes = np.bincount(labels.ravel())
    sizes[0] = 0
    largest = int(sizes.argmax())

    ys_main, xs_main = np.where(labels == largest)
    main_x0, main_y0 = int(xs_main.min()), int(ys_main.min())
    main_x1, main_y1 = int(xs_main.max()), int(ys_main.max())
    keep_x0 = main_x0 - accessory_margin
    keep_y0 = main_y0 - accessory_margin
    keep_x1 = main_x1 + accessory_margin
    keep_y1 = main_y1 + accessory_margin

    keep_mask = labels == largest
    for lbl in range(1, n + 1):
        if lbl == largest:
            continue
        if sizes[lbl] < min_accessory_size:
            continue
        ys, xs = np.where(labels == lbl)
        bx0, by0 = int(xs.min()), int(ys.min())
        bx1, by1 = int(xs.max()), int(ys.max())
        if bx0 < keep_x0 or by0 < keep_y0 or bx1 > keep_x1 or by1 > keep_y1:
            continue
        keep_mask |= labels == lbl

    arr[:, :, 3] = np.where(keep_mask, arr[:, :, 3], 0)
    return Image.fromarray(arr, mode="RGBA")


def tight_trim(img: Image.Image) -> Image.Image:
    bbox = img.getbbox()
    if bbox is None:
        return img
    return img.crop(bbox)


def pad_to_circle_safe_square(sticker: Image.Image) -> Image.Image:
    """Center the sticker inside a transparent square whose side is at least
    the bounding-box diagonal. Ensures the sticker fits inside the inscribed
    circle of the square when rendered under `border-radius: 50%`."""
    w, h = sticker.size
    diagonal = math.sqrt(w * w + h * h)
    side = math.ceil(diagonal * CIRCLE_SAFETY)
    if side % 2:
        side += 1
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    offset = ((side - w) // 2, (side - h) // 2)
    canvas.paste(sticker, offset, sticker)
    return canvas


def slice_sheet(src: Path, out: Path) -> list[Path]:
    sheet = Image.open(src).convert("RGBA")
    sheet_w, sheet_h = sheet.size
    cell_w = sheet_w // 3
    cell_h = sheet_h // 3

    out.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for idx, slug in enumerate(SLUGS):
        r, c = divmod(idx, 3)
        left = c * cell_w
        top = r * cell_h
        cell = sheet.crop((left, top, left + cell_w, top + cell_h))

        # Drop the bottom ribbon (Chinese label).
        keep_h = int(cell_h * LABEL_CROP_RATIO)
        cell = cell.crop((0, 0, cell_w, keep_h))

        cell = flood_fill_background(cell)
        cell = clear_cream_residuals(cell)
        cell = filter_decoration_components(cell)
        cell = tight_trim(cell)
        cell = pad_to_circle_safe_square(cell)

        dst = out / f"{slug}.png"
        cell.save(dst, format="PNG", optimize=True)
        written.append(dst)
        print(f"wrote {dst.relative_to(REPO_ROOT)} ({cell.size[0]}x{cell.size[1]})")
    return written


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", type=Path, default=DEFAULT_SRC)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    src = args.src.resolve()
    if not src.exists():
        raise SystemExit(f"Source sheet not found: {src}")
    slice_sheet(src, args.out.resolve())


if __name__ == "__main__":
    main()
