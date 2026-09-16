import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { api, LUMEN_TENANT_ID, type MeView, type ServiceView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';
import { formatMoney } from '@/src/utils/money';

export default function ServicesScreen() {
  const [rows, setRows] = useState<ServiceView[]>([]);
  const [me, setMe] = useState<MeView | null>(null);

  useEffect(() => {
    api.services(LUMEN_TENANT_ID).then(setRows).catch(() => setRows([]));
    api.me(LUMEN_TENANT_ID).then(setMe).catch(() => setMe(null));
  }, []);

  const currency = me?.currencyCode ?? 'GEL';

  return (
    <ScrollView contentContainerStyle={styles.pad}>
      <Text style={styles.h1}>{t('nav.services')}</Text>
      {rows.length === 0 ? <EmptyState title={t('empty.services')} /> : null}
      <View style={styles.grid}>
        {rows.map((s) => (
          <View key={s.id} style={styles.card}>
            <View style={styles.header}>
              <View style={[styles.swatch, { backgroundColor: s.color || tokens.color.primary }]} />
              <Text style={styles.name}>{s.name}</Text>
            </View>
            {s.description ? <Text style={styles.desc}>{s.description}</Text> : null}
            <Text style={styles.price}>{formatMoney(s.priceMinor, currency)}</Text>
            <Text style={styles.meta}>
              {s.durationMinutes} min
              {s.bufferBeforeMinutes || s.bufferAfterMinutes
                ? ` · +${s.bufferBeforeMinutes ?? 0}/${s.bufferAfterMinutes ?? 0} buffer`
                : ''}
            </Text>
            <View style={styles.flags}>
              <Flag on={s.active !== false} onLabel={t('catalog.active')} offLabel={t('catalog.inactive')} />
              <Flag on={s.publicVisible !== false} onLabel={t('catalog.public')} offLabel={t('catalog.private')} />
            </View>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

function Flag({ on, onLabel, offLabel }: { on: boolean; onLabel: string; offLabel: string }) {
  return (
    <View style={[styles.flag, on ? styles.flagOn : styles.flagOff]}>
      <Text style={[styles.flagText, on ? styles.flagTextOn : styles.flagTextOff]}>{on ? onLabel : offLabel}</Text>
    </View>
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
    minWidth: 240,
    flexGrow: 1,
    flexBasis: 260,
    maxWidth: 360,
  },
  header: { flexDirection: 'row', alignItems: 'center', gap: tokens.space.sm, marginBottom: tokens.space.sm },
  swatch: { width: 14, height: 14, borderRadius: tokens.radius.pill },
  name: { fontWeight: '700', fontSize: 16, color: tokens.color.ink, fontFamily: 'Plus Jakarta Sans', flexShrink: 1 },
  desc: { color: tokens.color.muted, marginBottom: tokens.space.sm, fontSize: 13 },
  price: { fontSize: 18, fontWeight: '700', color: tokens.color.primary, marginBottom: 4 },
  meta: { color: tokens.color.muted, fontSize: 13, marginBottom: tokens.space.md },
  flags: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.sm },
  flag: {
    paddingHorizontal: tokens.space.sm + 2,
    paddingVertical: 2,
    borderRadius: tokens.radius.pill,
  },
  flagOn: { backgroundColor: `${tokens.color.primary}18` },
  flagOff: { backgroundColor: tokens.color.mist },
  flagText: { fontSize: 11, fontWeight: '600' },
  flagTextOn: { color: tokens.color.primary },
  flagTextOff: { color: tokens.color.muted },
});
