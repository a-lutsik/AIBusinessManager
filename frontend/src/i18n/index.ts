import './polyfills';

import { getLocales } from 'expo-localization';
import { IntlMessageFormat } from 'intl-messageformat';
import { Platform } from 'react-native';
import { useSyncExternalStore } from 'react';

import { loadStoredLocale, saveStoredLocale } from './localeStorage';
import en from './locales/en.json';
import ru from './locales/ru.json';
import ka from './locales/ka.json';
import uk from './locales/uk.json';
import cs from './locales/cs.json';

export const supportedLocales = ['en', 'ru', 'ka', 'uk', 'cs'] as const;
export type AppLocale = (typeof supportedLocales)[number];

/** Values a message may interpolate: plain strings and numbers (plural, number), dates (date, time). */
export type MessageParams = Record<string, string | number | boolean | Date | null | undefined>;

type Catalog = Record<string, string>;

const catalogs: Record<AppLocale, Catalog> = {
  en: en as Catalog,
  ru: ru as Catalog,
  ka: ka as Catalog,
  uk: uk as Catalog,
  cs: cs as Catalog,
};

/** Locales whose catalog must be complete in the MVP (product plan section 9); the others fall back to en by design. */
const completeLocales: readonly AppLocale[] = ['en', 'ru'];

function isAppLocale(value: string | null | undefined): value is AppLocale {
  return value != null && (supportedLocales as readonly string[]).includes(value);
}

function resolveDeviceLocale(): AppLocale {
  const tag = getLocales()[0]?.languageCode?.toLowerCase();
  return isAppLocale(tag) ? tag : 'en';
}

let locale: AppLocale = resolveDeviceLocale();
const listeners = new Set<() => void>();

function notify() {
  listeners.forEach((listener) => listener());
}

function applyDocumentLanguage(next: AppLocale) {
  // Lets the browser pick language-specific fonts and hyphenation (Georgian in particular).
  if (Platform.OS === 'web' && typeof document !== 'undefined') {
    document.documentElement.lang = next;
  }
}

/** The user's explicit choice: applied at once, remembered across restarts. */
export function setLocale(next: AppLocale) {
  if (next === locale) return;
  locale = next;
  applyDocumentLanguage(next);
  notify();
  void saveStoredLocale(next);
}

/**
 * Applies the remembered choice, when there is one, over the device language. Call once at startup, before the first
 * screen renders; a stored value that is not a supported locale is ignored.
 */
export async function restoreLocale(): Promise<void> {
  const stored = await loadStoredLocale();
  if (isAppLocale(stored) && stored !== locale) {
    locale = stored;
    notify();
  }
  applyDocumentLanguage(locale);
}

export function currentLocale(): AppLocale {
  return locale;
}

/** Re-renders the calling component when the locale changes; returns the current locale. */
export function useLocale(): AppLocale {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    currentLocale,
    currentLocale
  );
}

const formatters = new Map<string, IntlMessageFormat>();
const warned = new Set<string>();

function warnOnce(id: string, message: string) {
  if (__DEV__ && !warned.has(id)) {
    warned.add(id);
    console.warn(`[i18n] ${message}`);
  }
}

function formatterFor(forLocale: AppLocale, message: string): IntlMessageFormat {
  const cacheKey = `${forLocale}\u0000${message}`;
  let formatter = formatters.get(cacheKey);
  if (!formatter) {
    formatter = new IntlMessageFormat(message, forLocale);
    formatters.set(cacheKey, formatter);
  }
  return formatter;
}

/**
 * Message for a key in ICU MessageFormat: `t('client.visits', { count: 3 })`.
 * Lookup chain: current locale, then en, then the key itself. A key missing everywhere (and, for ru, a key missing
 * from the complete catalogs) logs a warning in development, once per key and locale.
 */
export function t(key: string, params?: MessageParams): string {
  let forLocale = locale;
  let message = catalogs[forLocale][key];
  if (message === undefined) {
    if (completeLocales.includes(forLocale) || catalogs.en[key] === undefined) {
      warnOnce(`${forLocale}:${key}`, `missing key "${key}" for locale "${forLocale}"`);
    }
    forLocale = 'en';
    message = catalogs.en[key];
  }
  if (message === undefined) {
    return key;
  }
  try {
    const formatted = formatterFor(forLocale, message).format(params as Record<string, never> | undefined);
    return Array.isArray(formatted) ? formatted.join('') : String(formatted);
  } catch (error) {
    warnOnce(`format:${forLocale}:${key}`, `cannot format "${key}" (${forLocale}): ${String(error)}`);
    return message;
  }
}

const percentFormatters = new Map<string, Intl.NumberFormat>();

/**
 * A fraction as a percentage in the current locale's own number format: `formatPercent(0.125)` is "12.5%" in en and
 * "12,5 %" in ru. `signed` always writes the sign ("+12.5%"), for deltas.
 */
export function formatPercent(fraction: number, options: { signed?: boolean } = {}): string {
  const signed = options.signed === true;
  const cacheKey = `${locale}:${signed}`;
  let formatter = percentFormatters.get(cacheKey);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'percent',
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
      signDisplay: signed ? 'always' : 'auto',
    });
    percentFormatters.set(cacheKey, formatter);
  }
  return formatter.format(fraction);
}
