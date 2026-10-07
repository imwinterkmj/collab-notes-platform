// 使用已有 Node 内置测试库，不安装 npm 包；静态检查不替代真实浏览器交互与布局验收。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('src/main/resources/static/index.html', 'utf8');
const script = fs.readFileSync('src/main/resources/static/app.js', 'utf8');
const alerts = fs.readFileSync('src/main/resources/static/reminder-alerts.js', 'utf8');
const preferences = fs.readFileSync('src/main/resources/static/reminder-preferences.js', 'utf8');
const assets = fs.readFileSync('src/main/resources/static/reminder-assets.js', 'utf8');
const css = fs.readFileSync('src/main/resources/static/styles.css', 'utf8');

test('前端 JavaScript 可以解析', () => {
  assert.doesNotThrow(() => new vm.Script(script));
  assert.doesNotThrow(() => new vm.Script(alerts));
  assert.doesNotThrow(() => new vm.Script(preferences));
  assert.doesNotThrow(() => new vm.Script(assets));
  assert.ok(html.indexOf('src="/reminder-preferences.js"') < html.indexOf('src="/reminder-alerts.js"'));
  assert.ok(html.indexOf('src="/reminder-alerts.js"') < html.indexOf('src="/app.js"'));
});
test('HTML id 唯一且 JavaScript 引用的节点都存在', () => {
  const ids = [...html.matchAll(/id="([^"]+)"/g)].map((match) => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  for (const match of script.matchAll(/\$\('([^']+)'\)/g)) {
    assert.ok(ids.includes(match[1]), '缺少绑定节点：' + match[1]);
  }
});
test('不使用 HTML 字符串渲染、浏览器凭据存储或远程资源', () => {
  assert.doesNotMatch(script + alerts + preferences + assets, /innerHTML|outerHTML|document\.write|sessionStorage|eval\(/);
  assert.doesNotMatch(script + alerts, /localStorage/);
  assert.doesNotMatch(preferences, /password|cookie|csrf|token|fetch\(/i);
  assert.doesNotMatch(assets, /fetch\(|XMLHttpRequest|localStorage|https?:\/\//);
  assert.doesNotMatch(html + css, /https?:\/\/|@import/);
  assert.match(script, /credentials: 'same-origin'/);
  assert.match(script, /csrf\.headerName/);
});
test('齿轮只保留全局通道，样式/素材/音量/试听全部在编辑弹窗', () => {
  const settings = html.slice(html.indexOf('<dialog id="settings-dialog"'), html.indexOf('<dialog id="reminder-dialog"'));
  const editor = html.slice(html.indexOf('<dialog id="editor-dialog"'), html.indexOf('<dialog id="trash-dialog"'));
  for (const id of ['sound-enabled', 'desktop-enabled', 'sound-toggle', 'desktop-toggle', 'desktop-test']) {
    assert.ok(settings.includes('id="' + id + '"'));
    assert.ok(!editor.includes('id="' + id + '"'));
  }
  for (const id of ['alert-style', 'alert-artwork', 'sound-melody', 'sound-volume', 'sound-test', 'sound-stop',
    'image-import', 'audio-import', 'remove-image', 'remove-audio', 'preview-popup', 'asset-status',
    'image-import-message', 'audio-import-message']) {
    assert.ok(!settings.includes('id="' + id + '"'));
    assert.ok(editor.includes('id="' + id + '"'));
  }
  assert.match(html, /id="open-settings"[^>]*aria-label="打开设置"/);
  assert.doesNotMatch(html.slice(html.indexOf('<section id="workspace"'), html.indexOf('</main>')), /id="sound-enabled"|id="sound-melody"/);
});
test('导入有格式/大小提示与辅助关联，非提交按钮不会误提交或关闭表单', () => {
  assert.match(html, /id="image-format-hint"[^>]*>可选格式：PNG（\.png）、JPEG（\.jpg \/ \.jpeg）、WebP（\.webp）/);
  assert.match(html, /id="audio-format-hint"[^>]*>可选格式：MP3（\.mp3）、WAV（\.wav）、OGG（\.ogg）/);
  assert.match(html, /id="image-import"[^>]*aria-describedby="image-format-hint image-file-status image-import-message"/);
  assert.match(html, /id="audio-import"[^>]*aria-describedby="audio-format-hint audio-file-status audio-import-message"/);
  for (const id of ['preview-popup', 'sound-test', 'sound-stop', 'remove-image', 'remove-audio']) {
    assert.match(html, new RegExp('id="' + id + '"[^>]*type="button"'));
  }
  assert.doesNotMatch(html, /method="dialog"/);
  for (const form of html.matchAll(/<form\b[^>]*>[\s\S]*?<\/form>/g)) {
    assert.doesNotMatch(form[0], /id="image-import"|id="audio-import"|id="alert-style"/);
  }
});
test('页面声明设备宽度并有手机布局断点', () => {
  assert.match(html, /name="viewport" content="width=device-width, initial-scale=1"/);
  assert.match(css, /@media\(max-width:700px\)/);
});
test('主页只放列表，编辑主体包含内容/提醒，底栏只有一个保存及完成/删除', () => {
  const workspace = html.slice(html.indexOf('<section id="workspace"'), html.indexOf('</main>'));
  assert.doesNotMatch(workspace, /id="note-form"|id="reminder-form"|最近更新/);
  assert.match(workspace, /<h2>备忘录<\/h2>/);
  const editor = html.slice(html.indexOf('<dialog id="editor-dialog"'), html.indexOf('<dialog id="trash-dialog"'));
  const footer = editor.slice(editor.indexOf('<footer class="modal-footer"'));
  for (const id of ['save-note', 'toggle-note', 'delete-note']) {
    assert.ok(footer.includes('id="' + id + '"'));
  }
  assert.match(footer, /id="save-note"[^>]*>保存<\/button>/);
  assert.match(footer, /form="note-form"/);
  assert.doesNotMatch(html, /id="reminder-form"|id="save-reminder"|id="cancel-reminder"/);
  const noteForm = html.match(/<form id="note-form">[\s\S]*?<\/form>/)[0];
  assert.match(noteForm, /id="reminder-time"/); assert.match(noteForm, /id="reminder-enabled"/);
  assert.doesNotMatch(noteForm.match(/<input id="reminder-time"[^>]*>/)[0], /disabled/);
  assert.match(css, /#account-bar\{display:flex;align-items:center/);
  assert.match(css, /#account-bar button\{[^}]*height:42px/);
});
