"""Read the original XLSX files without changing them; write aggregate audit evidence.

Used by docs/资料审计.md. OOXML coordinates and merge anchors are retained so
incorrect worksheet dimensions cannot truncate the analysis. Employee values
remain in memory; generated reports contain counts and source locations only.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
from pathlib import Path
import posixpath
import re
from xml.etree import ElementTree as ET
from zipfile import ZipFile
from zoneinfo import ZoneInfo


NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
REL_ID = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
FILES = {
    "organizations": "市政公司部门信息表.xlsx",
    "employees": "市政公司员工信息表.xlsx",
    "permissions": "市政公司应用软件功能权限清单(1).xlsx",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def coordinates(address: str) -> tuple[int, int]:
    match = re.fullmatch(r"([A-Z]+)(\d+)", address)
    if match is None:
        raise ValueError(f"Invalid cell address: {address}")
    column = 0
    for char in match[1]:
        column = column * 26 + ord(char) - ord("A") + 1
    return column, int(match[2])


def column_name(number: int) -> str:
    result = ""
    while number:
        number, digit = divmod(number - 1, 26)
        result = chr(ord("A") + digit) + result
    return result


@dataclass
class Sheet:
    name: str
    xml_path: str
    declared_dimension: str
    rows: list[int]
    cells: dict[str, str]
    types: dict[str, str]
    merges: list[str]
    formulas: list[str]

    def raw(self, column: str, row: int) -> str:
        return self.cells.get(f"{column}{row}", "")

    def anchor(self, address: str) -> str:
        """Carry values only inside a declared merge, never across real blanks."""
        column, row = coordinates(address)
        for merged in self.merges:
            start, end = merged.split(":") if ":" in merged else (merged, merged)
            left, top = coordinates(start)
            right, bottom = coordinates(end)
            if left <= column <= right and top <= row <= bottom:
                return start
        return address

    def resolved(self, column: str, row: int) -> str:
        return self.cells.get(self.anchor(f"{column}{row}"), "")

    def metadata(self) -> dict:
        nonempty = [coordinates(cell) for cell, value in self.cells.items() if value != ""]
        max_column = max((column for column, _ in nonempty), default=1)
        max_row = max((row for _, row in nonempty), default=1)
        return {
            "name": self.name,
            "xml_path": self.xml_path,
            "declared_dimension": self.declared_dimension,
            "nonempty_extent": f"A1:{column_name(max_column)}{max_row}",
            "physical_row_count": len(self.rows),
            "nonempty_cell_count": len(nonempty),
            "merged_range_count": len(self.merges),
            "merged_ranges": self.merges,
            "formula_cells": self.formulas,
            "error_cells": [cell for cell, kind in self.types.items() if kind == "e"],
        }


def read_workbook(path: Path) -> tuple[dict, list[Sheet]]:
    """Resolve workbook relationships instead of assuming sheet1.xml exists."""
    before = sha256(path)
    with ZipFile(path) as archive:
        strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            strings = ["".join(t.text or "" for t in item.findall(".//m:t", NS)) for item in root]
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        targets = {item.attrib["Id"]: item.attrib["Target"] for item in relationships}
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        sheets = []
        for entry in workbook.findall("m:sheets/m:sheet", NS):
            target = targets[entry.attrib[REL_ID]]
            xml_path = target.lstrip("/") if target.startswith("/") else posixpath.normpath("xl/" + target)
            root = ET.fromstring(archive.read(xml_path))
            cells, types, formulas = {}, {}, []
            row_elements = root.findall("m:sheetData/m:row", NS)
            for row in row_elements:
                for cell in row.findall("m:c", NS):
                    address, kind = cell.attrib["r"], cell.attrib.get("t", "n")
                    node = cell.find("m:v", NS)
                    value = (node.text or "") if node is not None else ""
                    if kind == "s" and value:
                        value = strings[int(value)]
                    elif kind == "inlineStr":
                        value = "".join(t.text or "" for t in cell.findall("m:is//m:t", NS))
                    cells[address], types[address] = value, kind
                    if cell.find("m:f", NS) is not None:
                        formulas.append(address)
            dimension = root.find("m:dimension", NS)
            sheets.append(Sheet(
                name=entry.attrib["name"], xml_path=xml_path,
                declared_dimension=dimension.attrib.get("ref", "") if dimension is not None else "",
                rows=[int(row.attrib["r"]) for row in row_elements],
                cells=cells, types=types, formulas=formulas,
                merges=[item.attrib["ref"] for item in root.findall("m:mergeCells/m:mergeCell", NS)],
            ))
    if sha256(path) != before:
        raise RuntimeError(f"Source changed while reading: {path.name}")
    return {"file": path.name, "bytes": path.stat().st_size, "sha256": before,
            "sheets": [sheet.metadata() for sheet in sheets]}, sheets


def duplicate_summary(values: list[tuple[int, str]], column: str) -> dict:
    groups = defaultdict(list)
    for row, value in values:
        if value:
            groups[value].append(f"{column}{row}")
    duplicates = [locations for locations in groups.values() if len(locations) > 1]
    return {"group_count": len(duplicates), "affected_records": sum(map(len, duplicates)),
            "extra_records": sum(len(group) - 1 for group in duplicates),
            "sample_locations": [group[:8] for group in duplicates[:5]]}


def analyze_organizations(sheet: Sheet) -> tuple[dict, dict]:
    records = [(row, sheet.raw("A", row), sheet.raw("C", row))
               for row in sheet.rows if row > 1 and sheet.raw("A", row)]
    by_name = {name: (row, parent) for row, name, parent in records}
    if len(by_name) != len(records):
        raise ValueError("Organization source names are not unique; parent matching requires review")
    paths = {}

    def path_for(name: str, ancestors: tuple = ()) -> list[str]:
        if name in ancestors:
            raise ValueError("Organization hierarchy contains a cycle")
        if name not in by_name:
            raise ValueError("Organization parent missing from source")
        parent = by_name[name][1]
        return (path_for(parent, ancestors + (name,)) if parent else []) + [name]

    for name in by_name:
        paths[name] = path_for(name)
    return {
        "records": len(records), "source_range": f"A2:C{max(row for row, _, _ in records)}",
        "fields": {f"{column}1": sheet.raw(column, 1) for column in "ABC"},
        "depth_counts": dict(Counter(len(path) for path in paths.values())),
        "root_count": sum(not parent for _, _, parent in records),
        "branch_count": sum(name.endswith("分公司") for _, name, _ in records),
        "parent_links": sum(bool(parent) for _, _, parent in records),
        "missing_parent_count": 0, "cycle_count": 0,
        "empty_fields": {column: [f"{column}{row}" for row, _, _ in records if not sheet.raw(column, row)]
                         for column in "ABC"},
        "source_key_present": False,
    }, paths


def analyze_employees(sheet: Sheet, paths: dict) -> dict:
    rows = [row for row in sheet.rows if row > 1 and any(sheet.raw(col, row) for col in "ABCDEF")]
    unmatched, direct_rows = [], []
    organization_targets = Counter()
    for row in rows:
        branch, department = sheet.raw("C", row), sheet.raw("D", row)
        candidate = department if branch == "总公司" or department == branch else f"{branch}-{department}"
        if branch not in paths or candidate not in paths or branch not in paths[candidate]:
            unmatched.append(f"C{row}:D{row}")
        else:
            organization_targets[candidate] += 1
            if branch == department:
                direct_rows.append(f"C{row}:D{row}")
    usernames = [(row, sheet.raw("B", row)) for row in rows]
    return {
        "records": len(rows), "source_range": f"A2:F{max(rows)}",
        "fields": {f"{column}1": sheet.raw(column, 1) for column in "ABCDEF"},
        "empty_field_counts": {col: sum(not sheet.raw(col, row) for row in rows) for col in "ABCDEF"},
        "name_duplicates": duplicate_summary([(row, sheet.raw("A", row)) for row in rows], "A"),
        "username_duplicates": duplicate_summary(usernames, "B"),
        "normalized_username_duplicates": duplicate_summary([(row, value.strip().casefold()) for row, value in usernames], "B"),
        "username_whitespace_locations": [f"B{row}" for row, value in usernames if value != value.strip()],
        "phone_duplicates": duplicate_summary([(row, sheet.raw("F", row)) for row in rows], "F"),
        "phone_cell_types": dict(Counter(sheet.types.get(f"F{row}", "missing") for row in rows)),
        "organization_unmatched_count": len(unmatched), "organization_unmatched_locations": unmatched,
        "direct_branch_records": len(direct_rows), "direct_branch_sample_locations": direct_rows[:10],
        "matched_organization_node_count": len(organization_targets),
        "organization_counts": dict(organization_targets),
        "independent_employee_id_present": False,
        "full_row_duplicates": duplicate_summary([(row, "\0".join(sheet.raw(col, row) for col in "ABCDEF")) for row in rows], "A"),
    }


def analyze_permissions(sheet: Sheet) -> dict:
    rows = [row for row in sheet.rows if row > 2 and sheet.raw("C", row)]
    apps, groups, role_texts, english, blanks = set(), set(), Counter(), [], []
    scope_cells, all_cells, none_cells, resolution_errors = [], [], [], []
    role_counts = {column: Counter() for column in "EFGHI"}
    for row in rows:
        app, group = sheet.resolved("A", row), sheet.resolved("B", row)
        apps.add(app)
        groups.add((app, group))
        if not app or not group:
            resolution_errors.append(f"A{row}:B{row}")
        for column in "EFGHI":
            address, value = f"{column}{row}", sheet.resolved(column, row)
            role_texts[value] += 1
            role_counts[column][value] += 1
            if not value:
                blanks.append(address)
            if re.search(r"[A-Za-z]", value):
                english.append({"cell": address, "text": value})
            if "本人" in value or "本部门" in value:
                scope_cells.append({"cell": address, "text": value})
            if value == "全部":
                all_cells.append(address)
            if value == "无":
                none_cells.append(address)
    keys = [(row, "\0".join((sheet.resolved("A", row), sheet.resolved("B", row), sheet.raw("C", row)))) for row in rows]
    return {
        "records": len(rows), "source_range": f"A3:I{max(rows)}", "header_range": "A2:I2",
        "fields": {f"{column}2": sheet.raw(column, 2) for column in "ABCDEFGHI"},
        "application_count": len(apps), "applications": sorted(apps),
        "application_module_group_count": len(groups),
        "feature_path_duplicates": duplicate_summary(keys, "C"),
        "role_cell_count": len(rows) * 5, "role_text_counts": dict(role_texts),
        "role_text_counts_by_column": {col: dict(counts) for col, counts in role_counts.items()},
        "true_blank_role_cells": blanks,
        "english_role_cells": english, "data_scope_cells": scope_cells,
        "all_cell_count": len(all_cells), "none_cell_count": len(none_cells),
        "unresolved_group_locations": resolution_errors,
        "merged_group_blank_count": sum(not sheet.raw(col, row) and bool(sheet.resolved(col, row)) for row in rows for col in "AB"),
        "core_requirements": [{"row": row, "application_anchor": sheet.anchor(f"A{row}"),
                               "module_anchor": sheet.anchor(f"B{row}"),
                               "feature_cell": f"C{row}", "feature": sheet.raw("C", row),
                               "description_cell": f"D{row}", "description": sheet.raw("D", row)}
                              for row in range(341, 347)],
    }


def independent_check(path: Path, sheet: Sheet) -> dict:
    """Use a second reader with dimensions reset, without saving any workbook."""
    import openpyxl

    workbook = openpyxl.load_workbook(path, read_only=True, data_only=False)
    try:
        worksheet = workbook[sheet.name]
        worksheet.reset_dimensions()
        nonempty_rows = sum(any(value is not None and value != "" for value in row)
                            for row in worksheet.iter_rows(values_only=True))
    finally:
        workbook.close()
    xml_rows = sum(any(sheet.raw(column_name(col), row) != "" for col in range(1, 10)) for row in sheet.rows)
    if nonempty_rows != xml_rows:
        raise AssertionError("Independent row counts disagree")
    return {"reader": f"openpyxl {openpyxl.__version__}, read_only + reset_dimensions",
            "nonempty_rows": nonempty_rows, "matches_ooxml": True}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = {"generated_at": datetime.now(ZoneInfo("Asia/Shanghai")).isoformat(),
              "method": "Read-only OOXML scan; merged ranges only; aggregate employee evidence",
              "sources": {}, "analysis": {}}
    loaded = {}
    for key, filename in FILES.items():
        metadata, sheets = read_workbook(args.root / filename)
        if len(sheets) != 1:
            raise ValueError("Unexpected workbook sheet count; review before using this audit")
        metadata["independent_check"] = independent_check(args.root / filename, sheets[0])
        report["sources"][key] = metadata
        loaded[key] = sheets[0]
    report["analysis"]["organizations"], paths = analyze_organizations(loaded["organizations"])
    report["analysis"]["employees"] = analyze_employees(loaded["employees"], paths)
    report["analysis"]["permissions"] = analyze_permissions(loaded["permissions"])
    for key, filename in FILES.items():
        if sha256(args.root / filename) != report["sources"][key]["sha256"]:
            raise AssertionError("Original source changed during audit")
    report["original_hashes_unchanged"] = True
    output = args.output or args.root / "docs" / "audit" / "source-audit.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"report": str(output), "original_hashes_unchanged": True,
                      "record_counts": {key: value["records"] for key, value in report["analysis"].items()}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
