// Regression checks for resource context and hidden grants retained by App.vue filters.
import test from 'node:test';
import assert from 'node:assert/strict';
import { filterResourceTree, filterGrantOptions, grantChanges } from '../src/viewFilters.ts';

const rows = [
  { id: 1, parentId: null, code: 'app', name: '业务应用', enabled: true },
  { id: 2, parentId: 1, code: 'app.module', name: '业务模块', enabled: true },
  { id: 3, parentId: 2, code: 'app.bridge', name: '桥梁巡检', enabled: false },
  { id: 4, parentId: 2, code: 'app.road', name: '道路维护', enabled: true },
];
test('a feature search retains its ancestors and excludes unrelated siblings', () => {
  assert.deepEqual(filterResourceTree(rows, 'BRIDGE').map(row => row.id), [1, 2, 3]);
  assert.deepEqual(filterResourceTree(rows, '不存在'), []);
});
test('status filtering keeps hierarchy context without claiming parents match', () => {
  assert.deepEqual(filterResourceTree(rows, '', 'disabled').map(row => row.id), [1, 2, 3]);
  assert.deepEqual(filterResourceTree(rows, 'bridge', 'enabled'), []);
});
test('grant searches preserve hidden selections and only-selected remains a view', () => {
  const selected = [3, 4];
  assert.deepEqual(filterGrantOptions(rows, 'road', selected, true).map(row => row.id), [4]);
  assert.deepEqual(selected, [3, 4]);
  assert.deepEqual(grantChanges([3, 4], selected), { added: [], removed: [] });
});
test('grant preview reports additions and revocations independently of filtering', () => {
  assert.deepEqual(grantChanges([1, 3], [3, 4, 4]), { added: [4], removed: [1] });
});
