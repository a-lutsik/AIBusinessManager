/**
 * Web sign-in is a full-page redirect (authorization code + PKCE), not a popup: popups degrade to
 * same-tab navigations in in-app webviews and with popup blockers. The values needed to finish the
 * flow after the round trip to Keycloak live in sessionStorage (per tab, gone when the tab closes).
 */
const PENDING_KEY = 'cadence.auth.pending';

export const DEFAULT_RETURN_PATH = '/dashboard';

export type PendingSignIn = {
  state: string;
  codeVerifier: string;
  returnTo: string;
};

export type CallbackParams = {
  code: string | null;
  state: string | null;
  error: string | null;
  errorDescription: string | null;
};

/** Accepts only same-origin app paths that are not the auth callback itself. */
export function safeReturnPath(path: string | null | undefined): string {
  if (!path || !path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) {
    return DEFAULT_RETURN_PATH;
  }
  if (path === '/auth' || path.startsWith('/auth?') || path.startsWith('/auth/')) {
    return DEFAULT_RETURN_PATH;
  }
  return path;
}

export function currentReturnPath(): string {
  if (typeof window === 'undefined') return DEFAULT_RETURN_PATH;
  const { pathname, search, hash } = window.location;
  return safeReturnPath(`${pathname}${search}${hash}`);
}

/** Returns false when sessionStorage is unavailable (the redirect could not be completed later). */
export function savePendingSignIn(pending: PendingSignIn): boolean {
  try {
    globalThis.sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending));
    return true;
  } catch {
    return false;
  }
}

/** One-shot read: the transient sign-in state is removed as soon as it is read. */
export function takePendingSignIn(): PendingSignIn | null {
  try {
    const raw = globalThis.sessionStorage.getItem(PENDING_KEY);
    globalThis.sessionStorage.removeItem(PENDING_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<PendingSignIn>;
    if (typeof parsed.state !== 'string' || typeof parsed.codeVerifier !== 'string') return null;
    return {
      state: parsed.state,
      codeVerifier: parsed.codeVerifier,
      returnTo: safeReturnPath(parsed.returnTo),
    };
  } catch {
    return null;
  }
}

export function clearPendingSignIn(): void {
  try {
    globalThis.sessionStorage.removeItem(PENDING_KEY);
  } catch {
    // ignore
  }
}

/** Authorization response parameters in the current URL, or null when this is not a callback. */
export function readCallbackParams(): CallbackParams | null {
  if (typeof window === 'undefined') return null;
  const query = new URLSearchParams(window.location.search);
  const params: CallbackParams = {
    code: query.get('code'),
    state: query.get('state'),
    error: query.get('error'),
    errorDescription: query.get('error_description'),
  };
  return params.code || params.state || params.error ? params : null;
}

/** Drops the one-time authorization response from the address bar and history entry. */
export function stripCallbackQuery(): void {
  if (typeof window === 'undefined') return;
  window.history.replaceState(window.history.state, '', window.location.pathname);
}
