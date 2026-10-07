'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validateFile, inspect, createStore } = require('../../main/resources/static/reminder-assets.js');
const { create } = require('../../main/resources/static/reminder-alerts.js');
const signatures = {
  png: [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0], jpg: [255, 216, 255, 0],
  webp: [...Buffer.from('RIFFxxxxWEBP')], mp3: [...Buffer.from('ID3xxxxxx')],
  wav: [...Buffer.from('RIFFxxxxWAVE')], ogg: [...Buffer.from('OggSxxxx')]
};
const file = (format) => new Blob([new Uint8Array(signatures[format])]);
function environment({ width = 800, height = 600, duration = 12, invalid = false } = {}) {
  const revoked = [], records = new Map(), storage = new Map(); let created = 0, fail = false, plays = 0;
  const later = (fn) => setImmediate(fn);
  class Media {
    constructor(url) { this.srcValue = url; this.duration = duration; this.currentTime = 0; this.volume = 1; }
    set src(value) { this.srcValue = value; later(() => invalid ? this.onerror?.() : this.onloadedmetadata?.()); }
    pause() { this.onpause?.(); }
    play() { plays++; this.onplaying?.(); return Promise.resolve(); }
    removeAttribute() { this.srcValue = null; }
    load() {}
  }
  const browser = {
    Blob, revoked, records, isSecureContext: true, Audio: Media,
    localStorage: { getItem: (key) => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    setTimeout, clearTimeout,
    URL: { createObjectURL() { return 'blob:local-test-' + (++created); }, revokeObjectURL(url) { revoked.push(url); } },
    Image: class {
      naturalWidth = width; naturalHeight = height;
      set src(_) { later(() => invalid ? this.onerror?.() : this.onload?.()); }
    },
    document: { createElement: () => new Media() },
    indexedDB: { open() {
      const request = {};
      later(() => {
        request.result = {
          close() {}, createObjectStore() {},
          transaction() {
            const tx = {};
            tx.objectStore = () => {
              const op = (action, input) => {
                const result = {};
                later(() => {
                  if (fail) { tx.onabort?.(); return; }
                  if (action === 'get') result.result = records.get(input);
                  if (action === 'put') records.set(input.key, input);
                  if (action === 'delete') records.delete(input);
                  result.onsuccess?.(); tx.oncomplete?.();
                });
                return result;
              };
              return { get: (key) => op('get', key), put: (data) => op('put', data), delete: (key) => op('delete', key) };
            };
            return tx;
          }
        };
        request.onupgradeneeded?.(); request.onsuccess?.();
      });
      return request;
    } },
    failStorage: () => { fail = true; }, played: () => plays
  };
  return browser;
}
test('按真实文件头区分图片和铃声，不只依赖后缀或声明类型', async () => {
  for (const [format, mime] of [['png', 'image/png'], ['jpg', 'image/jpeg'], ['webp', 'image/webp']]) {
    assert.equal(await validateFile(file(format), 'image'), mime);
  }
  for (const [format, mime] of [['mp3', 'audio/mpeg'], ['wav', 'audio/wav'], ['ogg', 'audio/ogg']]) {
    assert.equal(await validateFile(file(format), 'audio'), mime);
  }
  await assert.rejects(validateFile(new Blob(['<svg onload="alert(1)">'], { type: 'image/png' }), 'image'), /不接受/);
  await assert.rejects(validateFile(file('png'), 'audio'), /实际格式/);
});
test('空文件、过大图片/音频在解码和存储前拒绝', async () => {
  await assert.rejects(validateFile(new Blob(), 'audio'), /非空/);
  await assert.rejects(validateFile({ size: 2 * 1024 * 1024 + 1 }, 'image'), /2 MB/);
  await assert.rejects(validateFile({ size: 5 * 1024 * 1024 + 1 }, 'audio'), /5 MB/);
});
test('解码校验图片尺寸与铃声时长，临时 Blob 地址总是回收', async () => {
  const browser = environment();
  assert.equal((await inspect(browser, file('png'), 'image')).width, 800);
  assert.equal((await inspect(browser, file('mp3'), 'audio')).duration, 12);
  assert.equal(browser.revoked.length, 2); assert.equal(browser.played(), 0);
  for (const dimensions of [{ width: 5000 }, { width: 4096, height: 4096 }, { width: 0 }]) {
    const bad = environment(dimensions);
    await assert.rejects(inspect(bad, file('png'), 'image'), /尺寸/); assert.equal(bad.revoked.length, 1);
  }
  for (const duration of [0, Infinity, 61]) {
    const bad = environment({ duration }); await assert.rejects(inspect(bad, file('mp3'), 'audio'), /时长/);
  }
});
test('损坏文件解码失败不提交成功结果，并释放临时地址', async () => {
  for (const kind of ['audio', 'image']) {
    const browser = environment({ invalid: true });
    await assert.rejects(inspect(browser, file(kind === 'audio' ? 'mp3' : 'png'), kind), /解码/);
    assert.equal(browser.revoked.length, 1);
  }
});
test('文件存储按账号及类型隔离，重建 Store 可读取，替换/移除仅影响精确副本', async () => {
  const browser = environment(), store = createStore(browser);
  await store.save(5, 'image', { blob: file('png'), name: 'first', key: '6:audio', kind: 'audio' });
  await store.save(5, 'audio', { blob: file('mp3'), name: 'sound' });
  await store.save(6, 'image', { blob: file('jpg'), name: 'other' });
  assert.equal((await createStore(browser).get(5, 'image')).name, 'first');
  await store.save(5, 'image', { blob: file('png'), name: 'replacement' });
  assert.equal((await store.get(5, 'image')).name, 'replacement');
  await store.remove(5, 'image'); assert.equal(await store.get(5, 'image'), null);
  assert.equal((await store.get(6, 'image')).name, 'other'); assert.equal((await store.get(5, 'audio')).name, 'sound');
});
test('存储失败或接口缺失必须报错，不假装刷新后可恢复', async () => {
  const browser = environment(), store = createStore(browser); browser.failStorage();
  await assert.rejects(store.save(5, 'audio', { blob: file('mp3') }), /保存失败/);
  assert.equal(browser.records.size, 0);
  await assert.rejects(createStore({}).get(5, 'image'), /内置/);
  await assert.rejects(store.get('../other', 'image'), /有效/);
});
test('导入铃声可试听/停止，不循环播放；退出清空播放器与播放状态', async () => {
  const browser = environment(), alerts = create(browser, () => {}, () => {});
  alerts.reset(5); alerts.setAudioAsset('blob:local-test'); alerts.updatePreferences({ melody: 'custom', volume: 25 });
  await alerts.previewSound(); assert.equal(browser.played(), 1); assert.equal(alerts.snapshot().playing, true);
  assert.equal(alerts.snapshot().preferences.soundWanted, false);
  alerts.stopSound(); assert.equal(alerts.snapshot().playing, false);
  await alerts.enableSound(); alerts.ingest([]); alerts.ingest([{ id: 1, noteId: 2, title: 'test', read: false }]);
  await new Promise(setImmediate); assert.equal(alerts.snapshot().playing, true);
  alerts.reset(); assert.equal(alerts.snapshot().playing, false); assert.equal(alerts.snapshot().sound, false);
});
test('自定义铃声未加载或播放失败时，保留卡片并提示恢复/选择内置', async () => {
  const browser = environment(), alerts = create(browser, () => {}, () => {});
  alerts.reset(5); alerts.updatePreferences({ melody: 'custom' }); await alerts.previewSound();
  assert.equal(alerts.snapshot().sound, false); assert.match(alerts.snapshot().soundStatus, /导入铃声不可用/);
  alerts.setAudioAsset('blob:local-test'); await alerts.enableSound();
  browser.Audio.prototype.play = () => Promise.reject(new Error('blocked'));
  alerts.ingest([]); alerts.ingest([{ id: 1, noteId: 2, title: 'test', read: false }]); await new Promise(setImmediate);
  assert.equal(alerts.snapshot().count, 1); assert.equal(alerts.snapshot().sound, false);
  assert.match(alerts.snapshot().soundStatus, /到期铃声播放失败或被浏览器阻止/);
});

test('旧到期播放的迟到失败不覆盖用户已经恢复的新音频状态', async () => {
  const browser = environment(), alerts = create(browser, () => {}, () => {});
  alerts.reset(5); alerts.setAudioAsset('blob:local-test'); alerts.updatePreferences({ melody: 'custom' });
  await alerts.enableSound(); alerts.ingest([]);
  let rejectOldPlay;
  browser.Audio.prototype.play = () => new Promise((_, reject) => { rejectOldPlay = reject; });
  alerts.ingest([{ id: 1, noteId: 2, title: 'test', read: false }]);
  browser.Audio.prototype.play = () => Promise.resolve(); await alerts.enableSound();
  rejectOldPlay(new Error('late blocked')); await new Promise(setImmediate);
  assert.equal(alerts.snapshot().sound, true); assert.match(alerts.snapshot().soundStatus, /声音已恢复/);
});
