<!-- Administrator-only Vue platform; employee grants and management pages call the RBAC Spring API. -->
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { ApiError, request } from './api';
import EmployeePicker from './EmployeePicker.vue';
import { filterGrantOptions, filterResourceTree, grantChanges, matchesCatalog, type CatalogRow } from './viewFilters';

type Row = Record<string, any>;
type Me = { id: number; username: string; displayName: string; employee?: Row; roles: Row[]; permissions: string[]; csrfToken: string; expiresAt: string };
const me = ref<Me | null>(null), route = ref(location.hash.slice(1) || '/'), loading = ref(false), busy = ref(false);
const error = ref(''), notice = ref(''), ready = ref(false), now = ref(Date.now());
const username = ref(''), password = ref(''), search = ref(''), page = ref(1), total = ref(0);
const employees = ref<Row[]>([]);
const accounts = ref<Row[]>([]), roles = ref<Row[]>([]), resources = ref<Row[]>([]), permissions = ref<Row[]>([]);
const summary = ref<Row>({});
const selectedOwner = ref<number | null>(null), selectedIds = ref<number[]>([]), grantVersion = ref(0);
const catalogQuery = ref(''), catalogStatus = ref(''), grantQuery = ref(''), onlySelected = ref(false);
const grantSnapshot = ref<number[]>([]), grantOwner = ref<number | null>(null);
let grantRequest = 0;
const editor = ref<{ kind: string; item: Row } | null>(null), effective = ref<{ name: string; codes: string[] } | null>(null);
const loginOpen = computed(() => !me.value && route.value === '/login');
watch(loginOpen, async opened => {
  if (opened) { await nextTick(); document.querySelector<HTMLInputElement>('#login-username')?.focus(); }
});
const can = (code: string) => me.value?.permissions.includes(code) ?? false;
const isManager = computed(() => can('platform:access'));
const current = computed(() => route.value.split('/')[2] || 'overview');
const remaining = computed(() => me.value ? Math.max(0, Math.ceil((Date.parse(me.value.expiresAt) - now.value) / 1000)) : 0);
const countdown = computed(() => `${String(Math.floor(remaining.value / 60)).padStart(2, '0')}:${String(remaining.value % 60).padStart(2, '0')}`);
const navigation = computed(() => [
  { id: 'overview', label: '工作台', permissions: ['platform:access'] },
  { id: 'accounts', label: '账号管理', permissions: ['accounts:read'] },
  { id: 'roles', label: '角色管理', permissions: ['roles:read'] },
  { id: 'resources', label: '资源目录', permissions: ['resources:read'] },
  { id: 'permissions', label: '操作权限', permissions: ['permissions:read', 'resources:read'] },
  { id: 'employee-roles', label: '员工角色分配', permissions: ['employees:read', 'grants:read', 'roles:read'] },
  { id: 'user-roles', label: '管理账号角色', permissions: ['grants:read', 'accounts:read', 'roles:read'] },
  { id: 'role-permissions', label: '角色权限分配', permissions: ['grants:read', 'roles:read', 'permissions:read'] },
].filter(item => item.permissions.every(can)));
const title = computed(() => navigation.value.find(item => item.id === current.value)?.label || '权限管理');
const descriptions: Record<string, string> = { overview: '维护账号、角色与操作权限及其授权关系。', accounts: '维护账号状态与员工关联，权限通过角色分配。', roles: '按业务职责维护角色及其权限集合，岗位与角色分别管理。', resources: '按应用、模块、功能点整理业务资源。', permissions: '为功能点配置操作权限及稳定编码。', 'employee-roles': '为员工直接分配业务角色，无需创建平台登录账号。', 'user-roles': '选定管理账号后配置管理角色，保存后后续鉴权立即使用新权限。', 'role-permissions': '为角色选择明确的操作权限，避免过度授权。' };
const editorTitle = computed(() => editor.value ? `${editor.value.item.id ? '编辑' : '新建'}${({ accounts: '账号', roles: '角色', resources: '资源', permissions: '操作权限', password: '账号密码' } as Record<string, string>)[editor.value.kind]}` : '');
const resourceLabel = (id: number) => resources.value.find(item => item.id === id)?.name || `资源 #${id}`;
const resourceDepth = (row: Row) => row.kind === 'APPLICATION' ? 0 : row.kind === 'MODULE' ? 1 : 2;
const sortedResources = computed(() => {
  const result: Row[] = [];
  const append = (parent: number | null) => resources.value.filter(row => (row.parentId ?? null) === parent).forEach(row => { result.push(row); append(row.id); });
  append(null); return result;
});
const grantOptions = computed(() => current.value === 'user-roles' ? roles.value.filter(row => row.purpose === 'MANAGEMENT') : current.value === 'employee-roles' ? roles.value.filter(row => row.purpose === 'BUSINESS') : permissions.value.filter(row => {
  const management = roles.value.find(role => role.id === selectedOwner.value)?.purpose === 'MANAGEMENT';
  return management ? row.applicationCode === 'management' : row.applicationCode !== 'management';
}));
const visibleResources = computed(() => filterResourceTree(sortedResources.value as (Row & CatalogRow)[], catalogQuery.value, catalogStatus.value));
const resourceMatches = computed(() => resources.value.filter(row => matchesCatalog(row as CatalogRow, catalogQuery.value, catalogStatus.value)).length);
const visiblePermissions = computed(() => permissions.value.filter(row => matchesCatalog(row as CatalogRow, catalogQuery.value, catalogStatus.value)));
const visibleGrantOptions = computed(() => filterGrantOptions(grantOptions.value as (Row & CatalogRow)[], grantQuery.value, selectedIds.value, onlySelected.value));
const changes = computed(() => grantChanges(grantSnapshot.value, selectedIds.value));
const grantsChanged = computed(() => changes.value.added.length + changes.value.removed.length > 0);
const grantName = (id: number) => grantOptions.value.find(row => row.id === id)?.name ?? `对象 #${id}`;
const ownerOptions = computed(() => current.value === 'user-roles' ? accounts.value : current.value === 'employee-roles' ? employees.value : roles.value);
const validParents = computed(() => resources.value.filter(row => row.kind === (editor.value?.item.kind === 'MODULE' ? 'APPLICATION' : 'MODULE')));

