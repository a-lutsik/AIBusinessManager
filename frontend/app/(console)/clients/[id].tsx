import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function ClientDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [data, setData] = useState<any>(null);
  useEffect(() => {
    if (id) {
      api.client(LUMEN_TENANT_ID, id).then(setData).catch(() => setData(null));
    }
  }, [id]);
  if (!data) {
    return <Text>{t('empty.clients')}</Text>;
  }
  return (
    <ScrollView>
      <Text style={styles.h1}>{data.client.displayName}</Text>
      <Text style={styles.muted}>{data.client.normalizedPhone}</Text>
      <View style={styles.badge}><Text>{t(`trust.${data.trust.effectiveLevel ?? data.trust.level}`)}</Text></View>
      {(data.trust.reasons ?? []).map((r: string) => <Text key={r} style={styles.muted}>{r}</Text>)}
      {(data.history ?? []).map((v: any) => (
        <View key={v.id} style={styles.card}>
          <Text>{v.serviceNameSnapshot} · {t(`status.${v.status}`)}</Text>
          <Text style={styles.muted}>{v.serviceStart}</Text>
        </View>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700' },
  muted: { color: tokens.color.muted },
  badge: { alignSelf: 'flex-start', backgroundColor: tokens.color.surface, padding: tokens.space.sm, borderRadius: tokens.radius.control, marginVertical: tokens.space.md },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, marginBottom: tokens.space.sm, borderRadius: tokens.radius.control },
});
