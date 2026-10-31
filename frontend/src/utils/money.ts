/** Format minor units (cents, tetri) in the currency's own number of fraction digits, using Intl when available. */
export function formatMoney(priceMinor: number | null | undefined, currencyCode = 'GEL', locale = 'en'): string {
  if (priceMinor == null || Number.isNaN(priceMinor)) {
    return '—';
  }
  try {
    const format = new Intl.NumberFormat(locale, { style: 'currency', currency: currencyCode });
    return format.format(priceMinor / 10 ** (format.resolvedOptions().maximumFractionDigits ?? 2));
  } catch {
    return `${(priceMinor / 100).toFixed(2)} ${currencyCode}`;
  }
}
