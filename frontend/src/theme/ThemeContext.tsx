import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Platform } from 'react-native';
import type { ThemeName } from './tokens';

type ThemeApi = {
  theme: ThemeName;
  toggle: () => void;
  setTheme: (theme: ThemeName) => void;
};

const Ctx = createContext<ThemeApi>({ theme: 'light', toggle: () => undefined, setTheme: () => undefined });

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<ThemeName>('light');

  useEffect(() => {
    const stored = Platform.OS === 'web' ? globalThis.localStorage?.getItem('abm.theme') : null;
    if (stored === 'dark' || stored === 'light') {
      setTheme(stored);
    }
  }, []);

  useEffect(() => {
    if (Platform.OS === 'web' && typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-theme', theme);
      globalThis.localStorage?.setItem('abm.theme', theme);
    }
  }, [theme]);

  const value = useMemo<ThemeApi>(
    () => ({
      theme,
      setTheme,
      toggle: () => setTheme((prev) => (prev === 'light' ? 'dark' : 'light')),
    }),
    [theme]
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useTheme() {
  return useContext(Ctx);
}
