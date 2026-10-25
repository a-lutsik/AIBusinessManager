// Validates the message catalogs: every message is valid ICU MessageFormat, every locale has only keys that exist in
// en, uses the same arguments as the en message, and every plural lists all CLDR categories of its locale.
// Run: npm run i18n:check (Node 22 with full ICU).
import { readFileSync } from 'node:fs';
import { parse, TYPE } from '@formatjs/icu-messageformat-parser';

const LOCALES = ['en', 'ru', 'ka', 'uk', 'cs'];
const COMPLETE = ['en', 'ru'];
const catalogs = Object.fromEntries(
  LOCALES.map((l) => [l, JSON.parse(readFileSync(new URL(`../src/i18n/locales/${l}.json`, import.meta.url), 'utf8'))])
);

const errors = [];

function walk(elements, visit) {
  for (const el of elements) {
    visit(el);
    if (el.type === TYPE.plural || el.type === TYPE.select) {
      for (const option of Object.values(el.options)) walk(option.value, visit);
    }
    if (el.type === TYPE.tag) walk(el.children, visit);
  }
}

function describe(message, locale, key) {
  let ast;
  try {
    ast = parse(message);
  } catch (e) {
    errors.push(`${locale}:${key}: not valid ICU MessageFormat (${e.message})`);
    return null;
  }
  const args = new Set();
  const plurals = [];
  walk(ast, (el) => {
    if (el.type === TYPE.literal || el.type === TYPE.pound) return;
    if (el.value) args.add(el.value);
    if (el.type === TYPE.plural) plurals.push(el);
  });
  return { args: [...args].sort().join(','), plurals };
}

for (const locale of LOCALES) {
  const required = new Intl.PluralRules(locale).resolvedOptions().pluralCategories;
  for (const [key, message] of Object.entries(catalogs[locale])) {
    if (!(key in catalogs.en)) errors.push(`${locale}:${key}: key does not exist in en`);
    const info = describe(message, locale, key);
    if (!info) continue;
    const reference = key in catalogs.en ? describe(catalogs.en[key], 'en', key) : null;
    if (reference && reference.args !== info.args) {
      errors.push(`${locale}:${key}: arguments [${info.args}] differ from en [${reference.args}]`);
    }
    for (const plural of info.plurals) {
      const missing = required.filter((c) => !(c in plural.options));
      if (missing.length) errors.push(`${locale}:${key}: plural lacks ${missing.join(', ')}`);
      if (!('other' in plural.options)) errors.push(`${locale}:${key}: plural lacks other`);
    }
  }
}

for (const locale of COMPLETE) {
  const missing = Object.keys(catalogs.en).filter((k) => !(k in catalogs[locale]));
  if (missing.length) errors.push(`${locale}: missing ${missing.length} key(s): ${missing.slice(0, 5).join(', ')}`);
}

for (const locale of LOCALES) {
  console.log(`${locale}: ${Object.keys(catalogs[locale]).length}/${Object.keys(catalogs.en).length} keys`);
}
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log('i18n catalogs OK');
