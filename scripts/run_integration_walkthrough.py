"""Exercise the real RBAC and document servers with isolated administrator/employee cookies.

Only changes fixed synthetic document/employee fixtures, restores their original grants and text,
and saves a new TXT/JSON evidence pair. Never overwrites the historical single-system walkthrough.
"""
from pathlib import Path
from datetime import datetime, timezone, timedelta
from urllib.request import Request, build_opener, HTTPCookieProcessor
from urllib.error import HTTPError
import http.cookiejar
import json
import os

ROOT = Path(__file__).resolve().parents[1]


def main():
    now = datetime.now(timezone(timedelta(hours=8)))
    checks = []
    lines = ['公文业务系统跨系统授权验收', now.isoformat(timespec='seconds'), '范围：本机合成数据；真实 HTTP；独立管理与员工会话。']
    config = dict(line.split('=', 1) for line in (ROOT / '.local/office-local.properties').read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
    editor_id = int(config['office.mock.editor-id'])

    def agent(port):
        opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))

        def call(method, path, expected, body=None, csrf=None, key=None):
            headers = {'Content-Type': 'application/json'}
            if csrf:
                headers['X-CSRF-Token'] = csrf
            if key:
                headers['X-App-Key'] = key
            request = Request(f'http://127.0.0.1:{port}' + path, method=method, headers=headers, data=None if body is None else json.dumps(body).encode())
            try:
                response = opener.open(request, timeout=5)
            except HTTPError as failure:
                response = failure
            with response:
                raw = response.read()
                assert response.status == expected, f'{method} {path}: expected {expected}, got {response.status}'
                value = json.loads(raw) if raw else None
            checks.append({'port': port, 'method': method, 'path': path, 'expected': expected, 'actual': response.status})
            lines.append(f'{len(checks):02d}. {method} :{port}{path} 预期 {expected}，实际 {response.status}')
            return value
        return call

    admin, office, reader, external = agent(8080), agent(9090), agent(9090), agent(8080)
    admin('GET', '/api/health', 200)
    office('GET', '/office-api/health', 200)
    admin('GET', '/api/accounts', 401)
    identity = admin('POST', '/api/session/login', 200, {'username': 'project_admin', 'password': os.environ.get('PLATFORM_TEST_PASSWORD', '1234')})
    csrf = identity['csrfToken']
    admin('POST', '/api/authorization/check', 401, {'employeeId': editor_id, 'permissionCodes': ['office.documents:read']})
    roles = admin('GET', '/api/roles', 200)
    read_role = next(row['id'] for row in roles if row['code'] == 'office-reader')
    original_grants = admin('GET', f'/api/employees/{editor_id}/roles', 200)
    session = office('POST', '/office-api/mock-session', 200, {'profile': 'editor'})
    token = session['csrfToken']
    original = office('GET', '/office-api/documents', 200)['items'][0]
    assert original['id'] == 'DOC-001'
    changed = False
    try:
        decisions = office('GET', '/office-api/permissions', 200)
        assert decisions['permissions']['office.documents:update'] is True
        payload = {'title': original['title'] + ' · 联调验证', 'content': original['content'], 'version': original['version']}
        saved = office('PUT', '/office-api/documents/DOC-001', 200, payload, token)
        admin('PUT', f'/api/employees/{editor_id}/roles', 200, {'version': original_grants['version'], 'ids': [read_role]}, csrf)
        changed = True
        payload['version'] = saved['version']
        payload['title'] = '不应被保存的公文标题'
        office('PUT', '/office-api/documents/DOC-001', 403, payload, token)
        unchanged = office('GET', '/office-api/documents', 200)['items'][0]
        assert unchanged['version'] == saved['version'] and unchanged['title'] != payload['title']
        lines.append('断言：撤权后原员工会话修改被拒；公文标题和版本保持不变。')
        latest = admin('GET', f'/api/employees/{editor_id}/roles', 200)
        admin('PUT', f'/api/employees/{editor_id}/roles', 200, {'version': latest['version'], 'ids': original_grants['ids']}, csrf)
        changed = False
        office('PUT', '/office-api/documents/DOC-001', 200, {'title': original['title'], 'content': original['content'], 'version': saved['version']}, token)
        lines.append('断言：恢复授权后，未重新登录的同一员工会话保存成功；原合成标题和正文已恢复，版本正常递增。')
        reader_identity = reader('POST', '/office-api/mock-session', 200, {'profile': 'reader'})
        reader('GET', '/office-api/documents', 200)
        reader('PUT', '/office-api/documents/DOC-001', 403, payload, reader_identity['csrfToken'])
        key = config['office.app-key']
        query = {'employeeId': editor_id, 'permissionCodes': ['office.documents:read', 'office.documents:update']}
        batch = external('POST', '/api/authorization/check', 200, query, key=key)
        assert all(result['allowed'] for result in batch['results'])
        external('POST', '/api/authorization/check', 403, {**query, 'permissionCodes': ['office.documents:read', 'accounts:read']}, key=key)
        external('POST', '/api/authorization/check', 400, {**query, 'permissionCodes': ['office.documents:read'] * 2}, key=key)
        external('POST', '/api/authorization/check', 400, {**query, 'permissionCodes': []}, key=key)
        external('POST', '/api/authorization/check', 400, {**query, 'permissionCodes': ['office.unknown:read'] * 51}, key=key)
        external('GET', '/api/accounts', 401, key=key)
        admin('GET', '/api/demo/documents', 404)
        admin('POST', '/api/session/login', 401, {'username': 'demo_user', 'password': '1234'})
        office('POST', '/office-api/logout', 200, csrf=token)
        office('GET', '/office-api/me', 401)
    finally:
        if changed:
            latest = admin('GET', f'/api/employees/{editor_id}/roles', 200)
            admin('PUT', f'/api/employees/{editor_id}/roles', 200, {'version': latest['version'], 'ids': original_grants['ids']}, csrf)
        admin('POST', '/api/session/logout', 204, csrf=csrf)
    lines += ['全部断言通过。', '未修改来源员工、数据库凭据或历史测试记录；未记录 Cookie、CSRF 或应用密钥。', '服务异常与损坏响应场景另由独立公文系统集成测试验证。']
    stem = '公文系统联调_' + now.strftime('%Y%m%dT%H%M%S')
    folder = ROOT / 'docs/audit'
    (folder / (stem + '.txt')).write_text('\n'.join(lines) + '\n', encoding='utf-8')
    report = {'result': 'passed', 'executed_at': now.isoformat(timespec='seconds'), 'checks': checks, 'same_employee_session_revoke_restore': True, 'denied_write_preserves_version_and_title': True, 'report': stem + '.txt'}
    (folder / (stem + '.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'result': 'passed', 'requests': len(checks), 'report': stem + '.txt'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
