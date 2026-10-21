<!-- Masked employee lookup used by App.vue account forms; selection is saved only with the account. -->
<script setup lang="ts">
import { onUnmounted, ref } from 'vue';
import { ApiError, request } from './api';

type Employee = { id: number; label: string; organizationName: string; linkedAccountId?: number | null };
const props = defineProps<{ modelValue: number | null; ownerAccountId?: number }>();
const emit = defineEmits<{ 'update:modelValue': [value: number | null]; failure: [error: unknown] }>();
const opened = ref(false), query = ref(''), page = ref(1), total = ref(0);
const rows = ref<Employee[]>([]), loading = ref(false), error = ref('');
let epoch = 0;
onUnmounted(() => { epoch++; });

async function search(reset = false) {
  if (reset) page.value = 1;
  const current = ++epoch;
  loading.value = true; error.value = '';
  try {
    const result = await request<{ items: Employee[]; total: number; page: number }>('/employees?page=' + page.value + '&q=' + encodeURIComponent(query.value));
    if (current !== epoch) return;
    rows.value = result.items; total.value = result.total; page.value = result.page;
  } catch (failure) {
    if (current !== epoch) return;
    rows.value = []; total.value = 0;
    error.value = failure instanceof Error ? failure.message : '员工查询失败，请重试';
    if (failure instanceof ApiError && failure.status === 401) emit('failure', failure);
  } finally { if (current === epoch) loading.value = false; }
}
async function toggle() { opened.value = !opened.value; if (opened.value) await search(true); }
function occupied(row: Employee) { return row.linkedAccountId != null && row.linkedAccountId !== props.ownerAccountId; }
function choose(row: Employee) { if (!occupied(row)) { emit('update:modelValue', row.id); opened.value = false; } }
</script>

<template>
  <div class="employee-picker">
    <div class="employee-current"><span>{{ modelValue ? `已关联员工 #${modelValue}` : '不关联员工资料' }}</span><div><button type="button" class="quiet" :disabled="!modelValue" @click="emit('update:modelValue', null)">清除关联</button><button type="button" class="secondary" :aria-expanded="opened" aria-controls="employee-search-panel" @click="toggle">{{ opened ? '收起查找' : '查找员工' }}</button></div></div>
    <p class="small muted">一个员工最多关联一个账号。原始员工仅显示编号；合成演示账号建议不关联真实员工。</p>
    <section v-if="opened" id="employee-search-panel" class="employee-search-panel" aria-label="员工查找">
      <label>员工编号、组织或合成姓名<input v-model="query" maxlength="80" placeholder="例如 #1000 或组织名称" @keydown.enter.prevent="search(true)"></label>
      <button type="button" class="secondary" :disabled="loading" @click="search(true)">查询员工</button>
      <p v-if="error" class="alert error" role="alert">{{ error }}</p><p v-if="loading" class="small muted" role="status">正在查询员工…</p>
      <p class="small muted" role="status">共 {{ total }} 条，第 {{ page }} 页；已关联其他账号的员工不可选择。</p>
      <ul class="employee-options"><li v-for="row in rows" :key="row.id"><div><strong>{{ row.label }}</strong><small>{{ row.organizationName }} · 员工 #{{ row.id }}</small><small v-if="occupied(row)" class="danger-text">已关联其他账号</small><small v-else-if="row.linkedAccountId">当前账号已关联</small></div><button type="button" class="secondary" :disabled="loading || occupied(row)" :aria-label="`选择员工 #${row.id}`" @click="choose(row)">选择</button></li></ul>
      <p v-if="!loading && !rows.length && !error" class="empty">没有符合条件的员工，请调整编号或组织名称。</p>
      <div class="pagination"><button type="button" class="secondary" :disabled="loading || page <= 1" @click="page--; search()">上一页员工</button><button type="button" class="secondary" :disabled="loading || page * 100 >= total" @click="page++; search()">下一页员工</button></div>
    </section>
  </div>
</template>
