"""Generate local API contracts, ER sources and schema dictionary from fixed design data.

Outputs belong to docs/ and reference the Spring controllers and Flyway migrations.
Run render_er.cjs afterwards to render the complete ER model. This generator
never replaces an existing diagram with an incomplete entity summary.
No secrets, employee values or live database writes are involved.
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'


def prop(kind, **values):
    return {'type': kind, **values}


def obj(properties, required=()):
    return {'type': 'object', 'properties': properties, 'required': list(required)}


ID = prop('integer', format='int64', minimum=1)
VERSION = prop('integer', format='int64', minimum=0)
BOOL = prop('boolean')
NAME = prop('string', minLength=1, maxLength=80)
CODE = prop('string', pattern='^[a-z][a-z0-9._-]{1,79}$', minLength=2, maxLength=80)
PASSWORD = prop('string', format='password', minLength=4, maxLength=64, description='本机课程测试最少 4 位；UTF-8 最多 72 字节；响应不返回密码。')
schemas = {
    'Error': obj({'code': prop('string'), 'message': prop('string')}, ['code', 'message']),
    'Created': obj({'id': ID}, ['id']),
    'Saved': obj({'saved': BOOL}, ['saved']),
    'Health': obj({'status': prop('string', enum=['UP'])}, ['status']),
    'Login': obj({'username': prop('string', maxLength=64), 'password': prop('string', format='password', maxLength=64)}, ['username', 'password']),
    'AccountCreate': obj({'username': prop('string', pattern='^[a-z][a-z0-9._-]{1,63}$', minLength=2, maxLength=64), 'displayName': NAME, 'employeeId': {**ID, 'nullable': True}, 'password': PASSWORD}, ['username', 'displayName', 'password']),
    'AccountUpdate': obj({'displayName': NAME, 'employeeId': {**ID, 'nullable': True}, 'enabled': BOOL, 'locked': BOOL, 'version': VERSION}, ['displayName', 'enabled', 'locked', 'version']),
    'PasswordReset': obj({'password': PASSWORD, 'version': VERSION}, ['password', 'version']),
    'RoleCreate': obj({'code': CODE, 'name': NAME, 'description': prop('string', maxLength=300)}, ['code', 'name']),
    'RoleUpdate': obj({'name': NAME, 'description': prop('string', maxLength=300), 'enabled': BOOL, 'version': VERSION}, ['name', 'enabled', 'version']),
    'ResourceCreate': obj({'code': {**CODE, 'maxLength': 100, 'pattern': '^[a-z][a-z0-9._-]{1,99}$'}, 'name': prop('string', minLength=1, maxLength=100), 'kind': prop('string', enum=['APPLICATION', 'MODULE', 'FEATURE']), 'parentId': {**ID, 'nullable': True}}, ['code', 'name', 'kind']),
    'ResourceUpdate': obj({'name': prop('string', minLength=1, maxLength=100), 'kind': prop('string', enum=['APPLICATION', 'MODULE', 'FEATURE']), 'parentId': {**ID, 'nullable': True}, 'enabled': BOOL, 'version': VERSION}, ['name', 'kind', 'enabled', 'version']),
    'PermissionCreate': obj({'resourceId': ID, 'action': prop('string', pattern='^[a-z][a-z0-9._-]{1,39}$', minLength=2, maxLength=40), 'name': prop('string', minLength=1, maxLength=100)}, ['resourceId', 'action', 'name']),
    'PermissionUpdate': obj({'name': prop('string', minLength=1, maxLength=100), 'enabled': BOOL, 'version': VERSION}, ['name', 'enabled', 'version']),
    'Grants': obj({'version': VERSION, 'ids': prop('array', items=ID, maxItems=5000, uniqueItems=True)}, ['version', 'ids']),
    'Effective': obj({'permissions': prop('array', items=prop('string'))}, ['permissions']),
    'Overview': obj({name: prop('integer', minimum=0) for name in ['accounts','roles','resources','permissions']}, ['accounts','roles','resources','permissions']),
    'Account': obj({'id': ID, 'username': prop('string'), 'displayName': NAME, 'employeeId': {**ID, 'nullable': True}, 'enabled': BOOL, 'locked': BOOL, 'version': VERSION, 'grantsVersion': VERSION, 'createdAt': prop('string', format='date-time')}, ['id','username','displayName','enabled','locked','version','grantsVersion']),
    'Role': obj({'id': ID,'code': CODE,'name': NAME,'description':prop('string'),'enabled':BOOL,'version':VERSION,'grantsVersion':VERSION}, ['id','code','name','enabled','version','grantsVersion']),
    'Resource': obj({'id':ID,'parentId':{**ID,'nullable':True},'code':prop('string'),'name':prop('string'),'kind':prop('string'),'enabled':BOOL,'version':VERSION}, ['id','code','name','kind','enabled','version']),
    'Permission': obj({'id':ID,'resourceId':ID,'code':prop('string'),'action':prop('string'),'name':prop('string'),'resourceName':prop('string'),'enabled':BOOL,'version':VERSION}, ['id','resourceId','code','action','name','enabled','version']),
    'Employee': obj({'id':ID,'label':prop('string',description='原始员工只返回员工 #ID，合成演示员工可返回合成名称'),'organizationName':prop('string'),'linkedAccountId':{**ID,'nullable':True,'description':'员工查询时返回已关联账号 ID；为空或省略表示未关联。身份接口可省略。'}}, ['id','label','organizationName']),
    'Organization': obj({'id':ID,'parentId':{**ID,'nullable':True},'name':prop('string')}, ['id','name']),
    'Document': obj({'id':prop('string'),'title':prop('string'),'department':prop('string'),'status':prop('string')}, ['id','title','department','status']),
}


def ref(name):
    return {'$ref': '#/components/schemas/' + name}


schemas['AccountPage'] = obj({'items': prop('array',items=ref('Account')), 'total':prop('integer'), 'page':prop('integer'), 'pageSize':prop('integer')}, ['items','total','page','pageSize'])
schemas['EmployeePage'] = obj({'items':prop('array',items=ref('Employee')),'total':prop('integer'),'page':prop('integer',minimum=1),'pageSize':prop('integer',enum=[100])}, ['items','total','page','pageSize'])
schemas['Documents'] = obj({'items':prop('array',items=ref('Document'))}, ['items'])
schemas['RoleSummary'] = obj({'id':ID,'code':prop('string'),'name':NAME,'enabled':BOOL}, ['id','code','name','enabled'])
schemas['Me'] = obj({'id':ID,'username':prop('string'),'displayName':NAME,'employeeId':{**ID,'nullable':True},'employee':ref('Employee'),'roles':prop('array',items=ref('RoleSummary')),'permissions':prop('array',items=prop('string')),'csrfToken':prop('string'),'expiresAt':prop('string',format='date-time')}, ['id','username','displayName','roles','permissions','csrfToken','expiresAt'])

# method, path, summary, permission, requirement, request schema, response schema
operations = [
 ('get','/health','检查服务及数据库连通性',None,'NFR-02',None,'Health'),
 ('post','/session/login','本地凭据登录',None,'FR-10','Login','Me'),
 ('post','/session/logout','退出并使当前会话失效','authenticated','FR-10',None,None),
 ('get','/me','读取当前身份与有效权限','authenticated','FR-11',None,'Me'),
 ('get','/overview','读取管理配置概况','platform:access','FR-07',None,'Overview'),
 ('get','/accounts','分页查询账号','accounts:read','FR-01',None,'AccountPage'),
 ('post','/accounts','创建账号并设置凭据','accounts:write','FR-01','AccountCreate','Created'),
 ('put','/accounts/{id}','编辑、启停或锁定账号','accounts:write','FR-01','AccountUpdate','Saved'),
 ('put','/accounts/{id}/password','重置密码并回收该账号会话','accounts:write','FR-10','PasswordReset','Saved'),
 ('get','/roles','查询角色','roles:read','FR-02',None,'Role[]'),
 ('post','/roles','创建角色','roles:write','FR-02','RoleCreate','Created'),
 ('put','/roles/{id}','编辑或启停角色','roles:write','FR-02','RoleUpdate','Saved'),
 ('get','/resources','查询资源目录','resources:read','FR-03',None,'Resource[]'),
 ('post','/resources','创建资源节点','resources:write','FR-03','ResourceCreate','Created'),
 ('put','/resources/{id}','编辑资源名称或启停资源','resources:write','FR-03','ResourceUpdate','Saved'),
 ('get','/permissions','查询操作权限','permissions:read','FR-03',None,'Permission[]'),
 ('post','/permissions','在功能点下创建操作权限','permissions:write','FR-03','PermissionCreate','Created'),
 ('put','/permissions/{id}','编辑或启停操作权限','permissions:write','FR-03','PermissionUpdate','Saved'),
 ('get','/accounts/{id}/roles','读取账号角色配置与版本','grants:read','FR-04',None,'Grants'),
 ('put','/accounts/{id}/roles','按版本整体替换账号角色','grants:write','FR-04','Grants','Saved'),
 ('get','/roles/{id}/permissions','读取角色权限配置与版本','grants:read','FR-05',None,'Grants'),
 ('put','/roles/{id}/permissions','按版本整体替换角色权限','grants:write','FR-05','Grants','Saved'),
 ('get','/accounts/{id}/permissions','计算指定账号的有效权限','grants:read','FR-06',None,'Effective'),
 ('get','/employees','分页读取脱敏员工关联选项','employees:read','FR-08',None,'EmployeePage'),
 ('get','/organizations','读取组织树节点','employees:read','FR-08',None,'Organization[]'),
 ('get','/demo/documents','读取受保护的合成文档','demo.documents:read','FR-13',None,'Documents'),
]
contract = {'openapi':'3.0.3','info':{'title':'市政统一权限管理 API','version':'0.1.0','description':'RBAC0 本机实现。管理 API 除具体权限外还要求 platform:access；正式数据范围过滤不在本轮实现。'},'servers':[{'url':'http://localhost:8080','description':'本机开发'}],'paths':{},'components':{'securitySchemes':{'SessionCookie':{'type':'apiKey','in':'cookie','name':'MUNI_SESSION'}},'schemas':schemas}}
for index,(method,path,summary,permission,requirement,body,response) in enumerate(operations,1):
    secured = permission is not None
    operation={'operationId':f'operation{index:02}','summary':summary,'tags':[path.split('/')[1]],'x-requirement-id':requirement,'x-required-permission':permission or 'public','x-implementation-status':'implemented; verification tracked in docs/测试与验收.md','description':'管理操作还要求 platform:access。权限按服务端实时计算。' if permission not in (None,'authenticated') and not permission.startswith('demo.') else '固定 1800 秒绝对会话；请求不续期。','security':[{'SessionCookie':[]}] if secured else [],'responses':{}}
    operation['parameters']=[]
    if '{id}' in path: operation['parameters'].append({'name':'id','in':'path','required':True,'schema':ID})
    if secured and method!='get': operation['parameters'].append({'name':'X-CSRF-Token','in':'header','required':True,'schema':prop('string'),'description':'从登录或 /me 响应获得，须与当前会话一致。'})
    if path in ('/accounts','/employees') and method=='get': operation['parameters'].append({'name':'page','in':'query','schema':prop('integer',minimum=1,default=1)})
    if path=='/accounts' and method=='get': operation['parameters'] += [{'name':'pageSize','in':'query','schema':prop('integer',minimum=1,maximum=100,default=20)},{'name':'q','in':'query','schema':prop('string'),'description':'按用户名或显示名称搜索。'}]
    if path=='/employees' and method=='get': operation['parameters'].append({'name':'q','in':'query','schema':prop('string',maxLength=80),'description':'数字、#编号或员工 #编号为精确查询；其他文字仅匹配组织及合成姓名，不查询原始员工真实姓名或电话。'})
    if body: operation['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
    if response:
        response_schema = prop('array',items=ref(response[:-2])) if response.endswith('[]') else ref(response)
        operation['responses']['200']={'description':'成功','content':{'application/json':{'schema':response_schema}}}
    else: operation['responses']['204']={'description':'已退出，无响应体'}
    for code,description in [('400','请求格式、字段或层级无效'),('401','未认证、会话过期或账号不可用'),('403','无操作权限、CSRF 或来源校验失败'),('404','引用对象不存在'),('409','唯一约束、版本冲突或最后管理员保护'),('429','登录尝试频率受限'),('500','内部处理失败，不返回内部细节')]:
        operation['responses'][code]={'description':description,'content':{'application/json':{'schema':ref('Error')}}}
    if path=='/session/login': operation['responses']['200']['headers']={'Set-Cookie':{'schema':prop('string'),'description':'MUNI_SESSION; Path=/api; HttpOnly; SameSite=Strict; Max-Age=1800。本机 HTTP 不设 Secure；HTTPS 部署必须开启。'}}
    contract['paths'].setdefault('/api'+path,{})[method]=operation
(DOCS/'api').mkdir(exist_ok=True)
(DOCS/'api/openapi.json').write_text(json.dumps(contract,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
lines=['# HTTP API 设计与实现映射','','本地权威契约：`docs/api/openapi.json`（OpenAPI 3.0.3）。HTTP JSON 接口与 Java 内部过程分别描述。实现及验证状态以《测试与验收》为准；Apifox 回读状态另记录，不能以导入后的平台默认状态代替测试。','','## 接口清单','','| 方法 | 路径（前缀 /api） | 用途 | 权限 | 需求 |','| --- | --- | --- | --- | --- |']
for method,path,summary,permission,requirement,_,_ in operations: lines.append(f'| {method.upper()} | `{path}` | {summary} | `{permission or "公开"}` | {requirement} |')
lines += ['','## 共用契约','','- 所有业务接口均受服务端保护；管理 API 同时要求 platform:access 和具体操作权限。没有用户名或中文角色名绕过。','- 登录 JSON 返回当前身份、有效权限、CSRF 和 expiresAt；Cookie 只在登录或退出时设置，绝对有效期 1800 秒不续期。退出、停用、锁定及密码重置使相关会话失效。','- 写请求携带 X-CSRF-Token；本机浏览器来源仅允许 localhost/127.0.0.1 的 8080、5173、4173 端口；不开放跨域凭据访问。Apifox 非浏览器请求可不带 Origin，但仍需 Cookie 和 CSRF。','- 401 表示未认证或会话不可用，403 表示当前身份无权或 CSRF/来源校验失败，409 表示版本或数据冲突。错误体为 code、message，不包含 SQL、密码或栈。','- 账号分页 page 从 1 开始，pageSize 1–100，默认 20；后端将越界分页值夹到允许范围。员工选项每页 100 条，只显示脱敏标签。','- 代码仅用小写字母、数字、点、短横线、下划线；更新不改变稳定编码。资源严格分为应用、模块、功能点；操作权限仅能属于功能点。','- 更新实体提交 version；替换授权提交独立的 grantsVersion（请求字段名为 version）。关系 ID 不得重复；全量替换在一个事务内执行，校验失败回滚。','- 员工 ID 可为空；一个员工最多关联一个账号。对外不返回凭据哈希、会话 token 哈希、真实姓名或电话。','- 不提供物理删除账号/角色/资源接口；本轮以停用及授权回收控制生命周期。数据范围与临时账号未实现。','','## 请求示例','','创建角色：','','```json','{"code":"project-reviewer","name":"项目阅览角色","description":"仅授予所需文档查看操作"}','```','','修改角色（先读取服务端 version）：','','```json','{"name":"项目阅览角色","description":"调整说明","enabled":true,"version":0}','```','','替换账号角色或角色权限（ids 是本项目实际 ID，示例不应直接当作业务授权执行）：','','```json','{"version":0,"ids":[2]}','```','','版本冲突返回 409：','','```json','{"code":"VERSION_CONFLICT","message":"配置已经变化，请重新加载后再保存"}','```','','## 字段、校验与响应','','每个请求及响应模型的字段类型、长度、必填、枚举、可空、数组上限均在 OpenAPI components/schemas 中定义。POST 成功返回 200 和 id；PUT 成功返回 200 和 saved；退出返回 204。引用不存在返回 404；实体版本不匹配（含失效对象更新）返回 409。资源类型和父节点是创建后稳定字段。','','最后管理员保护在管理写事务持有 policy_lock 后执行，任何会使完整管理账号集合变空的更新返回 LAST_ADMINISTRATOR 并回滚。已有在途请求允许完成，但之后开始鉴权的请求不使用授权缓存。']
lines += ['', '## 员工关联查找', '', '`GET /api/employees?page=1&q=...` 每页 100 条。数字、#编号或员工 #编号按精确 ID 查询；其余文字只匹配组织名称及合成姓名，不查询真实员工姓名或电话。q 最多 80 字，超长返回 400。', '', '响应包含 items、total、page、pageSize。员工查询项可带 linkedAccountId，表示已有账号关联；客户端可禁选其他账号占用的员工，但最终仍由数据库唯一约束保护。并发关联冲突返回 409 / EMPLOYEE_ALREADY_LINKED，不返回 SQL 或员工明文资料。']
(DOCS/'API设计.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')

entities = {
 'organization':['id PK','parent_id FK (nullable)','source_key UK','name / source_row'],
 'employee':['id PK','organization_id FK','source_key UK','full_name / synthetic'],
 'account':['id PK','employee_id FK, UK (nullable)','username UK','enabled / locked','version / grants_version'],
 'role':['id PK','code UK','name / enabled','version / grants_version'],
 'resource':['id PK','parent_id FK (nullable)','code UK / kind','name / enabled / version'],
 'permission':['id PK','resource_id FK','code UK','action / enabled / version'],
 'user_role':['account_id PK, FK','role_id PK, FK'],
 'role_permission':['role_id PK, FK','permission_id PK, FK'],
 'credential':['account_id PK, FK','password_hash (BCrypt)','changed_at'],
 'auth_session':['token_hash PK','account_id FK','csrf_token','created_at / expires_at'],
 'source_batch':['id PK','kind + file_sha256 UK','record_count / imported_at'],
 'permission_template_source':['id PK','source_batch_id FK','resource_id FK','source_cell / raw_text','review_status'],
 'change_log':['id PK','actor_id FK','operation / occurred_at'],
 'policy_lock':['id PK (constant 1)'],
}
relations=[('organization','o|','o{','organization','parent'),('organization','||','o{','employee','belongs'),('employee','o|','o|','account','optional_link'),('account','||','o|','credential','credential'),('account','||','o{','auth_session','sessions'),('account','||','o{','user_role','assignments'),('role','||','o{','user_role','assignments'),('role','||','o{','role_permission','grants'),('permission','||','o{','role_permission','grants'),('resource','o|','o{','resource','parent'),('resource','||','o{','permission','operations'),('source_batch','o|','o{','organization','source'),('source_batch','o|','o{','employee','source'),('source_batch','o|','o{','resource','source'),('source_batch','||','o{','permission_template_source','templates'),('resource','||','o{','permission_template_source','source'),('account','||','o{','change_log','actor')]
(DOCS/'数据库ER.model.json').write_text(json.dumps({'entities':entities,'relations':relations},indent=2),encoding='utf-8')
mermaid=['erDiagram']
for left,a,b,right,label in relations: mermaid.append(f'    {left} {a}--{b} {right} : {label}')
for name,fields in entities.items():
    mermaid.append(f'    {name} {{')
    for field in fields:
        key=field.split()[0].replace('/','_').replace('+','_')
        if key.endswith('_id') or key=='id': mermaid.append(f'        bigint {key}')
    mermaid.append('    }')
(DOCS/'数据库ER.mmd').write_text('\n'.join(mermaid)+'\n',encoding='utf-8')

sql=(ROOT/'backend/src/main/resources/db/migration/V1_1__rbac_schema_mysql84.sql').read_text(encoding='utf-8')
dictionary=['# 数据库字段字典','','此文件从当前版本化建表 SQL 提取，类型、默认值和约束原文可追踪。业务解释、基数、索引及迁移记录见《数据库设计》。']
for name,body in re.findall(r'CREATE TABLE(?: IF NOT EXISTS)? (\w+) \((.*?)\) ENGINE=InnoDB;',sql,re.S):
    dictionary += ['',f'## {name}','','| 字段 | 定义 |','| --- | --- |']
    for line in body.splitlines():
        match=re.match(r'    (\w+)\s+(.+)',line)
        if match and match[1] not in ('CONSTRAINT','PRIMARY','KEY','UNIQUE'):
            dictionary.append(f'| `{match[1]}` | `{match[2].rstrip(",")}` |')
    constraints=[line.strip().rstrip(',') for line in body.splitlines() if line.strip().startswith(('CONSTRAINT','KEY','PRIMARY'))]
    if name == 'resource': dictionary.append('| `description` | `VARCHAR(1000) NOT NULL DEFAULT 空字符串`；由 V3 增加 |')
    dictionary += ['', '约束及索引：', ''] + [f'- `{line}`' for line in constraints]
(DOCS/'数据库字段字典.md').write_text('\n'.join(dictionary)+'\n',encoding='utf-8')
print(json.dumps({'api_operations':len(operations),'entity_count':len(entities),'outputs':['API设计.md','api/openapi.json','数据库ER.mmd','数据库ER.model.json','数据库字段字典.md'],'render':'node scripts/render_er.cjs'},ensure_ascii=False))
