// Business UI transport and editor. Never stores application secrets or accepts arbitrary employee identity.
// office-api performs authorization again for every document operation; button visibility is only feedback.
const $ = selector => document.querySelector(selector);
let session = null, records = [], selected = null, decisions = {}, busy = false;
function message(text, error = false) { $('#message').hidden = !text; $('#message').textContent = text; $('#message').className = 'message' + (error ? ' error' : ''); }
async function api(path, method = 'GET', body) {
  let response;
  try { response = await fetch('/office-api' + path, { method, credentials: 'same-origin', headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(method !== 'GET' && session ? { 'X-CSRF-Token': session.csrfToken } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) }); }
  catch { throw new Error('无法连接公文服务，请确认服务已启动。'); }
  const value = await response.json().catch(() => ({}));
  if (!response.ok) { if (response.status === 401) { session = null; showSession(); } throw new Error(value.message || '请求未完成，请重新读取。'); }
  return value;
}
function showSession() {
  $('#entry').hidden = !!session; $('#workspace').hidden = !session; $('#identity').hidden = !session;
  if (session) $('#employee-name').textContent = (session.profile === 'editor' ? '公文编辑员工' : '公文阅读员工') + ` · #${session.employeeId}`;
}
function renderPermission() {
  $('#read-permission').textContent = '查看公文：' + (decisions['office.documents:read'] ? '已授权' : '未授权');
  $('#update-permission').textContent = '修改公文：' + (decisions['office.documents:update'] ? '已授权' : '未授权');
  const editable = decisions['office.documents:update'] === true;
  $('#title').readOnly = !editable; $('#content').readOnly = !editable; $('#save').disabled = !editable || busy;
  $('#edit-help').textContent = editable ? '保存时重新校验权限，并检查公文版本。' : '当前没有修改权限，可联系管理员调整业务角色。';
}
function select(row) {
  selected = row; $('#empty').hidden = true; $('#editor-form').hidden = false;
  $('#document-id').textContent = row.id; $('#document-version').textContent = '版本 ' + row.version;
  $('#title').value = row.title; $('#content').value = row.content;
  for (const button of document.querySelectorAll('.document-item')) button.classList.toggle('active', button.dataset.id === row.id);
  renderPermission();
}
function renderDocuments() {
  $('#documents').replaceChildren(); $('#document-count').textContent = records.length;
  for (const row of records) {
    const button = document.createElement('button'); button.className = 'document-item'; button.dataset.id = row.id;
    const number = document.createElement('small'); number.textContent = row.id;
    const title = document.createElement('strong'); title.textContent = row.title;
    button.append(number, title); button.onclick = () => select(row); $('#documents').append(button);
  }
  if (records.length) select(records.find(row => row.id === selected?.id) || records[0]);
  else { selected = null; $('#empty').hidden = false; $('#editor-form').hidden = true; }
}
async function refresh() {
  const result = await api('/permissions'); decisions = result.permissions; renderPermission();
  try { records = (await api('/documents')).items; renderDocuments(); }
  catch (error) { records = []; renderDocuments(); throw error; }
}
async function run(action) {
  if (busy) return; busy = true; message('');
  for (const button of document.querySelectorAll('button')) button.disabled = true;
  try { await action(); } catch (error) { message(error.message, true); }
  finally { busy = false; for (const button of document.querySelectorAll('button')) button.disabled = false; renderPermission(); }
}
$('#refresh').onclick = () => run(refresh);
$('#logout').onclick = () => run(async () => { await api('/logout', 'POST'); session = null; records = []; selected = null; showSession(); });
$('#editor-form').onsubmit = event => { event.preventDefault(); void run(async () => {
  if (!selected) return;
  const result = await api('/documents/' + encodeURIComponent(selected.id), 'PUT', { title: $('#title').value, content: $('#content').value, version: selected.version });
  await refresh(); message('公文修改成功，已保存至业务数据库。版本 ' + result.version);
}); };
async function initialize() {
  try {
    const profiles = await api('/profiles');
    for (const profile of profiles) {
      const button = document.createElement('button'); const title = document.createElement('strong'); title.textContent = profile.name;
      const detail = document.createElement('small'); detail.textContent = '员工编号 ' + profile.employeeId;
      button.append(title, detail); button.onclick = () => run(async () => { session = await api('/mock-session', 'POST', { profile: profile.profile }); showSession(); await refresh(); }); $('#profiles').append(button);
    }
    try { session = await api('/me'); showSession(); await refresh(); } catch (error) { if (session) message(error.message, true); }
  } catch (error) { message(error.message, true); }
}
void initialize();
