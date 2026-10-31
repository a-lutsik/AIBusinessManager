import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, ApiError, LUMEN_TENANT_ID, setSessionRefusalListener, type MeView } from '@/src/api/client';
import { useAuth } from './AuthContext';

export type ConsoleRole = 'OWNER' | 'MASTER';

export type ProfileProblem = { code?: string };

type ConsoleSessionValue = {
  me: MeView | null;
  /** null until known (oidc: until `api.me()` answers). */
  role: ConsoleRole | null;
  /** True once the role is known and screens may load data. */
  ready: boolean;
  /** True once `api.me()` has answered, also with an error: `me` (the tenant's zone in it) is as known as it will be. */
  profileLoaded: boolean;
  /** The profile did not load, with the API's code when it gave one; null while it is loading or loaded. */
  profileProblem: ProfileProblem | null;
  /** Reads the profile again. */
  retryProfile: () => void;
  /** dev-headers only: the role switcher. No-op in oidc mode. */
  switchRole: (next: ConsoleRole) => void;
};

const ConsoleSessionContext = createContext<ConsoleSessionValue | null>(null);

export function useConsoleSession(): ConsoleSessionValue {
  const value = useContext(ConsoleSessionContext);
  if (!value) {
    throw new Error('useConsoleSession must be used inside <ConsoleSessionProvider>');
  }
  return value;
}

const DEV_SPECIALIST_ID = '00000000-0000-4000-8000-000000000011';

function normalizeRole(role: string | undefined | null): ConsoleRole | null {
  return role === 'OWNER' || role === 'MASTER' ? role : null;
}

/** Holds the current actor. oidc: role comes from `api.me()` (token claims). dev-headers: localStorage switcher. */
export function ConsoleSessionProvider({ children }: { children: ReactNode }) {
  const { mode, status } = useAuth();
  const [me, setMe] = useState<MeView | null>(null);
  const [devRole, setDevRole] = useState<ConsoleRole | null>(null);
  const [meLoaded, setMeLoaded] = useState(false);
  const [profileProblem, setProfileProblem] = useState<ProfileProblem | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (mode !== 'dev-headers') return;
    const stored = typeof localStorage !== 'undefined' ? localStorage.getItem('cadence.role') : null;
    // Reads the dev-only role from browser storage (an external system) once the mode is known.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDevRole(stored === 'MASTER' ? 'MASTER' : 'OWNER');
  }, [mode]);

  useEffect(() => {
    if (status !== 'authenticated') {
      // Resets the loaded profile when the session ends.
      /* eslint-disable react-hooks/set-state-in-effect */
      setMe(null);
      setMeLoaded(false);
      setProfileProblem(null);
      /* eslint-enable react-hooks/set-state-in-effect */
      return;
    }
    if (mode === 'dev-headers' && !devRole) return;
    let cancelled = false;
    setMeLoaded(false);
    api
      .me(LUMEN_TENANT_ID)
      .then((view) => {
        if (cancelled) return;
        setMe(view);
        setProfileProblem(null);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setMe(null);
        setProfileProblem({ code: e instanceof ApiError ? e.code : undefined });
      })
      .finally(() => {
        if (!cancelled) setMeLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [mode, status, devRole, attempt]);

  // Any console request can be the first to learn it. The handler sends nothing, so it cannot start a loop; the screens
  // that were asking are unmounted by the state it sets.
  useEffect(() => {
    setSessionRefusalListener((code) => setProfileProblem({ code }));
    return () => setSessionRefusalListener(null);
  }, []);

  const retryProfile = useCallback(() => setAttempt((n) => n + 1), []);

  const switchRole = useCallback(
    (next: ConsoleRole) => {
      if (mode !== 'dev-headers') return;
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('cadence.role', next);
        localStorage.setItem('cadence.tenantId', LUMEN_TENANT_ID);
        if (next === 'MASTER') {
          localStorage.setItem('cadence.specialistId', DEV_SPECIALIST_ID);
        } else {
          localStorage.removeItem('cadence.specialistId');
        }
      }
      setDevRole(next);
    },
    [mode]
  );

  const role = mode === 'dev-headers' ? devRole : normalizeRole(me?.role);
  const ready = role !== null && (mode === 'dev-headers' || meLoaded);

  const value = useMemo<ConsoleSessionValue>(
    () => ({ me, role, ready, profileLoaded: meLoaded, profileProblem, retryProfile, switchRole }),
    [me, role, ready, meLoaded, profileProblem, retryProfile, switchRole]
  );
  return <ConsoleSessionContext.Provider value={value}>{children}</ConsoleSessionContext.Provider>;
}
