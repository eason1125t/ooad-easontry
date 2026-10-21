"""Validate live cross-service response bodies against both authoritative OpenAPI files.

Uses private local app credentials without printing them. Only synthetic document writes
are performed; content is unchanged while successful writes increment the audit/version.
"""
from pathlib import Path
from datetime import datetime, timezone
from urllib.request import Request, build_opener, HTTPCookieProcessor
from urllib.error import HTTPError
import http.cookiejar
import json
from verify_platform import json_schema, Draft7Validator

ROOT = Path(__file__).resolve().parents[1]


def main():
    contracts = {port: json.loads((ROOT / filename).read_text(encoding='utf-8')) for port, filename in
                 [(8080, 'docs/api/openapi.json'), (9090, 'docs/api/documents.openapi.json')]}
    props = dict(line.split('=', 1) for line in (ROOT / '.local/office-local.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
    agent = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    checks = []

    def call(port, method, path, status, body=None, csrf=None, key=None, template=None):
        headers = {'Content-Type': 'application/json'}
        if csrf:
            headers['X-CSRF-Token'] = csrf
        if key:
            headers['X-App-Key'] = key
        request = Request(f'http://127.0.0.1:{port}' + path, method=method, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            response = agent.open(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            assert response.status == status, f'{method} {path}: unexpected HTTP status'
            raw = response.read()
        value = json.loads(raw) if raw else None
        spec = contracts[port]
        shape = spec['paths'][template or path][method.lower()]['responses'][str(status)]
        if value is not None:
            schema = shape['content']['application/json']['schema']
            Draft7Validator(json_schema({'components': spec['components'], **schema})).validate(value)
        checks.append({'port': port, 'method': method, 'path': template or path, 'status': status, 'schema_checked': value is not None})
        return value

    query = {'employeeId': int(props['office.mock.editor-id']), 'permissionCodes': ['office.documents:read', 'office.documents:update']}
    call(8080, 'POST', '/api/authorization/check', 200, query, key=props['office.app-key'])
    call(8080, 'POST', '/api/authorization/check', 401, query)
    call(8080, 'POST', '/api/authorization/check', 400, {**query, 'permissionCodes': []}, key=props['office.app-key'])
    call(8080, 'POST', '/api/authorization/check', 403, {**query, 'permissionCodes': ['accounts:read']}, key=props['office.app-key'])
    call(9090, 'GET', '/office-api/health', 200)
    call(9090, 'GET', '/office-api/profiles', 200)
    call(9090, 'GET', '/office-api/me', 401)
    me = call(9090, 'POST', '/office-api/mock-session', 200, {'profile': 'editor'})
    try:
        call(9090, 'GET', '/office-api/me', 200)
        call(9090, 'GET', '/office-api/permissions', 200)
        document = call(9090, 'GET', '/office-api/documents', 200)['items'][0]
        update = {key: document[key] for key in ('title', 'content', 'version')}
        path = '/office-api/documents/' + document['id']
        call(9090, 'PUT', path, 403, update, template='/office-api/documents/{id}')
        call(9090, 'PUT', path, 200, update, csrf=me['csrfToken'], template='/office-api/documents/{id}')
        call(9090, 'PUT', path, 409, update, csrf=me['csrfToken'], template='/office-api/documents/{id}')
    finally:
        call(9090, 'POST', '/office-api/logout', 200, csrf=me['csrfToken'])
    call(9090, 'GET', '/office-api/me', 401)
    report = {'result': 'passed', 'executed_at': datetime.now(timezone.utc).isoformat(), 'checks': checks}
    (ROOT / 'docs/audit/integration-contracts-v4.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'PASS: {len(checks)} live HTTP/response-schema checks across both services')


if __name__ == '__main__':
    main()
