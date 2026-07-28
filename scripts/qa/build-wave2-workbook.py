#!/usr/bin/env python3
"""Fill a downloaded WAVE-2 template with deterministic synthetic rows."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from pathlib import PurePosixPath
from xml.etree import ElementTree
from zipfile import ZIP_DEFLATED, ZipFile


SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
MAX_TEMPLATE_SIZE_BYTES = 20 * 1024 * 1024
MAX_ROWS_JSON_SIZE_BYTES = 5 * 1024 * 1024
MAX_UNCOMPRESSED_WORKBOOK_BYTES = 80 * 1024 * 1024
MAX_WORKBOOK_ENTRIES = 2_048
MAX_ENTRY_COMPRESSION_RATIO = 200
MAX_PEOPLE_ROWS = 50_000
ElementTree.register_namespace("", SPREADSHEET_NS)


def spreadsheet_tag(local_name: str) -> str:
    return ElementTree.QName(SPREADSHEET_NS, local_name).text


def first_child(element: ElementTree.Element, local_name: str) -> ElementTree.Element | None:
    expected_tag = spreadsheet_tag(local_name)
    return next((child for child in element if child.tag == expected_tag), None)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--template", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--rows-json", required=True, type=Path)
    return parser.parse_args()


def main() -> None:
    arguments = parse_arguments()
    validate_input_file(arguments.template, MAX_TEMPLATE_SIZE_BYTES, ".xlsx")
    validate_input_file(arguments.rows_json, MAX_ROWS_JSON_SIZE_BYTES, ".json")
    rows = json.loads(arguments.rows_json.read_text(encoding="utf-8"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("rows-json must contain a non-empty array")
    validate_people_row_count(len(rows))

    with ZipFile(arguments.template, "r") as source:
        validate_archive_entries(source)
        entries = {info.filename: source.read(info.filename) for info in source.infolist()}
        entry_info = {info.filename: info for info in source.infolist()}
    required_entries = {
        "xl/workbook.xml",
        "xl/sharedStrings.xml",
        "xl/worksheets/sheet1.xml",
        "xl/worksheets/sheet2.xml",
    }
    if not required_entries.issubset(entries):
        raise ValueError("downloaded template is missing required workbook parts")

    workbook_root = ElementTree.fromstring(entries["xl/workbook.xml"])
    sheet_names = [
        sheet.attrib["name"]
        for sheet in workbook_root.iter(spreadsheet_tag("sheet"))
    ]
    if sheet_names != ["Data", "Field Instructions"]:
        raise ValueError("downloaded template has unexpected worksheets")

    shared_root = ElementTree.fromstring(entries["xl/sharedStrings.xml"])
    shared_values = [
        "".join(text.text or "" for text in item.iter(spreadsheet_tag("t")))
        for item in shared_root
        if item.tag == spreadsheet_tag("si")
    ]
    worksheet_root = ElementTree.fromstring(entries["xl/worksheets/sheet1.xml"])
    sheet_data = first_child(worksheet_root, "sheetData")
    if sheet_data is None:
        raise ValueError("downloaded template has no Data sheet body")
    header_row = first_child(sheet_data, "row")
    if header_row is None:
        raise ValueError("downloaded template has no Data header row")
    headers = []
    for cell in header_row:
        if cell.tag != spreadsheet_tag("c"):
            continue
        value = first_child(cell, "v")
        if cell.attrib.get("t") != "s" or value is None:
            raise ValueError("downloaded template has a non-text header")
        headers.append(shared_values[int(value.text)])
    if not headers or any(not isinstance(header, str) or not header for header in headers):
        raise ValueError("downloaded template has an invalid Data header row")

    allowed_headers = set(headers)
    for index, row in enumerate(rows, start=2):
        if not isinstance(row, dict):
            raise ValueError(f"row {index} is not an object")
        unexpected = set(row) - allowed_headers
        if unexpected:
            raise ValueError(f"row {index} has unknown columns: {sorted(unexpected)}")

    for existing in list(sheet_data)[1:]:
        sheet_data.remove(existing)
    for row_number, row in enumerate(rows, start=2):
        row_element = ElementTree.SubElement(
            sheet_data,
            spreadsheet_tag("row"),
            {"r": str(row_number)},
        )
        for column_index, header in enumerate(headers, start=1):
            value = row.get(header)
            if value is None or value == "":
                continue
            text = str(value)
            shared_index = len(shared_values)
            shared_values.append(text)
            string_item = ElementTree.SubElement(
                shared_root,
                spreadsheet_tag("si"),
            )
            ElementTree.SubElement(
                string_item,
                spreadsheet_tag("t"),
            ).text = text
            cell = ElementTree.SubElement(
                row_element,
                spreadsheet_tag("c"),
                {
                    "r": f"{excel_column(column_index)}{row_number}",
                    "t": "s",
                },
            )
            ElementTree.SubElement(
                cell,
                spreadsheet_tag("v"),
            ).text = str(shared_index)

    dimension = first_child(worksheet_root, "dimension")
    if dimension is not None:
        dimension.set("ref", f"A1:{excel_column(len(headers))}{len(rows) + 1}")
    shared_root.set("count", str(len(shared_values)))
    shared_root.set("uniqueCount", str(len(shared_values)))
    entries["xl/sharedStrings.xml"] = ElementTree.tostring(
        shared_root,
        encoding="utf-8",
        xml_declaration=True,
    )
    entries["xl/worksheets/sheet1.xml"] = ElementTree.tostring(
        worksheet_root,
        encoding="utf-8",
        xml_declaration=True,
    )

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(arguments.output, "w", compression=ZIP_DEFLATED) as destination:
        for name, content in entries.items():
            destination.writestr(entry_info[name], content)
    print(json.dumps({
        "output": str(arguments.output),
        "rows": len(rows),
        "sheets": sheet_names,
        "headers": headers,
    }, ensure_ascii=False))


def validate_input_file(path: Path, maximum_size: int, required_suffix: str) -> None:
    if not path.is_file() or path.suffix.lower() != required_suffix:
        raise ValueError(f"input must be an existing {required_suffix} file")
    size = path.stat().st_size
    if size <= 0 or size > maximum_size:
        raise ValueError(f"input size must be between 1 and {maximum_size} bytes")


def validate_archive_entries(archive: ZipFile) -> None:
    entries = archive.infolist()
    if len(entries) > MAX_WORKBOOK_ENTRIES:
        raise ValueError("template contains too many ZIP entries")
    names = [entry.filename for entry in entries]
    if len(names) != len(set(names)):
        raise ValueError("template contains duplicate ZIP entry names")
    if sum(entry.file_size for entry in entries) > MAX_UNCOMPRESSED_WORKBOOK_BYTES:
        raise ValueError("template expands beyond the permitted workbook budget")
    for entry in entries:
        path = PurePosixPath(entry.filename)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("template contains an unsafe ZIP entry path")
        if entry.file_size > 0 and entry.compress_size == 0:
            raise ValueError("template contains an invalid compressed ZIP entry")
        if entry.compress_size > 0 and entry.file_size / entry.compress_size > MAX_ENTRY_COMPRESSION_RATIO:
            raise ValueError("template ZIP entry exceeds the compression-ratio budget")


def validate_people_row_count(row_count: int) -> None:
    if row_count < 0 or row_count > MAX_PEOPLE_ROWS:
        raise ValueError("people workbook cannot exceed 50,000 data rows")


def excel_column(index: int) -> str:
    value = ""
    while index:
        index, remainder = divmod(index - 1, 26)
        value = chr(65 + remainder) + value
    return value


if __name__ == "__main__":
    main()
