/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./app/**/*.{js,jsx,ts,tsx}', './components/**/*.{js,jsx,ts,tsx}', './src/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        ink: 'var(--color-ink)',
        muted: 'var(--color-muted)',
        canvas: 'var(--color-canvas)',
        accent: 'var(--color-accent)',
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
      },
    },
  },
  plugins: [],
};