function navigate(path: string) { if (route.value === path) void load(); else location.hash = path; }
function report(failure: unknown) {
  error.value = failure instanceof Error ? failure.message : '操作未完成';
  if (failure instanceof ApiError && failure.status === 401) { me.value = null; location.hash = '/login'; }
}
async function call<T>(path: string, method = 'GET', body?: unknown): Promise<T> { return request<T>(path, method, body, method === 'GET' ? undefined : me.value?.csrfToken); }
async function refreshMe() { me.value = await call<Me>('/me'); }
async function login() {
  busy.value = true; error.value = '';
  try { me.value = await request<Me>('/session/login', 'POST', { username: username.value, password: password.value }); now.value = Date.now(); password.value = ''; navigate('/manage/overview'); }
  catch (failure) { report(failure); } finally { busy.value = false; }
}
async function logout() {
  busy.value = true;
  try { await call('/session/logout', 'POST'); me.value = null; password.value = ''; navigate('/'); }
  catch (failure) { report(failure); } finally { busy.value = false; }
}
async function load() {
  if (!me.value) return;
  loading.value = true; error.value = ''; notice.value = ''; editor.value = null; effective.value = null; selectedOwner.value = null;
  grantRequest++; grantOwner.value = null; selectedIds.value = []; grantSnapshot.value = [];
  try {
    if (route.value.startsWith('/manage')) {
      await refreshMe();
      if (!isManager.value || !navigation.value.some(item => item.id === current.value)) { navigate('/manage/overview'); throw new ApiError(403, 'FORBIDDEN', '当前账号没有此管理页面的访问权限'); }
      if (current.value === 'overview') summary.value = await call<Row>('/overview');
      if (['accounts', 'user-roles'].includes(current.value)) {
        const data = await call<Row>(`/accounts?page=${page.value}&pageSize=20&q=${encodeURIComponent(search.value)}`);
        accounts.value = data.items; total.value = data.total;
      }
      if (current.value === 'employee-roles') {
        const data = await call<Row>(`/employees?page=${page.value}&q=${encodeURIComponent(search.value)}`);
        employees.value = data.items; total.value = data.total;
      }
      if (['roles', 'user-roles', 'employee-roles', 'role-permissions'].includes(current.value)) roles.value = await call<Row[]>('/roles');
      if (['resources', 'permissions'].includes(current.value)) resources.value = await call<Row[]>('/resources');
      if (['permissions', 'role-permissions'].includes(current.value)) permissions.value = await call<Row[]>('/permissions');
    } else navigate('/manage/overview');
  } catch (failure) { report(failure); } finally { loading.value = false; }
}
async function openEditor(kind: string, row?: Row) {
  error.value = ''; notice.value = '';
  const item = row ? { ...row } : { enabled: true, locked: false, employeeId: null, parentId: null, kind: 'APPLICATION', purpose: 'BUSINESS', resourceId: null, description: '' };
  editor.value = { kind, item };
  await nextTick(); (document.querySelector('.drawer input:not([disabled]), .drawer select:not([disabled])') as HTMLElement | null)?.focus();
}
async function saveEditor() {
  if (!editor.value) return;
  busy.value = true; error.value = '';
  const { kind, item } = editor.value;
  try {
    const endpoint = kind === 'password' ? `/accounts/${item.id}/password` : `/${kind}${item.id ? `/${item.id}` : ''}`;
    await call(endpoint, item.id ? 'PUT' : 'POST', item);
    await load(); notice.value = '已保存，服务端配置已更新。';
  } catch (failure) { report(failure); } finally { busy.value = false; }
}
async function inspectPermissions(row: Row) {
  try { const data = await call<{ permissions: string[] }>(`/accounts/${row.id}/permissions`); effective.value = { name: row.displayName, codes: data.permissions }; }
  catch (failure) { report(failure); }
}
async function readGrants() {
  if (!selectedOwner.value) return;
  const owner = selectedOwner.value, view = current.value, epoch = ++grantRequest;
  grantOwner.value = null; selectedIds.value = []; grantSnapshot.value = [];
  loading.value = true; error.value = ''; notice.value = '';
  try {
    const prefix = view === 'user-roles' ? `/accounts/${owner}/roles` : view === 'employee-roles' ? `/employees/${owner}/roles` : `/roles/${owner}/permissions`;
    const data = await call<{ version: number; ids: number[] }>(prefix);
    // A response for an old owner/route must never become another owner's editable grants.
    if (epoch !== grantRequest || owner !== selectedOwner.value || view !== current.value) return;
    grantVersion.value = data.version; selectedIds.value = [...data.ids]; grantSnapshot.value = [...data.ids]; grantOwner.value = owner;
  } catch (failure) { if (epoch === grantRequest) report(failure); }
  finally { if (epoch === grantRequest) loading.value = false; }
}
async function saveGrants() {
  if (!selectedOwner.value || grantOwner.value !== selectedOwner.value || !grantsChanged.value) return;
  busy.value = true; error.value = '';
  try {
    const prefix = current.value === 'user-roles' ? `/accounts/${selectedOwner.value}/roles` : current.value === 'employee-roles' ? `/employees/${selectedOwner.value}/roles` : `/roles/${selectedOwner.value}/permissions`;
    await call(prefix, 'PUT', { version: grantVersion.value, ids: selectedIds.value });
    await refreshMe(); await readGrants(); notice.value = '授权配置已保存，后续鉴权立即使用新权限。';
  } catch (failure) { report(failure); } finally { busy.value = false; }
}
async function toggleEmployee(row: Row) {
  busy.value = true; error.value = '';
  try { await call(`/employees/${row.id}`, 'PUT', { enabled: !row.enabled, version: row.version }); await load(); notice.value = '员工状态已更新。'; }
  catch (failure) { report(failure); } finally { busy.value = false; }
}
function changedRoute() {
  route.value = location.hash.slice(1) || '/'; search.value = ''; page.value = 1;
  catalogQuery.value = ''; catalogStatus.value = ''; grantQuery.value = ''; onlySelected.value = false;
  void load();
}
async function openLogin() {
  error.value = ''; navigate('/login');
  await nextTick(); document.querySelector<HTMLInputElement>('#login-username')?.focus();
}
async function closeLogin() {
  if (busy.value) return;
  password.value = ''; navigate('/'); route.value = '/';
  await nextTick(); document.querySelector<HTMLButtonElement>('.home-login-trigger')?.focus();
}
function trapLoginFocus(event: KeyboardEvent) {
  // Keep keyboard navigation inside the open modal and return focus on close.
  const controls = [...document.querySelectorAll<HTMLElement>('.login-dialog button:not([disabled]), .login-dialog input')];
  const first = controls[0], last = controls[controls.length - 1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
}
function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape' && !busy.value) {
    if (loginOpen.value) void closeLogin();
    else { editor.value = null; effective.value = null; }
  }
}
let ticker: ReturnType<typeof setInterval>;
onMounted(async () => {
  window.addEventListener('hashchange', changedRoute); window.addEventListener('keydown', closeOnEscape);
  ticker = setInterval(() => { now.value = Date.now(); if (me.value && remaining.value === 0) { me.value = null; error.value = '30 分钟会话已到期，请重新登录'; navigate('/login'); } }, 1000);
  try { await refreshMe(); if (route.value === '/' || route.value === '/login') navigate('/manage/overview'); else await load(); }
  catch (failure) {
    if (!(failure instanceof ApiError && failure.status === 401)) report(failure);
    if (!['/', '/login'].includes(route.value)) navigate('/login');
  }
  finally { ready.value = true; if (loginOpen.value) { await nextTick(); document.querySelector<HTMLInputElement>('#login-username')?.focus(); } }
});
onUnmounted(() => { clearInterval(ticker); window.removeEventListener('hashchange', changedRoute); window.removeEventListener('keydown', closeOnEscape); });
</script>

