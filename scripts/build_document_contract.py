"""Generate the independent document service OpenAPI without local identities or secrets.

Matches DocumentController and OfficeSecurity; RBAC's contract has a separate generator.
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]


def main():
    ref = lambda name: {'$ref': '#/components/schemas/' + name}

    def obj(properties, required=None, **extra):
        return {'type': 'object', 'properties': properties,
                'required': required or list(properties), **extra}

    string = {'type': 'string'}
    number = {'type': 'integer', 'format': 'int64', 'minimum': 1}
    schemas = {
        'Health': obj({'status': {'type': 'string', 'enum': ['UP']}}),
        'Profile': obj({'profile': {'type': 'string', 'enum': ['editor', 'reader']},
                        'employeeId': number, 'name': string}),
        'IdentitySelection': obj({'profile': {'type': 'string', 'enum': ['editor', 'reader']}}, additionalProperties=False),
        'Session': obj({'employeeId': number, 'profile': string, 'csrfToken': string,
                        'expiresAt': {'type': 'string', 'format': 'date-time'}}),
        'Permissions': obj({'permissions': {'type': 'object', 'additionalProperties': {'type': 'boolean'}}, 'requestId': string}),
        'Document': obj({'id': string, 'title': string, 'content': string,
                         'version': {'type': 'integer', 'format': 'int64', 'minimum': 0}}),
        'Documents': obj({'items': {'type': 'array', 'items': ref('Document')}, 'requestId': string}),
        'DocumentUpdate': obj({'title': {'type': 'string', 'minLength': 1, 'maxLength': 120},
                              'content': {'type': 'string', 'minLength': 1, 'maxLength': 10000},
                              'version': {'type': 'integer', 'format': 'int64', 'minimum': 0}}, additionalProperties=False),
        'Saved': obj({'saved': {'type': 'boolean', 'enum': [True]},
                      'version': number, 'requestId': string}),
        'Error': obj({'code': string, 'message': string}),
    }
    definitions = [
        ('get', '/health', '健康检查', None, 'Health', False),
        ('get', '/profiles', '列出两个预设合成身份', None, 'Profile[]', False),
        ('post', '/mock-session', '建立本机合成身份会话', 'IdentitySelection', 'Session', False),
        ('get', '/me', '读取业务会话', None, 'Session', True),
        ('post', '/logout', '销毁业务会话', None, None, True),
        ('get', '/permissions', '批量读取当前员工公文权限', None, 'Permissions', True),
        ('get', '/documents', '重新鉴权并读取合成公文', None, 'Documents', True),
        ('put', '/documents/{id}', '重新鉴权并按版本保存公文', 'DocumentUpdate', 'Saved', True),
    ]
    contract = {'openapi': '3.0.3', 'info': {'title': '独立公文业务系统 API', 'version': '0.2.0',
                'description': '仅供本机合成身份接入验证；不是正式员工认证。应用密钥仅由业务后端持有。'},
                'servers': [{'url': 'http://127.0.0.1:9090'}], 'paths': {},
                'components': {'schemas': schemas, 'securitySchemes': {'OfficeSession':
                    {'type': 'apiKey', 'in': 'cookie', 'name': 'OFFICE_SESSION'}}}}
    for index, (method, path, summary, body, response, authenticated) in enumerate(definitions, 1):
        operation = {'operationId': f'office{index:02}', 'summary': summary, 'tags': ['office'],
                     'security': [{'OfficeSession': []}] if authenticated else [], 'parameters': [],
                     'responses': {'200': {'description': '业务处理成功；退出响应体为空'}}}
        if response:
            schema = {'type': 'array', 'items': ref(response[:-2])} if response.endswith('[]') else ref(response)
            operation['responses']['200']['content'] = {'application/json': {'schema': schema}}
        if '{id}' in path:
            operation['parameters'].append({'name': 'id', 'in': 'path', 'required': True, 'schema': string})
        if authenticated and method != 'get':
            operation['parameters'].append({'name': 'X-CSRF-Token', 'in': 'header', 'required': True, 'schema': string})
        if body:
            operation['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        for status, description in [('400', '输入无效'), ('401', '业务会话不可用'),
                                    ('403', '权限不足、来源或 CSRF 拒绝'), ('409', '公文版本冲突'),
                                    ('503', '鉴权依赖不可用，业务未执行'), ('500', '内部错误')]:
            operation['responses'][status] = {'description': description, 'content': {'application/json': {'schema': ref('Error')}}}
        if path == '/mock-session':
            operation['responses']['200']['headers'] = {'Set-Cookie': {'description': 'OFFICE_SESSION; HttpOnly; SameSite=Strict。绝对 1800 秒，不续期；本机 HTTP。', 'schema': string}}
        contract['paths']['/office-api' + path] = {method: operation}
    target = ROOT / 'docs/api/documents.openapi.json'
    target.write_text(json.dumps(contract, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'operations': len(definitions), 'contract': target.relative_to(ROOT).as_posix()}))


if __name__ == '__main__':
    main()
