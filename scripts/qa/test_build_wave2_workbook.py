import importlib.util
import tempfile
import unittest
import warnings
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


SCRIPT = Path(__file__).with_name("build-wave2-workbook.py")
SPEC = importlib.util.spec_from_file_location("build_wave2_workbook", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class WorkbookArchivePolicyTest(unittest.TestCase):
    def validate(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "fixture.xlsx"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with ZipFile(archive_path, "w", compression=ZIP_DEFLATED) as archive:
                    for name, content, compression in entries:
                        archive.writestr(name, content, compress_type=compression)
            with ZipFile(archive_path, "r") as archive:
                MODULE.validate_archive_entries(archive)

    def test_rejects_duplicate_names(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.validate([
                ("xl/workbook.xml", b"a", ZIP_STORED),
                ("xl/workbook.xml", b"b", ZIP_STORED),
            ])

    def test_rejects_absolute_and_parent_paths(self):
        for unsafe_path in ("/xl/workbook.xml", "../workbook.xml", "xl/../workbook.xml"):
            with self.subTest(unsafe_path=unsafe_path):
                with self.assertRaisesRegex(ValueError, "unsafe"):
                    self.validate([(unsafe_path, b"a", ZIP_STORED)])

    def test_rejects_excessive_entry_count(self):
        entries = [
            (f"xl/entry-{index}.xml", b"x", ZIP_STORED)
            for index in range(MODULE.MAX_WORKBOOK_ENTRIES + 1)
        ]
        with self.assertRaisesRegex(ValueError, "too many"):
            self.validate(entries)

    def test_rejects_excessive_compression_ratio(self):
        with self.assertRaisesRegex(ValueError, "compression-ratio"):
            self.validate([("xl/workbook.xml", b"0" * 100_000, ZIP_DEFLATED)])

    def test_accepts_bounded_archive(self):
        self.validate([("xl/workbook.xml", b"<workbook/>", ZIP_DEFLATED)])

    def test_rejects_more_than_fifty_thousand_people_rows(self):
        with self.assertRaisesRegex(ValueError, "50,000"):
            MODULE.validate_people_row_count(50_001)

    def test_accepts_exactly_fifty_thousand_people_rows(self):
        MODULE.validate_people_row_count(50_000)


if __name__ == "__main__":
    unittest.main()
