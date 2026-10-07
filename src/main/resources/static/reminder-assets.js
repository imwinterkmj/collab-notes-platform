'use strict';
// 导入副本存 IndexedDB，不发送网络请求，不保存磁盘路径；原文件不修改。
((root) => {
  const limits = { image: 2 * 1024 * 1024, audio: 5 * 1024 * 1024 };
  async function validateFile(file, kind) {
    if (!limits[kind] || !file || !file.size || file.size > limits[kind]) throw new Error(kind === 'image'
      ? '请选择非空且不超过 2 MB 的图片。' : '请选择非空且不超过 5 MB 的铃声。');
    const bytes = new Uint8Array(await file.slice(0, 12).arrayBuffer());
    const text = (start, length) => String.fromCharCode(...bytes.slice(start, start + length));
    let mime;
    if (kind === 'image') {
      if ([137, 80, 78, 71, 13, 10, 26, 10].every((value, i) => bytes[i] === value)) mime = 'image/png';
      else if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) mime = 'image/jpeg';
      else if (text(0, 4) === 'RIFF' && text(8, 4) === 'WEBP') mime = 'image/webp';
    } else {
      if (text(0, 3) === 'ID3' || (bytes[0] === 255 && (bytes[1] & 224) === 224 && (bytes[1] & 6) !== 0)) mime = 'audio/mpeg';
      else if (text(0, 4) === 'RIFF' && text(8, 4) === 'WAVE') mime = 'audio/wav';
      else if (text(0, 4) === 'OggS') mime = 'audio/ogg';
    }
    if (!mime) throw new Error(kind === 'image' ? '图片只支持 PNG、JPEG、WebP；不接受 SVG、HTML 或改后缀的文件。'
      : '铃声只支持 MP3、WAV、OGG；请检查文件实际格式。');
    return mime;
  }
  async function inspect(env, file, kind) {
    const mime = await validateFile(file, kind), blob = new env.Blob([file], { type: mime });
    const url = env.URL.createObjectURL(blob);
    try {
      return await new Promise((resolve, reject) => {
        const element = kind === 'image' ? new env.Image() : env.document.createElement('audio');
        let timer;
        const finish = (error, details) => {
          env.clearTimeout(timer); element.onload = element.onerror = element.onloadedmetadata = null;
          if (kind === 'audio') { element.pause(); element.removeAttribute('src'); element.load(); }
          if (error) reject(error); else resolve({ blob, mime, ...details });
        };
        element.onerror = () => finish(new Error('文件无法解码，或当前浏览器不支持该格式。'));
        if (kind === 'image') element.onload = () => {
          const width = element.naturalWidth, height = element.naturalHeight;
          finish(!width || !height || width > 4096 || height > 4096 || width * height > 12000000
            ? new Error('图片尺寸过大；请使用不超过 4096×4096 且总计 1200 万像素的图片。') : null, { width, height });
        };
        else {
          element.preload = 'metadata';
          element.onloadedmetadata = () => finish(!Number.isFinite(element.duration) || element.duration <= 0 || element.duration > 60
            ? new Error('请选择时长在 0～60 秒之间的短铃声。') : null, { duration: element.duration });
        }
        timer = env.setTimeout(() => finish(new Error('读取文件超时，请换一个文件重试。')), 10000);
        element.src = url;
      });
    } finally { env.URL.revokeObjectURL(url); }
  }
  function createStore(env) {
    const valid = (id, kind) => Number.isSafeInteger(id) && id > 0 && ['audio', 'image'].includes(kind);
    async function operation(id, kind, action, data) {
      if (!valid(id, kind)) throw new Error('导入文件需要有效的登录账号。');
      const db = await new Promise((resolve, reject) => {
        try {
          let failed = false;
          const request = env.indexedDB.open('shiji-reminder-assets', 1);
          request.onupgradeneeded = () => request.result.createObjectStore('assets', { keyPath: 'key' });
          request.onsuccess = () => { if (failed) request.result.close(); else resolve(request.result); };
          request.onerror = request.onblocked = () => { failed = true; reject(new Error('无法打开浏览器文件存储，请关闭旧标签页或检查浏览器存储权限。')); };
        } catch (_) { reject(new Error('此浏览器无法使用导入文件存储，内置样式和铃声仍可使用。')); }
      });
      db.onversionchange = () => db.close();
      try {
        return await new Promise((resolve, reject) => {
          let result;
          const transaction = db.transaction('assets', action === 'get' ? 'readonly' : 'readwrite');
          const store = transaction.objectStore('assets'), key = id + ':' + kind;
          const request = action === 'get' ? store.get(key) : action === 'remove' ? store.delete(key)
            : store.put({ ...data, key, kind });
          request.onsuccess = () => { result = request.result; };
          transaction.oncomplete = () => resolve(result ?? null);
          transaction.onerror = transaction.onabort = () => reject(new Error('导入副本保存失败，可能是浏览器存储权限或空间不足；原文件没有改动。'));
        });
      } finally { db.close(); }
    }
    return { get: (id, kind) => operation(id, kind, 'get'),
      save: (id, kind, data) => operation(id, kind, 'save', data), remove: (id, kind) => operation(id, kind, 'remove') };
  }
  const api = { validateFile, inspect, createStore };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.ReminderAssets = api;
})(globalThis);
