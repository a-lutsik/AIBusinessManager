import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function ServicesScreen() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    api.services(LUMEN_TENANT_ID).then(setRows).catch(() => setRows([]));
  }, []);
  return (
    <ScrollView>
      <Text style={styles.h1}>{t('nav.services')}</Text>
      {rows.length === 0 ? <Text style={styles.muted}>{t('empty.services')}</Text> : null}
      {rows.map((s) => (
        <View key={s.id} style={styles.card}>
          <Text style={styles.k}>{s.name}</Text>
          <Text style={styles.muted}>{s.durationMinutes} min · {s.priceMinor} minor</Text>
        </View>
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
