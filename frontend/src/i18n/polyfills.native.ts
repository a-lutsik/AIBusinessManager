import { shouldPolyfill } from '@formatjs/intl-pluralrules/should-polyfill.js';

/**
 * Hermes implements Intl.Collator, Intl.NumberFormat, Intl.DateTimeFormat and Intl.getCanonicalLocales only; it has
 * no Intl.PluralRules (facebook/hermes doc/IntlAPIs.md), and intl-messageformat needs it for every `plural` message.
 * The polyfill is loaded only when the engine lacks PluralRules or lacks data for one of our five locales, and it
 * carries CLDR plural data for exactly those locales (ru/uk/cs have 3-4 forms, ka and en two). Adding a locale means
 * adding its data line here.
 */
const LOCALES = ['en', 'ru', 'ka', 'uk', 'cs'];

// The requires are deliberately lazy: they must run only when the engine needs the polyfill, which static imports
// cannot express.
/* eslint-disable @typescript-eslint/no-require-imports */
if (LOCALES.some((locale) => shouldPolyfill(locale))) {
  require('@formatjs/intl-pluralrules/polyfill-force.js');
  require('@formatjs/intl-pluralrules/locale-data/en.js');
  require('@formatjs/intl-pluralrules/locale-data/ru.js');
  require('@formatjs/intl-pluralrules/locale-data/ka.js');
  require('@formatjs/intl-pluralrules/locale-data/uk.js');
  require('@formatjs/intl-pluralrules/locale-data/cs.js');
}
/* eslint-enable @typescript-eslint/no-require-imports */
