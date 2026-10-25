// ESLint for the Expo app (flat config, SDK 57): the Expo recommended rules plus the product rule from PRODUCT_PLAN
// section 9 - no user-visible text in component code, every string comes from the i18n catalogs (src/i18n/locales).
//
// The ban has three layers:
//   1. react/jsx-no-literals       - raw text children: <Text>Hello</Text>, {'Hello'}, {`Hello`}.
//   2. no-restricted-syntax        - what jsx-no-literals cannot see:
//        a. literals inside conditional / logical expressions that render as children: {ok ? 'Saved' : 'Failed'}, {x && 'Shown'}
//        b. string / template literals in props: DEFAULT-DENY. Every prop may carry a literal only if it is on the
//           NON_TEXT_PROPS allow-list below (style values, ids, hrefs, icon names, enums). A new text prop such as
//           suffix, text or emptyText is therefore caught without anyone having to remember it.
//        c. text keys in objects passed to props: options={{ title: 'Book' }}
//        d. imperative UI text: Alert.alert('Error', ...), alert(), confirm(), prompt(), ToastAndroid.show()
// A string counts as text when it contains a letter, so separators, digits and symbols ('·', '—', '24', '%') stay free.
const { defineConfig, globalIgnores } = require('eslint/config');
const expoConfig = require('eslint-config-expo/flat');

// Props whose string value is an identifier, an enum, a URL or a style token - never copy a user reads. Anything not
// listed here must not be given a string literal in JSX. Add a name here only when its value is not text.
const NON_TEXT_PROPS = [
  // identity and structure
  'key', 'ref', 'id', 'nativeID', 'testID', 'data-testid', 'name', 'type', 'role', 'as', 'lang', 'dir',
  // styling
  'style', 'className', 'contentContainerClassName', 'color', 'size', 'variant', 'tone', 'fill',
  // navigation and links
  'href', 'to', 'src', 'rel', 'target', 'download',
  // accessibility roles and platform enums
  'accessibilityRole', 'accessibilityLiveRegion', 'importantForAccessibility', 'keyboardType', 'inputMode',
  'autoComplete', 'autoCapitalize', 'autoCorrect', 'textContentType', 'returnKeyType', 'enterKeyHint',
  'animationType', 'presentationStyle', 'resizeMode', 'behavior', 'orientation', 'direction', 'mode', 'pointerEvents',
  'statusBarStyle', 'keyboardShouldPersistTaps', 'showsVerticalScrollIndicator',
  // document head (app/+html.tsx)
  'charSet', 'httpEquiv', 'crossOrigin', 'content', 'media', 'sizes',
].join('|');

// Keys of objects handed to a prop (options={{ ... }}, options={[{ value, label }]}) that hold text.
const TEXT_KEYS = [
  'title', 'label', 'placeholder', 'description', 'subtitle', 'hint', 'message', 'caption', 'text', 'alt',
  'actionLabel', 'emptyText', 'suffix', 'prefix', 'tabBarLabel', 'headerTitle', 'headerBackTitle',
  'accessibilityLabel', 'accessibilityHint', 'aria-label',
].join('|');

const HAS_LETTER = '/\\p{L}/u';
const STRING = `Literal[value=${HAS_LETTER}]`;
const TEMPLATE = `TemplateLiteral > TemplateElement[value.cooked=${HAS_LETTER}]`;
const FLOW = ':matches(ConditionalExpression, LogicalExpression)';

// A literal that ends up as the rendered value of an expression container: the branches of ?: and the right side of
// && || ??, nested up to four levels deep. `base` selects the container.
function valuePositions(base) {
  const selectors = [];
  for (let depth = 0; depth <= 3; depth += 1) {
    const chain = `${base}${` > ${FLOW}`.repeat(depth)}`;
    selectors.push(
      `${chain} > ConditionalExpression > ${STRING}.consequent`,
      `${chain} > ConditionalExpression > ${STRING}.alternate`,
      `${chain} > LogicalExpression > ${STRING}.right`,
      `${chain} > ConditionalExpression > TemplateLiteral.consequent > TemplateElement[value.cooked=${HAS_LETTER}]`,
      `${chain} > ConditionalExpression > TemplateLiteral.alternate > TemplateElement[value.cooked=${HAS_LETTER}]`,
      `${chain} > LogicalExpression > TemplateLiteral.right > TemplateElement[value.cooked=${HAS_LETTER}]`
    );
  }
  return selectors;
}

