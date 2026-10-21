# HTTP API 设计与实现映射

本地权威契约：`docs/api/openapi.json`（OpenAPI 3.0.3）。HTTP JSON 接口与 Java 内部过程分别描述。实现及验证状态以《测试与验收》为准；Apifox 回读状态另记录，不能以导入后的平台默认状态代替测试。

## 接口清单

| 方法 | 路径（前缀 /api） | 用途 | 权限 | 需求 |
| --- | --- | --- | --- | --- |
| GET | `/health` | 检查服务及数据库连通性 | `公开` | NFR-02 |
| POST | `/session/login` | 本地凭据登录 | `公开` | FR-10 |
| POST | `/session/logout` | 退出并使当前会话失效 | `authenticated` | FR-10 |
| GET | `/me` | 读取当前身份与有效权限 | `authenticated` | FR-11 |
| GET | `/overview` | 读取管理配置概况 | `platform:access` | FR-07 |
| GET | `/accounts` | 分页查询账号 | `accounts:read` | FR-01 |
| POST | `/accounts` | 创建账号并设置凭据 | `accounts:write` | FR-01 |
| PUT | `/accounts/{id}` | 编辑、启停或锁定账号 | `accounts:write` | FR-01 |
| PUT | `/accounts/{id}/password` | 重置密码并回收该账号会话 | `accounts:write` | FR-10 |
| GET | `/roles` | 查询角色 | `roles:read` | FR-02 |
| POST | `/roles` | 创建角色 | `roles:write` | FR-02 |
| PUT | `/roles/{id}` | 编辑或启停角色 | `roles:write` | FR-02 |
| GET | `/resources` | 查询资源目录 | `resources:read` | FR-03 |
| POST | `/resources` | 创建资源节点 | `resources:write` | FR-03 |
| PUT | `/resources/{id}` | 编辑资源名称或启停资源 | `resources:write` | FR-03 |
| GET | `/permissions` | 查询操作权限 | `permissions:read` | FR-03 |
| POST | `/permissions` | 在功能点下创建操作权限 | `permissions:write` | FR-03 |
| PUT | `/permissions/{id}` | 编辑或启停操作权限 | `permissions:write` | FR-03 |
| GET | `/accounts/{id}/roles` | 读取账号角色配置与版本 | `grants:read` | FR-04 |
| PUT | `/accounts/{id}/roles` | 按版本整体替换账号角色 | `grants:write` | FR-04 |
| GET | `/roles/{id}/permissions` | 读取角色权限配置与版本 | `grants:read` | FR-05 |
| PUT | `/roles/{id}/permissions` | 按版本整体替换角色权限 | `grants:write` | FR-05 |
| GET | `/accounts/{id}/permissions` | 计算指定账号的有效权限 | `grants:read` | FR-06 |
| GET | `/employees` | 分页读取脱敏员工关联选项 | `employees:read` | FR-08 |
| GET | `/organizations` | 读取组织树节点 | `employees:read` | FR-08 |
| GET | `/demo/documents` | 读取受保护的合成文档 | `demo.documents:read` | FR-13 |

## 共用契约

- 所有业务接口均受服务端保护；管理 API 同时要求 platform:access 和具体操作权限。没有用户名或中文角色名绕过。
- 登录 JSON 返回当前身份、有效权限、CSRF 和 expiresAt；Cookie 只在登录或退出时设置，绝对有效期 1800 秒不续期。退出、停用、锁定及密码重置使相关会话失效。
- 写请求携带 X-CSRF-Token；本机浏览器来源仅允许 localhost/127.0.0.1 的 8080、5173、4173 端口；不开放跨域凭据访问。Apifox 非浏览器请求可不带 Origin，但仍需 Cookie 和 CSRF。
- 401 表示未认证或会话不可用，403 表示当前身份无权或 CSRF/来源校验失败，409 表示版本或数据冲突。错误体为 code、message，不包含 SQL、密码或栈。
- 账号分页 page 从 1 开始，pageSize 1–100，默认 20；后端将越界分页值夹到允许范围。员工选项每页 100 条，只显示脱敏标签。
- 代码仅用小写字母、数字、点、短横线、下划线；更新不改变稳定编码。资源严格分为应用、模块、功能点；操作权限仅能属于功能点。
- 更新实体提交 version；替换授权提交独立的 grantsVersion（请求字段名为 version）。关系 ID 不得重复；全量替换在一个事务内执行，校验失败回滚。
- 员工 ID 可为空；一个员工最多关联一个账号。对外不返回凭据哈希、会话 token 哈希、真实姓名或电话。
- 不提供物理删除账号/角色/资源接口；本轮以停用及授权回收控制生命周期。数据范围与临时账号未实现。

## 请求示例

创建角色：

```json
{"code":"project-reviewer","name":"项目阅览角色","description":"仅授予所需文档查看操作"}
```

修改角色（先读取服务端 version）：

```json
{"name":"项目阅览角色","description":"调整说明","enabled":true,"version":0}
```

替换账号角色或角色权限（ids 是本项目实际 ID，示例不应直接当作业务授权执行）：

```json
{"version":0,"ids":[2]}
```

版本冲突返回 409：

```json
{"code":"VERSION_CONFLICT","message":"配置已经变化，请重新加载后再保存"}
```

## 字段、校验与响应

每个请求及响应模型的字段类型、长度、必填、枚举、可空、数组上限均在 OpenAPI components/schemas 中定义。POST 成功返回 200 和 id；PUT 成功返回 200 和 saved；退出返回 204。引用不存在返回 404；实体版本不匹配（含失效对象更新）返回 409。资源类型和父节点是创建后稳定字段。

最后管理员保护在管理写事务持有 policy_lock 后执行，任何会使完整管理账号集合变空的更新返回 LAST_ADMINISTRATOR 并回滚。已有在途请求允许完成，但之后开始鉴权的请求不使用授权缓存。

## 员工关联查找

`GET /api/employees?page=1&q=...` 每页 100 条。数字、#编号或员工 #编号按精确 ID 查询；其余文字只匹配组织名称及合成姓名，不查询真实员工姓名或电话。q 最多 80 字，超长返回 400。

响应包含 items、total、page、pageSize。员工查询项可带 linkedAccountId，表示已有账号关联；客户端可禁选其他账号占用的员工，但最终仍由数据库唯一约束保护。并发关联冲突返回 409 / EMPLOYEE_ALREADY_LINKED，不返回 SQL 或员工明文资料。
