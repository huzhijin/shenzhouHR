#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def page_number(path: Path) -> int:
    match = re.search(r"(\d+)$", path.stem)
    if not match:
        raise ValueError(f"Cannot parse page number from {path.name}")
    return int(match.group(1))


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: make_contact_sheets.py <render_dir> <output_dir>")
    render_dir = Path(sys.argv[1])
    output_dir = Path(sys.argv[2])
    output_dir.mkdir(parents=True, exist_ok=True)
    pages = sorted(render_dir.glob("page-*.png"), key=page_number)
    if not pages:
        raise SystemExit(f"No page PNGs found in {render_dir}")

    font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 28)
    gutter = 24
    label_height = 48
    for start in range(0, len(pages), 4):
        group = pages[start:start + 4]
        opened = [Image.open(path).convert("RGB") for path in group]
        page_width = max(image.width for image in opened)
        page_height = max(image.height for image in opened)
        sheet = Image.new(
            "RGB",
            (page_width * 2 + gutter * 3, (page_height + label_height) * 2 + gutter * 3),
            "#D8DEE8",
        )
        draw = ImageDraw.Draw(sheet)
        for index, (path, image) in enumerate(zip(group, opened)):
            row, col = divmod(index, 2)
            x = gutter + col * (page_width + gutter)
            y = gutter + row * (page_height + label_height + gutter)
            draw.text((x, y + 6), f"Page {page_number(path)}", font=font, fill="#17365D")
            sheet.paste(image, (x, y + label_height))
        first = page_number(group[0])
        last = page_number(group[-1])
        sheet.save(output_dir / f"pages-{first:02d}-{last:02d}.png", quality=95)


if __name__ == "__main__":
    main()
