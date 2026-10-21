"""Smoke-test the running local platform against docs/api/openapi.json.

Uses only read APIs plus login/logout, validates response schemas and bundled UI
assets, and writes aggregate evidence to docs/audit/http-smoke.json. Credentials
come from hidden input or PLATFORM_TEST_PASSWORD, never from the output report.
"""
from pathlib import Path
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError
from urllib.request import HTTPCookieProcessor, Request, build_opener
import getpass
import http.cookiejar
import json
import os
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".tools/validation-deps"))
from jsonschema import Draft7Validator

BASE = "http://127.0.0.1:8080"


def json_schema(value):
    """Translate OpenAPI 3.0 nullable into JSON Schema without changing references."""
    if isinstance(value, list):
        return [json_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: json_schema(item) for key, item in value.items() if key != "nullable"}
    if value.get("nullable"):
        if "type" in result:
            result["type"] = [result["type"], "null"]
        else:
            result = {"anyOf": [result, {"type": "null"}]}
    return result


def main():
    contract = json.loads((ROOT / "docs/api/openapi.json").read_text(encoding="utf-8"))
    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    checks = []

    def call(method, path, expected, template=None, body=None, csrf=None):
        headers = {"Content-Type": "application/json"}
        if csrf:
            headers["X-CSRF-Token"] = csrf
        request = Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = opener.open(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            assert response.status == expected, f"{method} {path}: {response.status} != {expected}"
            payload = json.loads(response.read()) if expected != 204 else None
        responses = contract["paths"][template or path][method.lower()]["responses"]
        if payload is not None:
            schema = responses[str(expected)]["content"]["application/json"]["schema"]
            Draft7Validator(json_schema({"components": contract["components"], **schema})).validate(payload)
        checks.append({"method": method, "path": template or path, "status": expected, "schema_checked": True})
        return payload

    call("GET", "/api/accounts", 401)
    password = os.environ.get("PLATFORM_TEST_PASSWORD") or getpass.getpass("本机网页管理员密码: ")
    me = call("POST", "/api/session/login", 200, body={"username": "project_admin", "password": password})
    for path in ("health", "me", "overview", "accounts", "roles", "resources", "permissions"):
        call("GET", "/api/" + path, 200)
    call("GET", f"/api/accounts/{me['id']}/roles", 200, "/api/accounts/{id}/roles")
    call("GET", "/api/roles/1/permissions", 200, "/api/roles/{id}/permissions")
    call("GET", f"/api/accounts/{me['id']}/permissions", 200, "/api/accounts/{id}/permissions")
    for path in ("employees", "organizations", "demo/documents"):
        call("GET", "/api/" + path, 200)
    call("POST", "/api/session/logout", 403)
    call("POST", "/api/session/logout", 204, csrf=me["csrfToken"])
    call("GET", "/api/me", 401)
    with opener.open(BASE, timeout=10) as response:
        assert response.status == 200
        html = response.read().decode()
    assets = sorted(set(re.findall(r'(?:src|href)="(/assets/[^\"]+)"', html)))
    assert assets, "No bundled frontend assets found"
    for asset in assets:
        with opener.open(BASE + asset, timeout=10) as response:
            assert response.status == 200
    report = {"checked_on": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"), "checks": checks, "bundled_frontend_assets_checked": len(assets), "result": "passed"}
    (ROOT / "docs/audit/http-smoke.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(checks)} HTTP/schema checks and {len(assets)} frontend assets")


if __name__ == "__main__":
    main()
