'use strict';
// 轻量 DOM/网络替身验证页面状态逻辑；不是浏览器、系统通知或扬声器实测。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('src/main/resources/static/index.html', 'utf8');
const scripts = ['reminder-preferences.js', 'reminder-assets.js', 'reminder-alerts.js', 'app.js'].map((name) => fs.readFileSync('src/main/resources/static/' + name, 'utf8'));
const settle = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };
class Element {
  constructor(tag = 'div') {
    this.tag = tag; this.children = []; this.events = {}; this.value = ''; this.dataset = {};
    this.hidden = false; this.disabled = false; this.isConnected = true;
    this.open = false; this.checked = false;
    this.classList = { toggle() {}, remove() {} };
  }
  setAttribute() {}
  removeAttribute(name) { delete this[name]; }
  append(...elements) { this.children.push(...elements); }
  replaceChildren(...elements) { for (const child of this.children) child.isConnected = false; this.children = elements; }
  addEventListener(name, handler) { this.events[name] = handler; }
  reset() { this.value = ''; }
  showModal() { this.open = true; }
  close() { this.open = false; }
  querySelectorAll(selector) {
    const tags = selector.split(',');
    return this.children.flatMap((child) => [...(tags.includes(child.tag) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
}
function harness(storage = new Map(), assetRecords = null) {
  const nodes = Object.fromEntries([...html.matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"/g)].map((m) => [m[2], new Element(m[1])]));
  // 原生 form.reset 会清空子输入；替身显式模拟，不能把其空实现当作浏览器残留隐私。
  nodes['note-form'].reset = () => {
    for (const id of ['note-title', 'note-content', 'reminder-time']) nodes[id].value = '';
    nodes['reminder-enabled'].checked = false;
  };
  const calls = [], windowEvents = {}, documentEvents = {};
  const document = {
    hidden: false, title: '', body: new Element('body'),
    getElementById: (id) => nodes[id], createElement: (tag) => new Element(tag),
    addEventListener: (name, handler) => { documentEvents[name] = handler; },
    querySelectorAll: (selector) => Object.values(nodes).flatMap((node) => [node, ...node.querySelectorAll(selector)])
  };
  let currentUser = { id: 5, username: 'test_user' }, unread = [], intercept = null, interval;
  let noteRecords = new Map(), reminderRecords = new Map(), trashRecords = new Map(), confirmValue = true, nextNoteId = 1000;
  const audioCalls = { resumes: 0, tones: 0, custom: 0 };
  let assetSaveError = null, inspectOverride = null;
  const ok = (body, status = 200) => ({ status, ok: status < 400, json: async () => body });
  async function fetch(path, options) {
    calls.push({ path, options });
    const delayed = intercept?.(path, options); if (delayed) return delayed;
    if (path === '/api/auth/me') return ok(currentUser);
    if (path === '/api/auth/csrf') return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-placeholder' });
    if (path === '/api/auth/login') return ok(currentUser);
    if (path === '/api/auth/logout') return ok(null, 204);
    if (path === '/api/notes/trash') return ok({ items: [...trashRecords.values()], limit: 30 });
    const restore = path.match(/^\/api\/notes\/trash\/(\d+)\/restore$/);
    if (restore) {
      const saved = trashRecords.get(Number(restore[1]));
      if (!saved) return ok({ message: '不可访问' }, 404);
      const note = { ...saved, id: ++nextNoteId }; delete note.deletedAt;
      trashRecords.delete(saved.id); noteRecords.set(note.id, note); return ok(note);
    }
    if (path.startsWith('/api/notes?page=')) {
      const query = new URL('http://test' + path).searchParams, page = Number(query.get('page')), size = Number(query.get('size'));
      const rows = [...noteRecords.values()];
      return ok({ items: rows.slice(page * size, (page + 1) * size), hasNext: (page + 1) * size < rows.length });
    }
    const reminderPath = path.match(/^\/api\/notes\/(\d+)\/reminder$/);
    if (reminderPath) {
      const reminder = reminderRecords.get(Number(reminderPath[1]));
      return reminder ? ok({ ...reminder }) : ok({ message: '没有提醒' }, 404);
    }
    const savePath = path.match(/^\/api\/notes\/(\d+)\/save$/);
    if (path === '/api/notes/save' || savePath) {
      const body = JSON.parse(options.body), id = savePath ? Number(savePath[1]) : ++nextNoteId;
      if (savePath && !noteRecords.has(id)) return ok({ message: '不可访问' }, 404);
      const note = { ...(noteRecords.get(id) || { id, completed: false, createdAt: '2026-10-07T10:00:00Z' }),
        title: body.title, content: body.content, updatedAt: '2026-10-07T10:01:00Z' };
      noteRecords.set(id, note);
      const previous = reminderRecords.get(id);
      if (body.reminderAction === 'SET') reminderRecords.set(id, {
        id: previous?.id || id + 10000, noteId: id, generation: (previous?.generation || 0) + 1, dueAt: body.dueAt, status: 'SCHEDULED'
      });
      if (body.reminderAction === 'CANCEL' && previous?.status === 'SCHEDULED') reminderRecords.set(id, { ...previous, status: 'CANCELLED' });
      const reminder = reminderRecords.get(id);
      return ok({ note: { ...note }, reminder: reminder ? { ...reminder } : null }, savePath ? 200 : 201);
    }
    if (path === '/api/notes' && options.method === 'POST') {
      const note = { id: ++nextNoteId, ...JSON.parse(options.body), completed: false, createdAt: '2026-10-07T10:00:00Z', updatedAt: '2026-10-07T10:00:00Z' };
      noteRecords.set(note.id, note); return ok(note, 201);
    }
    const notePath = path.match(/^\/api\/notes\/(\d+)$/);
    if (notePath) {
      const id = Number(notePath[1]), note = noteRecords.get(id);
      if (!note) return ok({ message: '不可访问' }, 404);
      if (options.method === 'DELETE') {
        noteRecords.delete(id); trashRecords.set(id + 10000, { ...note, id: id + 10000, deletedAt: '2026-10-07T10:01:00Z' }); return ok(null, 204);
      }
      if (options.method === 'PUT') { Object.assign(note, JSON.parse(options.body)); }
      return ok(note);
    }
    if (path.endsWith('/read')) { unread = unread.filter((item) => !path.includes('/' + item.id + '/')); return ok(null, 204); }
    if (path.startsWith('/api/notifications?')) {
      const query = new URL('http://test' + path).searchParams;
      const page = Number(query.get('page')), size = Number(query.get('size'));
      return ok({ items: unread.slice(page * size, (page + 1) * size), hasNext: (page + 1) * size < unread.length });
    }
    throw new Error('unexpected path: ' + path);
  }
  const window = { addEventListener: (name, fn) => { windowEvents[name] = fn; }, confirm: () => confirmValue, isSecureContext: true, focus() {},
    localStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) } };
  window.AudioContext = class {
    constructor() { this.state = 'suspended'; this.currentTime = 0; this.destination = {}; }
    resume() { audioCalls.resumes++; this.state = 'running'; return Promise.resolve(); }
    close() { this.state = 'closed'; return Promise.resolve(); }
    createOscillator() { return { frequency: {}, connect() {}, disconnect() {}, start() { audioCalls.tones++; }, stop() {} }; }
    createGain() { return { gain: { setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {}, disconnect() {} }; }
  };
  const context = vm.createContext({ document, window, fetch, setInterval: (fn) => { interval = fn; }, console });
  scripts.slice(0, -1).forEach((source) => vm.runInContext(source, context));
  if (assetRecords) {
    let nextUrl = 0;
    window.URL = { createObjectURL: () => 'blob:fixture-' + (++nextUrl), revokeObjectURL() {} };
    window.Audio = class { pause() {} load() {} removeAttribute() {} play() { audioCalls.custom++; return Promise.resolve(); } };
    // 文件解码/存储单元测试在 reminder-assets.test.cjs；此处只替换 I/O，验证页面串接。
    context.ReminderAssets.inspect = async (_, file, kind) => {
      if (inspectOverride) return inspectOverride(file, kind);
      if (file.invalid) throw new Error('文件无法解码');
      return { blob: file, mime: kind === 'image' ? 'image/png' : 'audio/mpeg' };
    };
    context.ReminderAssets.createStore = () => ({
      get: async (id, kind) => assetRecords.get(id + ':' + kind),
      save: async (id, kind, record) => {
        if (assetSaveError) throw new Error(assetSaveError);
        assetRecords.set(id + ':' + kind, record);
      },
      remove: async (id, kind) => { assetRecords.delete(id + ':' + kind); }
    });
  }
  vm.runInContext(scripts.at(-1), context);
  return {
    nodes, calls, document, audioCalls, poll: () => interval(), focus: () => windowEvents.focus(), visible: () => documentEvents.visibilitychange(),
    click: (id) => nodes[id].events.click(), user: (value) => { currentUser = value; },
    intercept: (fn) => { intercept = fn; },
    notifications: (items) => { unread = items; },
    response: ok, submit: () => nodes['auth-form'].events.submit({ preventDefault() {} })
    , change: (id, value) => { nodes[id].value = value; return nodes[id].events.change(); }
    , importFile: (id, file) => { nodes[id].files = [file]; nodes[id].events.change(); }
    , notes: (rows) => { noteRecords = new Map(rows.map((row) => [row.id, row])); }
    , reminders: (rows) => { reminderRecords = new Map(rows.map((row) => [row.noteId, row])); }
    , time: (value) => { nodes['reminder-time'].value = value; nodes['reminder-time'].events.input(); }
    , check: (id, checked) => { nodes[id].checked = checked; return nodes[id].events.change(); }
    , save: () => nodes['note-form'].events.submit({ preventDefault() {} })
    , trash: (rows) => { trashRecords = new Map(rows.map((row) => [row.id, row])); }
    , confirm: (value) => { confirmValue = value; }
    , cancel: (id, target = id) => nodes[id].events.cancel({ target: nodes[target], preventDefault() {} })
    , assetSaveError: (value) => { assetSaveError = value; }
    , inspect: (fn) => { inspectOverride = fn; }
  };
}
const item = (id) => ({ id, noteId: id + 100, title: '<script>alert(1)</script> ' + id,
  dueAt: '2026-10-07T10:00:00Z', createdAt: '2026-10-07T10:00:01Z', read: false });
test('页面启动、补查多页未读、常驻卡片与按钮状态联动', async () => {
  const app = harness(); await settle();
  app.notifications(Array.from({ length: 140 }, (_, i) => item(i + 1))); await app.poll();
  assert.match(app.nodes['alert-summary'].textContent, /140/);
  assert.match(app.document.title, /140 条未读/);
  assert.equal(app.nodes['active-alerts-list'].querySelectorAll('button').length, 30);
  assert.ok(app.calls.some((c) => c.path.includes('unreadOnly=true&page=1&size=100')));
  assert.match(app.nodes['active-alerts-list'].children[0].children[0].children[0].textContent, /<script>/);
  assert.ok(app.nodes['active-alerts-list'].querySelectorAll('button').every((button) => !button.disabled));
});
test('后台不主动停止轮询，切回与聚焦触发补查；在途轮询不重叠', async () => {
  const app = harness(); await settle(); app.document.hidden = true;
  const before = app.calls.length; await app.poll(); assert.ok(app.calls.length > before);
  let resolve;
  app.intercept((path) => path.includes('unreadOnly=true') ? new Promise((r) => { resolve = r; }) : null);
  const flight = app.poll(); const inFlightCalls = app.calls.length;
  await app.poll(); assert.equal(app.calls.length, inFlightCalls);
  resolve(app.response({ items: [], hasNext: false })); await flight;
  app.intercept(null); app.document.hidden = false; app.visible(); await settle();
  const after = app.calls.length; app.focus(); await settle(); assert.ok(app.calls.length > after);
});
test('收起不调用已读接口；恢复的动态按钮在操作结束后可用', async () => {
  const app = harness(); await settle(); app.notifications([item(1)]); await app.poll();
  const [view, read, dismiss] = app.nodes['active-alerts-list'].querySelectorAll('button');
  dismiss.events.click(); await settle();
  assert.equal(app.nodes['active-alerts-list'].querySelectorAll('button').length, 0);
  assert.ok(!app.calls.some((c) => c.options.method === 'PATCH'));
  app.click('restore-alerts'); await settle();
  const restored = app.nodes['active-alerts-list'].querySelectorAll('button');
  assert.ok(restored.every((button) => !button.disabled)); restored[1].events.click(); await settle();
  assert.ok(app.calls.some((c) => c.path.endsWith('/1/read') && c.options.method === 'PATCH'));
  assert.equal(app.nodes['active-alerts-list'].querySelectorAll('button').length, 0);
});
test('暂时断网保留卡片与错误提示，下一轮恢复同步', async () => {
  const app = harness(); await settle(); app.notifications([item(1)]); await app.poll();
  app.intercept((path) => path.includes('unreadOnly=true') ? Promise.reject(new Error('offline')) : null);
  await app.poll(); assert.match(app.nodes['alert-sync-status'].textContent, /暂时无法同步/);
  assert.equal(app.nodes['active-alerts-list'].querySelectorAll('button').length, 3);
  app.intercept(null); await app.poll(); assert.match(app.nodes['alert-sync-status'].textContent, /最近检查/);
});
test('退出清空私密卡片；旧账号迟到的 401 不踢出新账号', async () => {
  const app = harness(); await settle(); app.notifications([item(1)]); await app.poll();
  let resolve;
  app.intercept((path) => path.includes('unreadOnly=true') ? new Promise((r) => { resolve = r; }) : null);
  const flight = app.poll();
  app.click('logout'); await settle();
  assert.equal(app.nodes['active-alerts-list'].querySelectorAll('button').length, 0);
  assert.ok(app.nodes.workspace.hidden); assert.doesNotMatch(app.document.title, /条未读/);
  app.intercept(null); app.notifications([]); app.user({ id: 6, username: 'next_user' });
  app.nodes.username.value = 'next_user'; app.nodes.password.value = 'x'; app.submit(); await settle();
  assert.equal(app.nodes['account-name'].textContent, 'next_user');
  resolve(app.response({ message: '请先登录' }, 401)); await flight;
  assert.equal(app.nodes.workspace.hidden, false); assert.equal(app.nodes['account-name'].textContent, 'next_user');
});
test('齿轮默认关闭，编辑中选择样式/插画/铃声/音量后刷新恢复', async () => {
  const storage = new Map(), app = harness(storage); await settle();
  assert.equal(app.nodes['settings-dialog'].open, false); app.click('open-settings');
  assert.equal(app.nodes['settings-dialog'].open, true);
  app.click('settings-close'); app.click('new-note'); await settle();
  app.change('alert-style', 'large'); app.change('alert-artwork', 'note'); app.change('sound-melody', 'soft');
  app.nodes['sound-volume'].value = '30'; app.nodes['sound-volume'].events.input(); app.click('editor-close');
  assert.equal(app.nodes['settings-dialog'].open, false);
  const next = harness(storage); await settle();
  assert.equal(next.nodes['alert-style'].value, 'large'); assert.equal(next.nodes['alert-artwork'].value, 'note');
  assert.equal(next.nodes['sound-melody'].value, 'soft'); assert.equal(next.nodes['sound-volume'].value, '30');
});
test('大弹层预览关闭回到编辑，不丢草稿/提醒时间，不调用通知写接口', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle();
  app.nodes['note-title'].value = '未提交标题'; app.nodes['note-content'].value = '未提交正文';
  app.nodes['reminder-time'].value = '2026-10-09T12:30:00'; app.click('preview-popup');
  assert.equal(app.nodes['settings-dialog'].open, false); assert.equal(app.nodes['reminder-dialog'].open, true);
  assert.equal(app.nodes['editor-dialog'].open, true);
  assert.match(app.nodes['popup-kind'].textContent, /预览/); assert.ok(app.nodes['popup-read'].disabled);
  app.cancel('reminder-dialog');
  assert.equal(app.nodes['reminder-dialog'].open, false); assert.equal(app.nodes['editor-dialog'].open, true);
  assert.equal(app.nodes['note-title'].value, '未提交标题'); assert.equal(app.nodes['note-content'].value, '未提交正文');
  assert.equal(app.nodes['reminder-time'].value, '2026-10-09T12:30:00');
  assert.ok(!app.calls.some((c) => c.options.method === 'PATCH'));
});
test('新提醒弹大卡片，关闭不标已读；同一 ID 不重复弹', async () => {
  const app = harness(); await settle(); app.change('alert-style', 'large');
  app.notifications([item(1)]); await app.poll();
  assert.equal(app.nodes['reminder-dialog'].open, true); assert.match(app.nodes['popup-title'].textContent, /<script>/);
  app.click('popup-close'); assert.equal(app.nodes['reminder-dialog'].open, false); await app.poll();
  assert.equal(app.nodes['reminder-dialog'].open, false); assert.match(app.nodes['alert-summary'].textContent, /1 条未读/);
  assert.ok(!app.calls.some((c) => c.options.method === 'PATCH'));
});
test('后台及正在调整设置时不抢弹层，切回或关闭设置后展示积压', async () => {
  const app = harness(); await settle(); app.change('alert-style', 'large'); app.document.hidden = true;
  app.notifications([item(1)]); await app.poll(); assert.equal(app.nodes['reminder-dialog'].open, false);
  app.document.hidden = false; app.visible(); await settle(); assert.equal(app.nodes['reminder-dialog'].open, true);
  app.click('popup-close'); app.click('open-settings'); app.notifications([item(2), item(1)]); await app.poll();
  assert.equal(app.nodes['reminder-dialog'].open, false); assert.equal(app.nodes['settings-dialog'].open, true);
  app.click('settings-close'); assert.equal(app.nodes['reminder-dialog'].open, true);
});
test('大弹层标记已读移除提醒，读到剩余新提醒时保持可处理', async () => {
  const app = harness(); await settle(); app.change('alert-style', 'large');
  app.notifications([item(2), item(1)]); await app.poll(); app.click('popup-read'); await settle();
  assert.equal(app.nodes['reminder-dialog'].open, true); assert.match(app.nodes['popup-title'].textContent, /1$/);
  assert.equal(app.nodes['popup-read'].disabled, false); app.click('popup-read'); await settle();
  assert.equal(app.nodes['reminder-dialog'].open, false); assert.match(app.nodes['alert-summary'].textContent, /暂无/);
});
test('已读请求失败不假装处理成功，弹层提示可重试；退出清空弹层', async () => {
  const app = harness(); await settle(); app.change('alert-style', 'large'); app.notifications([item(1)]); await app.poll();
  app.intercept((path) => path.endsWith('/read') ? Promise.reject(new Error('offline')) : null);
  app.click('popup-read'); await settle(); assert.equal(app.nodes['reminder-dialog'].open, true);
  assert.match(app.nodes['popup-message'].textContent, /offline/); assert.equal(app.nodes['popup-read'].disabled, false);
  app.cancel('reminder-dialog'); app.click('logout'); await settle();
  assert.equal(app.nodes['reminder-dialog'].open, false); assert.equal(app.nodes['settings-dialog'].open, false);
});

const importedImage = (name) => Object.assign(new Blob([new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])]), { name });
test('图片导入后选中自定义并可预览，刷新加载副本；移除不删除其他账号副本', async () => {
  const storage = new Map(), records = new Map(), app = harness(storage, records); await settle();
  app.click('new-note'); await settle(); app.importFile('image-import', importedImage('my.png')); await settle();
  assert.equal(app.nodes['alert-artwork'].value, 'custom'); assert.equal(app.nodes['custom-image-option'].disabled, false);
  assert.match(app.nodes['image-file-status'].textContent, /my.png/);
  app.click('preview-popup'); assert.equal(app.nodes['popup-art-custom'].hidden, false);
  assert.equal(app.nodes['popup-artwork'].hidden, false); assert.match(app.nodes['popup-art-custom'].src, /^blob:/);
  assert.equal(app.nodes['popup-art-bell'].hidden, true);
  const next = harness(storage, records); await settle();
  next.click('new-note'); await settle();
  assert.match(next.nodes['image-file-status'].textContent, /my.png/);
  records.set('6:image', { blob: importedImage('other.png'), name: 'other.png' });
  next.click('remove-image'); await settle();
  assert.equal(next.nodes['alert-artwork'].value, 'bell'); assert.equal(next.nodes['custom-image-option'].disabled, true);
  assert.equal(records.has('5:image'), false); assert.equal(records.has('6:image'), true);
  assert.ok(!next.calls.some((call) => call.options.method !== 'GET'));
});
test('失败导入保留旧图片与内置选项，不假装保存成功', async () => {
  const app = harness(new Map(), new Map()); await settle();
  app.click('new-note'); await settle();
  app.importFile('image-import', importedImage('good.png')); await settle();
  app.importFile('image-import', { name: 'bad.png', invalid: true }); await settle();
  assert.match(app.nodes['image-import-message'].textContent, /无法解码/);
  assert.match(app.nodes['image-file-status'].textContent, /good.png/);
  assert.equal(app.nodes['alert-artwork'].value, 'custom'); assert.equal(app.nodes['image-import'].value, '');
  assert.equal(app.nodes['editor-dialog'].open, true);
  app.change('alert-artwork', 'note'); app.click('preview-popup');
  assert.equal(app.nodes['popup-art-note'].hidden, false); assert.equal(app.nodes['popup-art-custom'].hidden, true);
});
test('铃声导入、试听、停止及移除入口连通；内置铃声可切回', async () => {
  const app = harness(new Map(), new Map()); await settle();
  app.click('new-note'); await settle();
  const audio = Object.assign(new Blob(['ID3fixture']), { name: 'my.mp3' });
  app.importFile('audio-import', audio); await settle();
  assert.equal(app.nodes['sound-melody'].value, 'custom'); assert.match(app.nodes['audio-file-status'].textContent, /my.mp3/);
  app.click('sound-test'); await settle(); assert.equal(app.nodes['sound-stop'].disabled, false);
  app.click('sound-stop'); assert.equal(app.nodes['sound-stop'].disabled, true);
  app.click('remove-audio'); await settle(); assert.equal(app.nodes['sound-melody'].value, 'chime');
  assert.equal(app.nodes['custom-audio-option'].disabled, true);
  assert.ok(!app.calls.some((call) => call.options.method !== 'GET'));
});

test('取消文件选择或重选同一文件的冒泡 cancel 不关闭编辑或全局设置', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle();
  for (const id of ['image-import', 'audio-import']) {
    app.cancel('editor-dialog', id);
    assert.equal(app.nodes['editor-dialog'].open, true);
  }
  app.click('editor-close'); app.click('open-settings');
  app.cancel('settings-dialog', 'audio-import');
  assert.equal(app.nodes['settings-dialog'].open, true);
  app.cancel('settings-dialog'); assert.equal(app.nodes['settings-dialog'].open, false);
});

for (const kind of ['image', 'audio']) {
  test(kind + ' 解码失败留在编辑，保留旧副本/选择/草稿/提醒时间并支持重试', async () => {
    const storage = new Map(), records = new Map(), app = harness(storage, records); await settle();
    app.click('new-note'); await settle();
    const file = kind === 'image' ? importedImage('good.png') : Object.assign(new Blob(['ID3fixture']), { name: 'good.mp3' });
    app.importFile(kind + '-import', file); await settle();
    const oldCopy = records.get('5:' + kind), savedPreferences = storage.get('shiji.reminderPreferences.v1.5');
    app.nodes['note-title'].value = '不能丢掉的标题'; app.nodes['note-content'].value = '不能丢掉的正文';
    app.nodes['reminder-time'].value = '2026-10-09T12:30:00';
    app.importFile(kind + '-import', { name: 'broken', invalid: true }); await settle();
    assert.match(app.nodes[kind + '-import-message'].textContent, /导入失败：文件无法解码/);
    assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(app.nodes['settings-dialog'].open, false);
    assert.equal(app.nodes['note-title'].value, '不能丢掉的标题'); assert.equal(app.nodes['note-content'].value, '不能丢掉的正文');
    assert.equal(app.nodes['reminder-time'].value, '2026-10-09T12:30:00');
    assert.equal(records.get('5:' + kind), oldCopy); assert.equal(storage.get('shiji.reminderPreferences.v1.5'), savedPreferences);
    assert.equal(app.nodes[kind === 'image' ? 'alert-artwork' : 'sound-melody'].value, 'custom');
    assert.equal(app.nodes[kind + '-import'].value, ''); assert.equal(app.nodes[kind + '-import'].disabled, false);
    assert.equal(app.nodes['editor-close'].disabled, false);
    app.importFile(kind + '-import', file); await settle();
    assert.match(app.nodes[kind + '-import-message'].textContent, /已导入/);
    assert.ok(!app.calls.some((call) => call.options.method !== 'GET'));
  });
}

test('文件存储失败不覆盖旧副本，显示错误且原编辑仍可继续操作', async () => {
  const records = new Map(), app = harness(new Map(), records); await settle();
  app.click('new-note'); await settle(); app.importFile('image-import', importedImage('old.png')); await settle();
  const oldCopy = records.get('5:image'); app.assetSaveError('浏览器空间不足');
  app.importFile('image-import', importedImage('new.png')); await settle();
  assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(records.get('5:image'), oldCopy);
  assert.match(app.nodes['image-import-message'].textContent, /导入失败：浏览器空间不足/);
  assert.match(app.nodes['image-file-status'].textContent, /old.png/);
  assert.equal(app.nodes['alert-artwork'].value, 'custom'); assert.equal(app.nodes['save-note'].disabled, false);
  app.assetSaveError(null); app.importFile('image-import', importedImage('new.png')); await settle();
  assert.match(app.nodes['image-file-status'].textContent, /new.png/);
});

test('导入处理中 Esc/聚焦/待处理提醒不能关掉编辑，失败后才恢复按钮', async () => {
  const app = harness(new Map(), new Map()); await settle(); app.click('new-note'); await settle();
  app.change('alert-style', 'large'); app.notifications([item(1)]); await app.poll();
  let rejectImport;
  app.inspect(() => new Promise((_, reject) => { rejectImport = reject; }));
  app.importFile('image-import', importedImage('slow.png')); await settle();
  assert.equal(app.nodes['editor-close'].disabled, true);
  app.cancel('editor-dialog'); app.focus(); await settle();
  assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(app.nodes['reminder-dialog'].open, false);
  rejectImport(new Error('读取超时')); await settle();
  assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(app.nodes['editor-close'].disabled, false);
  assert.match(app.nodes['image-import-message'].textContent, /导入失败：读取超时/);
  app.click('editor-close'); assert.equal(app.nodes['reminder-dialog'].open, true);
});

test('关闭编辑中的预览不收起真正待处理的大提醒队列', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle(); app.change('alert-style', 'large');
  app.notifications([item(1)]); await app.poll(); app.click('preview-popup');
  assert.match(app.nodes['popup-kind'].textContent, /预览/); app.click('popup-close');
  assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(app.nodes['reminder-dialog'].open, false);
  app.click('editor-close'); assert.equal(app.nodes['reminder-dialog'].open, true);
  assert.match(app.nodes['popup-title'].textContent, /<script>/);
  assert.ok(!app.calls.some((call) => call.options.method === 'PATCH'));
});

