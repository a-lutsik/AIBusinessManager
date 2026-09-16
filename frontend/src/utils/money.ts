/** Format priceMinor (cents/tetri) using Intl when available. */
export function formatMoney(priceMinor: number | null | undefined, currencyCode = 'GEL', locale = 'en'): string {
  if (priceMinor == null || Number.isNaN(priceMinor)) {
    return '—';
  }
  const major = priceMinor / 100;
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: currencyCode,
      maximumFractionDigits: 2,
    }).format(major);
  } catch {
    return `${major.toFixed(2)} ${currencyCode}`;
  }
}
