'use strict';
// 唯一使用持久浏览器存储的模块：固定白名单、按账号隔离，仅保存非敏感提醒偏好。
((root) => {
  const defaults = Object.freeze({ style: 'card', artwork: 'bell', melody: 'chime', volume: 50,
    soundWanted: false, desktopWanted: false });
  function normalize(value) {
    const input = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    return {
      style: ['card', 'large'].includes(input.style) ? input.style : defaults.style,
      artwork: ['bell', 'note', 'none', 'custom'].includes(input.artwork) ? input.artwork : defaults.artwork,
      melody: ['chime', 'soft', 'clear', 'custom'].includes(input.melody) ? input.melody : defaults.melody,
      volume: Number.isInteger(input.volume) && input.volume >= 0 && input.volume <= 100 ? input.volume : defaults.volume,
      soundWanted: typeof input.soundWanted === 'boolean' ? input.soundWanted : defaults.soundWanted,
      desktopWanted: typeof input.desktopWanted === 'boolean' ? input.desktopWanted : defaults.desktopWanted
    };
  }
  const key = (id) => Number.isSafeInteger(id) && id > 0 ? 'shiji.reminderPreferences.v1.' + id : null;
  function load(env, id) {
    try {
      const storageKey = key(id);
      if (!storageKey) return { preferences: normalize(), message: '尚未登录。' };
      const raw = env.localStorage.getItem(storageKey);
      const data = raw === null ? null : JSON.parse(raw);
      if (data !== null && (!data || data.version !== 1 || Array.isArray(data))) throw new Error('invalid-format');
      return { preferences: normalize(data), message: '偏好按账号保存在当前浏览器，不跨设备同步。' };
    } catch (_) {
      return { preferences: normalize(), message: '无法读取本地偏好，已使用默认设置；本次仍可调整。' };
    }
  }
  function save(env, id, preferences) {
    try {
      const storageKey = key(id);
      if (!storageKey) return '尚未登录，未保存。';
      env.localStorage.setItem(storageKey, JSON.stringify({ version: 1, ...normalize(preferences) }));
      return '已保存到当前浏览器，刷新或重新登录可恢复；不跨设备同步。';
    } catch (_) { return '浏览器无法保存偏好，当前设置仍有效，但刷新后可能丢失。'; }
  }
  const api = { defaults, normalize, load, save };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ReminderPreferences = api;
})(globalThis);
