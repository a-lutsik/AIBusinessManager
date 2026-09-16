import { useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  api,
  LUMEN_TENANT_ID,
  type MatrixRowView,
  type MeView,
  type ServiceView,
} from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';
import { formatMoney } from '@/src/utils/money';

type EnrichedRow = {
  key: string;
  serviceId: string;
  name: string;
  color: string;
  offered: boolean;
  defaultDuration: number | null;
  defaultPrice: number | null;
  defaultBefore: number | null;
  defaultAfter: number | null;
  durationOverride: number | null;
  priceOverride: number | null;
  beforeOverride: number | null;
  afterOverride: number | null;
};

function enrichRow(row: MatrixRowView, servicesById: Map<string, ServiceView>): EnrichedRow {
  const service = row.service ?? servicesById.get(row.serviceId);
  const name =
    row.name ??
    row.serviceName ??
    service?.name ??
    `${t('matrix.service')} ${String(row.serviceId).slice(0, 8)}`;
  const color = row.color ?? service?.color ?? tokens.color.primary;
  return {
    key: row.id ?? row.serviceId,
    serviceId: row.serviceId,
    name,
    color,
    offered: !!row.offered,
    defaultDuration: row.defaultDurationMinutes ?? service?.durationMinutes ?? null,
    defaultPrice: row.defaultPriceMinor ?? service?.priceMinor ?? null,
    defaultBefore: row.defaultBufferBeforeMinutes ?? service?.bufferBeforeMinutes ?? null,
    defaultAfter: row.defaultBufferAfterMinutes ?? service?.bufferAfterMinutes ?? null,
    durationOverride: row.durationMinutesOverride ?? null,
    priceOverride: row.priceMinorOverride ?? null,
    beforeOverride: row.bufferBeforeMinutesOverride ?? null,
    afterOverride: row.bufferAfterMinutesOverride ?? null,
  };
}

export default function SpecialistMatrix() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { width } = useWindowDimensions();
  const compact = width < 900;
  const [rows, setRows] = useState<MatrixRowView[]>([]);
  const [services, setServices] = useState<ServiceView[]>([]);
  const [me, setMe] = useState<MeView | null>(null);

  useEffect(() => {
    if (!id) return;
    api.matrix(LUMEN_TENANT_ID, id).then(setRows).catch(() => setRows([]));
    api.services(LUMEN_TENANT_ID).then(setServices).catch(() => setServices([]));
    api.me(LUMEN_TENANT_ID).then(setMe).catch(() => setMe(null));
  }, [id]);

  const servicesById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services]);
  const enriched = useMemo(() => rows.map((r) => enrichRow(r, servicesById)), [rows, servicesById]);
  const currency = me?.currencyCode ?? 'GEL';

  return (
    <ScrollView horizontal={compact} contentContainerStyle={styles.pad}>
      <View style={{ minWidth: compact ? 720 : undefined, width: compact ? undefined : '100%' }}>
        <Text style={styles.h1}>{t('matrix.title')}</Text>
        <Text style={styles.lead}>{t('matrix.lead')}</Text>
        {enriched.length === 0 ? <EmptyState title={t('matrix.empty')} /> : null}

        {enriched.length > 0 ? (
          <View style={styles.table}>
            <View style={[styles.tr, styles.thead]}>
              <Text style={[styles.th, styles.colService]}>{t('matrix.col.service')}</Text>
              <Text style={[styles.th, styles.colOffered]}>{t('matrix.col.offered')}</Text>
              <Text style={[styles.th, styles.colNum]}>{t('matrix.col.defaultDur')}</Text>
              <Text style={[styles.th, styles.colNum]}>{t('matrix.col.overrideDur')}</Text>
              <Text style={[styles.th, styles.colMoney]}>{t('matrix.col.defaultPrice')}</Text>
              <Text style={[styles.th, styles.colMoney]}>{t('matrix.col.overridePrice')}</Text>
              <Text style={[styles.th, styles.colBuf]}>{t('matrix.col.buffers')}</Text>
            </View>
            {enriched.map((r) => (
              <View key={r.key} style={styles.tr}>
                <View style={[styles.colService, styles.serviceCell]}>
                  <View style={[styles.swatch, { backgroundColor: r.color }]} />
                  <Text style={styles.serviceName} numberOfLines={2}>
                    {r.name}
                  </Text>
                </View>
                <Text style={[styles.td, styles.colOffered, r.offered ? styles.on : styles.off]}>
                  {r.offered ? t('catalog.yes') : t('catalog.no')}
                </Text>
                <Text style={[styles.td, styles.colNum]}>{fmtNum(r.defaultDuration, 'min')}</Text>
                <Text style={[styles.td, styles.colNum, r.durationOverride != null ? styles.override : null]}>
                  {fmtNum(r.durationOverride, 'min')}
                </Text>
                <Text style={[styles.td, styles.colMoney]}>
                  {r.defaultPrice != null ? formatMoney(r.defaultPrice, currency) : '—'}
                </Text>
                <Text style={[styles.td, styles.colMoney, r.priceOverride != null ? styles.override : null]}>
                  {r.priceOverride != null ? formatMoney(r.priceOverride, currency) : '—'}
                </Text>
                <Text style={[styles.td, styles.colBuf]}>
                  {fmtBuf(r.defaultBefore, r.defaultAfter)}
                  {r.beforeOverride != null || r.afterOverride != null
                    ? `\n→ ${fmtBuf(r.beforeOverride ?? r.defaultBefore, r.afterOverride ?? r.defaultAfter)}`
                    : ''}
                </Text>
              </View>
            ))}
          </View>
        ) : null}
      </View>
    </ScrollView>
  );
}

