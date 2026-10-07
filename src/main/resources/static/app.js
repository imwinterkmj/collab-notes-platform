'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const state = { user: null, mode: 'login', note: null, reminder: null, page: 0, hasNext: false,
    notificationPage: 0, notificationNext: false, busy: false, selection: 0, epoch: 0,
    trashItems: [], trashSelected: null, reminderBaseline: { enabled: false, time: '' } };
  let alertFlight = null, polling = false, alertSignature = '';
  let popupPreview = false, popupTarget = null;
  const assetStore = ReminderAssets.createStore(window);
  const assets = { audio: null, image: null }, assetUrls = { audio: null, image: null };
  const alerts = ReminderAlerts.create(window, renderAlerts, (id) => act(async () => {
    if (canLeave()) await openNote(id);
  }));
  const dateText = (value) => new Date(value).toLocaleString('zh-CN', { hour12: false });
  const localInput = (date) => {
    const pad = (n) => String(n).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + 'T'
      + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
  };
  function message(text, success = false) {
    const target = !state.user ? $('auth-message') : $('editor-dialog').open ? $('editor-message')
      : $('trash-dialog').open ? $('trash-message') : $('workspace-message');
    target.textContent = text;
    target.classList.toggle('success', success);
  }
  function signedOut() {
    state.epoch++; state.selection++; state.user = null; state.note = null; state.reminder = null;
    popupPreview = false; popupTarget = null;
    if ($('settings-dialog').open) $('settings-dialog').close();
    if ($('reminder-dialog').open) $('reminder-dialog').close();
    if ($('editor-dialog').open) $('editor-dialog').close();
    if ($('trash-dialog').open) $('trash-dialog').close();
    state.trashItems = []; state.trashSelected = null;
    $('trash-list').replaceChildren(); $('trash-title').textContent = ''; $('trash-content').textContent = '';
    $('trash-preview').hidden = true; $('trash-message').textContent = ''; $('editor-message').textContent = '';
    state.page = 0; state.notificationPage = 0; alerts.reset();
    clearAssets();
    $('workspace').hidden = true; $('auth-view').hidden = false; $('account-bar').hidden = true;
    $('local-badge').hidden = false; $('notes-list').replaceChildren(); $('notifications-list').replaceChildren();
    $('note-form').reset(); $('reminder-enabled').checked = false; $('reminder-time').value = '';
    state.reminderBaseline = { enabled: false, time: '' }; $('password').value = '';
    $('workspace-message').textContent = ''; $('alert-sync-status').textContent = '';
  }
  async function decode(response, epoch = state.epoch) {
    if (response.status === 204) return null;
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401 && state.user && epoch === state.epoch) signedOut();
      const detail = body.fields ? Object.values(body.fields).join('；') : '';
      const error = new Error(detail || body.message || '请求失败，请稍后重试');
      error.status = response.status; throw error;
    }
    return body;
  }
  async function api(path, method = 'GET', body) {
    const epoch = state.epoch;
    const options = { method, credentials: 'same-origin', cache: 'no-store', headers: {} };
    if (method !== 'GET') {
      // 每次写操作取当前会话的 CSRF，不缓存登录前的值，不盲目重试写请求。
      const csrf = await decode(await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' }), epoch);
      if (epoch !== state.epoch) throw new Error('登录状态已变化，请重新操作。');
      options.headers[csrf.headerName] = csrf.token;
      if (body !== undefined) { options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body); }
    }
    return decode(await fetch(path, options), epoch);
  }
  function controls() {
    const saved = !!state.note;
    $('toggle-note').disabled = state.busy || !saved; $('delete-note').disabled = state.busy || !saved;
    $('save-note').disabled = state.busy;
    $('editor-close').disabled = state.busy; $('trash-close').disabled = state.busy;
    $('trash-restore').disabled = state.busy || !state.trashSelected;
    $('reminder-enabled').disabled = state.busy;
    $('reminder-time').disabled = state.busy;
    $('reminder-time').required = $('reminder-enabled').checked;
    $('trash-list').querySelectorAll('button').forEach((button) => { button.disabled = state.busy; });
    $('notes-prev').disabled = state.page === 0; $('notes-next').disabled = !state.hasNext;
    $('notifications-prev').disabled = state.notificationPage === 0;
    $('notifications-next').disabled = !state.notificationNext;
    alertControls(alerts.snapshot());
    renderPopup(alerts.snapshot());
  }
  function alertControls(snapshot) {
    const preferences = snapshot.preferences;
    $('sound-enabled').checked = preferences.soundWanted;
    $('desktop-enabled').checked = preferences.desktopWanted;
    $('sound-enabled').disabled = state.busy || snapshot.soundBusy;
    $('desktop-enabled').disabled = state.busy || snapshot.permissionBusy;
    $('alert-style').value = preferences.style; $('alert-artwork').value = preferences.artwork;
    $('sound-melody').value = preferences.melody; $('sound-volume').value = String(preferences.volume);
    $('sound-volume-value').textContent = preferences.volume + '%';
    $('preference-status').textContent = snapshot.preferenceStatus;
    $('sound-toggle').textContent = snapshot.sound ? '声音已激活' : '恢复声音';
    $('desktop-toggle').textContent = snapshot.desktop ? '检查已有权限' : '申请 / 检查权限';
    $('sound-toggle').disabled = state.busy || snapshot.soundBusy || !preferences.soundWanted || snapshot.sound;
    $('sound-test').disabled = state.busy || snapshot.soundBusy;
    $('sound-stop').disabled = !snapshot.playing && !snapshot.soundBusy;
    $('editor-restore-sound').hidden = !preferences.soundWanted || snapshot.sound;
    $('editor-restore-sound').disabled = state.busy || snapshot.soundBusy;
    $('editor-sound-ready').textContent = !preferences.soundWanted
      ? '到期声音：已关闭。试听不会开启总开关，请在右上角设置中开启。'
      : preferences.volume === 0 ? '到期声音：音量为 0。请在下面调高音量。'
      : snapshot.sound ? '到期声音：已激活。仍需保持网页打开；实际声音受系统音量影响。'
        : '到期声音：尚未激活。请点击恢复，或在保存时尝试恢复。';
    $('editor-sound-ready').classList.toggle('success', preferences.soundWanted && snapshot.sound && preferences.volume > 0);
    $('desktop-toggle').disabled = state.busy || snapshot.permissionBusy;
    $('desktop-test').disabled = state.busy || snapshot.permissionBusy || !snapshot.desktop;
    $('popup-view').disabled = state.busy || popupPreview || !popupTarget;
    $('popup-read').disabled = state.busy || popupPreview || !popupTarget;
    $('active-alerts-list').querySelectorAll('button').forEach((button) => { button.disabled = state.busy; });
    renderAssetInfo();
  }
  function renderAlerts(snapshot) {
    $('sound-status').textContent = snapshot.soundStatus;
    $('editor-sound-status').textContent = snapshot.soundStatus;
    $('alert-sound-status').textContent = state.user ? snapshot.soundStatus : '';
    $('desktop-status').textContent = snapshot.desktopStatus;
    alertControls(snapshot);
    renderPopup(snapshot);
    const signature = JSON.stringify([snapshot.items, snapshot.count, snapshot.hasMore]);
    if (signature === alertSignature) return;
    alertSignature = signature;
    const list = $('active-alerts-list'); list.replaceChildren();
    $('alert-summary').textContent = snapshot.count
      ? '已加载 ' + snapshot.count + (snapshot.hasMore ? '+' : '') + ' 条未读提醒。卡片不会自动消失；收起不等于已读。'
      : '暂无待处理提醒。到期后会在这里持续显示。';
    document.title = (state.user && snapshot.count ? '(' + snapshot.count + (snapshot.hasMore ? '+' : '') + ' 条未读) ' : '') + '拾记 · 备忘录与提醒';
    for (const item of snapshot.items.slice(0, 10)) {
      const card = document.createElement('article'); card.className = 'active-alert-card';
      const detail = document.createElement('div');
      const title = document.createElement('strong'); title.textContent = item.title;
      const time = document.createElement('p'); time.className = 'small muted'; time.textContent = '计划提醒 ' + dateText(item.dueAt);
      detail.append(title, time);
      const actions = document.createElement('div'); actions.className = 'alert-actions';
      for (const [label, action] of [
        ['查看备忘录', () => { if (canLeave()) return openNote(item.noteId); }],
        ['标为已读', async () => {
          await api('/api/notifications/' + item.id + '/read', 'PATCH');
          alerts.remove(item.id); await refreshNotifications();
        }],
        ['收起卡片', () => alerts.dismiss(item.id)]
      ]) {
        const button = document.createElement('button'); button.type = 'button'; button.className = 'quiet';
        button.textContent = label; button.disabled = state.busy;
        button.addEventListener('click', () => act(action)); actions.append(button);
      }
      card.append(detail, actions); list.append(card);
    }
    if (snapshot.items.length > 10 || snapshot.hasMore) {
      const text = document.createElement('p'); text.className = 'small';
      text.textContent = '顶部最多展示 10 张卡片；更多提醒请在下方站内通知翻页处理。未读补查每轮最多加载 500 条。'; list.append(text);
    }
  }
  function showSettings() {
    if (!state.user || state.busy || $('reminder-dialog').open || $('editor-dialog').open || $('trash-dialog').open) return;
    try { if (!$('settings-dialog').open) $('settings-dialog').showModal(); }
    catch (_) { message('浏览器无法打开设置，请使用支持 dialog 的新版浏览器。'); }
  }
  function closeSettings(checkPending = true) {
    if ($('settings-dialog').open) $('settings-dialog').close();
    if (checkPending) renderPopup(alerts.snapshot());
  }
  function closePopup(dismiss = true) {
    const wasPreview = popupPreview;
    popupPreview = false; popupTarget = null;
    if ($('reminder-dialog').open) $('reminder-dialog').close();
    if (dismiss && !wasPreview) alerts.closePopup();
  }
  function renderPopup(snapshot) {
    if (!state.user) return;
    // 预览叠在编辑弹窗上，不关闭或重填编辑弹窗；关闭预览后草稿仍在。
    if (!popupPreview && ($('settings-dialog').open || $('editor-dialog').open || $('trash-dialog').open)) return;
    const item = popupPreview ? { title: '重要的事，到时间了', dueAt: new Date().toISOString() } : snapshot.popupItems[0];
    if (!item || (!popupPreview && snapshot.preferences.style !== 'large')) {
      if ($('reminder-dialog').open) closePopup(false);
      return;
    }
    popupTarget = popupPreview ? null : item;
    $('popup-kind').textContent = popupPreview ? '样式预览 · 不是真实通知' : '提醒到期';
    $('popup-title').textContent = item.title;
    $('popup-meta').textContent = popupPreview ? '这是大弹层的展示效果，不写入通知或已读状态。' : '计划提醒 ' + dateText(item.dueAt);
    $('popup-count').textContent = popupPreview ? '铃声请在备忘录编辑弹窗中单独试听。' : '本次共有 ' + snapshot.popupItems.length + ' 条待处理；未读卡片在主页保留。';
    $('popup-art-bell').hidden = snapshot.preferences.artwork !== 'bell';
    $('popup-art-note').hidden = snapshot.preferences.artwork !== 'note';
    const customImage = snapshot.preferences.artwork === 'custom' && assetUrls.image;
    $('popup-art-custom').hidden = !customImage;
    if (customImage) $('popup-art-custom').src = assetUrls.image;
    else $('popup-art-custom').removeAttribute('src');
    $('popup-artwork').hidden = snapshot.preferences.artwork === 'none' || (snapshot.preferences.artwork === 'custom' && !customImage);
    $('popup-view').disabled = state.busy || popupPreview;
    $('popup-read').disabled = state.busy || popupPreview;
    if (document.hidden || state.busy || $('reminder-dialog').open) return;
    try { $('popup-message').textContent = ''; $('reminder-dialog').showModal(); }
    catch (_) { $('alert-sync-status').textContent = '大弹层无法展示，已保留常驻卡片。'; }
  }
  function renderAssetInfo() {
    $('custom-image-option').disabled = !assets.image;
    $('custom-audio-option').disabled = !assets.audio;
    $('remove-image').disabled = state.busy || !assets.image;
    $('remove-audio').disabled = state.busy || !assets.audio;
    $('image-file-status').textContent = assets.image ? '已导入：' + assets.image.name : '尚未导入图片（或副本未能加载）。';
    $('audio-file-status').textContent = assets.audio ? '已导入：' + assets.audio.name : '尚未导入铃声（或副本未能加载）。';
  }
  function clearAssets() {
    for (const kind of ['audio', 'image']) {
      if (assetUrls[kind]) window.URL.revokeObjectURL(assetUrls[kind]);
      assetUrls[kind] = null; assets[kind] = null;
    }
    $('popup-art-custom').removeAttribute('src'); $('asset-status').textContent = '';
    $('audio-import-message').textContent = ''; $('image-import-message').textContent = ''; renderAssetInfo();
  }
  function assetMessage(kind, text, success = false) {
    const target = $(kind + '-import-message');
    target.textContent = text; target.classList.toggle('success', success);
  }
  function useAsset(kind, record) {
    const previous = assetUrls[kind];
    const url = record ? window.URL.createObjectURL(record.blob) : null;
    if (kind === 'audio') alerts.setAudioAsset(url);
    assets[kind] = record; assetUrls[kind] = url;
    if (previous) window.URL.revokeObjectURL(previous);
    renderAssetInfo();
  }
  async function loadAssets() {
    const epoch = state.epoch, id = state.user.id;
    for (const kind of ['audio', 'image']) {
      try {
        const record = await assetStore.get(id, kind);
        if (epoch !== state.epoch || !state.user) return;
        if (record) {
          await ReminderAssets.validateFile(record.blob, kind);
          if (epoch !== state.epoch || !state.user) return;
          useAsset(kind, record);
        }
      } catch (_) {
        if (epoch === state.epoch && state.user) $('asset-status').textContent = '导入副本未能加载；内置铃声和插画仍可使用。请检查浏览器存储或重新导入。';
      }
    }
  }
  function importAsset(kind, file) {
    if (!file || !state.user || !$('editor-dialog').open) return;
    const epoch = state.epoch, id = state.user.id;
    act(async () => {
      alerts.stopSound(false);
      $('asset-status').textContent = '';
      assetMessage(kind, '正在检查并保存到当前浏览器……');
      try {
        const inspected = await ReminderAssets.inspect(window, file, kind);
        const record = { ...inspected, name: String(file.name || '导入文件').slice(0, 120) };
        if (epoch !== state.epoch || !state.user) return;
        await assetStore.save(id, kind, record);
        if (epoch !== state.epoch || !state.user) return;
        useAsset(kind, record); alerts.updatePreferences(kind === 'audio' ? { melody: 'custom' } : { artwork: 'custom' });
        assetMessage(kind, '已导入到当前浏览器，原文件不修改、不上传；可选择内置选项切换回来。', true);
      } catch (error) {
        // 失败只更新原弹窗中的状态，不关闭、不重填标题/正文/时间，也不替换旧副本。
        if (epoch === state.epoch && state.user) assetMessage(kind, '导入失败：' + (error.message || '请更换文件。'));
      } finally { $(kind === 'audio' ? 'audio-import' : 'image-import').value = ''; }
    });
  }
  function removeAsset(kind) {
    if (!state.user) return;
    const epoch = state.epoch, id = state.user.id;
    act(async () => {
      try {
        await assetStore.remove(id, kind);
        if (epoch !== state.epoch || !state.user) return;
        useAsset(kind, null);
        const preferences = alerts.snapshot().preferences;
        if (kind === 'audio' && preferences.melody === 'custom') alerts.updatePreferences({ melody: 'chime' });
        if (kind === 'image' && preferences.artwork === 'custom') alerts.updatePreferences({ artwork: 'bell' });
        assetMessage(kind, '已移除当前浏览器的导入副本，原文件没有删除。', true);
      } catch (error) { if (epoch === state.epoch && state.user) assetMessage(kind, error.message || '移除失败，请重试。'); }
    });
  }
  async function refreshAlerts(force = false) {
    const epoch = state.epoch;
    if (alertFlight?.epoch === epoch) {
      await alertFlight.promise;
      if (!force || epoch !== state.epoch || !state.user) return;
    }
    const flight = { epoch };
    flight.promise = (async () => {
      const result = await ReminderAlerts.collectUnread((page) => api('/api/notifications?unreadOnly=true&page=' + page + '&size=100'));
      if (epoch !== state.epoch || !state.user) return;
      alerts.ingest(result.items, result.hasMore);
      $('alert-sync-status').textContent = '最近检查 ' + dateText(new Date().toISOString()) + ' · 每 10 秒尝试检查，切回页面补查。';
    })();
    alertFlight = flight;
    try { await flight.promise; }
    finally { if (alertFlight === flight) alertFlight = null; }
  }
  async function act(action) {
    if (state.busy) return;
    state.busy = true; document.body.dataset.busy = 'true';
    const previous = new Map();
    document.querySelectorAll('button,input,textarea,select').forEach((node) => { previous.set(node, node.disabled); node.disabled = true; });
    try { message(''); await action(); }
    catch (error) { message(error.message || '连接失败，请检查应用和数据库是否运行'); }
    finally {
      previous.forEach((disabled, node) => { if (node.isConnected) node.disabled = disabled; });
      state.busy = false; delete document.body.dataset.busy; controls();
    }
  }
  function empty(container, text) {
    const p = document.createElement('p'); p.className = 'empty-state'; p.textContent = text; container.append(p);
  }
  function dirty() {
    return $('note-title').value !== (state.note?.title || '') || $('note-content').value !== (state.note?.content || '') || reminderDirty();
  }
  function reminderDirty() {
    return $('reminder-enabled').checked !== state.reminderBaseline.enabled || $('reminder-time').value !== state.reminderBaseline.time;
  }
  function canLeave() { return !$('editor-dialog').open || !dirty() || window.confirm('有尚未保存的内容，确定放弃这些修改吗？'); }
  function showEditor() {
    if (!state.user) return;
    closeSettings(false);
    if ($('trash-dialog').open) $('trash-dialog').close();
    if ($('reminder-dialog').open) closePopup(false);
    $('editor-message').textContent = '';
    try { if (!$('editor-dialog').open) $('editor-dialog').showModal(); }
    catch (_) { message('浏览器无法打开编辑弹窗，请使用支持 dialog 的新版浏览器。'); }
  }
  function closeEditor(force = false, checkPending = true) {
    if (!force && (state.busy || !canLeave())) return false;
    if ($('editor-dialog').open) $('editor-dialog').close();
    state.selection++; state.note = null; state.reminder = null; renderEditor();
    $('editor-message').textContent = '';
    document.querySelectorAll('.note-item').forEach((node) => node.classList.remove('selected'));
    if (checkPending) renderPopup(alerts.snapshot());
    return true;
  }
  function closeTrash() {
    if (state.busy) return;
    if ($('trash-dialog').open) $('trash-dialog').close();
    renderPopup(alerts.snapshot());
  }
  function selectTrash(item) {
    state.trashSelected = item;
    $('trash-preview').hidden = !item;
    $('trash-title').textContent = item?.title || '';
    $('trash-content').textContent = item?.content || (item ? '（正文为空）' : '');
    $('trash-times').textContent = item ? (item.completed ? '已完成' : '未完成') + ' · 删除于 ' + dateText(item.deletedAt) : '';
    document.querySelectorAll('.trash-item').forEach((node) => node.classList.toggle('selected', node.dataset.id === String(item?.id)));
    controls();
  }
  async function loadTrash() {
    const epoch = state.epoch;
    const page = await api('/api/notes/trash');
    if (epoch !== state.epoch || !state.user) return false;
    state.trashItems = page.items; state.trashSelected = null;
    const list = $('trash-list'); list.replaceChildren();
    $('trash-summary').textContent = '共 ' + page.items.length + ' 条 · 最多保留 30 条';
    for (const item of page.items) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'note-item trash-item';
      button.dataset.id = String(item.id);
      const title = document.createElement('strong'); title.textContent = item.title;
      const time = document.createElement('time'); time.textContent = '删除于 ' + dateText(item.deletedAt);
      button.append(title, time); button.addEventListener('click', () => { if (!state.busy) selectTrash(item); }); list.append(button);
    }
    if (!page.items.length) empty(list, '回收站为空。');
    selectTrash(page.items[0] || null);
    return true;
  }
  function renderEditor() {
    const note = state.note;
    $('editor-heading').textContent = note ? '编辑备忘录' : '新建备忘录';
    $('note-title').value = note?.title || ''; $('note-content').value = note?.content || '';
    $('note-state').textContent = note ? (note.completed ? '已完成' : '未完成') : '未保存';
    $('toggle-note').textContent = note?.completed ? '恢复未完成' : '标记完成';
    $('note-times').textContent = note ? '创建于 ' + dateText(note.createdAt) + ' · 更新于 ' + dateText(note.updatedAt) : '';
    renderReminder(); controls();
  }
  function renderReminder(preserveDraft = false) {
    const labels = { SCHEDULED: '待提醒', FIRED: '已生成站内通知', CANCELLED: '已取消' };
    $('reminder-state').textContent = !state.note ? '新建时就能填写时间；内容和提醒点击一次保存即可。'
      : state.note.completed ? '备忘录已完成，待执行提醒已取消。恢复未完成后请重新设置。'
      : state.reminder ? labels[state.reminder.status] + ' · ' + dateText(state.reminder.dueAt)
      : '尚未设置提醒。';
    if (!preserveDraft) {
      const enabled = state.reminder?.status === 'SCHEDULED';
      const time = enabled ? localInput(new Date(state.reminder.dueAt)) : '';
      state.reminderBaseline = { enabled, time };
      $('reminder-enabled').checked = enabled; $('reminder-time').value = time;
    } else {
      $('reminder-state').textContent += ' · 服务端状态已更新，你正在修改的时间保留，点击保存后生效。';
    }
    controls();
  }
  async function getReminder(id) {
    try { return await api('/api/notes/' + id + '/reminder'); }
    catch (error) { if (error.status === 404) return null; throw error; }
  }
  async function openNote(id) {
    const version = ++state.selection;
    const [note, reminder] = await Promise.all([api('/api/notes/' + id), getReminder(id)]);
    if (version !== state.selection || !state.user) return;
    state.note = note; state.reminder = reminder; renderEditor(); showEditor();
    document.querySelectorAll('.note-item').forEach((node) => node.classList.toggle('selected', node.dataset.id === String(id)));
  }
  async function loadNotes() {
    const epoch = state.epoch;
    const page = await api('/api/notes?page=' + state.page + '&size=10');
    if (epoch !== state.epoch || !state.user) return;
    // 删除最后一项后回到前页，不把空页当成没有任何备忘录。
    if (page.items.length === 0 && state.page > 0) { state.page--; return loadNotes(); }
    state.hasNext = page.hasNext; $('notes-page').textContent = '第 ' + (state.page + 1) + ' 页';
    const list = $('notes-list'); list.replaceChildren();
    for (const note of page.items) {
      const button = document.createElement('button'); button.type = 'button'; button.className = 'note-item';
      button.dataset.id = String(note.id); button.classList.toggle('selected', state.note?.id === note.id);
      button.classList.toggle('completed-note', note.completed);
      const title = document.createElement('strong'); title.textContent = note.title;
      const tag = document.createElement('span'); tag.className = 'tag'; tag.textContent = note.completed ? '已完成' : '未完成';
      const time = document.createElement('time'); time.dateTime = note.updatedAt; time.textContent = dateText(note.updatedAt);
      button.append(title, tag, time);
      button.addEventListener('click', () => act(async () => { if (canLeave()) await openNote(note.id); }));
      list.append(button);
    }
    if (!page.items.length) empty(list, '还没有备忘录，从第一条开始吧。');
    controls();
  }
  async function refreshNotifications(silent = false) {
    const epoch = state.epoch;
    const displayPage = state.notificationPage;
    await refreshAlerts(!silent);
    const page = await api('/api/notifications?page=' + displayPage + '&size=20');
    if (epoch !== state.epoch || !state.user) return;
    if (silent && (state.busy || state.notificationPage !== displayPage)) return;
    if (page.items.length === 0 && state.notificationPage > 0) {
      state.notificationPage--; return refreshNotifications(silent);
    }
    state.notificationNext = page.hasNext;
    $('notifications-page').textContent = '第 ' + (state.notificationPage + 1) + ' 页';
    $('notification-status').textContent = '本页未读 ' + page.items.filter((n) => !n.read).length + ' 条 · 每 10 秒检查';
    const list = $('notifications-list'); list.replaceChildren();
    for (const item of page.items) {
      const card = document.createElement('article'); card.className = 'notification-item';
      const title = document.createElement('strong'); title.textContent = item.title;
      const time = document.createElement('p'); time.textContent = '计划提醒 ' + dateText(item.dueAt);
      const generated = document.createElement('p'); generated.textContent = '通知生成 ' + dateText(item.createdAt);
      const row = document.createElement('div'); row.className = 'read-row';
      const status = document.createElement('span'); status.className = 'small muted';
      status.textContent = item.read ? '已读' : '● 未读';
      const view = document.createElement('button'); view.className = 'quiet'; view.textContent = '查看备忘录';
      view.addEventListener('click', () => act(async () => { if (canLeave()) await openNote(item.noteId); }));
      row.append(status, view);
      if (!item.read) {
        const read = document.createElement('button'); read.className = 'secondary'; read.textContent = '标为已读';
        read.addEventListener('click', () => act(async () => {
          await api('/api/notifications/' + item.id + '/read', 'PATCH'); await refreshNotifications();
        }));
        row.append(read);
      }
      card.append(title, time, generated, row); list.append(card);
    }
    if (!page.items.length) empty(list, '到期的提醒会出现在这里。');
    controls();
  }
  async function enter(user) {
    state.epoch++; state.user = user; state.page = 0; state.notificationPage = 0; clearAssets(); alerts.reset(user.id);
    $('auth-view').hidden = true; $('workspace').hidden = false; $('account-bar').hidden = false;
    $('local-badge').hidden = true; $('account-name').textContent = user.username; $('password').value = '';
    state.note = null; state.reminder = null; renderEditor();
    await loadAssets();
    if (!state.user) return;
    await Promise.all([loadNotes(), refreshNotifications()]);
  }
  function authMode(mode) {
    state.mode = mode;
    $('login-tab').classList.toggle('active', mode === 'login'); $('register-tab').classList.toggle('active', mode === 'register');
    $('login-tab').setAttribute('aria-pressed', String(mode === 'login'));
    $('register-tab').setAttribute('aria-pressed', String(mode === 'register'));
    $('auth-title').textContent = mode === 'login' ? '欢迎回来' : '开始记录你的日常';
    $('auth-subtitle').textContent = mode === 'login' ? '登录后，继续整理你的备忘录。' : '创建账号后登录，数据只属于你。';
    $('auth-submit').textContent = mode === 'login' ? '登录' : '创建账号';
    $('password').autocomplete = mode === 'login' ? 'current-password' : 'new-password';
    $('auth-message').textContent = '';
  }
  $('login-tab').addEventListener('click', () => authMode('login'));
  $('register-tab').addEventListener('click', () => authMode('register'));
  $('auth-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const payload = { username: $('username').value, password: $('password').value };
    act(async () => {
      if (state.mode === 'register') {
        await api('/api/users/register', 'POST', payload); $('password').value = ''; authMode('login');
        message('注册成功，请使用刚才的账号密码登录。', true);
      } else { await enter(await api('/api/auth/login', 'POST', payload)); }
    }).finally(() => { payload.password = ''; });
  });
  $('logout').addEventListener('click', () => act(async () => {
    if (!canLeave()) return; await api('/api/auth/logout', 'POST'); signedOut(); message('已退出登录。', true);
  }));
  $('new-note').addEventListener('click', () => act(async () => {
    if (!canLeave()) return; state.selection++; state.note = null; state.reminder = null; renderEditor(); showEditor();
    document.querySelectorAll('.note-item').forEach((n) => n.classList.remove('selected'));
  }));
  $('note-form').addEventListener('submit', (event) => {
    event.preventDefault();
    if (state.busy || !state.user) return;
    const body = { title: $('note-title').value, content: $('note-content').value };
    const epoch = state.epoch, selection = state.selection, noteId = state.note?.id;
    // 在用户提交的同步回调内恢复音频，不在等待 CSRF/网络之后调用，也不自动打开已关闭的总开关。
    const sound = alerts.snapshot();
    if (sound.preferences.soundWanted && !sound.sound && !sound.soundBusy) alerts.enableSound();
    act(async () => {
      if ([...body.title].length < 1 || [...body.title].length > 120 || !body.title.trim()) throw new Error('标题须为 1～120 个字符，不能全空白');
      if ([...body.content].length > 10000) throw new Error('正文最多 10000 个字符');
      body.reminderAction = 'KEEP';
      if ($('reminder-enabled').checked) {
        if (!state.reminderBaseline.enabled || $('reminder-time').value !== state.reminderBaseline.time) {
          const value = $('reminder-time').value, date = new Date(value);
          if (!value || Number.isNaN(date.getTime())) throw new Error('请选择有效的提醒时间');
          if (date.getTime() <= Date.now() || date.getTime() > Date.now() + 365 * 86400000) throw new Error('提醒时间须在未来一年内');
          body.reminderAction = 'SET'; body.dueAt = date.toISOString();
        }
      } else if (state.reminderBaseline.enabled) body.reminderAction = 'CANCEL';
      let saved;
      try { saved = await api(noteId ? '/api/notes/' + noteId + '/save' : '/api/notes/save', noteId ? 'PUT' : 'POST', body); }
      catch (error) {
        if (error.status === 404 || error.status === 405) {
          throw new Error('保存入口不可用或备忘录已不存在；请确认重启了最新后端。内容和时间草稿保留，请勿回退到分开提交。');
        }
        throw error;
      }
      if (epoch !== state.epoch || selection !== state.selection || !state.user) return;
      state.note = saved.note; state.reminder = saved.reminder; state.page = 0; renderEditor();
      try { await loadNotes(); message('已保存内容与提醒。', true); }
      catch (_) { if (epoch === state.epoch && state.user) message('内容与提醒已保存，但列表刷新失败；稍后点击刷新，不必重复新建。', true); }
    });
  });
  $('reminder-enabled').addEventListener('change', () => {
    if ($('reminder-enabled').checked && !$('reminder-time').value) $('reminder-time').value = localInput(new Date(Date.now() + 60000));
    controls();
  });
  $('reminder-time').addEventListener('input', () => {
    $('reminder-enabled').checked = !!$('reminder-time').value; controls();
  });
  $('toggle-note').addEventListener('click', () => act(async () => {
    if (dirty()) throw new Error('请先保存内容和提醒，再修改完成状态。');
    state.note = await api('/api/notes/' + state.note.id + '/completion', 'PATCH', { completed: !state.note.completed });
    state.reminder = await getReminder(state.note.id); state.page = 0; renderEditor(); await loadNotes();
    message(state.note.completed ? '已完成，待执行提醒已取消。' : '已恢复未完成；旧提醒不会自动恢复。', true);
  }));
  $('delete-note').addEventListener('click', () => act(async () => {
    if (!window.confirm('将已保存的备忘录移到回收站？未保存的修改不会保留，关联提醒和通知会移除。回收站只保留最近 30 条，超出时最早的一条永久移出。')) return;
    // 开发时静态资源可比 JVM 类更新得早；旧后端还是永久删除，先确认回收站能力再允许 DELETE。
    const epoch = state.epoch;
    let trashPage;
    try { trashPage = await api('/api/notes/trash'); }
    catch (error) {
      if (error.status === 400 || error.status === 404) throw new Error('当前后端尚不支持回收站，请重启 Spring Boot 应用后再删除。');
      throw error;
    }
    if (epoch !== state.epoch || !state.user) return;
    if (trashPage.limit !== 30 || !Array.isArray(trashPage.items)) throw new Error('回收站服务版本不匹配，请重启 Spring Boot 应用后再删除。');
    await api('/api/notes/' + state.note.id, 'DELETE'); closeEditor(true, false); state.page = 0;
    await Promise.all([loadNotes(), refreshNotifications()]); message('已移到回收站，可恢复最近 30 条中的记录；旧提醒不会恢复。', true);
  }));
  $('refresh-notes').addEventListener('click', () => act(async () => {
    await loadNotes();
  }));
  $('editor-close').addEventListener('click', () => closeEditor());
  $('editor-dialog').addEventListener('cancel', (event) => {
    // 文件选择器的 cancel 会冒泡，不能将它误认成编辑弹窗自己的 Esc。
    if (event.target !== $('editor-dialog')) return;
    event.preventDefault(); closeEditor();
  });
  $('open-trash').addEventListener('click', () => act(async () => {
    if (!canLeave()) return;
    if (!await loadTrash()) return;
    $('trash-message').textContent = '';
    try { if (!$('trash-dialog').open) $('trash-dialog').showModal(); }
    catch (_) { message('浏览器无法打开回收站，请使用支持 dialog 的新版浏览器。'); }
  }));
  $('trash-close').addEventListener('click', closeTrash);
  $('trash-dialog').addEventListener('cancel', (event) => {
    if (event.target !== $('trash-dialog')) return;
    event.preventDefault(); closeTrash();
  });
  $('trash-restore').addEventListener('click', () => act(async () => {
    const item = state.trashSelected;
    if (!item) return;
    const epoch = state.epoch;
    const note = await api('/api/notes/trash/' + item.id + '/restore', 'POST');
    if (epoch !== state.epoch || !state.user) return;
    $('trash-dialog').close(); state.trashItems = []; state.trashSelected = null; $('trash-list').replaceChildren();
    state.note = note; state.reminder = null; state.page = 0; state.selection++; renderEditor(); showEditor();
    await loadNotes(); message('已恢复备忘录；旧提醒和通知不恢复，需要时重新设置提醒。', true);
  }));
  $('notes-prev').addEventListener('click', () => act(async () => { state.page--; await loadNotes(); }));
  $('notes-next').addEventListener('click', () => act(async () => { state.page++; await loadNotes(); }));
  $('refresh-notifications').addEventListener('click', () => act(() => refreshNotifications()));
  $('notifications-prev').addEventListener('click', () => act(async () => { state.notificationPage--; await refreshNotifications(); }));
  $('notifications-next').addEventListener('click', () => act(async () => { state.notificationPage++; await refreshNotifications(); }));
  // 声音恢复和权限申请直接发生在点击回调中，不等待 CSRF 或其他网络请求。
  $('open-settings').addEventListener('click', showSettings);
  $('settings-close').addEventListener('click', () => { if (!state.busy) closeSettings(); });
  $('settings-dialog').addEventListener('cancel', (event) => {
    if (event.target !== $('settings-dialog')) return;
    event.preventDefault(); if (!state.busy) closeSettings();
  });
  $('alert-style').addEventListener('change', () => alerts.updatePreferences({ style: $('alert-style').value }));
  $('alert-artwork').addEventListener('change', () => alerts.updatePreferences({ artwork: $('alert-artwork').value }));
  $('sound-melody').addEventListener('change', () => alerts.updatePreferences({ melody: $('sound-melody').value }));
  $('sound-volume').addEventListener('input', () => alerts.updatePreferences({ volume: Number($('sound-volume').value) }));
  $('sound-enabled').addEventListener('change', () => $('sound-enabled').checked ? alerts.enableSound() : alerts.mute());
  $('desktop-enabled').addEventListener('change', () => $('desktop-enabled').checked ? alerts.enableDesktop() : alerts.disableDesktop());
  $('sound-toggle').addEventListener('click', () => alerts.enableSound());
  $('editor-restore-sound').addEventListener('click', () => {
    if (alerts.snapshot().preferences.soundWanted) alerts.enableSound();
  });
  $('sound-test').addEventListener('click', () => alerts.previewSound());
  $('sound-stop').addEventListener('click', () => alerts.stopSound());
  $('audio-import').addEventListener('change', () => importAsset('audio', $('audio-import').files[0]));
  $('image-import').addEventListener('change', () => importAsset('image', $('image-import').files[0]));
  $('remove-audio').addEventListener('click', () => removeAsset('audio'));
  $('remove-image').addEventListener('click', () => removeAsset('image'));
  $('desktop-toggle').addEventListener('click', () => alerts.enableDesktop());
  $('desktop-test').addEventListener('click', () => alerts.testDesktop());
  $('preview-popup').addEventListener('click', () => {
    if (state.busy || !$('editor-dialog').open) return;
    popupPreview = true; renderPopup(alerts.snapshot());
    if (!$('reminder-dialog').open) popupPreview = false;
  });
  $('popup-close').addEventListener('click', () => closePopup());
  $('reminder-dialog').addEventListener('cancel', (event) => {
    if (event.target !== $('reminder-dialog')) return;
    event.preventDefault(); closePopup();
  });
  $('popup-view').addEventListener('click', () => {
    const item = popupTarget;
    if (!item || !canLeave()) return;
    closePopup(); act(() => openNote(item.noteId));
  });
  $('popup-read').addEventListener('click', () => {
    const item = popupTarget;
    if (!item) return;
    act(async () => {
      try {
        await api('/api/notifications/' + item.id + '/read', 'PATCH'); alerts.remove(item.id); await refreshNotifications();
      } catch (error) {
        $('popup-message').textContent = error.message || '标记已读失败，请重试。';
        message(error.message || '标记已读失败，请重试。');
      }
    });
  });
  $('restore-alerts').addEventListener('click', () => act(() => alerts.restore()));
  window.addEventListener('beforeunload', (event) => { if (state.user && $('editor-dialog').open && dirty()) { event.preventDefault(); event.returnValue = ''; } });
  async function poll() {
    if (!state.user || state.busy || polling) return;
    const epoch = state.epoch; polling = true;
    try {
      await refreshNotifications(true);
      if (epoch !== state.epoch || !state.user) return;
      const version = state.selection;
      if (state.note) {
        const reminder = await getReminder(state.note.id);
        if (epoch === state.epoch && version === state.selection && state.user && !state.busy
            && JSON.stringify(reminder) !== JSON.stringify(state.reminder)) {
          const preserveDraft = reminderDirty();
          state.reminder = reminder; renderReminder(preserveDraft);
        }
      }
    } catch (error) {
      if (epoch !== state.epoch) return;
      if (state.user) $('alert-sync-status').textContent = '暂时无法同步，保留已加载的卡片；稍后自动重试。';
      else message('登录已失效，请重新登录。');
    } finally { polling = false; }
  }
  setInterval(poll, 10000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { renderPopup(alerts.snapshot()); poll(); } });
  window.addEventListener('focus', () => { renderPopup(alerts.snapshot()); poll(); });
  act(async () => {
    try { await enter(await api('/api/auth/me')); }
    catch (error) { if (error.status !== 401) throw error; signedOut(); }
  });
})();
