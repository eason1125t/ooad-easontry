"""Preflight and optionally initialize all three audited sources in the development database.

Uses audit_sources.py for OOXML/merge semantics. One DML transaction imports
organizations, employees, resource paths and PENDING authorization templates.
Never creates employee login accounts or guesses template grants.
"""
from pathlib import Path
import argparse
import json
import sys
from audit_sources import FILES, read_workbook, analyze_organizations, analyze_employees, analyze_permissions, sha256

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools' / 'setup-deps'))
import pymysql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Commit the first initialization in the named development schema')
    args = parser.parse_args()
    audit = json.loads((ROOT / 'docs/audit/source-audit.json').read_text(encoding='utf-8'))
    sheets = {}
    for kind, filename in FILES.items():
        metadata, tabs = read_workbook(ROOT / filename)
        if metadata['sha256'] != audit['sources'][kind]['sha256'] or len(tabs) != 1:
            raise ValueError('来源文件已改变，请先重新审计；未写数据库。')
        sheets[kind] = tabs[0]
    org_result, paths = analyze_organizations(sheets['organizations'])
    employee_result = analyze_employees(sheets['employees'], paths)
    permission_result = analyze_permissions(sheets['permissions'])
    if employee_result['organization_unmatched_count'] or employee_result['username_duplicates']['group_count']:
        raise ValueError('员工归属或来源用户名预检失败；未写数据库。')
    summary = {'organizations':org_result['records'],'employees':employee_result['records'],
               'applications':permission_result['application_count'],'module_groups':permission_result['application_module_group_count'],
               'features':permission_result['records'],'pending_template_cells':permission_result['role_cell_count'],
               'true_blank_template_cells':permission_result['true_blank_role_cells'],
               'accounts_created':0,'automatic_grants_created':0,'applied':False}
    if args.apply:
        settings = dict(line.split('=',1) for line in (ROOT/'.local/application-local.properties').read_text(encoding='utf-8').splitlines() if '=' in line)
        connection = pymysql.connect(host='localhost',port=3306,user=settings['spring.datasource.username'],password=settings['spring.datasource.password'],database='municipal_access_ooad_dev',charset='utf8mb4',autocommit=False)
        try:
            with connection.cursor() as cursor:
                cursor.execute('SELECT id FROM policy_lock WHERE id=1 FOR UPDATE')
                cursor.execute("SELECT COUNT(*) FROM source_batch WHERE kind IN ('organizations','employees','permissions')")
                if cursor.fetchone()[0]: raise ValueError('首次初始化已执行，已停止；不合并、不覆盖、不重复导入。')
                batches={}
                for kind,filename in FILES.items():
                    cursor.execute('INSERT INTO source_batch(kind,file_sha256,file_name,record_count) VALUES(%s,%s,%s,%s)',(kind,audit['sources'][kind]['sha256'],filename,summary[{'organizations':'organizations','employees':'employees','permissions':'features'}[kind]]))
                    batches[kind]=cursor.lastrowid
                org=sheets['organizations']; org_ids={}
                # Resolve parents topologically; source row ordering is not a required invariant.
                pending=[row for row in org.rows if row>1 and org.raw('A',row)]
                while pending:
                    progressed=False
                    for row in pending.copy():
                        name,parent=org.raw('A',row),org.raw('C',row)
                        if parent and parent not in org_ids: continue
                        cursor.execute('INSERT INTO organization(source_key,name,parent_id,source_batch_id,source_row) VALUES(%s,%s,%s,%s,%s)',('xlsx:'+name,name,org_ids.get(parent),batches['organizations'],row))
                        org_ids[name]=cursor.lastrowid;pending.remove(row);progressed=True
                    if not progressed: raise ValueError('组织存在未解析父节点，事务回滚。')
                employee=sheets['employees']; employees=[]
                for row in employee.rows:
                    if row<=1 or not employee.raw('B',row): continue
                    branch,department=employee.raw('C',row),employee.raw('D',row)
                    target=department if branch=='总公司' or department==branch else branch+'-'+department
                    employees.append(('xlsx:'+employee.raw('B',row),employee.raw('A',row),org_ids[target],employee.raw('E',row),employee.raw('F',row),batches['employees'],row))
                cursor.executemany('INSERT INTO employee(source_key,full_name,organization_id,position_name,phone,source_batch_id,source_row) VALUES(%s,%s,%s,%s,%s,%s,%s)',employees)
                sheet=sheets['permissions'];apps={};modules={};templates=[]
                for row in sheet.rows:
                    if row<=2 or not sheet.raw('C',row): continue
                    app,module=sheet.resolved('A',row),sheet.resolved('B',row)
                    if app not in apps:
                        code=f'xlsx.app.{len(apps)+1:03}'
                        cursor.execute('INSERT INTO resource(code,name,kind,source_batch_id,source_row) VALUES(%s,%s,%s,%s,%s)',(code,app,'APPLICATION',batches['permissions'],int(sheet.anchor(f'A{row}')[1:])))
                        apps[app]=cursor.lastrowid
                    if (app,module) not in modules:
                        code=f'xlsx.module.{len(modules)+1:03}'
                        cursor.execute('INSERT INTO resource(parent_id,code,name,kind,source_batch_id,source_row) VALUES(%s,%s,%s,%s,%s,%s)',(apps[app],code,module,'MODULE',batches['permissions'],int(sheet.anchor(f'B{row}')[1:])))
                        modules[app,module]=cursor.lastrowid
                    cursor.execute('INSERT INTO resource(parent_id,code,name,kind,description,source_batch_id,source_row) VALUES(%s,%s,%s,%s,%s,%s,%s)',(modules[app,module],f'xlsx.feature.{row:03}',sheet.raw('C',row),'FEATURE',sheet.raw('D',row),batches['permissions'],row))
                    feature=cursor.lastrowid
                    for column in 'EFGHI': templates.append((batches['permissions'],feature,f'{column}{row}',sheet.raw(column,2),sheet.resolved(column,row) or None))
                cursor.executemany('INSERT INTO permission_template_source(source_batch_id,resource_id,source_cell,role_label,raw_text) VALUES(%s,%s,%s,%s,%s)',templates)
            connection.commit();summary['applied']=True
        except Exception:
            connection.rollback();raise
        finally:
            connection.close()
    report_name = 'import-result.json' if args.apply else 'import-preview.json'
    (ROOT/'docs/audit'/report_name).write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))


if __name__=='__main__':
    try: main()
    except (pymysql.MySQLError, ValueError) as failure:
        # Driver messages may include bound employee values; never print them.
        print(str(failure) if isinstance(failure,ValueError) else '数据库初始化失败，事务已回滚；未输出原始个人资料。',file=sys.stderr)
        raise SystemExit(1)
