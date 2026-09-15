import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function SpecialistMatrix() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    if (id) {
      api.matrix(LUMEN_TENANT_ID, id).then(setRows).catch(() => setRows([]));
    }
  }, [id]);
  return (
    <ScrollView>
      <Text style={styles.h1}>{t('nav.specialists')}</Text>
      {rows.map((r) => (
        <View key={r.id} style={styles.card}>
          <Text>Service {r.serviceId}</Text>
          <Text style={styles.muted}>offered={String(r.offered)} duration={r.durationMinutesOverride ?? 'default'}</Text>
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.md },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, marginBottom: tokens.space.sm, borderRadius: tokens.radius.control },
  muted: { color: tokens.color.muted },
});
