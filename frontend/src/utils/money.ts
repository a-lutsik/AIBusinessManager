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

/** The number of fraction digits of a currency, by the rule of formatMoney: Intl when it knows the code, otherwise 2. */
export function currencyFractionDigits(currencyCode: string): number {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency: currencyCode }).resolvedOptions().maximumFractionDigits ?? 2;
  } catch {
    return 2;
  }
}

export type MoneyInputError = 'PRICE_REQUIRED' | 'PRICE_NEGATIVE' | 'PRICE_FORMAT' | 'PRICE_PRECISION' | 'PRICE_TOO_LARGE';
export type MoneyInput = { minor: number; error?: undefined } | { minor?: undefined; error: MoneyInputError };

/** What the API stores in an int column. */
const MAX_MINOR = 2_147_483_647;

/**
 * The reverse of formatMoney for a typed price in major units: "80", "80.5", "80,50" (a comma is what ru and cs type),
 * "1 000" (a space only groups thousands). Works on the digits, not on floats, and never rounds: a price the currency
 * cannot hold is refused rather than changed. Three digits after a separator are refused unless the currency has three:
 * "1,000" and "1.000" are a thousand to some and one to others. Zeros past the digits of the currency change nothing.
 */
export function parseMoneyInput(text: string, currencyCode: string): MoneyInput {
  const typed = text.trim();
  if (!typed) return { error: 'PRICE_REQUIRED' };
  if (/^[-−–]/.test(typed)) return { error: 'PRICE_NEGATIVE' };
  const parts = /^(\d{1,3}(?:\s\d{3})+|\d*)(?:[.,](\d*))?$/.exec(typed);
  const whole = (parts?.[1] ?? '').replace(/\s/g, '');
  const fraction = parts?.[2] ?? '';
  if (!parts || (!whole && !fraction)) return { error: 'PRICE_FORMAT' };
  const digits = currencyFractionDigits(currencyCode);
  if (fraction.length === 3 && digits !== 3) return { error: 'PRICE_FORMAT' };
  if (/[1-9]/.test(fraction.slice(digits))) return { error: 'PRICE_PRECISION' };
  const significant = whole.replace(/^0+/, '');
  if (significant.length + digits > 10) return { error: 'PRICE_TOO_LARGE' };
  const minor = Number(`${significant}${fraction.slice(0, digits).padEnd(digits, '0')}`);
  return minor > MAX_MINOR ? { error: 'PRICE_TOO_LARGE' } : { minor };
}

/** Minor units as the text of a price field in major units, without grouping or a currency sign: 8000 -> "80.00". */
export function minorToInput(priceMinor: number, currencyCode: string): string {
  const digits = currencyFractionDigits(currencyCode);
  const text = String(priceMinor).padStart(digits + 1, '0');
  return digits === 0 ? text : `${text.slice(0, -digits)}.${text.slice(-digits)}`;
}
