"""Create a local RBAC0 review snapshot with sources, docs, and hash evidence.

Uses an explicit input allowlist; excludes local credentials, dependencies,
build outputs, and original Excel. Related: docs/首检交付目录.md and MANIFEST.json.
Every run creates a new directory and ZIP without deleting or replacing files.
"""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.local', '.tools', 'node_modules', 'target', 'dist', '__pycache__', '.git'}
FRONTEND_FILES = (
    'package.json', 'package-lock.json', 'index.html', 'vite.config.ts', 'tsconfig.json',
)


def digest(path):
    """Hash actual bytes for both the source snapshot and the archive readback."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    """Visit only reviewed source/document folders, never dependency directories."""
    selected = {ROOT / 'README.md', ROOT / '.gitignore', ROOT / 'backend/pom.xml',
                ROOT / 'backend/mvnw', ROOT / 'backend/mvnw.cmd'}
    for folder in ('backend/src', 'backend/.mvn', 'frontend/src', 'frontend/test',
                   'database', 'docs'):
        selected.update(path for path in (ROOT / folder).rglob('*') if path.is_file())
    selected.update(ROOT / 'frontend' / name for name in FRONTEND_FILES)
    selected.update(path for path in (ROOT / 'scripts').iterdir()
                    if path.is_file() and path.suffix in {'.py', '.ps1', '.cjs'})
    result = []
    for path in sorted(selected):
        relative = path.relative_to(ROOT)
        if any(part in EXCLUDED for part in relative.parts):
            continue
        if path.suffix.lower() in {'.xlsx', '.xls', '.jar', '.class', '.pyc', '.log'}:
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError(f'Unexpected source link: {relative}')
        if not path.is_file():
            raise FileNotFoundError(relative)
        result.append(path)
    return result


def check_links(folder):
    """Check local Markdown links inside the copied artifact, not the workspace."""
    count = 0
    for path in [folder / 'README.md', *(folder / 'docs').rglob('*.md')]:
        for target in re.findall(r'\]\(([^)]+)\)', path.read_text(encoding='utf-8-sig')):
            target = target.strip('<>').split('#', 1)[0]
            if not target or '://' in target or target.startswith('mailto:'):
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.is_relative_to(folder) or not resolved.exists():
                raise ValueError(f'Broken artifact link: {path.relative_to(folder)} -> {target}')
            count += 1
    return count


def main():
    now = datetime.now(timezone(timedelta(hours=8)))
    destination = ROOT / 'deliverables' / ('首检_' + now.strftime('%Y%m%dT%H%M%S%f'))
    # exist_ok=False and ZIP mode x preserve every earlier delivery snapshot.
    destination.mkdir(parents=True, exist_ok=False)
    records = []
    for source in inputs():
        relative = source.relative_to(ROOT)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if digest(source) != digest(target):
            raise ValueError(f'Copy mismatch: {relative}')
        records.append({'path': relative.as_posix(), 'bytes': target.stat().st_size,
                        'sha256': digest(target)})
    links = check_links(destination)
    manifest = {'created_at': now.isoformat(timespec='seconds'),
                'purpose': 'Local RBAC0 source and documentation review; not a preconfigured installation',
                'excluded': sorted(EXCLUDED) + ['original Excel', 'build binaries'],
                'files': records}
    (destination / 'MANIFEST.json').write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    archive = destination.with_suffix('.zip')
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as output:
        for path in sorted(destination.rglob('*')):
            if path.is_file():
                output.write(path, path.relative_to(destination).as_posix())
    # Read all ZIP members and verify bytes; merely creating a ZIP is insufficient.
    with zipfile.ZipFile(archive) as zipped:
        if zipped.testzip() is not None:
            raise ValueError('ZIP CRC check failed')
        expected = {row['path'] for row in records} | {'MANIFEST.json'}
        if set(zipped.namelist()) != expected:
            raise ValueError('ZIP entries differ from manifest')
        for row in records:
            content = zipped.read(row['path'])
            if len(content) != row['bytes'] or hashlib.sha256(content).hexdigest() != row['sha256']:
                raise ValueError(f'ZIP hash mismatch: {row["path"]}')
        if zipped.read('MANIFEST.json') != (destination / 'MANIFEST.json').read_bytes():
            raise ValueError('Archived manifest differs')
    report = {'result': 'passed', 'created_at': now.isoformat(timespec='seconds'),
              'directory': destination.relative_to(ROOT).as_posix(),
              'archive': archive.relative_to(ROOT).as_posix(),
              'files': len(records), 'local_markdown_links': links,
              'archive_bytes': archive.stat().st_size, 'archive_sha256': digest(archive),
              'checks': ['source/copy hashes', 'local Markdown links', 'ZIP CRC',
                         'ZIP exact entry set', 'all ZIP member hashes', 'archived manifest']}
    archive.with_suffix('.verification.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