test('导入等待中会话失效，迟到文件不能保存副本、重开编辑或留下错误', async () => {
  const records = new Map(), app = harness(new Map(), records); await settle(); app.click('new-note'); await settle();
  let finishPoll, finishImport;
  app.intercept((path) => path.includes('unreadOnly=true') ? new Promise((resolve) => { finishPoll = resolve; }) : null);
  const polling = app.poll(); await settle();
  app.inspect(() => new Promise((resolve) => { finishImport = resolve; }));
  app.importFile('image-import', importedImage('late.png')); await settle();
  finishPoll(app.response({ message: '请先登录' }, 401)); await polling;
  finishImport({ blob: importedImage('late.png'), mime: 'image/png' }); await settle();
  assert.equal(app.nodes.workspace.hidden, true); assert.equal(app.nodes['editor-dialog'].open, false);
  assert.equal(app.nodes['asset-status'].textContent, ''); assert.equal(records.size, 0);
  assert.equal(app.nodes['image-import-message'].textContent, '');
});

const note = (id) => ({ id, title: '备忘录 ' + id, content: '正文 <img onerror=alert(1)>', completed: false,
  createdAt: '2026-10-07T10:00:00Z', updatedAt: '2026-10-07T10:00:00Z' });
test('主页默认不打开编辑，点击备忘录后在弹窗填充标题/正文和提醒状态', async () => {
  const app = harness(); await settle(); assert.equal(app.nodes['editor-dialog'].open, false);
  app.notes([note(1)]); app.click('refresh-notes'); await settle();
  assert.equal(app.nodes['editor-dialog'].open, false);
  app.nodes['notes-list'].children[0].events.click(); await settle();
  assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(app.nodes['note-title'].value, '备忘录 1');
  assert.equal(app.nodes['note-content'].value, note(1).content); assert.equal(app.nodes['delete-note'].disabled, false);
  app.click('editor-close'); assert.equal(app.nodes['editor-dialog'].open, false);
});
test('新建编辑弹窗支持保存，未保存文字关闭/Esc 先确认且不会丢掉取消的草稿', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle();
  assert.equal(app.nodes['editor-dialog'].open, true); assert.equal(app.nodes['delete-note'].disabled, true);
  app.nodes['note-title'].value = '草稿'; app.confirm(false);
  app.cancel('editor-dialog'); assert.equal(app.nodes['editor-dialog'].open, true);
  assert.equal(app.nodes['note-title'].value, '草稿');
  app.nodes['note-form'].events.submit({ preventDefault() {} }); await settle();
  assert.ok(app.calls.some((call) => call.path === '/api/notes/save' && call.options.method === 'POST'));
  assert.match(app.nodes['editor-message'].textContent, /已保存/); assert.equal(app.nodes['delete-note'].disabled, false);
  app.click('editor-close'); assert.equal(app.nodes['editor-dialog'].open, false);
  app.click('new-note'); await settle(); assert.equal(app.nodes['note-title'].value, '');
});
test('删除二次确认后移入回收站并关闭编辑，取消删除不发写请求', async () => {
  const app = harness(); await settle(); app.notes([note(1)]); app.click('refresh-notes'); await settle();
  app.nodes['notes-list'].children[0].events.click(); await settle(); app.confirm(false);
  app.click('delete-note'); await settle(); assert.equal(app.nodes['editor-dialog'].open, true);
  assert.ok(!app.calls.some((call) => call.options.method === 'DELETE'));
  app.confirm(true); app.click('delete-note'); await settle(); assert.equal(app.nodes['editor-dialog'].open, false);
  assert.match(app.nodes['workspace-message'].textContent, /回收站/);
  app.click('open-trash'); await settle(); assert.equal(app.nodes['trash-dialog'].open, true);
  assert.equal(app.nodes['trash-title'].textContent, '备忘录 1'); assert.equal(app.nodes['trash-restore'].disabled, false);
});
test('回收站恢复带 CSRF 请求，成功后在编辑弹窗展示内容；失败不假装恢复', async () => {
  const app = harness(); await settle(); app.trash([{ ...note(7), deletedAt: '2026-10-07T10:01:00Z' }]);
  app.click('open-trash'); await settle(); assert.equal(app.nodes['trash-content'].textContent, note(7).content);
  app.intercept((path) => path.endsWith('/restore') ? Promise.reject(new Error('offline')) : null);
  app.click('trash-restore'); await settle(); assert.equal(app.nodes['trash-dialog'].open, true);
  assert.match(app.nodes['trash-message'].textContent, /offline/); assert.equal(app.nodes['trash-restore'].disabled, false);
  app.intercept(null); app.click('trash-restore'); await settle();
  const request = app.calls.find((call) => call.path.endsWith('/7/restore'));
  assert.equal(request.options.method, 'POST'); assert.equal(request.options.headers['X-CSRF-TOKEN'], 'test-placeholder');
  assert.equal(app.nodes['trash-dialog'].open, false); assert.equal(app.nodes['editor-dialog'].open, true);
  assert.equal(app.nodes['note-title'].value, '备忘录 7'); assert.match(app.nodes['editor-message'].textContent, /重新设置提醒/);
});
test('编辑与回收站模态不被大提醒打断，关闭后展示待处理提醒；退出清除私密内容', async () => {
  const app = harness(); await settle(); app.change('alert-style', 'large'); app.click('new-note'); await settle();
  app.notifications([item(1)]); await app.poll(); assert.equal(app.nodes['reminder-dialog'].open, false);
  app.click('editor-close'); assert.equal(app.nodes['reminder-dialog'].open, true); app.click('popup-close');
  app.trash([{ ...note(7), deletedAt: '2026-10-07T10:01:00Z' }]); app.click('open-trash'); await settle();
  app.notifications([item(2), item(1)]); await app.poll(); assert.equal(app.nodes['reminder-dialog'].open, false);
  app.click('trash-close'); assert.equal(app.nodes['reminder-dialog'].open, true); app.click('popup-close');
  app.click('logout'); await settle(); assert.equal(app.nodes['trash-dialog'].open, false);
  assert.equal(app.nodes['trash-content'].textContent, ''); assert.equal(app.nodes['editor-dialog'].open, false);
});

