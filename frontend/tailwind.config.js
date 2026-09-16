/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./app/**/*.{js,jsx,ts,tsx}', './components/**/*.{js,jsx,ts,tsx}', './src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        primary: 'var(--color-primary)',
        secondary: 'var(--color-secondary)',
        tertiary: 'var(--color-tertiary)',
        ink: 'var(--color-ink)',
        muted: 'var(--color-muted)',
        canvas: 'var(--color-canvas)',
        surface: 'var(--color-surface)',
        mist: 'var(--color-mist)',
        accent: 'var(--color-accent)',
        ai: 'var(--color-ai)',
        line: 'var(--color-line)',
        'status-pending': 'var(--color-status-pending)',
        'status-confirmed': 'var(--color-status-confirmed)',
        'status-completed': 'var(--color-status-completed)',
        'status-noshow': 'var(--color-status-noshow)',
        'status-cancelled': 'var(--color-status-cancelled)',
      },
      fontFamily: {
        sans: ['var(--font-sans)'],
        display: ['var(--font-display)'],
      },
      borderRadius: {
        control: 'var(--radius-control)',
        card: 'var(--radius-card)',
        pill: 'var(--radius-pill)',
      },
    },
  },
  plugins: [],
};
