// Pure filtering and grant-diff helpers used by App.vue and its Node regression tests.
// Resource searches retain ancestor context; filtering never mutates selected grants.
export type CatalogRow = { id: number; name: string; code: string; enabled: boolean; parentId?: number | null };

export function matchesCatalog(row: CatalogRow, query: string, status = ''): boolean {
  const term = query.trim().toLocaleLowerCase();
  return (!status || row.enabled === (status === 'enabled'))
    && (!term || `${row.name} ${row.code}`.toLocaleLowerCase().includes(term));
}

export function filterResourceTree<T extends CatalogRow>(rows: T[], query: string, status = ''): T[] {
  if (!query.trim() && !status) return rows;
  const byId = new Map(rows.map(row => [row.id, row]));
  const visible = new Set<number>();
  for (const row of rows.filter(item => matchesCatalog(item, query, status))) {
    let cursor: CatalogRow | undefined = row;
    // Stop on a previously visited ancestor, including malformed cycles in a response.
    while (cursor && !visible.has(cursor.id)) {
      visible.add(cursor.id);
      cursor = cursor.parentId == null ? undefined : byId.get(cursor.parentId);
    }
  }
  return rows.filter(row => visible.has(row.id));
}

export function filterGrantOptions<T extends CatalogRow>(rows: T[], query: string, selected: number[], onlySelected: boolean): T[] {
  const ids = new Set(selected);
  return rows.filter(row => matchesCatalog(row, query) && (!onlySelected || ids.has(row.id)));
}

export function grantChanges(before: number[], after: number[]): { added: number[]; removed: number[] } {
  const original = new Set(before), current = new Set(after);
  return { added: [...current].filter(id => !original.has(id)), removed: [...original].filter(id => !current.has(id)) };
}