test('回收站恢复等待中会话失效，迟到成功不得重新显示私人编辑内容', async () => {
  const app = harness(); await settle(); app.trash([{ ...note(7), deletedAt: '2026-10-07T10:01:00Z' }]);
  app.click('open-trash'); await settle();
  let finishPoll, finishRestore;
  app.intercept((path) => path.includes('unreadOnly=true') ? new Promise((resolve) => { finishPoll = resolve; })
    : path.endsWith('/restore') ? new Promise((resolve) => { finishRestore = resolve; }) : null);
  const polling = app.poll(); await settle(); app.click('trash-restore'); await settle();
  finishPoll(app.response({ message: '请先登录' }, 401)); await polling;
  finishRestore(app.response(note(999))); await settle();
  assert.equal(app.nodes['workspace'].hidden, true); assert.equal(app.nodes['editor-dialog'].open, false);
  assert.equal(app.nodes['trash-content'].textContent, '');
});

test('旧后端没有回收站时阻止 DELETE，不把永久删除冒充可恢复', async () => {
  const app = harness(); await settle(); app.notes([note(1)]); app.click('refresh-notes'); await settle();
  app.nodes['notes-list'].children[0].events.click(); await settle();
  app.intercept((path) => path === '/api/notes/trash' ? Promise.resolve(app.response({ message: '不存在' }, 404)) : null);
  app.click('delete-note'); await settle();
  assert.ok(!app.calls.some((call) => call.options.method === 'DELETE'));
  assert.equal(app.nodes['editor-dialog'].open, true); assert.match(app.nodes['editor-message'].textContent, /重启/);
});

