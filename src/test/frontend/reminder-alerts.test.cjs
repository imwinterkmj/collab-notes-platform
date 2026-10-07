'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { create, collectUnread } = require('../../main/resources/static/reminder-alerts.js');
const item = (id, read = false) => ({ id, noteId: id + 100, title: '<img src=x onerror=alert(1)> ' + id, read });
function harness() {
  const calls = { tones: 0, notifications: [], permissions: 0, focus: 0, opened: [] };
  class Audio {
    constructor() { this.state = 'suspended'; this.currentTime = 0; this.destination = {}; calls.context = this; }
    resume() { this.state = 'running'; return Promise.resolve(); }
    close() { this.state = 'closed'; return Promise.resolve(); }
    createOscillator() {
      return { frequency: {}, connect() {}, disconnect() {}, start() { calls.tones++; }, stop() {} };
    }
    createGain() { return { gain: { setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} }, connect() {}, disconnect() {} }; }
  }
  class Desktop {
    static permission = 'default';
    static requestPermission() { calls.permissions++; this.permission = 'granted'; return Promise.resolve('granted'); }
    constructor(title, options) { this.title = title; this.options = options; this.closed = false; calls.notifications.push(this); }
    close() { this.closed = true; this.onclose?.(); }
  }
  const env = { AudioContext: Audio, Notification: Desktop, isSecureContext: true, focus() { calls.focus++; } };
  let snapshot;
  const engine = create(env, (value) => { snapshot = value; }, (id) => calls.opened.push(id));
  engine.reset(5);
  return { engine, env, calls, snapshot: () => snapshot };
}
test('历史未读持续显示但不突然响铃；新发现批次只响一次', async () => {
  const { engine, calls, snapshot } = harness();
  await engine.enableSound(); await engine.enableDesktop();
  engine.ingest([item(1)]);
  assert.equal(calls.tones, 0); assert.equal(calls.notifications.length, 0);
  assert.equal(snapshot().items.length, 1);
  engine.ingest([item(3), item(2), item(1)]);
  assert.equal(calls.tones, 2); assert.equal(calls.notifications.length, 1);
  assert.match(calls.notifications[0].options.body, /等 2 条/);
  engine.ingest([item(3), item(2), item(1)]);
  engine.ingest([item(1)]); engine.ingest([item(3), item(2), item(1)]);
  assert.equal(calls.tones, 2); assert.equal(calls.notifications.length, 1);
});
test('收起卡片不标记已读，恢复可见；已读/删除移除卡片与系统通知', async () => {
  const { engine, snapshot, calls } = harness();
  engine.ingest([]); await engine.enableDesktop(); engine.ingest([item(1)]);
  engine.dismiss(1); assert.equal(snapshot().count, 1); assert.equal(snapshot().items.length, 0);
  engine.ingest([item(1)]); assert.equal(snapshot().items.length, 0);
  engine.restore(); assert.equal(snapshot().items.length, 1);
  engine.ingest([item(1, true)]); assert.equal(snapshot().count, 0); assert.ok(calls.notifications[0].closed);
  engine.ingest([item(2)]); engine.ingest([]); assert.equal(snapshot().count, 0);
});
test('系统通知点击仅打开当前账号备忘录，不自动标记已读', async () => {
  const { engine, calls, snapshot } = harness();
  engine.ingest([]); await engine.enableDesktop(); engine.ingest([item(1)]);
  calls.notifications[0].onclick();
  assert.deepEqual(calls.opened, [101]); assert.equal(calls.focus, 1); assert.equal(snapshot().count, 1);
});
test('声音与桌面通知可独立关闭，关闭桌面不撤销站点权限', async () => {
  const { engine, calls, env } = harness();
  engine.ingest([]); await engine.enableSound(true); assert.equal(calls.tones, 2);
  await engine.enableDesktop(); engine.testDesktop();
  engine.mute(); engine.disableDesktop(); engine.ingest([item(1)]);
  assert.equal(calls.tones, 2); assert.equal(calls.notifications.length, 1);
  assert.equal(env.Notification.permission, 'granted'); assert.ok(calls.notifications[0].closed);
});
test('未授权时不弹权限窗口、不播放声音或桌面测试', () => {
  const { engine, calls } = harness();
  engine.ingest([]); engine.ingest([item(1)]); engine.testDesktop();
  assert.equal(calls.permissions, 0); assert.equal(calls.notifications.length, 0); assert.equal(calls.tones, 0);
});
test('拒绝权限或关闭授权弹窗均回退页面卡片，不重复申请', async () => {
  for (const permission of ['denied', 'default']) {
    const { engine, env, calls, snapshot } = harness();
    env.Notification.requestPermission = () => { calls.permissions++; return Promise.resolve(permission); };
    if (permission === 'denied') env.Notification.permission = permission;
    await engine.enableDesktop(); engine.ingest([]); engine.ingest([item(1)]);
    assert.equal(snapshot().desktop, false); assert.equal(snapshot().items.length, 1);
    assert.equal(calls.permissions, permission === 'denied' ? 0 : 1);
    assert.equal(calls.notifications.length, 0);
  }
});
test('不安全连接、缺少接口、移动浏览器构造失败都有回退说明', async () => {
  for (const scenario of ['insecure', 'unsupported', 'constructor']) {
    const { engine, env, snapshot } = harness();
    if (scenario === 'insecure') env.isSecureContext = false;
    if (scenario === 'unsupported') delete env.Notification;
    if (scenario === 'constructor') {
      env.Notification = class { static permission = 'granted'; constructor() { throw new TypeError(); } };
    }
    await engine.enableDesktop(); engine.ingest([]); engine.ingest([item(1)]);
    assert.equal(snapshot().desktop, false); assert.equal(snapshot().items.length, 1);
    assert.match(snapshot().desktopStatus, /页面提醒/);
  }
});
test('运行中撤销权限或系统报错不会清除未读卡片', async () => {
  const { engine, env, calls, snapshot } = harness();
  await engine.enableDesktop(); engine.ingest([]); engine.ingest([item(1)]);
  calls.notifications[0].onerror(); assert.equal(snapshot().desktop, false); assert.equal(snapshot().count, 1);
  await engine.enableDesktop(); env.Notification.permission = 'denied'; engine.ingest([item(2), item(1)]);
  assert.equal(snapshot().desktop, false); assert.equal(snapshot().count, 2);
});
test('音频接口缺失、恢复失败、后台暂停均回退为卡片', async () => {
  for (const scenario of ['missing', 'resume', 'suspended']) {
    const { engine, env, snapshot } = harness();
    if (scenario === 'missing') delete env.AudioContext;
    if (scenario === 'resume') env.AudioContext.prototype.resume = () => Promise.reject(new Error('blocked'));
    if (scenario === 'suspended') env.AudioContext.prototype.resume = () => Promise.resolve();
    await engine.enableSound(true); engine.ingest([]); engine.ingest([item(1)]);
    assert.equal(snapshot().sound, false); assert.match(snapshot().soundStatus, /页面提醒/);
    assert.equal(snapshot().items.length, 1);
  }
});
test('退出/切换账号关闭系统提醒、清空卡片与开关，旧点击无效', async () => {
  const { engine, calls, snapshot } = harness();
  await engine.enableSound(); await engine.enableDesktop(); engine.ingest([]); engine.ingest([item(1)]);
  const stale = calls.notifications[0]; engine.reset(6); stale.onclick();
  assert.ok(stale.closed); assert.equal(snapshot().count, 0); assert.equal(snapshot().sound, false);
  assert.equal(snapshot().desktop, false); assert.deepEqual(calls.opened, []);
  engine.ingest([item(1)]); assert.equal(calls.notifications.length, 1);
  engine.reset(); engine.ingest([item(2)]); assert.equal(snapshot().count, 0);
});
test('开启后音频被暂停，保留卡片并允许用户点击测试恢复', async () => {
  const { engine, calls, snapshot } = harness();
  await engine.enableSound(); engine.ingest([]); calls.context.state = 'suspended'; engine.ingest([item(1)]);
  assert.equal(snapshot().sound, false); assert.equal(snapshot().count, 1); assert.equal(calls.tones, 0);
  await engine.enableSound(true); assert.equal(snapshot().sound, true); assert.equal(calls.tones, 2);
});
test('立即处理已读即关闭对应系统提醒，不依赖下一轮补查', async () => {
  const { engine, calls } = harness();
  await engine.enableDesktop(); engine.ingest([]); engine.ingest([item(1)]); engine.remove(1);
  assert.ok(calls.notifications[0].closed); assert.equal(engine.snapshot().count, 0);
});
test('等待权限/音频恢复时退出，迟到的结果不能启用下一账号开关', async () => {
  const { engine, env, snapshot } = harness();
  let resolvePermission, resolveAudio;
  env.Notification.requestPermission = () => new Promise((resolve) => { resolvePermission = resolve; });
  env.AudioContext.prototype.resume = () => new Promise((resolve) => { resolveAudio = resolve; });
  const permission = engine.enableDesktop(), audio = engine.enableSound(true);
  engine.reset(6); resolvePermission('granted'); resolveAudio(); await Promise.all([permission, audio]);
  assert.equal(snapshot().desktop, false); assert.equal(snapshot().sound, false); assert.equal(snapshot().permissionBusy, false);
});
test('积压超过 20 条时翻页补查，重复行去重；单轮上限明确', async () => {
  let requests = 0;
  const result = await collectUnread(async (page) => {
    requests++; return { items: [...Array.from({ length: 100 }, (_, i) => item(page * 100 + i + 1)), item(1)], hasNext: page < 3 };
  });
  assert.equal(requests, 4); assert.equal(result.items.length, 400); assert.equal(result.hasMore, false);
  const bounded = await collectUnread(async () => ({ items: [item(1)], hasNext: true }));
  assert.equal(bounded.hasMore, true); assert.equal(bounded.items.length, 1);
});
test('补查失败不提交半个结果，下一轮可重新检查', async () => {
  await assert.rejects(collectUnread(async (page) => {
    if (page === 1) throw new Error('offline'); return { items: [item(1)], hasNext: true };
  }), /offline/);
  assert.equal((await collectUnread(async () => ({ items: [item(1)], hasNext: false }))).items.length, 1);
});