function fmtNum(n: number | null, suffix: string): string {
  if (n == null) return '—';
  return `${n} ${suffix}`;
}

function fmtBuf(before: number | null, after: number | null): string {
  if (before == null && after == null) return '—';
  return `${before ?? 0}/${after ?? 0}`;
}

const styles = StyleSheet.create({
  pad: { paddingBottom: tokens.space.xxl },
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.sm, fontFamily: 'Plus Jakarta Sans', color: tokens.color.ink },
  lead: { color: tokens.color.muted, marginBottom: tokens.space.lg, maxWidth: 560 },
  table: {
    borderWidth: 1,
    borderColor: tokens.color.line,
    borderRadius: tokens.radius.card,
    overflow: 'hidden',
    backgroundColor: tokens.color.surface,
  },
  tr: {
    flexDirection: 'row',
    alignItems: 'stretch',
    borderBottomWidth: 1,
    borderBottomColor: tokens.color.line,
    paddingVertical: tokens.space.md,
    paddingHorizontal: tokens.space.sm,
  },
  thead: { backgroundColor: tokens.color.mist, borderBottomWidth: 1 },
  th: { fontSize: 11, fontWeight: '700', color: tokens.color.muted, textTransform: 'uppercase', letterSpacing: 0.3 },
  td: { fontSize: 13, color: tokens.color.ink },
  colService: { flex: 2.2, minWidth: 160 },
  colOffered: { flex: 0.8, minWidth: 70 },
  colNum: { flex: 1, minWidth: 80 },
  colMoney: { flex: 1.1, minWidth: 90 },
  colBuf: { flex: 1, minWidth: 80 },
  serviceCell: { flexDirection: 'row', alignItems: 'center', gap: tokens.space.sm },
  swatch: { width: 10, height: 10, borderRadius: tokens.radius.pill },
  serviceName: { fontWeight: '600', flexShrink: 1 },
  on: { color: tokens.color.primary, fontWeight: '700' },
  off: { color: tokens.color.muted },
  override: { color: tokens.color.secondary, fontWeight: '700' },
});