const localTime = (date) => {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};
const futureTime = (minutes = 10) => localTime(new Date(Date.now() + minutes * 60000));
const saveCalls = (app) => app.calls.filter((call) => /\/api\/notes\/(?:\d+\/)?save$/.test(call.path));
const scheduled = (time) => ({ id: 101, noteId: 1, generation: 1, dueAt: new Date(time).toISOString(), status: 'SCHEDULED' });
async function editExisting(app, reminder = null) {
  app.notes([note(1)]); if (reminder) app.reminders([reminder]);
  app.click('refresh-notes'); await settle(); app.nodes['notes-list'].children[0].events.click(); await settle();
}

test('新建时直接填写时间，一次带 CSRF 的保存同时创建内容与提醒', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle();
  assert.equal(app.nodes['reminder-time'].disabled, false);
  const time = futureTime(); app.nodes['note-title'].value = '第一次直接设提醒'; app.time(time);
  assert.equal(app.nodes['reminder-enabled'].checked, true); app.save(); await settle();
  const writes = app.calls.filter((call) => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(call.options.method));
  assert.equal(writes.length, 1); assert.equal(writes[0].path, '/api/notes/save');
  assert.equal(writes[0].options.headers['X-CSRF-TOKEN'], 'test-placeholder');
  assert.deepEqual(JSON.parse(writes[0].options.body), {
    title: '第一次直接设提醒', content: '', reminderAction: 'SET', dueAt: new Date(time).toISOString()
  });
  assert.equal(app.nodes['reminder-time'].value, time); assert.match(app.nodes['reminder-state'].textContent, /待提醒/);
});

