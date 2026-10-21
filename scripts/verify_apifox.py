"""Compare the local OpenAPI contract with the UI-exported Apifox specification.

Reads the selected contract/export without contacting Apifox. Defaults to V4
evidence paths, preserving the original 26-operation historical comparison.
"""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import hashlib
import json
import argparse

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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='docs/api/openapi.json')
    parser.add_argument('--export', default='docs/audit/apifox-export-v4-verified.json')
    parser.add_argument('--report', default='docs/audit/apifox-comparison-v4.json')
    parser.add_argument('--module', default='8752957')
    args = parser.parse_args()
    source_path = ROOT / args.source
    export_path = ROOT / args.export
    source = json.loads(source_path.read_text(encoding="utf-8-sig"))
    exported = json.loads(export_path.read_text(encoding="utf-8-sig"))
    expected, actual = operations(source), operations(exported)
    differences = []
    for name, operation in actual.items():
        for requirement in operation.get('security', []):
            for scheme in requirement:
                if scheme not in exported.get('components', {}).get('securitySchemes', {}):
                    differences.append({'operation': name, 'unresolved_security_scheme': scheme})
    if expected.keys() != actual.keys():
        differences.append({"missing": sorted(expected.keys() - actual.keys()), "extra": sorted(actual.keys() - expected.keys())})
    fields = ("operationId", "summary", "description", "parameters", "requestBody", "responses", "security", "x-requirement-id", "x-required-permission", "x-implementation-status")
    for name in sorted(expected.keys() & actual.keys()):
        for field in fields:
            if normalized(expected[name].get(field)) != normalized(actual[name].get(field)):
                differences.append({"operation": name, "field": field})
    for field in ("schemas",):
        if normalized(source["components"][field]) != normalized(exported["components"][field]):
            differences.append({"components": field})
    # Retained, unused historical auth components are harmless; every referenced scheme must exist and match.
    def referenced_schemes(spec):
        names = {name for operation in operations(spec).values()
                 for requirement in operation.get('security', spec.get('security', [])) for name in requirement}
        return {name: spec['components']['securitySchemes'].get(name) for name in names}
    expected_auth, actual_auth = referenced_schemes(source), referenced_schemes(exported)
    if normalized(expected_auth) != normalized(actual_auth):
        differences.append({'components': 'referenced securitySchemes'})
    report = {
        "checked_on": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "project_id": "8950931", "branch": "main", "module_id": args.module,
        "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
        "export_sha256": hashlib.sha256(export_path.read_bytes()).hexdigest(),
        "source_file": str(source_path.relative_to(ROOT)),
        "export_file": str(export_path.relative_to(ROOT)),
        "operations": len(actual), "schemas": len(exported["components"]["schemas"]),
        "compared_operation_fields": list(fields),
        "ignored_export_normalization": ["info/module title and version", "OpenAPI 3.0 patch version", "optional false flags", "empty containers", "empty description/example", "array ordering", "retained unreferenced authentication components (reported separately; referenced definitions and resolution always checked)"],
        "differences": differences,
        "referenced_security_schemes": sorted(actual_auth),
        "retained_unreferenced_security_schemes": sorted(set(exported['components']['securitySchemes']) - set(actual_auth)),
        "result": "passed" if not differences else "failed",
    }
    (ROOT / args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if differences:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
