'use strict';
// 只使用浏览器原生能力；环境可注入，以便离线验证，不把逻辑测试当作真实音响/系统通知验收。
((root) => {
  const Preferences = typeof module !== 'undefined' && module.exports ? require('./reminder-preferences.js') : root.ReminderPreferences;
  async function collectUnread(fetchPage, maxPages = 5) {
    const items = new Map();
    let hasMore = false;
    for (let page = 0; page < maxPages; page++) {
      const result = await fetchPage(page);
      for (const item of result.items) if (!item.read) items.set(item.id, item);
      hasMore = result.hasNext;
      if (!hasMore) break;
    }
    return { items: [...items.values()], hasMore };
  }

  function create(env, changed, openNote) {
    let account = null, epoch = 0, initialized = false, sound = false, desktop = false;
    let context = null, soundBusy = false, permissionBusy = false, hasMore = false;
    let customAudio = null, playing = false, soundAttempt = 0;
    const oscillators = new Set();
    let soundStatus = '声音未开启；请先点击开启，再测试。';
    let desktopStatus = '桌面通知未开启，仅在你点击后申请权限。';
    let preferences = Preferences.normalize(), preferenceStatus = '尚未登录。';
    const seen = new Set(), dismissed = new Set(), unread = new Map(), windows = new Set(), popup = new Map();
    const supported = () => env.isSecureContext && typeof env.Notification === 'function';
    const snapshot = () => ({
      items: [...unread.values()].filter((item) => !dismissed.has(item.id)),
      count: unread.size, hasMore, sound, desktop, soundBusy, permissionBusy,
      soundStatus, desktopStatus, playing, preferences: { ...preferences }, preferenceStatus,
      popupItems: [...popup.values()]
    });
    const notify = () => changed(snapshot());
    function closeWindows() {
      for (const record of windows) {
        try { record.notification.close(); } catch (_) { /* 页面卡片不依赖系统通知成功。 */ }
      }
      windows.clear();
    }
    function reset(userId = null) {
      epoch++; account = userId; initialized = false; sound = false; desktop = false;
      stopSound(false);
      if (customAudio) { customAudio.removeAttribute('src'); customAudio.load(); customAudio = null; }
      soundBusy = false; permissionBusy = false; hasMore = false;
      seen.clear(); dismissed.clear(); unread.clear(); popup.clear(); closeWindows();
      const oldContext = context; context = null;
      if (oldContext) { try { Promise.resolve(oldContext.close()).catch(() => {}); } catch (_) {} }
      const saved = Preferences.load(env, userId); preferences = saved.preferences; preferenceStatus = saved.message;
      soundStatus = preferences.soundWanted ? '已记住声音开启偏好；本页尚未激活播放，请点击恢复声音。' : '声音未开启；可选择铃声并试听。';
      desktop = preferences.desktopWanted && supported() && env.Notification.permission === 'granted';
      desktopStatus = !supported() ? '当前浏览器或连接不支持桌面通知，仍保留页面提醒。'
        : env.Notification.permission === 'denied' ? '通知权限已被拒绝，仍保留页面提醒；可自行在浏览器站点设置中修改。'
          : desktop ? '已恢复桌面通知偏好及已有授权；实际展示请用测试确认。'
            : preferences.desktopWanted ? '已记住桌面通知偏好；请点击申请权限，不会自动弹授权窗口。'
              : '桌面通知未开启；开启后会显示备忘录标题，请留意隐私。';
      notify();
    }
    function updatePreferences(patch) {
      if (account === null) return;
      const previousMelody = preferences.melody;
      preferences = Preferences.normalize({ ...preferences, ...patch });
      if (previousMelody !== preferences.melody) {
        stopSound(false);
        sound = preferences.soundWanted && preferences.melody !== 'custom' && context?.state === 'running';
        soundStatus = sound ? '已切换内置铃声，声音已激活。' : '已切换铃声；如需到期播放，请开启开关并点击恢复声音。';
      }
      if (customAudio && preferences.melody === 'custom') customAudio.volume = preferences.volume / 100;
      preferenceStatus = Preferences.save(env, account, preferences);
      if (preferences.style !== 'large') popup.clear();
      notify();
    }
    function tone() {
      if (preferences.volume === 0) return;
      if (preferences.melody === 'custom') {
        if (!customAudio) throw new Error('custom-audio-missing');
        customAudio.pause(); customAudio.currentTime = 0; customAudio.volume = preferences.volume / 100;
        playing = true; notify(); return customAudio.play();
      }
      if (!context || context.state !== 'running') throw new Error('audio-unavailable');
      const start = context.currentTime;
      const melodies = { chime: [[0, 660], [0.23, 880]], soft: [[0, 523.25], [0.26, 659.25], [0.52, 783.99]],
        clear: [[0, 1046.5], [0.16, 1046.5], [0.32, 1318.5]] };
      for (const [offset, frequency] of melodies[preferences.melody]) {
        const oscillator = context.createOscillator(), gain = context.createGain();
        oscillator.frequency.value = frequency;
        gain.gain.setValueAtTime(0, start + offset);
        gain.gain.linearRampToValueAtTime(0.16 * preferences.volume / 100, start + offset + 0.015);
        gain.gain.exponentialRampToValueAtTime(0.0001, start + offset + 0.20);
        oscillator.connect(gain); gain.connect(context.destination);
        oscillators.add(oscillator); playing = true;
        oscillator.onended = () => {
          oscillator.disconnect(); gain.disconnect(); oscillators.delete(oscillator);
          if (!oscillators.size) { playing = false; notify(); }
        };
        oscillator.start(start + offset); oscillator.stop(start + offset + 0.22);
      }
    }
    // 必须由点击事件直接调用，不能先等待网络请求再恢复 AudioContext。
    async function startAudio(test, enable) {
      if (account === null || soundBusy) return;
      if (enable) updatePreferences({ soundWanted: true });
      const current = epoch, attempt = ++soundAttempt; soundBusy = true; notify();
      try {
        if (preferences.melody === 'custom') {
          if (!customAudio) throw new Error('custom-audio-missing');
          if (test) await tone();
          else {
            const player = customAudio;
            player.volume = 0;
            await player.play();
            if (current !== epoch || attempt !== soundAttempt || customAudio !== player) return;
            player.pause(); player.currentTime = 0;
          }
        } else {
          const Audio = env.AudioContext || env.webkitAudioContext;
          if (!Audio) throw new Error('unsupported');
          if (!context) {
            context = new Audio();
            const audioContext = context, contextEpoch = epoch;
            audioContext.onstatechange = () => {
              if (contextEpoch !== epoch || context !== audioContext || preferences.melody === 'custom') return;
              if (audioContext.state !== 'running' && sound) {
                sound = false; soundStatus = '浏览器暂停了到期声音，请点击恢复声音；页面提醒仍有效。'; notify();
              }
            };
          }
          await context.resume();
          if (current !== epoch || attempt !== soundAttempt) return;
          if (context.state !== 'running') throw new Error('suspended');
          if (test) await tone();
        }
        if (current !== epoch || attempt !== soundAttempt) return;
        if (enable || preferences.soundWanted) sound = true;
        soundStatus = test ? preferences.soundWanted
          ? '已请求试听；到期声音已激活，请确认实际音量。'
          : '试听正常不代表到期会响：到期提示音开关仍关闭，请在设置中开启。'
          : '声音已恢复；新发现的未读提醒响一次，不循环响铃。';
      } catch (_) {
        if (current !== epoch || attempt !== soundAttempt) return;
        playing = false; sound = false;
        soundStatus = preferences.melody === 'custom' ? '导入铃声不可用或播放被阻止；请重新导入或选内置铃声，页面提醒仍有效。'
          : '声音不可用或被浏览器暂停，点击试听铃声重试；页面提醒仍有效。';
      } finally { if (current === epoch) { soundBusy = false; notify(); } }
    }
    const enableSound = (test = false) => startAudio(test, true);
    function stopSound(report = true) {
      soundAttempt++;
      if (customAudio) { try { customAudio.pause(); customAudio.currentTime = 0; } catch (_) {} }
      for (const oscillator of oscillators) { try { oscillator.stop(); } catch (_) {} }
      oscillators.clear(); playing = false;
      if (report) { soundStatus = '已停止当前播放；提醒声音开关保持不变。'; notify(); }
    }
    function setAudioAsset(url) {
      stopSound(false);
      if (customAudio) { customAudio.removeAttribute('src'); customAudio.load(); }
      customAudio = url ? new env.Audio(url) : null;
      if (preferences.melody === 'custom') { sound = false; soundStatus = '导入铃声已更换或重新加载；请点击恢复声音。'; }
      if (customAudio) {
        const current = epoch, player = customAudio;
        customAudio.preload = 'none'; customAudio.loop = false;
        customAudio.onended = customAudio.onpause = () => { if (current === epoch && customAudio === player) { playing = false; notify(); } };
        customAudio.onplaying = () => { if (current === epoch && customAudio === player) { playing = true; notify(); } };
      }
      notify();
    }
    function mute() { sound = false; stopSound(false); soundStatus = '已关闭提示音；页面提醒仍有效。'; updatePreferences({ soundWanted: false }); }
    async function enableDesktop() {
      if (account === null || permissionBusy) return;
      updatePreferences({ desktopWanted: true });
      const current = epoch; permissionBusy = true; notify();
      try {
        if (!supported()) throw new Error('unsupported');
        const permission = env.Notification.permission === 'default'
          ? await env.Notification.requestPermission() : env.Notification.permission;
        if (current !== epoch) return;
        desktop = permission === 'granted';
        desktopStatus = desktop ? '桌面通知已开启；已授权不等于系统实际展示，可点击测试。'
          : permission === 'denied' ? '通知权限被拒绝，回退为页面提醒；请自行在浏览器站点设置修改权限。'
            : '尚未获得通知权限，回退为页面提醒。';
      } catch (_) {
        if (current !== epoch) return;
        desktop = false; desktopStatus = '当前浏览器或连接无法启用桌面通知，回退为页面提醒。';
      } finally { if (current === epoch) { permissionBusy = false; notify(); } }
    }
    function desktopMessage(items, test = false) {
      if (!desktop || !supported() || env.Notification.permission !== 'granted') {
        desktop = false; desktopStatus = '桌面通知未开启或权限已变化；页面提醒仍有效。'; notify(); return;
      }
      const current = epoch;
      try {
        const notification = new env.Notification(test ? '拾记 · 测试通知' : '拾记 · 提醒到期', {
          body: test ? '如果看到了这条通知，桌面展示测试通过。' : items[0].title + (items.length > 1 ? ' 等 ' + items.length + ' 条提醒' : ''),
          tag: 'shiji-' + account + '-' + (test ? 'test' : items[0].id), silent: true
        });
        const record = { notification, ids: items.map((item) => item.id) }; windows.add(record);
        notification.onclick = () => {
          if (current !== epoch || account === null) return;
          env.focus();
          if (!test) openNote(items[0].noteId);
          notification.close(); windows.delete(record);
        };
        notification.onclose = () => windows.delete(record);
        notification.onerror = () => {
          windows.delete(record);
          if (current === epoch) {
            desktop = false; desktopStatus = '系统通知展示失败，回退为页面提醒；可点击开启后重试。'; notify();
          }
        };
        desktopStatus = '已向浏览器请求展示' + (test ? '测试通知' : '提醒') + '；实际可见性受系统通知设置影响。';
      } catch (_) {
        desktop = false; desktopStatus = '此浏览器无法展示桌面通知（部分手机浏览器不支持），回退为页面提醒。';
      }
      notify();
    }
    function ingest(items, more = false) {
      if (account === null) return;
      const fresh = items.filter((item) => !item.read && !seen.has(item.id));
      unread.clear(); hasMore = more;
      for (const item of items) { seen.add(item.id); if (!item.read) unread.set(item.id, item); }
      for (const id of popup.keys()) if (!unread.has(id)) popup.delete(id);
      for (const record of windows) if (record.ids.length && !record.ids.some((id) => unread.has(id))) {
        try { record.notification.close(); } catch (_) {} windows.delete(record);
      }
      // 首次加载的历史未读显示卡片，但不突然响铃/弹桌面；每页、每次登录独立去重。
      if (initialized && fresh.length) {
        if (preferences.style === 'large') for (const item of fresh) popup.set(item.id, item);
        if (!preferences.soundWanted) {
          soundStatus = '新提醒已到期，但到期提示音开关关闭；试听不会自动开启，请在设置中开启。';
        } else if (preferences.volume === 0) {
          soundStatus = '新提醒已到期，但提醒音量为 0；请在编辑弹窗调高音量。';
        } else if (!sound) {
          soundStatus = '新提醒已到期，但到期声音尚未激活；请点击恢复声音，页面提醒仍保留。';
        } else {
          const current = epoch, attempt = soundAttempt;
          const failed = () => {
            if (current === epoch && attempt === soundAttempt) {
              sound = false; playing = false;
              soundStatus = '到期铃声播放失败或被浏览器阻止，请点击恢复声音；页面提醒仍有效。'; notify();
            }
          };
          try {
            const playback = tone();
            soundStatus = '已请求播放本次到期铃声；实际听见仍取决于浏览器和系统音量。';
            Promise.resolve(playback).catch(failed);
          } catch (_) { failed(); }
        }
        if (desktop) desktopMessage(fresh);
      }
      initialized = true; notify();
    }
    function remove(id) {
      unread.delete(id); dismissed.delete(id); popup.delete(id);
      for (const record of windows) if (record.ids.length && !record.ids.some((value) => unread.has(value))) {
        try { record.notification.close(); } catch (_) {} windows.delete(record);
      }
      notify();
    }
    return {
      reset, ingest, snapshot, enableSound, mute, enableDesktop, updatePreferences, stopSound, setAudioAsset,
      previewSound() { return startAudio(true, false); },
      closePopup() { popup.clear(); notify(); },
      disableDesktop() { desktop = false; closeWindows(); desktopStatus = '已关闭桌面通知偏好；不会撤销浏览器站点权限。'; updatePreferences({ desktopWanted: false }); },
      testDesktop() { if (account !== null) desktopMessage([], true); },
      dismiss(id) { dismissed.add(id); notify(); },
      restore() { dismissed.clear(); notify(); },
      remove
    };
  }
  const api = { create, collectUnread };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ReminderAlerts = api;
})(globalThis);