test('编辑一次提交正文和改期，只改正文使用 KEEP 不递增提醒批次，取消也随保存提交', async () => {
  const app = harness(); await settle(); await editExisting(app, scheduled(futureTime()));
  const time = futureTime(20); app.nodes['note-content'].value = '正文一起修改'; app.time(time); app.save(); await settle();
  assert.equal(saveCalls(app)[0].options.method, 'PUT'); assert.equal(saveCalls(app)[0].path, '/api/notes/1/save');
  assert.equal(JSON.parse(saveCalls(app)[0].options.body).reminderAction, 'SET');
  app.nodes['note-title'].value = '只改标题'; app.save(); await settle();
  assert.equal(JSON.parse(saveCalls(app)[1].options.body).reminderAction, 'KEEP');
  app.check('reminder-enabled', false); app.save(); await settle();
  assert.equal(JSON.parse(saveCalls(app)[2].options.body).reminderAction, 'CANCEL');
  assert.equal(app.nodes['reminder-enabled'].checked, false); assert.match(app.nodes['reminder-state'].textContent, /已取消/);
});

test('已触发提醒只改正文不重新设任务；勾选开关可直接填入未来时间', async () => {
  const app = harness(); await settle(); await editExisting(app, { ...scheduled(futureTime()), status: 'FIRED' });
  assert.equal(app.nodes['reminder-enabled'].checked, false);
  app.nodes['note-title'].value = '已提醒后改标题'; app.save(); await settle();
  assert.equal(JSON.parse(saveCalls(app)[0].options.body).reminderAction, 'KEEP');
  app.check('reminder-enabled', true); assert.ok(new Date(app.nodes['reminder-time'].value).getTime() > Date.now());
  app.save(); await settle(); assert.equal(JSON.parse(saveCalls(app)[1].options.body).reminderAction, 'SET');
});

