#!/usr/bin/env python3
"""Generate placeholder PNGs for the Causa + Story tutorials (rf2-6e53j).

These are committed so the docs site builds cleanly even without re-running
the live Playwright capture pipeline (`generate-tutorial-screenshots.cjs`).

Running the Playwright pipeline overwrites these with the real captures.

Each placeholder paints:
  - The expected output filename in large text
  - The descriptive caption in smaller text
  - A coloured strip identifying causa vs story

The PIL/Pillow runtime ships with the standard MkDocs/CI Python environment.

Usage (from repo root):
    python docs/scripts/generate-placeholder-images.py
"""

from __future__ import annotations

import os
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_CAUSA = REPO_ROOT / "docs" / "images" / "causa"
OUT_STORY = REPO_ROOT / "docs" / "images" / "story"

# (tool, filename, caption)
PLACEHOLDERS = [
    ("causa", "01-floating-pill.png",   "The Causa floating pill on the live counter app"),
    ("causa", "02-shell-opened.png",    "The Causa shell, opened over the live app"),
    ("causa", "02-sidebar-panels.png",  "Sixteen panels in three bands"),
    ("causa", "02-event-detail.png",    "Event detail — the landing panel"),
    ("causa", "02-causality.png",       "Causality graph — the deeper walk"),
    ("causa", "03-time-travel.png",     "Time-travel scrubber with epoch history"),
    ("causa", "04-trace.png",           "Trace panel with live event count + filter bar"),
    ("causa", "05-dom-attribute.png",   "data-rf2-source-coord on every rendered element"),
    ("causa", "09-machines.png",        "Machine inspector with state-chart"),
    ("causa", "10-app-db-diff.png",     "App-DB diff for a cascade"),

    ("story", "01-shell-overview.png",  "The Story shell — sidebar / canvas / inspectors"),
    ("story", "01-variant-loaded.png",  "A registered variant rendered in the canvas"),
    ("story", "02-mode-tabs.png",       "Mode-tab strip on a variant canvas"),
    ("story", "02-docs-mode.png",       "Docs mode for a variant"),
    ("story", "03-test-mode.png",       "Test mode — every assertion in order"),
    ("story", "04-workspace-grid.png",  "A 2×2 workspace mounting four variants"),
]

SIZE = (1280, 800)
COLOURS = {
    "causa": ("#0d47a1", "#bbdefb"),  # blue band, light blue body
    "story": ("#1b5e20", "#c8e6c9"),  # green band, light green body
}


def find_font(size: int) -> ImageFont.FreeTypeFont:
    """Best-effort font lookup; falls back to the bundled default."""
    candidates = [
        "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def draw_placeholder(out: Path, caption: str, tool: str) -> None:
    band_colour, body_colour = COLOURS[tool]
    img = Image.new("RGB", SIZE, body_colour)
    draw = ImageDraw.Draw(img)

    # Top band
    draw.rectangle([0, 0, SIZE[0], 96], fill=band_colour)
    band_font = find_font(40)
    label = f"{tool.upper()}  ·  {out.name}"
    draw.text((48, 26), label, fill="#ffffff", font=band_font)

    # Body — caption + "screenshot placeholder" subtitle
    caption_font = find_font(36)
    draw.text((48, 160), caption, fill="#212121", font=caption_font)

    sub_font = find_font(22)
    draw.text(
        (48, 220),
        "Placeholder. Re-run docs/scripts/generate-tutorial-screenshots.cjs",
        fill="#424242",
        font=sub_font,
    )
    draw.text(
        (48, 252),
        "against a live dev build to replace with the real capture.",
        fill="#424242",
        font=sub_font,
    )

    # Centerpiece box mimicking the panel-shape
    draw.rectangle([200, 360, 1080, 700], outline=band_colour, width=4)
    centre_font = find_font(54)
    centre_text = caption.split(" — ")[0][:40]
    bbox = draw.textbbox((0, 0), centre_text, font=centre_font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    draw.text(
        ((SIZE[0] - text_w) / 2, (SIZE[1] - text_h) / 2 + 60),
        centre_text,
        fill=band_colour,
        font=centre_font,
    )

    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out, optimize=True)


def main() -> int:
    for tool, name, caption in PLACEHOLDERS:
        out = (OUT_CAUSA if tool == "causa" else OUT_STORY) / name
        draw_placeholder(out, caption, tool)
        print(f"OK  {out.relative_to(REPO_ROOT)}")
    print(f"\nGenerated {len(PLACEHOLDERS)} placeholders.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
