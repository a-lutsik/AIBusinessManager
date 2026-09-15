import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

function parseField(text: string, original: unknown): unknown {
  if (typeof original === 'boolean') {
    const lower = text.trim().toLowerCase();
    if (lower === 'true') return true;
    if (lower === 'false') return false;
    return text;
  }
  if (typeof original === 'number') {
    if (text.trim() === '') return text;
    const n = Number(text);
    return Number.isNaN(n) ? text : n;
  }
  return text;
}

export default function RulesScreen() {
  const [rules, setRules] = useState<any>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.rules(LUMEN_TENANT_ID)
      .then((r) => {
        setRules(r);
        setDraft(Object.fromEntries(Object.entries(r).map(([k, v]) => [k, String(v)])));
      })
      .catch(() => setRules(null));
  }, []);

  const save = async () => {
    if (!rules) return;
    setError(null);
    const body: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(draft)) {
      body[k] = parseField(v, rules[k]);
    }
    try {
      await api.saveRules(LUMEN_TENANT_ID, body);
    } catch (e: any) {
      setError(String(e.message ?? e));
    }
  };

  if (!rules) {
    return <Text>{t('nav.rules')}</Text>;
  }
  return (
    <ScrollView>
      <Text style={styles.h1}>{t('nav.rules')}</Text>
      {error ? <Text style={styles.err}>{error}</Text> : null}
      {Object.keys(rules).map((k) => (
        <View key={k} style={styles.row}>
          <Text style={styles.k}>{k}</Text>
          <TextInput
            style={styles.input}
            value={draft[k]}
            onChangeText={(text) => setDraft((prev) => ({ ...prev, [k]: text }))}
          />
        </View>
      ))}
      <Pressable style={styles.btn} onPress={save}>
        <Text style={styles.btnText}>Save</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.md },
  row: { marginBottom: tokens.space.sm },
  k: { color: tokens.color.muted },
  input: { borderWidth: 1, borderColor: tokens.color.line, padding: tokens.space.sm, borderRadius: tokens.radius.control, backgroundColor: tokens.color.surface },
  btn: { marginTop: tokens.space.lg, backgroundColor: tokens.color.accent, padding: tokens.space.md, borderRadius: tokens.radius.control, alignSelf: 'flex-start' },
  btnText: { color: '#ecfdf5', fontWeight: '600' },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.sm },
});
