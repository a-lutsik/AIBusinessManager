// Self-test of the literal ban (PRODUCT_PLAN section 9): feeds snippets through the real ESLint config in memory
// (no fixture files) and fails when a hardcoded text is not reported or an allowed one is.
// Run: npm run lint:selftest
import { ESLint } from 'eslint';

const eslint = new ESLint({ cwd: new URL('..', import.meta.url).pathname });

const MUST_REPORT = {
  'text child': `export const A = () => <Text>Hello</Text>;`,
  'string expression child': `export const A = () => <Text>{'Hello'}</Text>;`,
  'template literal child': 'export const A = () => <Text>{`Hello`}</Text>;',
  'ternary branches in a child': `export const A = () => <Text>{ok ? 'Saved' : 'Failed'}</Text>;`,
  'ternary with one translated branch': `export const A = () => <Text>{ok ? t('a') : 'Failed'}</Text>;`,
  'nested ternary in a child': `export const A = () => <Text>{a ? t('a') : b ? t('b') : 'Other'}</Text>;`,
  'logical and in a child': `export const A = () => <Text>{shown && 'Shown'}</Text>;`,
  'nullish fallback in a child': `export const A = () => <Text>{name ?? 'Anonymous'}</Text>;`,
  'template branch in a child': 'export const A = () => <Text>{ok ? `Saved` : t("b")}</Text>;',
  'static words in a template child': 'export const A = () => <Text>{`Total: ${n} items`}</Text>;',
  'text prop': `export const A = () => <Button label="Save" />;`,
  'text prop in an expression': `export const A = () => <Button label={'Save'} />;`,
  'text prop as template literal': 'export const A = () => <Button label={`Save`} />;',
  'text prop as template with words': 'export const A = () => <Button label={`${t("a")} now`} />;',
  'text prop ternary': `export const A = () => <Button label={ok ? 'Saved' : t('b')} />;`,
  'text prop logical': `export const A = () => <Button label={custom || 'Save'} />;`,
  'unlisted text prop suffix': `export const A = () => <NumberInput suffix="min" />;`,
  'unlisted text prop emptyText': `export const A = () => <List emptyText="Nothing here" />;`,
  'unlisted text prop windowLabel': `export const A = () => <MetricCard windowLabel="Last 30 days" />;`,
  'unlisted text prop error': `export const A = () => <Field error="Required" />;`,
  'accessibility label': `export const A = () => <View accessibilityLabel="Close" />;`,
  'screen option title': `export const A = () => <Stack.Screen options={{ title: 'Book' }} />;`,
  'option label in an array': `export const A = () => <Segment options={[{ value: 1, label: 'Monthly' }]} />;`,
  'Alert.alert title': `export const f = () => Alert.alert('Error', t('x'));`,
  'Alert.alert message': `export const f = () => Alert.alert(t('x'), 'Could not save');`,
  'Alert.alert template': 'export const f = () => Alert.alert(t("x"), `Could not save`);',
  'Alert.alert button text': `export const f = () => Alert.alert(t('x'), t('y'), [{ text: 'OK' }]);`,
  'window.alert': `export const f = () => window.alert('Saved');`,
  confirm: `export const f = () => confirm('Delete?');`,
};

const MUST_ALLOW = {
  'translated child': `export const A = () => <Text>{t('book.submit')}</Text>;`,
  'translated ternary': `export const A = () => <Text>{ok ? t('a') : t('b')}</Text>;`,
  'ternary with null': `export const A = () => <Text>{ok ? t('a') : null}</Text>;`,
  'separator punctuation': `export const A = () => <Text>{a} · {b} — {c}</Text>;`,
  'punctuation fallback': `export const A = () => <Text>{value ?? '—'}</Text>;`,
  'punctuation in a ternary': `export const A = () => <Text>{ok ? t('a') : '…'}</Text>;`,
  'digits and symbols only': 'export const A = () => <Text>{ok ? `${n}%` : null}</Text>;',
  'newline escape in a template': 'export const A = () => <Text>{ok ? `\\n→ ${x}` : null}</Text>;',
  'translated prop': `export const A = () => <Button label={t('book.submit')} />;`,
  'translated prop ternary': `export const A = () => <Button label={ok ? t('a') : t('b')} />;`,
  'translated prop with fallback': `export const A = () => <Button label={label ?? t('a')} />;`,
  testID: `export const A = () => <View testID="row" />;`,
  'style string values': `export const A = () => <View style={{ flexDirection: 'row', justifyContent: ok ? 'center' : 'flex-start' }} />;`,
  href: `export const A = () => <Link href="/dashboard" />;`,
  'href template': 'export const A = () => <Link href={`/book/${slug}`} />;',
  'icon name': `export const A = () => <MaterialIcon name="check" color="#FFFFFF" />;`,
  'icon name ternary': `export const A = () => <MaterialIcon name={dark ? 'light_mode' : 'dark_mode'} />;`,
  variant: `export const A = () => <Button variant="ghost" label={t('a')} />;`,
  'keyboard and role enums': `export const A = () => <TextInput keyboardType="number-pad" accessibilityRole="button" />;`,
  className: `export const A = () => <View className="flex-1 bg-white" />;`,
  'string logic in a callback': `export const A = () => <Pressable onPress={() => go(ok ? 'a' : 'b')} />;`,
  'string comparison in a child': `export const A = () => <View>{status === 'PENDING' ? <X /> : null}</View>;`,
  'translated Alert': `export const f = () => Alert.alert(t('a'), t('b'), [{ text: t('c') }]);`,
  'Alert button style enum': `export const f = () => Alert.alert(t('a'), t('b'), [{ text: t('c'), style: 'cancel' }]);`,
};

async function messagesFor(code) {
  const [result] = await eslint.lintText(code, { filePath: 'selftest.tsx' });
  return result.messages.filter((m) => m.ruleId === 'react/jsx-no-literals' || m.ruleId === 'no-restricted-syntax');
}

const failures = [];
for (const [name, code] of Object.entries(MUST_REPORT)) {
  if ((await messagesFor(code)).length === 0) failures.push(`not reported: ${name}`);
}
for (const [name, code] of Object.entries(MUST_ALLOW)) {
  const found = await messagesFor(code);
  if (found.length > 0) failures.push(`wrongly reported: ${name} (${found[0].message})`);
}

if (failures.length > 0) {
  console.error(`lint self-test failed:\n${failures.map((f) => `  - ${f}`).join('\n')}`);
  process.exit(1);
}
console.log(`lint self-test ok (${Object.keys(MUST_REPORT).length} reported, ${Object.keys(MUST_ALLOW).length} allowed)`);
