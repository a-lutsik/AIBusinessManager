import { Link } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { api, LUMEN_TENANT_ID, type SpecialistView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function SpecialistsScreen() {
  const [rows, setRows] = useState<SpecialistView[]>([]);
  useEffect(() => {
    api.specialists(LUMEN_TENANT_ID).then(setRows).catch(() => setRows([]));
  }, []);

  return (
    <ScrollView contentContainerStyle={styles.pad}>
      <Text style={styles.h1}>{t('nav.specialists')}</Text>
      {rows.length === 0 ? <EmptyState title={t('empty.specialists')} /> : null}
      <View style={styles.grid}>
        {rows.map((s) => (
          <Link key={s.id} href={`/specialists/${s.id}`} asChild>
            <Pressable style={styles.card}>
              <View style={styles.header}>
                <View style={[styles.swatch, { backgroundColor: s.calendarColor || tokens.color.secondary }]} />
                <Text style={styles.name}>{s.displayName}</Text>
              </View>
              <View style={styles.flags}>
                <View style={[styles.flag, s.active !== false ? styles.flagOn : styles.flagOff]}>
                  <Text style={[styles.flagText, s.active !== false ? styles.flagTextOn : styles.flagTextOff]}>
                    {s.active !== false ? t('catalog.active') : t('catalog.inactive')}
                  </Text>
                </View>
              </View>
              <Text style={styles.cta}>{t('specialists.openMatrix')}</Text>
            </Pressable>
          </Link>
        ))}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  pad: { paddingBottom: tokens.space.xxl },
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.lg, fontFamily: 'Plus Jakarta Sans', color: tokens.color.ink },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.md },
  card: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderRadius: tokens.radius.card,
    borderWidth: 1,
    borderColor: tokens.color.line,
    minWidth: 220,
    flexGrow: 1,
    flexBasis: 240,
    maxWidth: 340,
  },
  header: { flexDirection: 'row', alignItems: 'center', gap: tokens.space.sm, marginBottom: tokens.space.md },
  swatch: { width: 14, height: 14, borderRadius: tokens.radius.pill },
  name: { fontWeight: '700', fontSize: 16, color: tokens.color.ink, fontFamily: 'Plus Jakarta Sans', flexShrink: 1 },
  flags: { flexDirection: 'row', marginBottom: tokens.space.md },
  flag: { paddingHorizontal: tokens.space.sm + 2, paddingVertical: 2, borderRadius: tokens.radius.pill },
  flagOn: { backgroundColor: `${tokens.color.primary}18` },
  flagOff: { backgroundColor: tokens.color.mist },
  flagText: { fontSize: 11, fontWeight: '600' },
  flagTextOn: { color: tokens.color.primary },
  flagTextOff: { color: tokens.color.muted },
  cta: { color: tokens.color.secondary, fontWeight: '600', fontSize: 13 },
});