<template>
  <div v-if="!ready" class="boot">正在连接权限平台…</div>
  <main v-else-if="!me" class="home-screen">
    <div :inert="loginOpen"><header class="home-header"><div class="flex items-center gap-3"><span class="brand-mark small-mark">政</span><strong>市政统一权限管理</strong></div><button class="secondary home-login-trigger" @click="openLogin">账号密码登录</button></header>
      <section class="home-main"><div class="home-intro"><p class="section-label">市政公司统一权限管理系统</p><h1>统一权限管理<br>与访问控制</h1><p class="home-description">统一维护账号、角色与操作权限。<br>支持权限分配、回收与服务端访问校验。</p><button class="primary" @click="openLogin">登录系统</button><p class="home-session">会话自登录起有效 30 分钟，操作不延长有效期。</p></div>
        <div class="home-guide"><section><span class="entry-symbol">管</span><h2>管理员登录</h2><p>维护员工角色、角色权限与平台管理账号。普通员工在业务系统中办理公文，无需登录权限平台。</p></section></div>
      </section><footer class="home-footer">市政统一权限管理 · 本机课程演示</footer></div>
    <div v-if="loginOpen" class="login-overlay"><section class="login-dialog" role="dialog" aria-modal="true" aria-labelledby="login-heading" @keydown.tab="trapLoginFocus"><div class="panel-heading"><h2 id="login-heading">账号密码登录</h2><button class="quiet" :disabled="busy" aria-label="关闭登录窗口" @click="closeLogin">关闭</button></div><p class="muted">请输入系统账号和密码。</p><form @submit.prevent="login"><label for="login-username">用户名<input id="login-username" v-model="username" required autocomplete="username" maxlength="64" placeholder="请输入用户名"></label><label for="login-password">密码<input id="login-password" v-model="password" required type="password" autocomplete="current-password" maxlength="64" placeholder="请输入密码"></label><p v-if="error" class="alert error" role="alert">{{ error }}</p><button class="primary wide" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button><p class="small muted login-note">仅供具有管理权限的账号使用。<br>会话自登录起固定 30 分钟，不续期。</p></form></section></div>
  </main>
  <div v-else class="app-shell">
    <aside class="sidebar"><div class="brand"><span class="brand-mark small-mark">政</span><div><strong>市政统一权限</strong><small>管理平台</small></div></div><nav v-if="route.startsWith('/manage')" aria-label="管理功能"><button v-for="item in navigation" :key="item.id" :class="{ active: current === item.id }" @click="navigate(`/manage/${item.id}`)">{{ item.label }}</button></nav><div class="sidebar-bottom"><p>操作权限由服务端检查<br>组织与岗位不自动授权</p></div></aside>
    <section class="workspace"><header class="topbar"><span>{{ '权限管理 / ' + title }}</span><div class="flex items-center gap-3"><span class="session-label">会话 {{ countdown }}</span><strong>{{ me.displayName }}</strong><button class="quiet" :disabled="busy" @click="logout">退出</button></div></header>
      <main class="page"><header class="page-heading"><div><h1>{{ title }}</h1><p class="muted">{{ descriptions[current] }}</p></div><button class="secondary" :disabled="loading || busy" @click="load">刷新</button></header>
        <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="notice" class="alert success" role="status">{{ notice }}</p><p v-if="loading" class="loading" role="status">正在读取服务端配置…</p>
        <template v-if="current === 'overview'">
          <section class="panel overview-intro"><div><p class="section-label">统一授权</p><h2>角色与操作权限配置</h2><p class="muted">角色之间不继承。一个操作可以通过多个角色获得；回收其中一条授权路径，不一定回收全部访问资格。</p></div><span class="overview-seal">RBAC0</span></section>
          <section class="panel"><div class="panel-heading"><h2>当前配置</h2><span class="muted small">来自本机 MySQL</span></div><div class="overview-counts"><div v-for="(label, key) in { accounts: '账号', roles: '角色', resources: '资源节点', permissions: '操作权限' }" :key="key"><strong>{{ summary[key] ?? '—' }}</strong><span>{{ label }}</span></div></div></section>
          <section class="panel"><h2>授权工作顺序</h2><ol class="workflow"><li><strong>准备资源和操作</strong><p>维护应用、模块和功能点，为功能点建立查看、编辑等明确权限。</p></li><li><strong>配置角色操作权限</strong><p>按照职责授予具体操作权限，不使用未来权限通配授权。</p></li><li><strong>分配员工业务角色并验证</strong><p>为员工分配业务角色，在独立公文系统验证授权与回收。</p></li></ol></section>
        </template>
        <section v-else-if="['user-roles', 'employee-roles', 'role-permissions'].includes(current)" class="panel">
          <div class="panel-heading"><div><h2>{{ current === 'user-roles' ? '选择管理账号' : current === 'employee-roles' ? '选择员工' : '选择角色' }}</h2><p class="muted small">提交时检查版本，冲突后请重新加载。</p></div><button class="primary" :disabled="!selectedOwner || grantOwner !== selectedOwner || !grantsChanged || busy || loading || !can('grants:write')" @click="saveGrants">{{ busy ? '正在保存…' : '保存授权配置' }}</button></div>
          <div v-if="['user-roles','employee-roles'].includes(current)" class="toolbar"><input v-model="search" :placeholder="current === 'employee-roles' ? '员工编号、组织或合成姓名' : '查找管理账号'" aria-label="查找授权对象" @keyup.enter="page = 1; load()"><button class="secondary" @click="page = 1; load()">搜索</button><button v-if="current === 'employee-roles'" class="quiet" @click="search = '公文'; page = 1; load()">公文合成员工</button></div>
          <div v-if="['user-roles','employee-roles'].includes(current)" class="pagination"><span>共 {{ total }} 个{{ current === 'employee-roles' ? '员工' : '账号' }}，第 {{ page }} 页</span><button class="secondary" :disabled="page <= 1 || loading || busy" @click="page--; load()">上一页</button><button class="secondary" :disabled="page * (current === 'employee-roles' ? 100 : 20) >= total || loading || busy" @click="page++; load()">下一页</button></div>
          <div v-if="current === 'employee-roles'" class="table-wrap employee-list"><table><thead><tr><th>员工</th><th>组织</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in employees" :key="row.id"><td>{{ row.label }}<small class="cell-meta">员工 #{{ row.id }}</small></td><td>{{ row.organizationName }}</td><td>{{ row.enabled ? '启用' : '停用' }}</td><td class="actions"><button @click="selectedOwner = row.id; readGrants()">分配角色</button><button :disabled="busy || !can('grants:write')" @click="toggleEmployee(row)">{{ row.enabled ? '停用' : '启用' }}</button></td></tr></tbody></table></div>
          <label class="owner-picker">{{ current === 'user-roles' ? '管理账号' : current === 'employee-roles' ? '员工' : '角色' }}<select v-model="selectedOwner" :disabled="loading || busy" @change="readGrants"><option :value="null" disabled>请选择</option><option v-for="owner in ownerOptions" :key="owner.id" :value="owner.id">{{ owner.displayName ?? owner.label ?? owner.name }}{{ owner.username ? ` (${owner.username})` : '' }}</option></select></label>
          <p v-if="!selectedOwner" class="empty">请选择授权对象，然后配置{{ current === 'role-permissions' ? '操作权限' : '角色' }}。</p>
          <template v-else-if="grantOwner === selectedOwner">
            <div class="selection-summary"><strong>已选择 {{ selectedIds.length }} 项</strong><button class="quiet" :disabled="loading || busy" @click="readGrants">重新加载配置</button></div>
            <div class="toolbar"><input v-model="grantQuery" aria-label="查找授权项" placeholder="按名称或编码查找授权项"><label class="checkbox-label compact-label"><input v-model="onlySelected" type="checkbox">仅显示已选</label></div>
            <p class="small muted filter-summary" role="status">显示 {{ visibleGrantOptions.length }} / {{ grantOptions.length }} 项。筛选只改变显示范围，隐藏的已选项会保留。</p>
            <div v-if="grantsChanged" class="change-preview" role="status"><strong>待保存：新增 {{ changes.added.length }} 项，回收 {{ changes.removed.length }} 项</strong><p v-if="changes.added.length">新增：{{ changes.added.map(grantName).join('、') }}</p><p v-if="changes.removed.length">回收：{{ changes.removed.map(grantName).join('、') }}</p></div>
            <div class="grant-grid"><label v-for="option in visibleGrantOptions" :key="option.id" class="grant-option"><input v-model="selectedIds" type="checkbox" :value="option.id" :disabled="busy || loading || !can('grants:write')"><span><strong>{{ option.name }}</strong><code>{{ option.code }}</code><small v-if="!option.enabled" class="danger-text">已停用，分配后仍不会生效</small></span></label></div>
            <p v-if="!visibleGrantOptions.length" class="empty">未查询到符合条件的授权项，请调整筛选条件。</p>
          </template>
        </section>
        <section v-else class="panel">
          <div class="panel-heading"><div><h2>{{ title }}列表</h2><p class="muted small">稳定编码用于鉴权，名称用于展示。</p></div><button class="primary" :disabled="busy || !can(`${current}:write`)" @click="openEditor(current)">新建{{ { accounts: '账号', roles: '角色', resources: '资源', permissions: '权限' }[current] }}</button></div>
          <div v-if="current === 'accounts'" class="toolbar"><input v-model="search" placeholder="搜索用户名或显示名称" aria-label="搜索账号" @keyup.enter="page = 1; load()"><button class="secondary" @click="page = 1; load()">搜索</button><span class="muted small">共 {{ total }} 个账号</span></div>
          <div v-if="['resources', 'permissions'].includes(current)" class="toolbar catalog-toolbar"><input v-model="catalogQuery" aria-label="查找目录或权限" placeholder="按名称或稳定编码查找"><select v-model="catalogStatus" aria-label="筛选启用状态"><option value="">全部状态</option><option value="enabled">启用</option><option value="disabled">停用</option></select></div>
          <p v-if="current === 'resources'" class="small muted filter-summary" role="status">匹配 {{ resourceMatches }} 项；显示 {{ visibleResources.length }} / {{ resources.length }} 个节点（保留匹配项的祖先层级）。</p>
          <p v-if="current === 'permissions'" class="small muted filter-summary" role="status">显示 {{ visiblePermissions.length }} / {{ permissions.length }} 项操作权限。</p>
          <div class="table-wrap"><table v-if="current === 'accounts'"><thead><tr><th>账号</th><th>显示名称</th><th>员工关联</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in accounts" :key="row.id"><td><strong>{{ row.username }}</strong><small class="cell-meta">账号 #{{ row.id }}</small></td><td>{{ row.displayName }}</td><td>{{ row.employeeId ? `员工 #${row.employeeId}` : '未关联' }}</td><td><span class="status" :class="row.enabled && !row.locked ? 'enabled' : 'disabled'">{{ row.locked ? '已锁定' : row.enabled ? '启用' : '停用' }}</span></td><td class="actions"><button :disabled="!can('accounts:write')" @click="openEditor('accounts', row)">编辑</button><button :disabled="!can('accounts:write')" @click="openEditor('password', row)">重置密码</button><button :disabled="!can('grants:read')" @click="inspectPermissions(row)">有效权限</button></td></tr></tbody></table>
          <table v-else-if="current === 'roles'"><thead><tr><th>角色名称</th><th>稳定编码</th><th>说明</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in roles" :key="row.id"><td><strong>{{ row.name }}</strong></td><td><code>{{ row.code }}</code></td><td>{{ row.purpose === 'MANAGEMENT' ? '平台管理' : '员工业务' }} · {{ row.description || '—' }}</td><td><span class="status" :class="row.enabled ? 'enabled' : 'disabled'">{{ row.enabled ? '启用' : '停用' }}</span></td><td class="actions"><button :disabled="!can('roles:write')" @click="openEditor('roles', row)">编辑</button></td></tr></tbody></table>
          <table v-else-if="current === 'resources'"><thead><tr><th>资源层级</th><th>稳定编码</th><th>类型</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleResources" :key="row.id"><td><span :style="{ paddingLeft: `${resourceDepth(row) * 22}px` }"><span class="tree-marker">{{ row.kind === 'APPLICATION' ? '▣' : row.kind === 'MODULE' ? '▦' : '·' }}</span>{{ row.name }}</span></td><td><code>{{ row.code }}</code></td><td>{{ { APPLICATION: '应用', MODULE: '模块', FEATURE: '功能点' }[row.kind as string] }}</td><td><span class="status" :class="row.enabled ? 'enabled' : 'disabled'">{{ row.enabled ? '启用' : '停用' }}</span></td><td class="actions"><button :disabled="!can('resources:write')" @click="openEditor('resources', row)">编辑</button></td></tr></tbody></table>
          <table v-else><thead><tr><th>操作权限</th><th>权限编码</th><th>功能点</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in visiblePermissions" :key="row.id"><td><strong>{{ row.name }}</strong></td><td><code>{{ row.code }}</code></td><td>{{ row.resourceName }}</td><td><span class="status" :class="row.enabled ? 'enabled' : 'disabled'">{{ row.enabled ? '启用' : '停用' }}</span></td><td class="actions"><button :disabled="!can('permissions:write')" @click="openEditor('permissions', row)">编辑</button></td></tr></tbody></table></div>
          <p v-if="!loading && ({ accounts, roles, resources, permissions }[current]?.length ?? 0) === 0" class="empty">暂无数据，请新建记录或调整搜索条件。</p>
          <p v-if="!loading && ((current === 'resources' && !visibleResources.length) || (current === 'permissions' && !visiblePermissions.length))" class="empty">没有符合筛选条件的数据，请调整查询。</p>
          <div v-if="current === 'accounts'" class="pagination"><span>第 {{ page }} 页</span><button class="secondary" :disabled="page <= 1 || loading" @click="page--; load()">上一页</button><button class="secondary" :disabled="page * 20 >= total || loading" @click="page++; load()">下一页</button></div>
        </section>
      </main>
    </section>
    <div v-if="editor" class="drawer-overlay"><section class="drawer" role="dialog" aria-modal="true" aria-labelledby="editor-heading"><div class="panel-heading"><h2 id="editor-heading">{{ editorTitle }}</h2><button class="quiet" :disabled="busy" @click="editor = null">关闭</button></div><form @submit.prevent="saveEditor">
      <template v-if="editor.kind === 'accounts'"><label>用户名<input v-model="editor.item.username" required pattern="[a-z][a-z0-9._-]{1,63}" maxlength="64" :disabled="!!editor.item.id" autocomplete="off"></label><label>显示名称<input v-model="editor.item.displayName" required maxlength="80"></label><p class="field-heading">员工关联（可不关联）</p><EmployeePicker v-if="can('employees:read')" v-model="editor.item.employeeId" :owner-account-id="editor.item.id" @failure="report"/><label v-else>员工 ID<input v-model.number="editor.item.employeeId" type="number" min="1" @input="editor.item.employeeId = editor.item.employeeId === '' ? null : editor.item.employeeId"><span class="small muted">没有员工查询权限时，可保留已有编号或留空。</span></label></template>
      <template v-else-if="editor.kind === 'roles'"><label>角色编码<input v-model="editor.item.code" required :disabled="!!editor.item.id" pattern="[a-z][a-z0-9._-]{1,79}" maxlength="80"></label><label>角色名称<input v-model="editor.item.name" required maxlength="80"></label><label v-if="!editor.item.id">角色用途<select v-model="editor.item.purpose"><option value="BUSINESS">员工业务角色</option><option value="MANAGEMENT">平台管理角色</option></select></label><label>说明<textarea v-model="editor.item.description" maxlength="300" rows="3"></textarea></label></template>
      <template v-else-if="editor.kind === 'resources'"><label>资源类型<select v-model="editor.item.kind" :disabled="!!editor.item.id" @change="editor.item.parentId = null"><option value="APPLICATION">应用</option><option value="MODULE">模块</option><option value="FEATURE">功能点</option></select></label><label v-if="editor.item.kind !== 'APPLICATION'">父节点<select v-model="editor.item.parentId" required :disabled="!!editor.item.id"><option :value="null" disabled>请选择</option><option v-for="parent in validParents" :key="parent.id" :value="parent.id">{{ parent.name }}</option></select></label><label>资源编码<input v-model="editor.item.code" required :disabled="!!editor.item.id" pattern="[a-z][a-z0-9._-]{1,99}" maxlength="100"></label><label>资源名称<input v-model="editor.item.name" required maxlength="100"></label></template>
      <template v-else-if="editor.kind === 'permissions'"><label v-if="!editor.item.id">所属功能点<select v-model="editor.item.resourceId" required><option :value="null" disabled>请选择功能点</option><option v-for="resource in resources.filter(row => row.kind === 'FEATURE')" :key="resource.id" :value="resource.id">{{ resource.name }} ({{ resource.code }})</option></select></label><label v-if="!editor.item.id">操作编码<input v-model="editor.item.action" required pattern="[a-z][a-z0-9._-]{1,39}" maxlength="40" placeholder="例如 read、edit、approve"></label><label>权限名称<input v-model="editor.item.name" required maxlength="100"></label><p class="small muted">{{ editor.item.code ?? `${resourceLabel(editor.item.resourceId)} : ${editor.item.action ?? '操作'}` }}</p></template>
      <label v-if="editor.kind === 'password' || (editor.kind === 'accounts' && !editor.item.id)">设置密码<input v-model="editor.item.password" type="password" required minlength="4" maxlength="64" autocomplete="new-password"><span class="small muted">本机测试 4–64 位；重置密码后，该账号已有会话全部失效。</span></label>
      <template v-if="editor.item.id && editor.kind !== 'password'"><label class="checkbox-label"><input v-model="editor.item.enabled" type="checkbox">启用</label><label v-if="editor.kind === 'accounts'" class="checkbox-label"><input v-model="editor.item.locked" type="checkbox">锁定账号</label><p class="small muted">当前版本 {{ editor.item.version }}，服务端会检查并发修改。</p></template>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p><div class="drawer-footer"><button type="button" class="secondary" :disabled="busy" @click="editor = null">取消</button><button class="primary" :disabled="busy">{{ busy ? '正在保存…' : '保存' }}</button></div>
    </form></section></div>
    <div v-if="effective" class="drawer-overlay"><section class="drawer" role="dialog" aria-modal="true" aria-labelledby="effective-heading"><div class="panel-heading"><h2 id="effective-heading">{{ effective.name }}的有效权限</h2><button class="quiet" @click="effective = null">关闭</button></div><p class="muted small">服务端按有效账号、角色、目录和权限计算并去重。</p><ul class="code-list"><li v-for="code in effective.codes" :key="code"><code>{{ code }}</code></li></ul><p v-if="!effective.codes.length" class="empty">当前没有有效操作权限。</p></section></div>
  </div>
</template>