test('无效时间不发写请求，服务端保存失败保留所有草稿并允许原地重试', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle();
  app.nodes['note-title'].value = '保留标题'; app.nodes['note-content'].value = '保留正文';
  app.time(localTime(new Date(Date.now() - 60000))); app.save(); await settle();
  assert.equal(saveCalls(app).length, 0); assert.match(app.nodes['editor-message'].textContent, /未来一年/);
  const time = futureTime(); app.time(time);
  app.intercept((path) => path === '/api/notes/save' ? Promise.resolve(app.response({ message: '保存失败' }, 500)) : null);
  app.save(); await settle(); assert.equal(app.nodes['editor-dialog'].open, true);
  assert.equal(app.nodes['note-title'].value, '保留标题'); assert.equal(app.nodes['note-content'].value, '保留正文');
  assert.equal(app.nodes['reminder-time'].value, time); assert.equal(app.nodes['reminder-enabled'].checked, true);
  assert.equal(app.nodes['save-note'].disabled, false);
  app.intercept(null); app.save(); await settle(); assert.match(app.nodes['editor-message'].textContent, /已保存/);
});

test('轮询服务端状态变化保留未保存改期，只有时间变动的关闭也需要确认', async () => {
  const app = harness(); await settle(); const original = scheduled(futureTime()); await editExisting(app, original);
  const draft = futureTime(30); app.time(draft); app.confirm(false); app.click('editor-close');
  assert.equal(app.nodes['editor-dialog'].open, true);
  app.reminders([{ ...original, status: 'FIRED' }]); await app.poll();
  assert.equal(app.nodes['reminder-time'].value, draft); assert.equal(app.nodes['reminder-enabled'].checked, true);
  assert.match(app.nodes['reminder-state'].textContent, /正在修改的时间保留/);
  app.save(); await settle(); assert.equal(JSON.parse(saveCalls(app)[0].options.body).dueAt, new Date(draft).toISOString());
});

