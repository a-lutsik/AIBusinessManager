import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, LUMEN_TENANT_ID, type MeView } from '@/src/api/client';
import { useAuth } from './AuthContext';

export type ConsoleRole = 'OWNER' | 'MASTER';

type ConsoleSessionValue = {
  me: MeView | null;
  /** null until known (oidc: until `api.me()` answers). */
  role: ConsoleRole | null;
  /** True once the role is known and screens may load data. */
  ready: boolean;
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
      /* eslint-enable react-hooks/set-state-in-effect */
      return;
    }
    if (mode === 'dev-headers' && !devRole) return;
    let cancelled = false;
    setMeLoaded(false);
    api
      .me(LUMEN_TENANT_ID)
      .then((view) => {
        if (!cancelled) setMe(view);
      })
      .catch(() => {
        if (!cancelled) setMe(null);
      })
      .finally(() => {
        if (!cancelled) setMeLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [mode, status, devRole]);

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
    () => ({ me, role, ready, switchRole }),
    [me, role, ready, switchRole]
  );
  return <ConsoleSessionContext.Provider value={value}>{children}</ConsoleSessionContext.Provider>;
}
