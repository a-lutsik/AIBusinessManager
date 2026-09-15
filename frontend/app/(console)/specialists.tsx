import { Link } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function SpecialistsScreen() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    api.specialists(LUMEN_TENANT_ID).then(setRows).catch(() => setRows([]));
  }, []);
  return (
    <ScrollView>
      <Text style={styles.h1}>{t('nav.specialists')}</Text>
      {rows.map((s) => (
        <Link key={s.id} href={`/specialists/${s.id}`} asChild>
          <Pressable style={styles.card}>
            <Text style={styles.k}>{s.displayName}</Text>
            <Text style={styles.muted}>{s.calendarColor}</Text>
          </Pressable>
        </Link>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.md },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, marginBottom: tokens.space.sm, borderRadius: tokens.radius.control },
  k: { fontWeight: '700' },
  muted: { color: tokens.color.muted },
});
