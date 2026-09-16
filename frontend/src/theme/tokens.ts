import { useTheme } from './ThemeContext';

export type ThemeName = 'light' | 'dark';

/** Cadence palette: emerald + indigo + amber on slate. */
export const tokens = {
  color: {
    primary: '#059669',
    secondary: '#6366F1',
    tertiary: '#F59E0B',
    ink: '#0F172A',
    muted: '#64748B',
    canvas: '#F9FBFA',
    surface: '#FFFFFF',
    mist: '#F1F5F4',
    accent: '#059669',
    ai: '#6366F1',
    line: '#E2E8F0',
    status: {
      pending: '#F59E0B',
      confirmed: '#059669',
      completed: '#6366F1',
      noShow: '#DC2626',
      cancelled: '#94A3B8',
    },
    trust: {
      NEW: '#6366F1',
      HIGH: '#059669',
      NORMAL: '#64748B',
      LOW: '#DC2626',
    },
  },
  dark: {
    primary: '#34D399',
    secondary: '#818CF8',
    tertiary: '#FBBF24',
    ink: '#F8FAFC',
    muted: '#94A3B8',
    canvas: '#0F172A',
    surface: '#1E293B',
    mist: '#1E293B',
    accent: '#34D399',
    ai: '#818CF8',
    line: '#334155',
    status: {
      pending: '#FBBF24',
      confirmed: '#34D399',
      completed: '#818CF8',
      noShow: '#F87171',
      cancelled: '#94A3B8',
    },
    trust: {
      NEW: '#818CF8',
      HIGH: '#34D399',
      NORMAL: '#94A3B8',
      LOW: '#F87171',
    },
  },
  space: {
    xs: 4,
    sm: 8,
    md: 12,
    lg: 16,
    xl: 24,
    xxl: 32,
  },
  radius: {
    control: 8,
    card: 16,
    pill: 9999,
  },
  rail: {
    min: 256,
    max: 280,
  },
} as const;

type WidenPalette<T> = { [K in keyof T]: T[K] extends object ? WidenPalette<T[K]> : string };
export type Palette = WidenPalette<typeof tokens.color>;

/** Theme-aware palette + shared scale tokens. Prefer this over the static `tokens` export in any component that should respect the dark-mode toggle. */
export function useThemeTokens() {
  const { theme } = useTheme();
  return {
    color: theme === 'dark' ? tokens.dark : tokens.color,
    space: tokens.space,
    radius: tokens.radius,
    rail: tokens.rail,
  };
}

export function statusColor(status: string, palette: Palette = tokens.color): string {
  switch (status) {
    case 'PENDING':
      return palette.status.pending;
    case 'CONFIRMED':
      return palette.status.confirmed;
    case 'COMPLETED':
      return palette.status.completed;
    case 'NO_SHOW':
      return palette.status.noShow;
    default:
      return palette.status.cancelled;
  }
}

export function trustColor(level: string, palette: Palette = tokens.color): string {
  const key = level as keyof typeof palette.trust;
  return palette.trust[key] ?? palette.muted;
}

export type Tokens = typeof tokens;
