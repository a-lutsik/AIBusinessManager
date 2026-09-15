import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { statusColor, tokens } from '@/src/theme/tokens';

export default function DashboardScreen() {
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    api.dashboard(LUMEN_TENANT_ID)
      .then(setData)
      .catch((e) => setError(String(e.message ?? e)));
  };

  useEffect(() => {
    load();
  }, []);

  return (
    <ScrollView>
      <Text style={styles.h1}>{t('nav.dashboard')}</Text>
      {error ? <Text style={styles.err}>{error}</Text> : null}
      <View style={styles.grid}>
        {(data?.metrics ?? []).map((m: any) => (
          <View key={m.key} style={styles.card}>
            <Text style={styles.k}>{m.key}</Text>
            <Text style={styles.v}>{m.insufficientData ? t('metric.pending') : String(m.value)}</Text>
            <Text style={styles.muted}>{m.explanation}</Text>
          </View>
        ))}
      </View>
      <Text style={styles.h2}>{t('dashboard.tasks')}</Text>
      {(data?.tasks ?? []).length === 0 ? <Text style={styles.muted}>{t('empty.tasks')}</Text> : null}
      {(data?.tasks ?? []).map((task: any) => (
        <View key={task.id} style={styles.card}>
          <Text style={styles.k}>{task.title}</Text>
          <Text>{task.body}</Text>
          <Pressable style={styles.btn} onPress={() => api.completeTask(LUMEN_TENANT_ID, task.id).then(load).catch((e) => setError(String(e.message ?? e)))}>
            <Text style={styles.btnText}>{t('action.done')}</Text>
          </Pressable>
        </View>
      ))}
      <Text style={styles.h2}>{t('dashboard.signals')}</Text>
      {(data?.signals ?? []).map((s: any) => (
        <View key={s.key + s.title} style={styles.card}>
          <Text style={styles.k}>{s.title}</Text>
          <Text style={styles.muted}>{s.evidence}</Text>
          <Text>{s.suggestedAction}</Text>
        </View>
      ))}
      <Text style={styles.h2}>{t('dashboard.today')}</Text>
      {(data?.today ?? []).length === 0 ? <Text style={styles.muted}>{t('empty.calendar')}</Text> : null}
      {(data?.today ?? []).map((v: any) => (
        <View key={v.id} style={[styles.card, { borderLeftColor: statusColor(v.status), borderLeftWidth: 4 }]}>
          <Text>{v.serviceNameSnapshot} · {v.clientDisplayName}</Text>
          <Text style={styles.muted}>{t(`status.${v.status}`)} · {v.serviceStart}</Text>
        </View>
      ))}
      <Pressable onPress={() => api.recalculate(LUMEN_TENANT_ID).then(load).catch((e) => setError(String(e.message ?? e)))} style={styles.btn}>
        <Text style={styles.btnText}>{t('metric.recalculate')}</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', color: tokens.color.ink, marginBottom: tokens.space.md },
  h2: { fontSize: 18, fontWeight: '600', marginTop: tokens.space.lg, marginBottom: tokens.space.sm, color: tokens.color.ink },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.md },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, borderRadius: tokens.radius.control, minWidth: 220, marginBottom: tokens.space.sm },
  k: { fontWeight: '700', color: tokens.color.ink },
  v: { fontSize: 18, marginVertical: 4, color: tokens.color.accent },
  muted: { color: tokens.color.muted },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.md },
  btn: { marginTop: tokens.space.lg, backgroundColor: tokens.color.accent, padding: tokens.space.md, borderRadius: tokens.radius.control, alignSelf: 'flex-start' },
  btnText: { color: '#ecfdf5', fontWeight: '600' },
});