test('旧后端不支持统一保存时保留草稿，不回退成两个独立写入', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle();
  app.nodes['note-title'].value = '不能部分保存'; const time = futureTime(); app.time(time);
  app.intercept((path) => path === '/api/notes/save' ? Promise.resolve(app.response({ message: '不存在' }, 404)) : null);
  app.save(); await settle(); assert.equal(saveCalls(app).length, 1);
  assert.ok(!app.calls.some((call) => call.path === '/api/notes' && call.options.method === 'POST'));
  assert.equal(app.nodes['reminder-time'].value, time); assert.match(app.nodes['editor-message'].textContent, /重启/);
});

test('保存成功但列表刷新失败明确说明已经保存，下次按已有 ID 更新不重复新建', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle(); app.nodes['note-title'].value = '一次新建';
  app.intercept((path) => path.startsWith('/api/notes?page=') ? Promise.reject(new Error('offline')) : null);
  app.save(); await settle(); assert.match(app.nodes['editor-message'].textContent, /已保存，但列表刷新失败/);
  app.intercept(null); app.save(); await settle();
  assert.equal(saveCalls(app)[0].options.method, 'POST'); assert.equal(saveCalls(app)[1].options.method, 'PUT');
});

test('保存等待中会话失效，迟到成功不能重新显示私人编辑内容', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle(); app.nodes['note-title'].value = '隐私';
  let finishPoll, finishSave;
  app.intercept((path) => path.includes('unreadOnly=true') ? new Promise((resolve) => { finishPoll = resolve; })
    : path === '/api/notes/save' ? new Promise((resolve) => { finishSave = resolve; }) : null);
  const polling = app.poll(); await settle(); app.save(); await settle();
  finishPoll(app.response({ message: '请先登录' }, 401)); await polling;
  finishSave(app.response({ note: note(999), reminder: null })); await settle();
  assert.equal(app.nodes.workspace.hidden, true); assert.equal(app.nodes['editor-dialog'].open, false);
  assert.equal(app.nodes['note-title'].value, '');
});

