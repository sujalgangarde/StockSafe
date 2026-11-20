// All calls go through here: adds the token, refreshes it once on 401, and throws readable errors.
const BASE = import.meta.env.VITE_API_BASE || '';
const KEY = 'stocksafe.auth';

export const auth = {
  get: () => { try { return JSON.parse(localStorage.getItem(KEY)); } catch { return null; } },
  set: (v) => localStorage.setItem(KEY, JSON.stringify(v)),
  clear: () => localStorage.removeItem(KEY),
};

let refreshing = null;
async function refresh() {
  const a = auth.get();
  if (!a?.refreshToken) throw new Error('Please log in again');
  refreshing ??= fetch(`${BASE}/api/v1/auth/refresh`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: a.refreshToken }),
  }).then(async (r) => {
    if (!r.ok) { auth.clear(); throw new Error('Session expired. Please log in again.'); }
    auth.set(await r.json());
  }).finally(() => { refreshing = null; });
  return refreshing;
}

export async function api(path, { method = 'GET', body, raw, headers = {} } = {}, retry = true) {
  const a = auth.get();
  const h = { ...headers };
  if (a?.accessToken) h.Authorization = `Bearer ${a.accessToken}`;
  if (body !== undefined && !h['Content-Type']) h['Content-Type'] = 'application/json';
  const res = await fetch(`${BASE}/api/v1${path}`, {
    method, headers: h,
    body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
  });
  if (res.status === 401 && retry && a?.refreshToken && !path.startsWith('/auth/')) {
    await refresh();
    return api(path, { method, body, raw, headers }, false);
  }
  if (!res.ok) {
    let msg = `Error ${res.status}`;
    try { const j = await res.json(); msg = j.detail || j.message || msg; } catch { /* no body */ }
    const e = new Error(msg); e.status = res.status; throw e;
  }
  if (raw) return res;
  if (res.status === 204) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export async function download(path, filename) {
  const res = await api(path, { raw: true });
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}

// Live events (SSE). Uses fetch so we can send the Authorization header.
// On reconnect we send Last-Event-ID, so the server replays what we missed.
export function subscribe(shopId, onEvent, onStatus) {
  let stop = false, lastId = null, controller = null, delay = 1000;
  async function run() {
    while (!stop) {
      controller = new AbortController();
      try {
        const a = auth.get();
        const h = { Authorization: `Bearer ${a?.accessToken}`, Accept: 'text/event-stream' };
        if (lastId) h['Last-Event-ID'] = lastId;
        const res = await fetch(`${BASE}/api/v1/shops/${shopId}/events`, { headers: h, signal: controller.signal });
        if (res.status === 401) { await refresh(); continue; }
        if (!res.ok) throw new Error(`stream ${res.status}`);
        onStatus?.(true); delay = 1000;
        const reader = res.body.getReader(); const dec = new TextDecoder(); let buf = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            const block = buf.slice(0, i); buf = buf.slice(i + 2);
            let id, name = 'message', data = '';
            for (const line of block.split('\n')) {
              if (line.startsWith('id:')) id = line.slice(3).trim();
              else if (line.startsWith('event:')) name = line.slice(6).trim();
              else if (line.startsWith('data:')) data += line.slice(5).trim();
            }
            if (id) lastId = id;
            if (name !== 'ready' && data) { try { onEvent(name, JSON.parse(data)); } catch { /* ignore */ } }
          }
        }
      } catch (e) { if (stop) return; }
      onStatus?.(false);
      await new Promise((r) => setTimeout(r, delay));
      delay = Math.min(delay * 2, 15000);
    }
  }
  run();
  return () => { stop = true; controller?.abort(); };
}
