'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const preferences = require('../../main/resources/static/reminder-preferences.js');
const { create } = require('../../main/resources/static/reminder-alerts.js');
function env() {
  const storage = new Map(), played = [], gains = [];
  return { storage, played, gains, isSecureContext: true,
    localStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    AudioContext: class {
      constructor() { this.state = 'suspended'; this.currentTime = 0; }
      resume() { this.state = 'running'; return Promise.resolve(); }
      close() { return Promise.resolve(); }
      createOscillator() { const frequency = {}; return { frequency, connect() {}, disconnect() {}, start() { played.push(frequency.value); }, stop() {} }; }
      createGain() { return { gain: { setValueAtTime() {}, exponentialRampToValueAtTime() {}, linearRampToValueAtTime(value) { gains.push(value); } }, connect() {}, disconnect() {} }; }
    }
  };
}
const item = (id) => ({ id, noteId: id + 100, title: 'test', read: false });
test('偏好白名单与类型/枚举/音量校验，不持久化额外字段', () => {
  const browser = env();
  preferences.save(browser, 5, { style: 'large', artwork: 'note', melody: 'soft', volume: 30, soundWanted: true,
    unrelatedPrivateValue: 'must-not-persist', title: 'secret-note', extra: { nested: true } });
  const raw = browser.storage.get('shiji.reminderPreferences.v1.5');
  assert.doesNotMatch(raw, /must-not-persist|secret-note|nested/);
  assert.equal(preferences.load(browser, 5).preferences.style, 'large');
  assert.deepEqual(preferences.normalize({ style: '<img>', artwork: 'remote', melody: 'remote', volume: 101, soundWanted: 'true' }), preferences.defaults);
  assert.equal(preferences.normalize({ volume: 0 }).volume, 0);
  assert.equal(preferences.normalize({ volume: -1 }).volume, 50);
  assert.equal(preferences.normalize({ volume: 1.5 }).volume, 50);
});
test('按账号与版本读取；异常 JSON、未知版本、空数组回退默认值', () => {
  const browser = env(); preferences.save(browser, 5, { style: 'large' });
  assert.equal(preferences.load(browser, 6).preferences.style, 'card');
  for (const raw of ['{broken', 'null', '[]', '{"version":2,"style":"large"}']) {
    browser.storage.set('shiji.reminderPreferences.v1.5', raw);
    assert.equal(preferences.load(browser, 5).preferences.style, 'card');
  }
  preferences.save(browser, '../other', { style: 'large' }); assert.equal(browser.storage.size, 1);
});
test('禁用或配额耗尽的浏览器存储不影响本次设置', () => {
  const browser = { get localStorage() { throw new Error('blocked'); } };
  assert.match(preferences.load(browser, 5).message, /无法读取/);
  assert.match(preferences.save(browser, 5, { style: 'large' }), /无法保存/);
  const alerts = create(browser, () => {}, () => {}); alerts.reset(5); alerts.updatePreferences({ style: 'large' });
  assert.equal(alerts.snapshot().preferences.style, 'large'); assert.match(alerts.snapshot().preferenceStatus, /无法保存/);
});
test('三种铃声可试听，音量与静音生效；试听不自动启用提醒声音', async () => {
  for (const [melody, length] of [['chime', 2], ['soft', 3], ['clear', 3]]) {
    const browser = env(), alerts = create(browser, () => {}, () => {}); alerts.reset(5);
    alerts.updatePreferences({ melody, volume: 25 }); await alerts.previewSound();
    assert.equal(browser.played.length, length); assert.ok(browser.gains.every((value) => value === 0.04));
    assert.equal(alerts.snapshot().preferences.soundWanted, false); assert.equal(alerts.snapshot().sound, false);
    alerts.updatePreferences({ volume: 0 }); await alerts.previewSound(); assert.equal(browser.played.length, length);
  }
});
test('声音偏好可持久化，但刷新后不假装已解锁播放；退出不删除偏好', async () => {
  const browser = env(), alerts = create(browser, () => {}, () => {}); alerts.reset(5); await alerts.enableSound();
  assert.ok(alerts.snapshot().sound); alerts.reset(); assert.equal(alerts.snapshot().sound, false);
  alerts.reset(5); assert.equal(alerts.snapshot().preferences.soundWanted, true); assert.equal(alerts.snapshot().sound, false);
  assert.match(alerts.snapshot().soundStatus, /尚未激活/); alerts.mute(); alerts.reset(5);
  assert.equal(alerts.snapshot().preferences.soundWanted, false);
});
test('大弹层只排入首次基线后的新未读，关闭不改变服务器已读状态', () => {
  const browser = env(), alerts = create(browser, () => {}, () => {}); alerts.reset(5); alerts.updatePreferences({ style: 'large' });
  alerts.ingest([item(1)]); assert.equal(alerts.snapshot().popupItems.length, 0);
  alerts.ingest([item(2), item(1)]); assert.equal(alerts.snapshot().popupItems.length, 1);
  alerts.closePopup(); assert.equal(alerts.snapshot().count, 2); alerts.ingest([item(2), item(1)]);
  assert.equal(alerts.snapshot().popupItems.length, 0);
  alerts.ingest([item(3), item(2), item(1)]); alerts.updatePreferences({ style: 'card' });
  assert.equal(alerts.snapshot().popupItems.length, 0);
});
test('大弹层中已读/删除被下一轮同步移除，剩余新提醒继续排队', () => {
  const browser = env(), alerts = create(browser, () => {}, () => {}); alerts.reset(5); alerts.updatePreferences({ style: 'large' });
  alerts.ingest([]); alerts.ingest([item(1), item(2)]); alerts.remove(1);
  assert.equal(alerts.snapshot().popupItems.length, 1); alerts.ingest([]);
  assert.equal(alerts.snapshot().popupItems.length, 0);
});
test('已有桌面授权可恢复偏好，未授权时不自动请求；退出清空当前账号开关', async () => {
  const browser = env(); let requests = 0;
  browser.Notification = class { static permission = 'granted'; static requestPermission() { requests++; return Promise.resolve('granted'); } };
  const alerts = create(browser, () => {}, () => {}); alerts.reset(5); await alerts.enableDesktop();
  alerts.reset(); assert.equal(alerts.snapshot().desktop, false);
  alerts.reset(5); assert.equal(alerts.snapshot().desktop, true);
  browser.Notification.permission = 'default'; alerts.reset(5);
  assert.equal(alerts.snapshot().desktop, false); assert.equal(requests, 0);
  assert.equal(alerts.snapshot().preferences.desktopWanted, true);
  alerts.reset(6); assert.equal(alerts.snapshot().preferences.desktopWanted, false);
});
