"""Compare the local OpenAPI contract with the UI-exported Apifox specification.

Reads docs/api/openapi.json and docs/audit/apifox-export.json without contacting
Apifox. Saves a semantic comparison report for docs/测试与验收.md.
"""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}


def normalized(value):
    """Ignore optional false flags and empty containers added by the exporter."""
    if isinstance(value, list):
        return sorted((normalized(item) for item in value), key=lambda x: json.dumps(x, sort_keys=True))
    if isinstance(value, dict):
        return {
            key: normalized(item)
            for key, item in value.items()
            if item not in ({}, [])
            and not (item is False and key in {"required", "deprecated"})
            and not (item == "" and key in {"description", "example"})
        }
    return value


def operations(spec):
    return {
        f"{method.upper()} {path}": operation
        for path, item in spec["paths"].items()
        for method, operation in item.items()
        if method in METHODS
    }


def main():
    source_path = ROOT / "docs/api/openapi.json"
    export_path = ROOT / "docs/audit/apifox-export.json"
    source = json.loads(source_path.read_text(encoding="utf-8-sig"))
    exported = json.loads(export_path.read_text(encoding="utf-8-sig"))
    expected, actual = operations(source), operations(exported)
    differences = []
    if expected.keys() != actual.keys():
        differences.append({"missing": sorted(expected.keys() - actual.keys()), "extra": sorted(actual.keys() - expected.keys())})
    fields = ("operationId", "summary", "description", "parameters", "requestBody", "responses", "security", "x-requirement-id", "x-required-permission", "x-implementation-status")
    for name in sorted(expected.keys() & actual.keys()):
        for field in fields:
            if normalized(expected[name].get(field)) != normalized(actual[name].get(field)):
                differences.append({"operation": name, "field": field})
    for field in ("schemas", "securitySchemes"):
        if normalized(source["components"][field]) != normalized(exported["components"][field]):
            differences.append({"components": field})
    report = {
        "checked_on": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "project_id": "8950931", "branch": "main", "module_id": "8746079",
        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "export_sha256": hashlib.sha256(export_path.read_bytes()).hexdigest(),
        "operations": len(actual), "schemas": len(exported["components"]["schemas"]),
        "compared_operation_fields": list(fields),
        "ignored_export_normalization": ["info/module title and version", "OpenAPI 3.0 patch version", "optional false flags", "empty containers", "empty description/example", "array ordering"],
        "differences": differences,
        "result": "passed" if not differences else "failed",
    }
    (ROOT / "docs/audit/apifox-comparison.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if differences:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