const CHILD_CONTAINER = ':matches(JSXElement, JSXFragment) > JSXExpressionContainer';
const TEXT_PROP = `JSXAttribute:not([name.name=/^(${NON_TEXT_PROPS})$/])`;

const MESSAGE_CHILD = 'Hardcoded user-visible text in JSX children: use t("...") from src/i18n instead of a string literal.';
const MESSAGE_PROP = 'Hardcoded user-visible text in a prop (any prop not on the NON_TEXT_PROPS allow-list in eslint.config.js): use t("...") from src/i18n.';
const MESSAGE_OPTIONS = 'Hardcoded user-visible text in an object passed to a prop: use t("...") from src/i18n instead of a string literal.';
const MESSAGE_IMPERATIVE = 'Hardcoded user-visible text in an imperative UI call (Alert/alert/confirm/prompt/Toast): use t("...") from src/i18n.';

const restrictedSyntax = [
  ...valuePositions(CHILD_CONTAINER).map((selector) => ({ selector, message: MESSAGE_CHILD })),
  // <Button label="Save" />, <Button label={'Save'} />, <Button label={`Save`} />
  { selector: `${TEXT_PROP} > ${STRING}`, message: MESSAGE_PROP },
  { selector: `${TEXT_PROP} > JSXExpressionContainer > ${STRING}`, message: MESSAGE_PROP },
  { selector: `${TEXT_PROP} > JSXExpressionContainer > ${TEMPLATE}`, message: MESSAGE_PROP },
  // <Button label={ok ? 'Saved' : 'Failed'} />
  ...valuePositions(`${TEXT_PROP} > JSXExpressionContainer`).map((selector) => ({ selector, message: MESSAGE_PROP })),
  // <Stack.Screen options={{ title: 'Book' }} />, <Segment options={[{ value: 1, label: 'Monthly' }]} />
  {
    selector: `JSXAttribute Property[key.name=/^(${TEXT_KEYS})$/] > ${STRING}`,
    message: MESSAGE_OPTIONS,
  },
  {
    selector: `JSXAttribute Property[key.name=/^(${TEXT_KEYS})$/] > ${TEMPLATE}`,
    message: MESSAGE_OPTIONS,
  },
  // Alert.alert('Error', 'Could not save'), window.alert(...), alert(...), ToastAndroid.show('Saved', ...)
  ...[
    "CallExpression[callee.object.name='Alert'][callee.property.name='alert']",
    "CallExpression[callee.object.name='ToastAndroid'][callee.property.name='show']",
    "CallExpression[callee.object.name='window'][callee.property.name=/^(alert|confirm|prompt)$/]",
    'CallExpression[callee.name=/^(alert|confirm|prompt)$/]',
  ].flatMap((call) => [
    { selector: `${call} > ${STRING}`, message: MESSAGE_IMPERATIVE },
    { selector: `${call} > ${TEMPLATE}`, message: MESSAGE_IMPERATIVE },
    // Alert buttons: Alert.alert(t('a'), t('b'), [{ text: 'OK' }])
    { selector: `${call} Property[key.name=/^(text|title|message)$/] > ${STRING}`, message: MESSAGE_IMPERATIVE },
  ]),
];

module.exports = defineConfig([
  globalIgnores(['dist/**', '.expo/**', 'node_modules/**', 'web-build/**', 'expo-env.d.ts', 'nativewind-env.d.ts']),
  expoConfig,
  {
    files: ['**/*.{js,jsx,ts,tsx}'],
    rules: {
      // Raw text in JSX: <Text>Hello</Text>, {'Hello'} and {`Hello`} as children. Pure punctuation or whitespace used as
      // a separator between translated parts is allowed; everything else must go through t(). Props are covered by
      // no-restricted-syntax below (jsx-no-literals would flag every variant="ghost" and href="/x").
      'react/jsx-no-literals': [
        'error',
        {
          noStrings: true,
          ignoreProps: true,
          allowedStrings: ['·', '—', '–', '•', '…', '#', ':', '/', '|', '(', ')', ',', '.', '+', '-', '%', '*', '&nbsp;'],
        },
      ],
      'no-restricted-syntax': ['error', ...restrictedSyntax],
    },
  },
]);
