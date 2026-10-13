#!/usr/bin/env python3
"""Render a PDF to individual PNG pages and six-page QA contact sheets."""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import pymupdf
from PIL import Image, ImageDraw, ImageFont


def render(pdf_path: Path, output_dir: Path, scale: float = 1.25) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    pages_dir = output_dir / "pages"
    sheets_dir = output_dir / "contact-sheets"
    pages_dir.mkdir(parents=True, exist_ok=True)
    sheets_dir.mkdir(parents=True, exist_ok=True)

    document = pymupdf.open(pdf_path)
    page_images: list[tuple[str, Image.Image]] = []
    for page_index, page in enumerate(document, start=1):
        pixmap = page.get_pixmap(matrix=pymupdf.Matrix(scale, scale), alpha=False)
        image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
        filename = f"page-{page_index:03d}.png"
        image.save(pages_dir / filename, optimize=True)
        page_images.append((filename, image))

    if not page_images:
        raise ValueError(f"PDF contains no pages: {pdf_path}")

    columns = 2
    rows = 3
    group_size = columns * rows
    gap = 18
    label_height = 30
    page_width = max(image.width for _, image in page_images)
    page_height = max(image.height for _, image in page_images)
    cell_width = page_width
    cell_height = label_height + page_height
    sheet_width = columns * cell_width + (columns + 1) * gap
    sheet_height = rows * cell_height + (rows + 1) * gap
    font = ImageFont.load_default(size=18)

    for group_index in range(math.ceil(len(page_images) / group_size)):
        group = page_images[group_index * group_size : (group_index + 1) * group_size]
        sheet = Image.new("RGB", (sheet_width, sheet_height), "#D8DCE3")
        draw = ImageDraw.Draw(sheet)
        for slot, (filename, page_image) in enumerate(group):
            row, column = divmod(slot, columns)
            x = gap + column * (cell_width + gap)
            y = gap + row * (cell_height + gap)
            draw.rectangle((x, y, x + cell_width, y + cell_height), fill="white")
            draw.text((x + 8, y + 5), filename, fill="#20242C", font=font)
            sheet.paste(page_image, (x, y + label_height))
        sheet.save(sheets_dir / f"sheet-{group_index + 1:02d}.jpg", quality=88)

    print(
        f"rendered {len(page_images)} pages and "
        f"{math.ceil(len(page_images) / group_size)} contact sheets -> {output_dir}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pdf", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--scale", type=float, default=1.25)
    args = parser.parse_args()
    render(args.pdf, args.output_dir, args.scale)


if __name__ == "__main__":
    main()
