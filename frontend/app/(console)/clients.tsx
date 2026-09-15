import { useEffect, useState } from 'react';
import { Link } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function ClientsScreen() {
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<any[]>([]);

  useEffect(() => {
    const timer = setTimeout(() => {
      api.clients(LUMEN_TENANT_ID, q).then(setRows).catch(() => setRows([]));
    }, 300);
    return () => clearTimeout(timer);
  }, [q]);

  return (
    <ScrollView>
      <Text style={styles.h1}>{t('nav.clients')}</Text>
      <TextInput value={q} onChangeText={setQ} placeholder="Search" style={styles.input} />
      {rows.length === 0 ? <Text style={styles.muted}>{t('empty.clients')}</Text> : null}
      {rows.map((c) => (
        <Link key={c.id} href={`/clients/${c.id}`} asChild>
          <Pressable style={styles.card}>
            <Text style={styles.k}>{c.displayName}</Text>
            <Text style={styles.muted}>{c.normalizedPhone} · {c.completedVisitCount} visits</Text>
          </Pressable>
        </Link>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', color: tokens.color.ink, marginBottom: tokens.space.md },
  input: { borderWidth: 1, borderColor: tokens.color.line, padding: tokens.space.md, borderRadius: tokens.radius.control, marginBottom: tokens.space.md, backgroundColor: tokens.color.surface },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, borderRadius: tokens.radius.control, marginBottom: tokens.space.sm },
  k: { fontWeight: '700' },
  muted: { color: tokens.color.muted },
});
