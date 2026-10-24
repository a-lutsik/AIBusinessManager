import {
  AuthRequest,
  exchangeCodeAsync,
  fetchDiscoveryAsync,
  makeRedirectUri,
  refreshAsync,
  ResponseType,
  type DiscoveryDocument,
  type TokenResponse,
} from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Platform } from 'react-native';
import { setAuthBridge } from '@/src/api/authBridge';
import { AUTH_MODE, OIDC_CLIENT_ID, OIDC_ISSUER, OIDC_SCOPES, type AuthMode } from './config';
import { clearRefreshToken, loadRefreshToken, saveRefreshToken } from './tokenStorage';
import {
  clearPendingSignIn,
  currentReturnPath,
  readCallbackParams,
  savePendingSignIn,
  stripCallbackQuery,
  takePendingSignIn,
} from './webRedirect';
import { t } from '@/src/i18n';

export type AuthStatus = 'loading' | 'unauthenticated' | 'authenticated';

export type AuthContextValue = {
  mode: AuthMode;
  status: AuthStatus;
  /** True while the browser sign-in is in progress. */
  signingIn: boolean;
  /** Last sign-in failure message, if any. */
  error: string | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  /**
   * Web only: finishes the redirect sign-in on the `/auth` callback route (verifies `state`, exchanges the
   * code once, strips the query). Resolves to `missing` when the URL carries no authorization response.
   */
  completeRedirect: () => Promise<RedirectResult>;
};

export type RedirectFailure =
  /** The URL has no authorization response at all. */
  | { ok: false; reason: 'missing' }
  /** Keycloak answered with `error` / `error_description`. */
  | { ok: false; reason: 'provider'; detail: string }
  /** `state` is absent, or does not match the one stored before the redirect. */
  | { ok: false; reason: 'state' }
  /** The token endpoint rejected the code, or the response had no code. */
  | { ok: false; reason: 'exchange'; detail: string };

export type RedirectResult = { ok: true; returnTo: string } | RedirectFailure;

/** One run per callback URL: React StrictMode / re-renders must never exchange the same code twice. */
const redirectRuns = new Map<string, Promise<RedirectResult>>();
/** Outcome of this page load's callback, still answerable after the query string has been stripped. */
let lastRedirectRun: Promise<RedirectResult> | null = null;

const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return value;
}

/** Refresh this many seconds before the access token expires. */
const REFRESH_MARGIN_S = 30;
const MIN_REFRESH_DELAY_MS = 5_000;

type Tokens = {
  accessToken: string;
  refreshToken: string | null;
  idToken: string | null;
  /** epoch ms */
  expiresAt: number;
};

function toTokens(res: TokenResponse, previous?: Tokens | null): Tokens {
  const lifetimeS = res.expiresIn ?? 300;
  return {
    accessToken: res.accessToken,
    refreshToken: res.refreshToken ?? previous?.refreshToken ?? null,
    idToken: res.idToken ?? previous?.idToken ?? null,
    expiresAt: (res.issuedAt + lifetimeS) * 1000,
  };
}

