"""Run the complete local authorization rehearsal and save a human-readable TXT.

Uses the running Spring API with separate administrator/employee cookie jars.
Only creates or adjusts the named flow_user/flow-reader-* synthetic fixtures;
it never imports source staff or prints session tokens/database credentials.
Related: docs/完整操作流程结果.txt and backend integration tests.
"""
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, build_opener, HTTPCookieProcessor
import http.cookiejar
import json
import os

ROOT = Path(__file__).resolve().parents[1]
BASE = "http://127.0.0.1:8080"
PASSWORD = os.environ.get("PLATFORM_TEST_PASSWORD", "1234")  # User-selected local rehearsal credential.
LINES = []


def note(text):
    LINES.append(text)


class Client:
    def __init__(self, label):
        self.label, self.csrf = label, None
        self.opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.headers = {}

    def call(self, method, path, expected=200, body=None, csrf=True):
        headers = {"Content-Type": "application/json"}
        if csrf and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        request = Request(BASE + path, method=method, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            response = self.opener.open(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            actual = response.status
            raw = response.read()
            data = json.loads(raw) if raw else None
            self.headers = dict(response.headers)
        note(f"  {self.label}: {method} {path}；预期 HTTP {expected}，实际 {actual}；{'通过' if actual == expected else '失败'}")
        assert actual == expected, f"Unexpected HTTP status: {actual}"
        if isinstance(data, dict) and data.get("csrfToken"):
            self.csrf = data["csrfToken"]
        if expected >= 400:
            note(f"  错误码：{data['code']}；反馈：{data['message']}")
        return data

    def login(self, username):
        return self.call("POST", "/api/session/login", body={"username": username, "password": PASSWORD})


def step(title):
    note("\n" + title)


def replace(admin, path, ids):
    current = admin.call("GET", path)
    admin.call("PUT", path, body={"version": current["version"], "ids": ids})


def main():
    now = datetime.now(timezone(timedelta(hours=8)))
    note("市政统一权限管理平台：完整操作流程及实际结果")
    note("执行时间：" + now.isoformat(timespec="seconds"))
    note("执行方式：真实运行版本的 HTTP API 演练；管理员与员工使用独立 Cookie 会话。")
    note("地址：" + BASE + "；开发库：municipal_access_ooad_dev。")
    note("合成账号：project_admin（管理员）、demo_user（演示）、flow_user（本次演练）；本机密码均为 1234。")
    note("本记录不包含 MySQL 密码、Cookie、CSRF 或任何真实员工明文资料。")
    admin, employee, anonymous = Client("管理员"), Client("演练员工"), Client("未登录客户端")

    step("01. 检查服务、管理员登录和 30 分钟绝对期限")
    admin.call("GET", "/api/health")
    before = datetime.now(timezone.utc)
    identity = admin.login("project_admin")
    duration = (datetime.fromisoformat(identity["expiresAt"].replace("Z", "+00:00")) - before).total_seconds()
    assert 1799 <= duration <= 1802
    note(f"  管理员登录返回到期时刻，距请求开始约 {duration:.2f} 秒；规则为固定 1800 秒。")
    assert "platform:access" in identity["permissions"]

    step("02. 准备合成账号与两个独立角色")
    accounts = admin.call("GET", "/api/accounts?q=flow_user")["items"]
    account = next((row for row in accounts if row["username"] == "flow_user"), None)
    if not account:
        account_id = admin.call("POST", "/api/accounts", body={"username": "flow_user", "displayName": "授权流程演练账号", "password": PASSWORD})["id"]
    else:
        account_id = account["id"]
        admin.call("PUT", f"/api/accounts/{account_id}", body={**account, "enabled": True, "locked": False, "employeeId": None})
    catalog = admin.call("GET", "/api/roles")
    role_ids = []
    for suffix in ("a", "b"):
        code = "flow-reader-" + suffix
        existing = next((row for row in catalog if row["code"] == code), None)
        if existing:
            role_id = existing["id"]
            admin.call("PUT", f"/api/roles/{role_id}", body={**existing, "enabled": True})
        else:
            role_id = admin.call("POST", "/api/roles", body={"code": code, "name": "流程演练阅读角色 " + suffix.upper(), "description": "仅用于合成文档授权演练"})["id"]
        role_ids.append(role_id)
    a, b = role_ids
    permission_id = next(row["id"] for row in admin.call("GET", "/api/permissions") if row["code"] == "demo.documents:read")
    note(f"  演练账号 ID={account_id}；角色 A={a}、B={b}；文档查看权限 ID={permission_id}。员工关联为空，不冒认来源员工。")
    for role_id in role_ids:
        replace(admin, f"/api/roles/{role_id}/permissions", [])
    replace(admin, f"/api/accounts/{account_id}/roles", [])

    step("03. 无角色账号登录；默认拒绝访问")
    original_employee = employee.login("flow_user")
    assert original_employee["permissions"] == []
    employee.call("GET", "/api/demo/documents", 403)
    anonymous.call("GET", "/api/accounts", 401)

    step("04. 为角色 A 分配文档查看权限，再给账号分配角色 A")
    replace(admin, f"/api/roles/{a}/permissions", [permission_id])
    replace(admin, f"/api/accounts/{account_id}/roles", [a])
    documents = employee.call("GET", "/api/demo/documents")
    assert len(documents["items"]) == 2
    note("  员工保持原 Cookie、没有重新登录，立即读取到 2 条合成文档。")
    employee.call("GET", "/api/accounts", 403)

    step("05. 增加角色 B 的同一权限，验证权限并集去重")
    replace(admin, f"/api/roles/{b}/permissions", [permission_id])
    replace(admin, f"/api/accounts/{account_id}/roles", [a, b])
    current = employee.call("GET", "/api/me")
    assert current["permissions"] == ["demo.documents:read"]
    note("  两个角色提供同一权限，有效权限只有 1 项。")

    step("06. 回收角色 A 的权限，保留角色 B 路径")
    replace(admin, f"/api/roles/{a}/permissions", [])
    employee.call("GET", "/api/demo/documents")
    note("  结果符合并集规则：仍存在 B 的授权路径，读取继续成功。")

    step("07. 回收角色 B 的权限；随后请求立即拒绝")
    replace(admin, f"/api/roles/{b}/permissions", [])
    employee.call("GET", "/api/demo/documents", 403)
    current = employee.call("GET", "/api/me")
    assert current["permissions"] == []
    assert current["expiresAt"] == original_employee["expiresAt"]
    assert not any(key.lower() == "set-cookie" for key in employee.headers)
    note("  会话仍有效但权限为空；expiresAt 与原登录完全相同，刷新/授权没有续期。")

    step("08. 恢复最小阅读权限，并验证账号停用与会话回收")
    replace(admin, f"/api/roles/{a}/permissions", [permission_id])
    replace(admin, f"/api/accounts/{account_id}/roles", [a])
    employee.call("GET", "/api/demo/documents")
    account = next(row for row in admin.call("GET", "/api/accounts?q=flow_user")["items"] if row["id"] == account_id)
    admin.call("PUT", f"/api/accounts/{account_id}", body={**account, "enabled": False})
    employee.call("GET", "/api/me", 401)
    account = next(row for row in admin.call("GET", "/api/accounts?q=flow_user")["items"] if row["id"] == account_id)
    admin.call("PUT", f"/api/accounts/{account_id}", body={**account, "enabled": True})
    employee.login("flow_user")
    employee.call("GET", "/api/demo/documents")

    step("09. 验证最后完整管理员保护及事务回滚")
    administrator = next(row for row in admin.call("GET", "/api/accounts?q=project_admin")["items"] if row["id"] == identity["id"])
    denied = admin.call("PUT", f"/api/accounts/{identity['id']}", 409, {**administrator, "enabled": False})
    assert denied["code"] == "LAST_ADMINISTRATOR"
    after = next(row for row in admin.call("GET", "/api/accounts?q=project_admin")["items"] if row["id"] == identity["id"])
    assert after["enabled"] and after["version"] == administrator["version"]
    note("  管理员保持启用且版本未增加，失败修改已回滚。")

    step("10. 验证并发版本冲突与 CSRF")
    role = next(row for row in admin.call("GET", "/api/roles") if row["id"] == a)
    admin.call("PUT", f"/api/roles/{a}", body={**role, "name": "流程演练阅读角色 A"})
    admin.call("PUT", f"/api/roles/{a}", 409, {**role, "name": "不应覆盖的旧版本"})
    admin.call("PUT", f"/api/roles/{a}", 403, {**role, "name": "不应绕过 CSRF"}, csrf=False)

    step("11. 验证退出；保留可复查的最小权限测试账号")
    employee.call("POST", "/api/session/logout", 204)
    employee.call("GET", "/api/me", 401)
    admin.call("POST", "/api/session/logout", 204)
    note("  最终：flow_user 启用、未锁定、无员工关联；仅分配角色 A 和文档查看权限；B 无权限。")
    note("\n自动演练结论：以上全部断言通过。没有批量删除文件、清空库、修改原始 Excel 或更改 MySQL 账号密码。")
    note("精确 1799/1800/1801 秒过期边界由本轮 JUnit 可控时钟验证，未把等待几秒冒充实际等待 30 分钟。")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        note("\n演练失败，已停止后续步骤：" + type(error).__name__ + " " + str(error))
        raise
    finally:
        path = ROOT / "docs/完整操作流程结果.txt"
        path.write_text("\n".join(LINES) + "\n", encoding="utf-8-sig")
        print("流程记录已保存：docs/完整操作流程结果.txt")
