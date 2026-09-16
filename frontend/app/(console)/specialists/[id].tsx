import { useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  api,
  LUMEN_TENANT_ID,
  type MatrixRowView,
  type MeView,
  type ServiceView,
} from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
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

function enrichRow(row: MatrixRowView, servicesById: Map<string, ServiceView>, fallbackColor: string): EnrichedRow {
  const service = row.service ?? servicesById.get(row.serviceId);
  const name =
    row.name ??
    row.serviceName ??
    service?.name ??
    `${t('matrix.service')} ${String(row.serviceId).slice(0, 8)}`;
  const color = row.color ?? service?.color ?? fallbackColor;
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
  const { color, space, radius } = useThemeTokens();
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
  const enriched = useMemo(() => rows.map((r) => enrichRow(r, servicesById, color.primary)), [rows, servicesById, color.primary]);
  const currency = me?.currencyCode ?? 'GEL';

  const th = { fontSize: 11, fontWeight: '700' as const, color: color.muted, textTransform: 'uppercase' as const, letterSpacing: 0.3 };
  const td = { fontSize: 13, color: color.ink };
  const colService = { flex: 2.2, minWidth: 160 };
  const colOffered = { flex: 0.8, minWidth: 70 };
  const colNum = { flex: 1, minWidth: 80 };
  const colMoney = { flex: 1.1, minWidth: 90 };
  const colBuf = { flex: 1, minWidth: 80 };

  return (
    <ScrollView horizontal={compact} contentContainerStyle={{ paddingBottom: space.xxl }}>
      <View style={{ minWidth: compact ? 720 : undefined, width: compact ? undefined : '100%' }}>
        <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.sm, fontFamily: 'Plus Jakarta Sans', color: color.ink }}>
          {t('matrix.title')}
        </Text>
        <Text style={{ color: color.muted, marginBottom: space.lg, maxWidth: 560 }}>{t('matrix.lead')}</Text>
        {enriched.length === 0 ? <EmptyState title={t('matrix.empty')} /> : null}

        {enriched.length > 0 ? (
          <View style={{ borderWidth: 1, borderColor: color.line, borderRadius: radius.card, overflow: 'hidden', backgroundColor: color.surface }}>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'stretch',
                borderBottomWidth: 1,
                borderBottomColor: color.line,
                paddingVertical: space.md,
                paddingHorizontal: space.sm,
                backgroundColor: color.mist,
              }}
            >
              <Text style={[th, colService]}>{t('matrix.col.service')}</Text>
              <Text style={[th, colOffered]}>{t('matrix.col.offered')}</Text>
              <Text style={[th, colNum]}>{t('matrix.col.defaultDur')}</Text>
              <Text style={[th, colNum]}>{t('matrix.col.overrideDur')}</Text>
              <Text style={[th, colMoney]}>{t('matrix.col.defaultPrice')}</Text>
              <Text style={[th, colMoney]}>{t('matrix.col.overridePrice')}</Text>
              <Text style={[th, colBuf]}>{t('matrix.col.buffers')}</Text>
            </View>
            {enriched.map((r) => (
              <View
                key={r.key}
                style={{
                  flexDirection: 'row',
                  alignItems: 'stretch',
                  borderBottomWidth: 1,
                  borderBottomColor: color.line,
                  paddingVertical: space.md,
                  paddingHorizontal: space.sm,
                }}
              >
                <View style={[{ flexDirection: 'row', alignItems: 'center', gap: space.sm }, colService]}>
                  <View style={{ width: 10, height: 10, borderRadius: radius.pill, backgroundColor: r.color }} />
                  <Text style={{ fontWeight: '600', flexShrink: 1, color: color.ink }} numberOfLines={2}>
                    {r.name}
                  </Text>
                </View>
                <Text style={[td, colOffered, { color: r.offered ? color.primary : color.muted, fontWeight: r.offered ? '700' : '400' }]}>
                  {r.offered ? t('catalog.yes') : t('catalog.no')}
                </Text>
                <Text style={[td, colNum]}>{fmtNum(r.defaultDuration, 'min')}</Text>
                <Text style={[td, colNum, r.durationOverride != null ? { color: color.secondary, fontWeight: '700' } : null]}>
                  {fmtNum(r.durationOverride, 'min')}
                </Text>
                <Text style={[td, colMoney]}>{r.defaultPrice != null ? formatMoney(r.defaultPrice, currency) : '—'}</Text>
                <Text style={[td, colMoney, r.priceOverride != null ? { color: color.secondary, fontWeight: '700' } : null]}>
                  {r.priceOverride != null ? formatMoney(r.priceOverride, currency) : '—'}
                </Text>
                <Text style={[td, colBuf]}>
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
