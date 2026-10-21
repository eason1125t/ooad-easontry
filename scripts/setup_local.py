"""Native masked-input setup for the local MySQL accounts and first web admin.

Creates ignored local configuration consumed by Spring/Flyway and Bootstrap.
Passwords are never printed or passed on a command line. This is an interactive
user tool, not automated authentication or a public application endpoint.
"""
from pathlib import Path
import json
import re
import secrets
import sys
import tkinter as tk
from tkinter import messagebox, ttk

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools' / 'setup-deps'))
import bcrypt
import pymysql


def configure() -> None:
    """Preflight names first, then create schema-scoped accounts without resetting data."""
    root_password, admin_name, admin_password = [entry.get() for entry in entries]
    if not re.fullmatch(r'[a-z][a-z0-9._-]{2,63}', admin_name):
        raise ValueError('管理员用户名须为 3–64 位小写字母、数字、点、下划线或短横线。')
    if any(not 4 <= len(value) <= 64 for value in (admin_password,)):
        raise ValueError('本机管理员密码须为 4–64 位。')
    if any(len(value.encode('utf-8')) > 72 for value in (admin_password,)):
        raise ValueError('BCrypt 密码 UTF-8 长度最多 72 字节。')
    local = ROOT / '.local'
    if (local / 'application-local.properties').exists():
        raise ValueError('本机配置已存在，已停止，避免覆盖数据库账号与凭据。')
    app_password, migration_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    connection = pymysql.connect(host='localhost', port=3306, user='root', password=root_password, charset='utf8mb4', autocommit=True)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) FROM mysql.user WHERE User IN ('municipal_ooad_app','municipal_ooad_migrator')")
            if cursor.fetchone()[0]:
                raise ValueError('预定数据库账号已存在，已停止；不会重置现有账号密码。')
            cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='municipal_access_ooad_dev'")
            if cursor.fetchone()[0] != 1:
                raise ValueError('独立开发库不存在，请按安装指南先创建 municipal_access_ooad_dev。')
            cursor.execute("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='municipal_access_ooad_test'")
            if cursor.fetchone()[0]:
                raise ValueError('预定测试库已存在，已停止；不会覆盖或清空。')
            cursor.execute('CREATE DATABASE municipal_access_ooad_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
            cursor.execute("CREATE USER 'municipal_ooad_app'@'localhost' IDENTIFIED BY %s", (app_password,))
            cursor.execute("CREATE USER 'municipal_ooad_migrator'@'localhost' IDENTIFIED BY %s", (migration_password,))
            for database in ('municipal_access_ooad_dev', 'municipal_access_ooad_test'):
                cursor.execute(f"GRANT SELECT, INSERT, UPDATE, DELETE ON {database}.* TO 'municipal_ooad_app'@'localhost'")
                cursor.execute(f"GRANT ALL PRIVILEGES ON {database}.* TO 'municipal_ooad_migrator'@'localhost'")
    finally:
        connection.close()
    local.mkdir(exist_ok=True)
    (local / 'application-local.properties').write_text(
        'spring.datasource.username=municipal_ooad_app\n'
        f'spring.datasource.password={app_password}\n'
        'spring.flyway.user=municipal_ooad_migrator\n'
        f'spring.flyway.password={migration_password}\n', encoding='utf-8')
    bootstrap = {'username': admin_name,
                 'passwordHash': bcrypt.hashpw(admin_password.encode(), bcrypt.gensalt(rounds=12)).decode()}
    (local / 'bootstrap.json').write_text(json.dumps(bootstrap), encoding='utf-8')
    (local / 'setup-status.json').write_text(json.dumps({'configured': True, 'database': 'municipal_access_ooad_dev', 'testDatabase': 'municipal_access_ooad_test'}), encoding='utf-8')
    for entry in entries:
        entry.delete(0, tk.END)


def submit() -> None:
    try:
        configure()
    except (ValueError, pymysql.MySQLError) as failure:
        # MySQL errors can echo statements. Only known safe advice is shown.
        safe_message = str(failure) if isinstance(failure, ValueError) else 'MySQL 连接或授权失败，请检查本机 root 密码及权限；未输出凭据。若初始化部分成功，请先检查已有对象，不要重复覆盖。'
        messagebox.showerror('初始化未完成', safe_message)
        return
    messagebox.showinfo('本机配置完成', '数据库应用/迁移账号和独立测试库已建立。管理员设置已写入被忽略的本机配置；后端首次启动时初始化。')
    window.destroy()


window = tk.Tk()
window.title('市政权限平台 · 本机初始化')
window.geometry('640x390')
frame = ttk.Frame(window, padding=24)
frame.pack(fill='both', expand=True)
ttk.Label(frame, text='本机环境初始化与凭据配置', font=('Microsoft YaHei UI', 12)).pack(anchor='w', pady=(0, 8))
ttk.Label(frame, text='将建立独立测试库、数据库应用/迁移账号和网页管理员。').pack(anchor='w', pady=(0, 14))
entries = []
for label, hidden, default in [('MySQL root 密码', True, ''), ('网页管理员用户名', False, 'project_admin'), ('网页管理员密码（4–64 位）', True, '')]:
    row = ttk.Frame(frame)
    row.pack(fill='x', pady=6)
    ttk.Label(row, text=label, width=31).pack(side='left')
    entry = ttk.Entry(row, show='●' if hidden else '')
    entry.insert(0, default)
    entry.pack(side='left', fill='x', expand=True)
    entries.append(entry)
ttk.Button(frame, text='配置本机环境', command=submit).pack(anchor='e', pady=20)
entries[0].focus_set()
window.mainloop()
