"""Provision a scoped document client and private H2 configuration after Flyway V4.

Uses only the project's DML credentials; never changes MySQL server accounts.
Related: AuthorizationProcesses, document-system and .local/office-local.properties.
"""
from pathlib import Path
import argparse
import hashlib
import json
import secrets
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/setup-deps'))
import pymysql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rotate', action='store_true', help='Create a new key; preserve old key until explicitly revoked')
    parser.add_argument('--revoke', help='Revoke one explicit credential identifier; never print its secret')
    args = parser.parse_args()
    path = ROOT / '.local/office-local.properties'
    if path.exists() and not args.rotate and not args.revoke:
        print('公文系统本机配置已存在，未覆盖。')
        return
    props = dict(line.split('=', 1) for line in (ROOT / '.local/application-local.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in line and not line.startswith('#'))
    with pymysql.connect(host='localhost', port=3306, user=props['spring.datasource.username'], password=props['spring.datasource.password'], database='municipal_access_ooad_dev', charset='utf8mb4') as connection:
        with connection.cursor() as cursor:
            if args.revoke:
                cursor.execute('UPDATE integration_credential SET enabled=0 WHERE id=%s', (args.revoke,))
                if cursor.rowcount != 1:
                    raise ValueError('凭据不存在或已经撤销，未修改其他对象。')
                connection.commit()
                print('指定应用凭据已撤销。')
                return
            cursor.execute("SELECT id FROM resource WHERE code='office' AND kind='APPLICATION'")
            row = cursor.fetchone()
            if not row:
                raise ValueError('请先启动权限平台并成功应用 V4 迁移。')
            application = row[0]
            cursor.execute("SELECT id,application_id FROM integration_client WHERE code='office-documents'")
            client = cursor.fetchone()
            if client and client[1] != application:
                raise ValueError('已有接入方应用范围不同，已停止。')
            if not client:
                cursor.execute("INSERT INTO integration_client(code,application_id) VALUES('office-documents',%s)", (application,))
                client_id = cursor.lastrowid
            else:
                client_id = client[0]
            key_id, secret = uuid.uuid4().hex, secrets.token_urlsafe(32)
            cursor.execute('INSERT INTO integration_credential(id,client_id,secret_hash) VALUES(%s,%s,%s)', (key_id, client_id, hashlib.sha256(secret.encode()).hexdigest()))
            staff = {}
            for profile in ('editor', 'reader'):
                cursor.execute('SELECT id FROM employee WHERE source_key=%s AND synthetic=1', ('demo:office-' + profile,))
                employee = cursor.fetchone()
                if not employee:
                    raise ValueError('缺少预设合成员工，请核对 V4 迁移状态。')
                staff[profile] = employee[0]
            prior = dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#')) if path.exists() else {}
            database = (ROOT / '.local/office-data/documents').as_posix()
            content = (
                '# Private document-system configuration; never commit or print.\n'
                f'office.app-key={key_id}.{secret}\n'
                f'office.mock.editor-id={staff["editor"]}\n'
                f'office.mock.reader-id={staff["reader"]}\n'
                f'spring.datasource.url=jdbc:h2:file:{database};DB_CLOSE_ON_EXIT=FALSE\n'
                'spring.datasource.username=office\n'
                f'spring.datasource.password={prior.get("spring.datasource.password", secrets.token_urlsafe(32))}\n'
            )
            path.parent.mkdir(exist_ok=True)
            # Preserve the previous local file before rotation; both keys remain valid until revocation.
            if path.exists():
                previous = path.with_name('office-local-before-' + key_id + '.properties')
                previous.write_bytes(path.read_bytes())
            # Stage before commit; a failed commit must not replace the active configuration.
            pending = path.with_name('office-local-pending-' + key_id + '.properties')
            pending.write_text(content, encoding='utf-8')
            connection.commit()
            pending.replace(path)
    print(json.dumps({'configured': True, 'credentialId': key_id, 'syntheticEmployees': staff, 'secretPrinted': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
