# 市政公司统一权限管理系统

面向课程首检的本机 RBAC0 初版，已完成开发设计、数据库与 ER、API、Java 后端和 Vue 前端。更新时间：2026-10-21（Asia/Shanghai）。

## 运行

打开 <http://127.0.0.1:8080>。管理员 project_admin，普通演示账号 demo_user，密码按用户要求均为 1234；详情见本机 .local/测试账号.md。没有程序内置默认密码。

```powershell
.\scripts\Start-Platform.ps1 -SkipBuild
```

当前已经启动，无需重复运行。重新构建前先执行 .\scripts\Stop-Platform.ps1，再运行不带 -SkipBuild 的启动命令。环境、配置与操作步骤见[运行与使用](docs/运行与使用.md)。

## 已完成及验证范围

| 成果 | 状态与证据 |
| --- | --- |
| 基础管理 | 账号、角色、资源、操作权限的查询、创建、编辑与启停；账号锁定与密码重置 |
| 授权闭环 | 用户角色、角色权限分配与回收；有效权限并集；提交后新鉴权立即生效 |
| 身份与入口 | BCrypt、MySQL 会话、HttpOnly Cookie、CSRF、固定 30 分钟过期；管理员双入口及普通员工入口 |
| 一致性保护 | 员工账号最多一对一、乐观版本冲突、事务回滚、最后完整管理员保护 |
| 数据库 | MySQL 8.4.8；14 张业务表及 Flyway 历史表；独立开发与测试库 |
| 三份 Excel | 已导入 111 条组织、10,000 条员工、492 个来源资源节点、1,795 个待审模板单元格；未自动建账号或授权，原件未改动 |
| API / Apifox | 26 个接口、29 个模型；已导入新项目 8950931 的 main/默认模块；导出语义比较通过 |
| 测试 | 15 项真实 MySQL 集成测试、4 项前端筛选回归、18 项 HTTP/契约检查通过；员工查询与实际页面验收见测试记录 |

原始数据与合成演示分开。员工关联 API 对真实员工只显示编号；组织、岗位和来源模板不自动转化为授权。系统只在本机运行。

## 文档入口

- [GitHub 与本机备份](docs/GitHub与本机备份.md)：仓库范围、完整数据库快照与后续提交方法
- [首检交付目录](docs/首检交付目录.md)、[8–10 分钟演示脚本](docs/首检演示脚本.md)、[本次预演记录](docs/首检预演记录.md)
- [账号、员工与角色说明](docs/账号员工与角色说明.md)：概念、关联基数、现有账号与入口规则
- [体验完善记录](docs/体验完善记录.md)：员工查找、目录筛选、授权搜索与变更预览
- [需求与范围](docs/需求与范围.md)、[阶段计划与当前进度](docs/分阶段计划.md)、[资料审计](docs/资料审计.md)
- [开发设计说明](docs/开发设计说明.md)：上下文图、DFD、功能分解、关键加工及内聚耦合分析
- [数据库设计](docs/数据库设计.md)、[字段字典](docs/数据库字段字典.md)、[ER 图](docs/数据库ER.svg)、[ER Mermaid 源码](docs/数据库ER.mmd)
- [界面设计](docs/界面设计.md)、[API 设计](docs/API设计.md)、[OpenAPI JSON](docs/api/openapi.json)
- [测试与验收](docs/测试与验收.md)、[运行与使用](docs/运行与使用.md)、[开发环境配置](docs/开发环境配置.md)

## 目录与复现

backend/ 为 Java 21、Spring Boot 3.5.16、MyBatis 工程；frontend/ 为 Vue 3、TypeScript、Tailwind 与 Vite 工程。后端 JAR 包含前端构建产物。版本与构建结果见设计及验收记录。

database/ 保存环境 SQL 和历史失败迁移原件；实际迁移在 backend/src/main/resources/db/migration/。scripts/audit_sources.py 只读审计原始文件；import_sources.py 默认预检，--apply 仅允许首次初始化；verify_apifox.py 比较本地 API 与 Apifox 导出。工具不会批量删除文件或清空数据库。

本机秘密与构建依赖位于被忽略的 .local/、.tools/，不得推送。根目录启动说明仅为需求来源，其中旧地址不作为当前配置。

## 技能与后续

已安装并使用 grill-me/grilling、frontend-design；前者用于确认关键业务边界，后者用于管理界面设计。原始资料审计使用 Spreadsheets 技能；Navicat/Apifox 操作使用 Computer Use。

首检日期按用户文字转述以 2026-10-27 为最早目标，建议 10 月 26 日前完成一次人工演练。尚未实现 RBAC1/RBAC2、数据范围、临时账号生命周期、增量导入或组织编辑；万级并发和 30ms 指标尚未定义口径、未压测，不宣称达标。先复核首检闭环与教师提交格式，再扩展后续阶段。

本轮已增加首页账号密码登录窗口与持续可见的会话倒计时；完整授权演练见 [完整操作流程结果 TXT](docs/完整操作流程结果.txt)。
