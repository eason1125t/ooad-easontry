# 版本管理与本机备份指南

日期：2026-10-21。仓库：[eason1125t/ooad-easontry](https://github.com/eason1125t/ooad-easontry)，2026-10-21 核验为私有仓库。开发主分支 main；首次本地进度提交为 cdef8fa。

关机后另存的数据库、Apifox 原生及 OpenAPI 快照见[关机恢复检查记录](关机恢复检查记录.md)，不覆盖下述首次备份。

## GitHub 保存的内容

源码、开发文档、ER、数据库迁移和合成种子、完整 OpenAPI、Apifox 导出、聚合测试结果及合成页面截图。原有 98 个版本文件已检查，不包含本机数据库密码、初始化密码哈希、GitHub Token、私钥或原始 Excel。

数据库结构与合成数据脚本位于 backend/src/main/resources/db/migration。它们可以重建设计中的结构和种子，但不等于当前数据库全量内容。接口权威文件为 docs/api/openapi.json；Apifox 回读为 docs/audit/apifox-export.json，已有 26 个接口操作、29 个模型，比较报告为 passed。

当前已保存的是 Apifox 接口定义，其范围不含整个云项目的全部历史和配置。若需保留 Apifox 专有调试用例、环境配置或测试场景，应额外导出对应格式并检查变量、认证头及请求历史；对应运行凭据未纳入远程仓库。

## 本机数据库快照

2026-10-21 首次完整开发库 SQL：`.local/backups/20261021T181713/municipal_access_ooad_dev.sql`。

同目录 README.txt 说明范围，backup-verification.json 记录大小、SHA-256 与导出检查。使用既有 mysqldump 8.4.8、single-transaction 导出开发库结构与数据；保留 Flyway 历史，不导出 MySQL 服务器用户/全局授权或测试库。临时客户端密码文件在导出后已单独移除。导出没有更改现有表或账号。

同目录的 `项目进度_数据库与接口.zip` 将该 SQL、说明及当前 OpenAPI / Apifox 接口定义一起保存；archive-verification.json 记录 ZIP CRC、成员内容与 SHA-256 检查。这个本机备份包不上传，也不等同于含专有调试配置的 Apifox 原生全项目备份。

SQL 含来源员工、账号密码哈希及会话记录，保持在被忽略的 .local 下，仅作本机备份，不纳入 Git。快照属于导出时刻，之后的修改需再备份。它不是加密文件；搬到其他设备前应使用可信的加密备份方式，密码另行保存。

已检查 mysqldump 成功退出、完成标记及员工表结构/数据存在。尚未做完整恢复演练，不把文件导出成功当作恢复已验证。恢复应使用隔离、空白的 MySQL 环境；SQL 带有原开发库名，不应直接导入当前实例覆盖数据；恢复后先处理旧会话与凭据。

## 后续保存进度

在项目根目录执行以下命令，先审阅变更再提交。PowerShell 中 git diff 的分页查看界面按 q 退出。

```powershell
git status --short
git diff
git add README.md .gitignore backend frontend document-system database docs scripts
git diff --cached --stat
git commit -m "docs: 规范项目交付文档"
git push origin main
```

不要给已应用的迁移改名或覆盖其内容，新结构变更新增迁移。不要强制添加被忽略的本机配置、SQL 备份或原始资料。仓库的 ZIP 下载只包含版本文件，不包含独立保存在本机的完整数据库快照。

## 员工接入版本补充

当前权威契约为 docs/api/openapi.json（30 个 RBAC 操作）与 docs/api/documents.openapi.json（8 个公文操作）。旧 26 操作比较保留历史；V4 同步结果见《公文系统接入验收记录》。独立公文源码在 document-system，打包已加入该目录；H2 文件及应用密钥仍只在 .local，不进入 Git。迁移前备份校验值及位置同样记在接入验收记录。

提交和推送前确认 git branch --show-current 与目标分支。在 detached HEAD 的托管工作区中，先按工作区交付流程合并或建立所需分支，不能假定 git push origin main 会推送当前工作区提交。源码 ZIP 不含本机数据库或应用密钥，新环境需重新初始化。
