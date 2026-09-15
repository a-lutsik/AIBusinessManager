export type ThemeName = 'light' | 'dark';

export const tokens = {
  color: {
    ink: '#1c1917',
    muted: '#57534e',
    canvas: '#f7f4ef',
    surface: '#ffffff',
    accent: '#0f766e',
    line: '#e7e5e4',
    status: {
      pending: '#b45309',
      confirmed: '#0f766e',
      completed: '#1d4ed8',
      noShow: '#be123c',
      cancelled: '#78716c',
    },
  },
  dark: {
    ink: '#f5f5f4',
    muted: '#a8a29e',
    canvas: '#1c1917',
    surface: '#292524',
    accent: '#2dd4bf',
    line: '#44403c',
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

export type Tokens = typeof tokens;
