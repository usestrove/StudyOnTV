export const SESSION_STORAGE_KEY = 'sotv-panel-session';
const SESSIONS_MAP_KEY = 'sotv-panel-sessions';
const REMEMBER_PREF_KEY = 'sotv-panel-remember';
const MAX_SAVED_HOSTS = 16;

type ParentBridge = {
  getSavedToken?: () => string;
  saveToken?: (token: string) => void;
};

type LocationLike = {
  host: string;
  protocol: string;
  port: string;
  pathname: string;
};

function bridge(): ParentBridge | null {
  if (typeof window === 'undefined') return null;
  return ((window as unknown as { StudyOnTVParent?: ParentBridge }).StudyOnTVParent) ?? null;
}

export function normalizeHostKey(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return '';
  const hasHttps = /^https:\/\//i.test(trimmed);
  const clean = trimmed.replace(/^https?:\/\//i, '').replace(/\/.*$/, '').toLowerCase();
  if (!clean) return '';
  return hasHttps ? `https://${clean}` : clean;
}

function readSessionsMap(): Record<string, string> {
  try {
    if (typeof localStorage === 'undefined') return {};
    const parsed = JSON.parse(localStorage.getItem(SESSIONS_MAP_KEY) || '{}');
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const out: Record<string, string> = {};
    for (const [k, v] of Object.entries(parsed)) {
      if (typeof v === 'string' && v.trim()) out[k] = v.trim();
    }
    return out;
  } catch {
    return {};
  }
}

function writeSessionsMap(map: Record<string, string>) {
  try {
    if (typeof localStorage === 'undefined') return;
    const entries = Object.entries(map).slice(-MAX_SAVED_HOSTS);
    localStorage.setItem(SESSIONS_MAP_KEY, JSON.stringify(Object.fromEntries(entries)));
  } catch {}
}

export function getRememberPreference(): boolean {
  try {
    if (typeof localStorage === 'undefined') return true;
    return localStorage.getItem(REMEMBER_PREF_KEY) !== '0';
  } catch {
    return true;
  }
}

export function setRememberPreference(remember: boolean) {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(REMEMBER_PREF_KEY, remember ? '1' : '0');
  } catch {}
}

export function getSavedTokenForHost(host: string): string {
  const key = normalizeHostKey(host);
  if (!key) return '';
  const map = readSessionsMap();
  if (map[key]) return map[key];
  try {
    if (typeof localStorage === 'undefined') return '';
    const v = JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY) || 'null');
    if (v?.token && v?.host && normalizeHostKey(v.host) === key) return String(v.token);
  } catch {}
  return '';
}

export function saveHostSession(host: string, token: string, remember = true) {
  const key = normalizeHostKey(host);
  if (!remember || !token) {
    forgetHostSession(host);
    return;
  }
  try {
    if (typeof localStorage !== 'undefined' && key) {
      localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ host, token }));
      const map = readSessionsMap();
      delete map[key];
      map[key] = token;
      writeSessionsMap(map);
    }
  } catch {}
  try {
    bridge()?.saveToken?.(token);
  } catch {}
}

export function forgetHostSession(host?: string) {
  const key = host ? normalizeHostKey(host) : '';
  try {
    if (typeof localStorage !== 'undefined') {
      if (key) {
        const map = readSessionsMap();
        if (key in map) {
          delete map[key];
          writeSessionsMap(map);
        }
        const single = JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY) || 'null');
        if (!single || !single.host || normalizeHostKey(single.host) === key) {
          localStorage.removeItem(SESSION_STORAGE_KEY);
        }
      } else {
        localStorage.removeItem(SESSION_STORAGE_KEY);
      }
    }
  } catch {}
  try {
    bridge()?.saveToken?.('');
  } catch {}
}

export function restoreInitialSession(
  loc: LocationLike | undefined = typeof location !== 'undefined' ? location : undefined,
): { host: string; token: string } | null {
  const isDevServer = loc?.port === '5173';
  const isServedByDevice =
    !!loc && !isDevServer && loc.protocol.startsWith('http') && !!loc.host && loc.pathname.startsWith('/admin');

  try {
    if (isServedByDevice && loc) {
      const fromMap = getSavedTokenForHost(loc.host);
      if (fromMap) return { host: loc.host, token: fromMap };

      const single =
        typeof localStorage !== 'undefined'
          ? JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY) || 'null')
          : null;
      if (single?.token && single?.host && !/^https:\/\//i.test(single.host)) {
        return { host: loc.host, token: String(single.token) };
      }

      // 独立家长端 App 按 deviceId 持久化的会话令牌：电视 DHCP 换 IP 后依然免密直进后台
      const nativeToken = bridge()?.getSavedToken?.()?.trim() || '';
      if (nativeToken) {
        saveHostSession(loc.host, nativeToken, true);
        return { host: loc.host, token: nativeToken };
      }
      return null;
    }

    if (typeof localStorage === 'undefined') return null;
    const single = JSON.parse(localStorage.getItem(SESSION_STORAGE_KEY) || 'null');
    if (single?.token && single?.host) {
      return { host: String(single.host), token: String(single.token) };
    }
  } catch {}
  return null;
}
