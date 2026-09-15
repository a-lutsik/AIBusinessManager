import { getLocales } from 'expo-localization';

import en from './locales/en.json';
import ru from './locales/ru.json';
import ka from './locales/ka.json';
import uk from './locales/uk.json';
import cs from './locales/cs.json';

export const supportedLocales = ['en', 'ru', 'ka', 'uk', 'cs'] as const;
export type AppLocale = (typeof supportedLocales)[number];

type Catalog = Record<string, string>;

const catalogs: Record<AppLocale, Catalog> = {
  en: en as Catalog,
  ru: ru as Catalog,
  ka: ka as Catalog,
  uk: uk as Catalog,
  cs: cs as Catalog,
};

function resolveDeviceLocale(): AppLocale {
  const tag = getLocales()[0]?.languageCode?.toLowerCase();
  if (tag === 'ru' || tag === 'ka' || tag === 'uk' || tag === 'cs') {
    return tag;
  }
  return 'en';
}

let locale: AppLocale = resolveDeviceLocale();

export function setLocale(next: AppLocale) {
  locale = next;
}

export function t(key: string): string {
  return catalogs[locale][key] ?? catalogs.en[key] ?? key;
}

export function currentLocale(): string {
  return locale;
}
