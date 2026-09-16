export type ThemeName = 'light' | 'dark';

/** Atelier Intelligence palette (Stitch design): emerald + indigo + amber on slate. */
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

export function statusColor(status: string): string {
  switch (status) {
    case 'PENDING':
      return tokens.color.status.pending;
    case 'CONFIRMED':
      return tokens.color.status.confirmed;
    case 'COMPLETED':
      return tokens.color.status.completed;
    case 'NO_SHOW':
      return tokens.color.status.noShow;
    default:
      return tokens.color.status.cancelled;
  }
}

export function trustColor(level: string): string {
  const key = level as keyof typeof tokens.color.trust;
  return tokens.color.trust[key] ?? tokens.color.muted;
}

export type Tokens = typeof tokens;