function DevHeadersAuthProvider({ children }: { children: ReactNode }) {
  const value = useMemo<AuthContextValue>(
    () => ({
      mode: 'dev-headers',
      status: 'authenticated',
      signingIn: false,
      error: null,
      signIn: async () => undefined,
      signOut: async () => undefined,
      completeRedirect: async () => ({ ok: true, returnTo: '/' }),
    }),
    []
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

function OidcAuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [signingIn, setSigningIn] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Captured on first render: child effects (the /auth route) strip the callback query before this provider's effects run.
  const [startedOnCallback] = useState(
    () =>
      Platform.OS === 'web' &&
      typeof window !== 'undefined' &&
      window.location.pathname.startsWith('/auth') &&
      readCallbackParams() !== null
  );

  const tokensRef = useRef<Tokens | null>(null);
  const discoveryRef = useRef<DiscoveryDocument | null>(null);
  const requestRef = useRef<AuthRequest | null>(null);
  const redirectUriRef = useRef<string | null>(null);
  const refreshInFlight = useRef<Promise<string | null> | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const mounted = useRef(true);
  const refreshRef = useRef<() => Promise<string | null>>(async () => null);
  const dropSessionRef = useRef<() => Promise<void>>(async () => undefined);

  const getRedirectUri = useCallback(() => {
    if (!redirectUriRef.current) {
      redirectUriRef.current = makeRedirectUri({ scheme: 'cadence', path: 'auth' });
    }
    return redirectUriRef.current;
  }, []);

  const getDiscovery = useCallback(async (): Promise<DiscoveryDocument> => {
    if (!discoveryRef.current) {
      discoveryRef.current = await fetchDiscoveryAsync(OIDC_ISSUER);
    }
    return discoveryRef.current;
  }, []);

  /** Prepares an auth request (PKCE verifier + URL) ahead of the click so the sign-in starts without a network wait. */
  const prepareRequest = useCallback(async (): Promise<AuthRequest> => {
    const discovery = await getDiscovery();
    const request = new AuthRequest({
      clientId: OIDC_CLIENT_ID,
      scopes: OIDC_SCOPES,
      redirectUri: getRedirectUri(),
      responseType: ResponseType.Code,
      usePKCE: true,
    });
    await request.makeAuthUrlAsync(discovery);
    requestRef.current = request;
    return request;
  }, [getDiscovery, getRedirectUri]);

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const dropSession = useCallback(async () => {
    clearTimer();
    tokensRef.current = null;
    await clearRefreshToken();
    if (mounted.current) {
      setStatus('unauthenticated');
    }
  }, []);

  const scheduleRefresh = useCallback((tokens: Tokens) => {
    clearTimer();
    if (!tokens.refreshToken) return;
    const delay = Math.max(tokens.expiresAt - Date.now() - REFRESH_MARGIN_S * 1000, MIN_REFRESH_DELAY_MS);
    timerRef.current = setTimeout(() => {
      refreshRef.current().then((token) => {
        if (!token && mounted.current) {
          dropSessionRef.current();
        }
      });
    }, delay);
  }, []);

  const applyTokens = useCallback(
    async (next: Tokens) => {
      tokensRef.current = next;
      if (next.refreshToken) {
        await saveRefreshToken(next.refreshToken);
      }
      if (mounted.current) {
        setStatus('authenticated');
      }
      scheduleRefresh(next);
    },
    [scheduleRefresh]
  );

  /** Exchanges the refresh token; resolves to the new access token or null (session dropped only on invalid_grant-like failures). */
  const refresh = useCallback((): Promise<string | null> => {
    if (refreshInFlight.current) {
      return refreshInFlight.current;
    }
    const run = (async () => {
      const refreshToken = tokensRef.current?.refreshToken ?? (await loadRefreshToken());
      if (!refreshToken) {
        return null;
      }
      try {
        const discovery = await getDiscovery();
        const res = await refreshAsync({ clientId: OIDC_CLIENT_ID, refreshToken }, discovery);
        const next = toTokens(res, tokensRef.current);
        await applyTokens(next);
        return next.accessToken;
      } catch {
        return null;
      }
    })().finally(() => {
      refreshInFlight.current = null;
    });
    refreshInFlight.current = run;
    return run;
  }, [applyTokens, getDiscovery]);

  useEffect(() => {
    refreshRef.current = refresh;
    dropSessionRef.current = dropSession;
  }, [refresh, dropSession]);

  const getAccessToken = useCallback(async (): Promise<string | null> => {
    const tokens = tokensRef.current;
    if (!tokens) return null;
    if (tokens.expiresAt - Date.now() > REFRESH_MARGIN_S * 1000) {
      return tokens.accessToken;
    }
    return (await refresh()) ?? null;
  }, [refresh]);

  // Register the token getter used by the API client.
  useEffect(() => {
    setAuthBridge({
      getAccessToken,
      refreshAccessToken: refresh,
      onUnauthorized: () => {
        dropSession();
      },
    });
    return () => setAuthBridge(null);
  }, [getAccessToken, refresh, dropSession]);

  // Restore the session from the persisted refresh token.
  useEffect(() => {
    mounted.current = true;
    let cancelled = false;
    (async () => {
      if (startedOnCallback) {
        // The /auth callback route calls completeRedirect(), which establishes the session; restoring a stale one would race it.
        return;
      }
      const stored = await loadRefreshToken();
      if (!stored) {
        if (!cancelled) setStatus('unauthenticated');
        return;
      }
      tokensRef.current = { accessToken: '', refreshToken: stored, idToken: null, expiresAt: 0 };
      const token = await refresh();
      if (cancelled) return;
      if (!token) {
        await dropSession();
      }
    })();
    return () => {
      cancelled = true;
      mounted.current = false;
      clearTimer();
    };
  }, [refresh, dropSession, startedOnCallback]);

  // Warm up discovery + PKCE request while the sign-in screen is shown.
  useEffect(() => {
    if (status !== 'unauthenticated') return;
    prepareRequest().catch(() => {
      // Network/Keycloak may be down; signIn() retries and reports the error.
    });
  }, [status, prepareRequest]);

  /** Exchanges an authorization code for tokens and starts the session (shared by native and web). */
  const exchangeAndApply = useCallback(
    async (code: string, codeVerifier: string | undefined) => {
      const discovery = await getDiscovery();
      const res = await exchangeCodeAsync(
        {
          clientId: OIDC_CLIENT_ID,
          code,
          redirectUri: getRedirectUri(),
          extraParams: codeVerifier ? { code_verifier: codeVerifier } : undefined,
        },
        discovery
      );
      await applyTokens(toTokens(res));
    },
    [applyTokens, getDiscovery, getRedirectUri]
  );

  const signIn = useCallback(async () => {
    if (signingIn) return;
    setError(null);
    setSigningIn(true);
    let leavingPage = false;
    try {
      const discovery = await getDiscovery();
      const request = requestRef.current ?? (await prepareRequest());
      requestRef.current = null;
      if (Platform.OS === 'web') {
        // Full-page redirect: persist what the /auth callback needs, then leave. No popup involved.
        if (!request.url || !request.codeVerifier) {
          throw new Error('Authorization request is not ready');
        }
        const saved = savePendingSignIn({
          state: request.state,
          codeVerifier: request.codeVerifier,
          returnTo: currentReturnPath(),
        });
        if (!saved) {
          throw new Error(t('auth.callback.storageUnavailable'));
        }
        leavingPage = true;
        window.location.assign(request.url);
        return;
      }
      const result = await request.promptAsync(discovery);
      if (result.type === 'success') {
        const code = result.params.code;
        if (!code) {
          throw new Error('Authorization code missing in response');
        }
        await exchangeAndApply(code, request.codeVerifier);
      } else if (result.type === 'error') {
        throw new Error(result.error?.message ?? result.errorCode ?? 'Sign-in failed');
      } else {
        // cancel / dismiss / locked: user closed the window. Prepare the next attempt.
        prepareRequest().catch(() => undefined);
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
      prepareRequest().catch(() => undefined);
    } finally {
      // While the page is navigating away the button stays in its waiting state.
      if (mounted.current && !leavingPage) setSigningIn(false);
    }
  }, [exchangeAndApply, getDiscovery, prepareRequest, signingIn]);

  // Coming back to a cached page (bfcache) after the Keycloak redirect must not leave the button stuck.
  useEffect(() => {
    if (Platform.OS !== 'web' || typeof window === 'undefined') return;
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setSigningIn(false);
    };
    window.addEventListener('pageshow', onPageShow);
    return () => window.removeEventListener('pageshow', onPageShow);
  }, []);

  const completeRedirect = useCallback((): Promise<RedirectResult> => {
    if (Platform.OS !== 'web' || typeof window === 'undefined') {
      return Promise.resolve({ ok: false, reason: 'missing' });
    }
    const params = readCallbackParams();
    if (!params) {
      return lastRedirectRun ?? Promise.resolve({ ok: false, reason: 'missing' });
    }
    const runKey = window.location.search;
    const existing = redirectRuns.get(runKey);
    if (existing) {
      return existing;
    }
    const run = (async (): Promise<RedirectResult> => {
      // The stored sign-in state is single use, whatever the outcome.
      const pending = takePendingSignIn();
      stripCallbackQuery();
      try {
        if (params.error) {
          return { ok: false, reason: 'provider', detail: params.errorDescription ?? params.error };
        }
        if (!pending || !params.state || params.state !== pending.state || !params.code) {
          return { ok: false, reason: 'state' };
        }
        try {
          await exchangeAndApply(params.code, pending.codeVerifier);
        } catch (e: unknown) {
          return { ok: false, reason: 'exchange', detail: e instanceof Error ? e.message : String(e) };
        }
        return { ok: true, returnTo: pending.returnTo };
      } finally {
        clearPendingSignIn();
      }
    })().then((result) => {
      if (!result.ok && !tokensRef.current && mounted.current) {
        setStatus('unauthenticated');
      }
      return result;
    });
    redirectRuns.set(runKey, run);
    lastRedirectRun = run;
    return run;
  }, [exchangeAndApply]);

  const signOut = useCallback(async () => {
    const idToken = tokensRef.current?.idToken ?? null;
    await dropSession();
    try {
      const discovery = await getDiscovery();
      const endpoint = discovery.endSessionEndpoint;
      if (!endpoint) return;
      const redirect = getRedirectUri();
      const postLogout = Platform.OS === 'web' && typeof window !== 'undefined' ? `${window.location.origin}/` : redirect;
      const params = new URLSearchParams({ post_logout_redirect_uri: postLogout });
      if (idToken) {
        params.set('id_token_hint', idToken);
      } else {
        params.set('client_id', OIDC_CLIENT_ID);
      }
      const url = `${endpoint}?${params.toString()}`;
      if (Platform.OS === 'web' && typeof window !== 'undefined') {
        window.location.assign(url);
      } else {
        await WebBrowser.openAuthSessionAsync(url, postLogout);
      }
    } catch {
      // Local session is already cleared; the SSO session simply expires on its own.
    }
  }, [dropSession, getDiscovery, getRedirectUri]);

  const value = useMemo<AuthContextValue>(
    () => ({ mode: 'oidc', status, signingIn, error, signIn, signOut, completeRedirect }),
    [status, signingIn, error, signIn, signOut, completeRedirect]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  if (AUTH_MODE === 'dev-headers') {
    return <DevHeadersAuthProvider>{children}</DevHeadersAuthProvider>;
  }
  return <OidcAuthProvider>{children}</OidcAuthProvider>;
}