test('实际到期区分总开关关闭、播放未激活和音量为零，不把试听成功误认成可到期播放', async () => {
  const { engine, calls, snapshot } = harness();
  await engine.previewSound(); engine.ingest([]); engine.ingest([item(1)]);
  assert.equal(calls.tones, 2); assert.equal(snapshot().preferences.soundWanted, false);
  assert.match(snapshot().soundStatus, /开关关闭/);
  engine.updatePreferences({ soundWanted: true }); engine.ingest([item(2), item(1)]);
  assert.match(snapshot().soundStatus, /尚未激活/); assert.equal(calls.tones, 2);
  await engine.enableSound(); engine.updatePreferences({ volume: 0 }); engine.ingest([item(3), item(2), item(1)]);
  assert.match(snapshot().soundStatus, /音量为 0/); assert.equal(calls.tones, 2);
});

test('音频上下文暂停立即更新状态，用户恢复后只对新通知播放，不补响已经展示的记录', async () => {
  const { engine, calls, snapshot } = harness(); await engine.enableSound(); engine.ingest([]);
  calls.context.state = 'suspended'; calls.context.onstatechange();
  assert.equal(snapshot().sound, false); assert.match(snapshot().soundStatus, /暂停/);
  engine.ingest([item(1)]); assert.equal(calls.tones, 0);
  await engine.enableSound(); engine.ingest([item(1)]); assert.equal(calls.tones, 0);
  engine.ingest([item(2), item(1)]); assert.equal(calls.tones, 2);
});
