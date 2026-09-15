import { useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { statusColor, tokens } from '@/src/theme/tokens';

export default function CalendarScreen() {
  const [mode, setMode] = useState<'day' | 'week'>('week');
  const [visits, setVisits] = useState<any[]>([]);
  const [specialists, setSpecialists] = useState<any[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const [selected, setSelected] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const range = useMemo(() => {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const end = new Date(start);
    end.setDate(end.getDate() + (mode === 'week' ? 7 : 1));
    return { from: start.toISOString(), to: end.toISOString() };
  }, [mode]);

  const load = () => {
    api.calendar(LUMEN_TENANT_ID, range.from, range.to, filter ?? undefined)
      .then(setVisits)
      .catch((e) => setError(String(e.message ?? e)));
  };

  useEffect(() => {
    api.specialists(LUMEN_TENANT_ID).then(setSpecialists).catch(() => setSpecialists([]));
  }, []);

  useEffect(() => {
    load();
  }, [range.from, range.to, filter]);

  const actionsFor = (status: string) => {
    if (status === 'PENDING') return ['CONFIRMED', 'CANCELLED_BY_BUSINESS'];
    if (status === 'CONFIRMED') return ['COMPLETED', 'NO_SHOW', 'CANCELLED_BY_BUSINESS'];
    if (status === 'NO_SHOW') return ['COMPLETED'];
    if (status === 'COMPLETED') return ['NO_SHOW'];
    return [];
  };

  return (
    <View style={styles.wrap}>
      <View style={{ flex: 1 }}>
        <Text style={styles.h1}>{t('nav.calendar')}</Text>
        <View style={styles.row}>
          <Pressable onPress={() => setMode('day')}><Text style={styles.chip}>{t('calendar.day')}</Text></Pressable>
          <Pressable onPress={() => setMode('week')}><Text style={styles.chip}>{t('calendar.week')}</Text></Pressable>
          <Pressable onPress={() => setFilter(null)}><Text style={styles.chip}>{t('calendar.allMasters')}</Text></Pressable>
          {specialists.map((s) => (
            <Pressable key={s.id} onPress={() => setFilter(s.id)}>
              <Text style={[styles.chip, filter === s.id && styles.sel]}>{s.displayName}</Text>
            </Pressable>
          ))}
        </View>
        {error ? <Text style={styles.err}>{error}</Text> : null}
        {visits.length === 0 ? <Text style={styles.muted}>{t('empty.calendar')}</Text> : null}
        <ScrollView>
          {visits.map((v) => (
            <Pressable
              key={v.id}
              onPress={() => setSelected(v)}
              style={[styles.block, { borderLeftColor: statusColor(v.status) }]}
            >
              <Text style={styles.k}>{v.serviceNameSnapshot}</Text>
              <Text>{v.clientDisplayName}</Text>
              <Text style={styles.muted}>{new Date(v.serviceStart).toLocaleString()} · {t(`status.${v.status}`)}</Text>
            </Pressable>
          ))}
        </ScrollView>
      </View>
      {selected ? (
        <View style={styles.panel}>
          <Text style={styles.h2}>{selected.serviceNameSnapshot}</Text>
          <Text>{selected.clientDisplayName}</Text>
          <Text style={styles.muted}>{t(`status.${selected.status}`)}</Text>
          <Text style={styles.muted}>{selected.clientPhone}</Text>
          {selected.note ? <Text>{selected.note}</Text> : null}
          {actionsFor(selected.status).map((st) => (
            <Pressable
              key={st}
              style={styles.btn}
              onPress={() =>
                api.transition(LUMEN_TENANT_ID, selected.id, st).then((next) => {
                  setSelected(next);
                  load();
                })
              }
            >
              <Text style={styles.btnText}>{t(`status.${st}`)}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, flexDirection: 'row', gap: tokens.space.lg },
  h1: { fontSize: 28, fontWeight: '700', color: tokens.color.ink },
  h2: { fontSize: 20, fontWeight: '600', color: tokens.color.ink },
  row: { flexDirection: 'row', gap: tokens.space.md, marginVertical: tokens.space.md, flexWrap: 'wrap' },
  chip: { color: tokens.color.accent, fontWeight: '600' },
  sel: { textDecorationLine: 'underline' },
  muted: { color: tokens.color.muted },
  err: { color: tokens.color.status.noShow },
  block: {
    padding: tokens.space.md,
    borderLeftWidth: 4,
    marginBottom: tokens.space.sm,
    backgroundColor: tokens.color.surface,
    borderRadius: tokens.radius.control,
  },
  k: { fontWeight: '700' },
  panel: { width: 320, backgroundColor: tokens.color.surface, padding: tokens.space.lg, borderRadius: tokens.radius.control },
  btn: { marginTop: tokens.space.sm, backgroundColor: tokens.color.accent, padding: tokens.space.sm, borderRadius: tokens.radius.control },
  btnText: { color: '#ecfdf5', textAlign: 'center' },
});
