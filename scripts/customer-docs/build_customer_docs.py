#!/usr/bin/env python3
"""Build polished DOCX files from the reviewed customer-delivery Markdown.

Dependencies are intentionally external to the repository:
  uv run --with python-docx --with cairosvg python scripts/customer-docs/build_customer_docs.py
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_ROW_HEIGHT_RULE, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Inches, Pt, RGBColor, Twips


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "outputs" / "customer-docs"
QA = ROOT / ".docx-qa" / "customer-docs"
LOGO_SVG = ROOT / "frontend" / "src" / "assets" / "shenzhou-logo.svg"
LOGO_PNG = QA / "shenzhou-logo.svg.png"

BLUE = "25449A"
BLUE_DARK = "17356F"
BLUE_LIGHT = "EAF0FB"
RED = "E60012"
TEAL = "0F6F73"
GRAY_900 = "20242C"
GRAY_700 = "4D5562"
GRAY_500 = "727B87"
GRAY_300 = "CDD3DB"
GRAY_100 = "F4F6F8"
AMBER_LIGHT = "FFF4D6"
WHITE = "FFFFFF"


@dataclass(frozen=True)
class DocSpec:
    source: Path
    output: Path
    cover_title: str
    subtitle: str
    edition: str
    audience: str
    short_title: str


SPECS = (
    DocSpec(
        ROOT / "docs" / "customer-delivery" / "神州HR客户操作手册.md",
        OUT / "神州HR客户操作手册.docx",
        "神州 HR 考勤核算与对账系统",
        "客户操作手册",
        "客户使用版 · 代码核对版 · 2026-08-12",
        "面向系统管理员、HR、高管、部门负责人、员工和审计人员",
        "客户操作手册",
    ),
    DocSpec(
        ROOT / "docs" / "customer-delivery" / "神州HR考勤与OA数据取数及计算逻辑说明.md",
        OUT / "神州HR考勤与OA数据取数及计算逻辑说明.docx",
        "神州 HR 考勤核算与对账系统",
        "考勤与 OA 数据取数及计算逻辑说明",
        "技术与验收版 · 代码核对版 · 2026-08-12",
        "面向实施、研发、运维、HR 验收和审计人员",
        "考勤与 OA 逻辑说明",
    ),
)


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=80, start=90, bottom=80, end=90) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for tag, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{tag}"))
        if node is None:
            node = OxmlElement(f"w:{tag}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_repeat_table_header(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = tr_pr.find(qn("w:tblHeader"))
    if tbl_header is None:
        tbl_header = OxmlElement("w:tblHeader")
        tr_pr.append(tbl_header)
    tbl_header.set(qn("w:val"), "true")


def prevent_row_split(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    cant_split = OxmlElement("w:cantSplit")
    tr_pr.append(cant_split)


def set_cell_width(cell, twips: int) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(twips))
    tc_w.set(qn("w:type"), "dxa")


def set_picture_alt(inline_shape, alt_text: str) -> None:
    """Add meaningful alternative text to an inline image."""

    doc_pr = inline_shape._inline.docPr
    doc_pr.set("descr", alt_text)
    doc_pr.set("title", alt_text)


def allocate_column_widths(
    weights: Iterable[float], total_twips: int = 9880, minimum_twips: int = 620
) -> list[int]:
    """Allocate positive integer widths whose sum is exactly total_twips."""

    normalized = [max(float(weight), 0.01) for weight in weights]
    if not normalized:
        raise ValueError("weights must not be empty")
    if minimum_twips * len(normalized) >= total_twips:
        minimum_twips = max(1, total_twips // (len(normalized) * 2))
    remaining = total_twips - minimum_twips * len(normalized)
    total_weight = sum(normalized)
    widths = [
        minimum_twips + int(round(remaining * weight / total_weight))
        for weight in normalized
    ]
    widths[-1] += total_twips - sum(widths)
    return widths


def apply_table_geometry(
    table,
    column_widths: Iterable[int],
    *,
    indent_twips: int,
    margins: dict[str, int],
) -> None:
    """Synchronize tblW, tblInd, tblGrid, and every tcW for stable rendering."""

    widths = [int(width) for width in column_widths]
    if len(widths) != len(table.columns) or any(width <= 0 for width in widths):
        raise ValueError(f"invalid table widths: {widths}")
    total_twips = sum(widths)

    tbl_pr = table._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:type"), "dxa")
    tbl_w.set(qn("w:w"), str(total_twips))

    tbl_ind = tbl_pr.find(qn("w:tblInd"))
    if tbl_ind is None:
        tbl_ind = OxmlElement("w:tblInd")
        tbl_pr.append(tbl_ind)
    tbl_ind.set(qn("w:type"), "dxa")
    tbl_ind.set(qn("w:w"), str(indent_twips))

    layout = tbl_pr.find(qn("w:tblLayout"))
    if layout is None:
        layout = OxmlElement("w:tblLayout")
        tbl_pr.append(layout)
    layout.set(qn("w:type"), "fixed")

    grid = table._tbl.tblGrid
    for child in list(grid):
        grid.remove(child)
    for width in widths:
        grid_col = OxmlElement("w:gridCol")
        grid_col.set(qn("w:w"), str(width))
        grid.append(grid_col)

    for col_index, width in enumerate(widths):
        table.columns[col_index].width = Twips(width)
    for row in table.rows:
        if len(row.cells) != len(widths):
            raise ValueError("merged table rows are not supported")
        for col_index, cell in enumerate(row.cells):
            cell.width = Twips(widths[col_index])
            set_cell_width(cell, widths[col_index])
            set_cell_margins(cell, **margins)


def set_font(run, size: float | None = None, bold: bool | None = None,
             color: str | None = None, mono: bool = False) -> None:
    name = "Consolas" if mono else "Calibri"
    east = "等线" if not mono else "Microsoft YaHei"
    run.font.name = name
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), east)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color:
        run.font.color.rgb = RGBColor.from_string(color)


def style_paragraph(paragraph, before=0, after=5, line=1.12, keep=False) -> None:
    fmt = paragraph.paragraph_format
    fmt.space_before = Pt(before)
    fmt.space_after = Pt(after)
    fmt.line_spacing_rule = WD_LINE_SPACING.SINGLE
    fmt.line_spacing = line
    fmt.keep_with_next = keep
    fmt.widow_control = True


def add_page_field(paragraph) -> None:
    run = paragraph.add_run("第 ")
    set_font(run, 8, color=GRAY_500)
    fld_char1 = OxmlElement("w:fldChar")
    fld_char1.set(qn("w:fldCharType"), "begin")
    instr_text = OxmlElement("w:instrText")
    instr_text.set(qn("xml:space"), "preserve")
    instr_text.text = " PAGE "
    fld_char2 = OxmlElement("w:fldChar")
    fld_char2.set(qn("w:fldCharType"), "end")
    run._r.append(fld_char1)
    run._r.append(instr_text)
    run._r.append(fld_char2)
    run2 = paragraph.add_run(" 页")
    set_font(run2, 8, color=GRAY_500)


def add_bottom_border(paragraph, color=GRAY_300, size="6") -> None:
    p_pr = paragraph._p.get_or_add_pPr()
    p_bdr = p_pr.find(qn("w:pBdr"))
    if p_bdr is None:
        p_bdr = OxmlElement("w:pBdr")
        p_pr.append(p_bdr)
    bottom = OxmlElement("w:bottom")
    bottom.set(qn("w:val"), "single")
    bottom.set(qn("w:sz"), size)
    bottom.set(qn("w:space"), "3")
    bottom.set(qn("w:color"), color)
    p_bdr.append(bottom)


def configure_styles(doc: Document) -> None:
    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = "Calibri"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "等线")
    normal.font.size = Pt(10.5)
    normal.font.color.rgb = RGBColor.from_string(GRAY_900)
    normal.paragraph_format.space_after = Pt(5)
    normal.paragraph_format.line_spacing = 1.12

    for style_name, size, color, before, after in (
        ("Heading 1", 16, BLUE_DARK, 15, 7),
        ("Heading 2", 13, BLUE, 12, 5),
        ("Heading 3", 11.5, TEAL, 9, 4),
        ("Heading 4", 10.5, GRAY_900, 7, 3),
    ):
        style = styles[style_name]
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "等线")
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string(color)
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True
        style.paragraph_format.keep_together = True

    if "Code Block" not in styles:
        style = styles.add_style("Code Block", WD_STYLE_TYPE.PARAGRAPH)
        style.font.name = "Consolas"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(8.5)
        style.font.color.rgb = RGBColor.from_string(GRAY_900)
        style.paragraph_format.left_indent = Cm(0.35)
        style.paragraph_format.right_indent = Cm(0.2)
        style.paragraph_format.space_before = Pt(3)
        style.paragraph_format.space_after = Pt(6)

    if "Callout" not in styles:
        style = styles.add_style("Callout", WD_STYLE_TYPE.PARAGRAPH)
        style.font.name = "Calibri"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "等线")
        style.font.size = Pt(9.5)
        style.font.color.rgb = RGBColor.from_string(BLUE_DARK)
        style.paragraph_format.left_indent = Cm(0.35)
        style.paragraph_format.right_indent = Cm(0.2)
        style.paragraph_format.space_before = Pt(3)
        style.paragraph_format.space_after = Pt(4)


def configure_section(doc: Document, short_title: str) -> None:
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = Inches(0.78)
    section.bottom_margin = Inches(0.75)
    section.left_margin = Inches(0.82)
    section.right_margin = Inches(0.82)
    section.header_distance = Inches(0.3)
    section.footer_distance = Inches(0.3)
    section.different_first_page_header_footer = True

    header = section.header
    table = header.add_table(rows=1, cols=2, width=Inches(6.86))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    p_left = table.cell(0, 0).paragraphs[0]
    p_left.alignment = WD_ALIGN_PARAGRAPH.LEFT
    try:
        logo = p_left.add_run().add_picture(str(LOGO_PNG), width=Inches(1.55))
        set_picture_alt(logo, "神州半导体标识")
    except Exception:
        r = p_left.add_run("神州半导体")
        set_font(r, 9, True, BLUE)
    p_right = table.cell(0, 1).paragraphs[0]
    p_right.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    r = p_right.add_run(short_title)
    set_font(r, 8, True, GRAY_500)
    for cell in table.rows[0].cells:
        set_cell_margins(cell, top=0, bottom=0, start=0, end=0)
    set_repeat_table_header(table.rows[0])
    apply_table_geometry(
        table,
        [6200, 3680],
        indent_twips=0,
        margins={"top": 0, "bottom": 0, "start": 0, "end": 0},
    )
    add_bottom_border(p_left, BLUE, "8")
    add_bottom_border(p_right, BLUE, "8")

    footer = section.footer
    p = footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    r = p.add_run("神州 HR · 2026-08-12  ·  ")
    set_font(r, 8, color=GRAY_500)
    add_page_field(p)


INLINE_RE = re.compile(r"(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\))")


def add_inline(paragraph, text: str, size: float | None = None,
               color: str | None = None) -> None:
    position = 0
    for match in INLINE_RE.finditer(text):
        if match.start() > position:
            r = paragraph.add_run(text[position:match.start()])
            set_font(r, size=size, color=color)
        token = match.group(0)
        if token.startswith("**"):
            r = paragraph.add_run(token[2:-2])
            set_font(r, size=size, bold=True, color=color or BLUE_DARK)
        elif token.startswith("`"):
            r = paragraph.add_run(token[1:-1])
            set_font(r, size=(size or 10.5) - 0.5, color=TEAL, mono=True)
            r._r.get_or_add_rPr().append(_shading(BLUE_LIGHT))
        else:
            label = re.match(r"\[([^\]]+)\]", token).group(1)
            r = paragraph.add_run(label)
            set_font(r, size=size, color=BLUE)
            r.underline = True
        position = match.end()
    if position < len(text):
        r = paragraph.add_run(text[position:])
        set_font(r, size=size, color=color)


def _shading(fill: str):
    node = OxmlElement("w:shd")
    node.set(qn("w:fill"), fill)
    return node


def add_callout(doc: Document, text: str, warning: bool = False) -> None:
    table = doc.add_table(rows=1, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_cell_shading(table.cell(0, 0), RED if warning else BLUE)
    set_cell_shading(table.cell(0, 1), AMBER_LIGHT if warning else BLUE_LIGHT)
    table.cell(0, 0).text = ""
    p = table.cell(0, 1).paragraphs[0]
    p.style = doc.styles["Callout"]
    add_inline(p, text, size=9.5, color=GRAY_900)
    for cell in table.rows[0].cells:
        set_cell_margins(cell, top=75, bottom=75, start=90, end=90)
        cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
    set_repeat_table_header(table.rows[0])
    apply_table_geometry(
        table,
        [150, 9730],
        indent_twips=90,
        margins={"top": 75, "bottom": 75, "start": 90, "end": 90},
    )
    prevent_row_split(table.rows[0])
    spacer = doc.add_paragraph()
    spacer.paragraph_format.space_after = Pt(1)


def table_weights(rows: list[list[str]]) -> list[float]:
    cols = len(rows[0])
    lengths = []
    for index in range(cols):
        samples = [len(re.sub(r"[`*]", "", row[index])) for row in rows[:18] if index < len(row)]
        value = max(5, min(38, max(samples, default=8)))
        lengths.append(float(value))
    total = sum(lengths)
    return [value / total for value in lengths]


def add_markdown_table(doc: Document, rows: list[list[str]]) -> None:
    if not rows:
        return
    cols = max(len(row) for row in rows)
    rows = [row + [""] * (cols - len(row)) for row in rows]
    table = doc.add_table(rows=len(rows), cols=cols)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    table.style = "Table Grid"
    widths = allocate_column_widths(table_weights(rows), total_twips=9880, minimum_twips=620)
    total_twips = 9880
    font_size = 8.3 if cols <= 4 else 7.4 if cols == 5 else 6.8

    for row_index, row_values in enumerate(rows):
        row = table.rows[row_index]
        row.height_rule = WD_ROW_HEIGHT_RULE.AT_LEAST
        if row_index == 0:
            set_repeat_table_header(row)
        for col_index, value in enumerate(row_values):
            cell = row.cells[col_index]
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            set_cell_margins(cell, top=70, bottom=70, start=70, end=70)
            set_cell_shading(cell, BLUE if row_index == 0 else (GRAY_100 if row_index % 2 == 0 else WHITE))
            p = cell.paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            p.paragraph_format.space_before = Pt(0)
            p.paragraph_format.space_after = Pt(0)
            p.paragraph_format.line_spacing = 1.0
            add_inline(p, value, size=font_size, color=WHITE if row_index == 0 else GRAY_900)
            if row_index == 0:
                for run in p.runs:
                    run.bold = True
    apply_table_geometry(
        table,
        widths,
        indent_twips=70,
        margins={"top": 70, "bottom": 70, "start": 70, "end": 70},
    )
    spacer = doc.add_paragraph()
    spacer.paragraph_format.space_after = Pt(1)


def add_heading(doc: Document, level: int, text: str) -> None:
    style_level = min(max(level - 1, 1), 4)
    p = doc.add_paragraph(style=f"Heading {style_level}")
    add_inline(p, text)
    if style_level == 1:
        add_bottom_border(p, BLUE_LIGHT, "12")


def add_code_block(doc: Document, lines: Iterable[str]) -> None:
    p = doc.add_paragraph(style="Code Block")
    p.paragraph_format.keep_together = False
    p._p.get_or_add_pPr().append(_shading(GRAY_100))
    r = p.add_run("\n".join(lines))
    set_font(r, 8.5, color=GRAY_900, mono=True)


def parse_table(lines: list[str], start: int) -> tuple[list[list[str]], int]:
    rows = []
    i = start
    while i < len(lines) and lines[i].strip().startswith("|"):
        row = [cell.strip() for cell in lines[i].strip().strip("|").split("|")]
        if not all(re.fullmatch(r":?-{3,}:?", cell.replace(" ", "")) for cell in row):
            rows.append(row)
        i += 1
    return rows, i


def render_markdown_body(doc: Document, markdown: str) -> list[str]:
    lines = markdown.splitlines()
    headings = [re.sub(r"^##\s+", "", line).strip() for line in lines if line.startswith("## ")]
    i = 0
    in_code = False
    code_lines: list[str] = []
    paragraph_buffer: list[str] = []

    def flush_paragraph() -> None:
        nonlocal paragraph_buffer
        if not paragraph_buffer:
            return
        text = " ".join(item.strip() for item in paragraph_buffer).strip()
        if text:
            p = doc.add_paragraph()
            p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
            style_paragraph(p)
            add_inline(p, text)
        paragraph_buffer = []

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("```"):
            flush_paragraph()
            if in_code:
                add_code_block(doc, code_lines)
                code_lines = []
                in_code = False
            else:
                in_code = True
            i += 1
            continue
        if in_code:
            code_lines.append(line)
            i += 1
            continue
        if not stripped:
            flush_paragraph()
            i += 1
            continue
        if stripped == "---":
            flush_paragraph()
            i += 1
            continue
        if line.startswith("# "):
            flush_paragraph()
            i += 1
            continue
        heading = re.match(r"^(#{2,5})\s+(.+)$", line)
        if heading:
            flush_paragraph()
            add_heading(doc, len(heading.group(1)), heading.group(2).strip())
            i += 1
            continue
        if stripped.startswith("|") and i + 1 < len(lines) and re.match(r"^\s*\|?\s*:?-{3,}", lines[i + 1]):
            flush_paragraph()
            table_rows, i = parse_table(lines, i)
            add_markdown_table(doc, table_rows)
            continue
        if stripped.startswith(">"):
            flush_paragraph()
            quote_lines = []
            while i < len(lines) and lines[i].strip().startswith(">"):
                quote_lines.append(lines[i].strip()[1:].strip())
                i += 1
            quote = " ".join(quote_lines)
            add_callout(doc, quote, warning=any(word in quote for word in ("重要", "风险", "未", "不能", "警告")))
            continue
        bullet = re.match(r"^\s*[-*]\s+(.*)$", line)
        numbered = re.match(r"^\s*(\d+)\.\s+(.*)$", line)
        if bullet or numbered:
            flush_paragraph()
            content = (bullet or numbered).group(1 if bullet else 2)
            if content.startswith("[ ] "):
                content = "☐ " + content[4:]
            elif content.startswith("[x] ") or content.startswith("[X] "):
                content = "☑ " + content[4:]
            prefix = "•" if bullet else f"{numbered.group(1)}."
            p = doc.add_paragraph()
            style_paragraph(p, after=3)
            p.paragraph_format.left_indent = Cm(0.62)
            p.paragraph_format.first_line_indent = Cm(-0.38)
            r = p.add_run(prefix + " ")
            set_font(r, 10, True, BLUE)
            add_inline(p, content)
            i += 1
            continue
        paragraph_buffer.append(line)
        i += 1
    flush_paragraph()
    if code_lines:
        add_code_block(doc, code_lines)
    return headings


def add_cover(doc: Document, spec: DocSpec) -> None:
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    p.paragraph_format.space_after = Pt(52)
    try:
        logo = p.add_run().add_picture(str(LOGO_PNG), width=Inches(2.35))
        set_picture_alt(logo, "神州半导体标识")
    except Exception:
        r = p.add_run("神州半导体")
        set_font(r, 18, True, BLUE)

    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(8)
    r = p.add_run(spec.cover_title)
    set_font(r, 16, True, BLUE)

    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(15)
    r = p.add_run(spec.subtitle)
    set_font(r, 27, True, BLUE_DARK)

    p = doc.add_paragraph()
    add_bottom_border(p, RED, "22")
    p.paragraph_format.space_after = Pt(22)

    card = doc.add_table(rows=3, cols=2)
    card.alignment = WD_TABLE_ALIGNMENT.LEFT
    card.autofit = False
    labels = (("版本", spec.edition), ("适用", spec.audience), ("口径", "以当前可执行代码为准；未接线能力已单独标注"))
    for idx, (label, value) in enumerate(labels):
        set_cell_shading(card.cell(idx, 0), BLUE)
        set_cell_shading(card.cell(idx, 1), BLUE_LIGHT if idx % 2 == 0 else GRAY_100)
        for cell in card.rows[idx].cells:
            set_cell_margins(cell, top=105, bottom=105, start=120, end=120)
            prevent_row_split(card.rows[idx])
        p1 = card.cell(idx, 0).paragraphs[0]
        r1 = p1.add_run(label)
        set_font(r1, 9, True, WHITE)
        p2 = card.cell(idx, 1).paragraphs[0]
        r2 = p2.add_run(value)
        set_font(r2, 9, idx == 0, GRAY_900)
    set_repeat_table_header(card.rows[0])
    apply_table_geometry(
        card,
        [1300, 8580],
        indent_twips=120,
        margins={"top": 105, "bottom": 105, "start": 120, "end": 120},
    )

    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(52)
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    r = p.add_run("客户内部使用")
    set_font(r, 9, True, RED)
    r2 = p.add_run("  ·  请结合上线验收结论使用")
    set_font(r2, 9, color=GRAY_500)
    doc.add_page_break()


def extract_h2(markdown: str) -> list[str]:
    return [line[3:].strip() for line in markdown.splitlines() if line.startswith("## ")]


def add_contents(doc: Document, headings: list[str]) -> None:
    p = doc.add_paragraph(style="Heading 1")
    r = p.add_run("目录")
    set_font(r, 20, True, BLUE_DARK)
    add_bottom_border(p, BLUE, "12")
    p2 = doc.add_paragraph()
    style_paragraph(p2, after=12)
    r2 = p2.add_run("一级章节导航 · 页码以 Word 打开后的实际分页为准")
    set_font(r2, 9, color=GRAY_500)

    columns = 2 if len(headings) > 10 else 1
    rows = (len(headings) + columns - 1) // columns
    table = doc.add_table(rows=rows, cols=columns)
    table.autofit = False
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for row in table.rows:
        prevent_row_split(row)
        for cell in row.cells:
            set_cell_margins(cell, top=45, bottom=45, start=40, end=110)
    for index, heading in enumerate(headings):
        col = index // rows
        row = index % rows
        p = table.cell(row, col).paragraphs[0]
        p.paragraph_format.space_after = Pt(2)
        match = re.match(r"(\d+(?:\.\d+)*)\.??\s*(.*)", heading)
        number = match.group(1) if match else str(index + 1)
        label = match.group(2) if match else heading
        r = p.add_run(f"{number:>2}  ")
        set_font(r, 9, True, RED)
        r = p.add_run(label)
        set_font(r, 9, True, BLUE_DARK)
    set_repeat_table_header(table.rows[0])
    apply_table_geometry(
        table,
        [4940, 4940] if columns == 2 else [9880],
        indent_twips=40,
        margins={"top": 45, "bottom": 45, "start": 40, "end": 110},
    )
    doc.add_page_break()


def build(spec: DocSpec) -> None:
    markdown = spec.source.read_text(encoding="utf-8")
    doc = Document()
    configure_styles(doc)
    configure_section(doc, spec.short_title)
    doc.core_properties.title = spec.subtitle
    doc.core_properties.subject = "神州 HR 客户交付文档"
    doc.core_properties.author = "神州 HR 项目组"
    doc.core_properties.keywords = "神州HR,考勤,OA,操作手册,数据逻辑"
    doc.core_properties.comments = "依据 2026-08-12 当前代码生成并经渲染检查"

    add_cover(doc, spec)
    add_contents(doc, extract_h2(markdown))
    render_markdown_body(doc, markdown)
    spec.output.parent.mkdir(parents=True, exist_ok=True)
    doc.save(spec.output)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    QA.mkdir(parents=True, exist_ok=True)
    if not LOGO_PNG.exists():
        subprocess.run(
            ["qlmanage", "-t", "-s", "1200", "-o", str(QA), str(LOGO_SVG)],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    for spec in SPECS:
        build(spec)
        print(spec.output)


if __name__ == "__main__":
    main()