test('记住声音开启但刷新未激活时，保存先恢复音频，新到期会响且同一通知不重复响', async () => {
  const storage = new Map([['shiji.reminderPreferences.v1.5', JSON.stringify({ version: 1, soundWanted: true })]]);
  const app = harness(storage); await settle(); app.click('new-note'); await settle(); app.nodes['note-title'].value = '听到实际提醒';
  assert.match(app.nodes['editor-sound-ready'].textContent, /尚未激活|未激活/);
  app.intercept((path) => { if (path === '/api/notes/save') assert.equal(app.audioCalls.resumes, 1); return null; });
  app.time(futureTime()); app.save(); await settle(); assert.equal(app.audioCalls.tones, 0);
  app.notifications([item(1)]); await app.poll(); assert.equal(app.audioCalls.tones, 2);
  assert.match(app.nodes['alert-sound-status'].textContent, /请求播放本次到期铃声/);
  await app.poll(); assert.equal(app.audioCalls.tones, 2);
});

test('仅试听不替用户打开全局声音，保存与实际到期都保留关闭并说明原因', async () => {
  const app = harness(); await settle(); app.click('new-note'); await settle(); app.click('sound-test'); await settle();
  assert.equal(app.audioCalls.tones, 2); assert.match(app.nodes['editor-sound-ready'].textContent, /关闭/);
  app.nodes['note-title'].value = '关闭仍试听'; app.time(futureTime()); app.save(); await settle();
  app.notifications([item(1)]); await app.poll(); assert.equal(app.audioCalls.tones, 2);
  assert.match(app.nodes['alert-sound-status'].textContent, /开关关闭/);
});

test('已开启声音后导入替换铃声，保存重新激活新播放器，实际到期使用导入铃声', async () => {
  const app = harness(new Map(), new Map()); await settle(); app.click('open-settings');
  app.check('sound-enabled', true); await settle(); app.click('settings-close'); app.click('new-note'); await settle();
  app.importFile('audio-import', Object.assign(new Blob(['ID3fixture']), { name: 'due.mp3' })); await settle();
  const before = app.audioCalls.custom; app.nodes['note-title'].value = '使用导入铃声'; app.time(futureTime()); app.save(); await settle();
  assert.equal(app.audioCalls.custom, before + 1);
  app.notifications([item(1)]); await app.poll(); assert.equal(app.audioCalls.custom, before + 2);
  await app.poll(); assert.equal(app.audioCalls.custom, before + 2);
});
