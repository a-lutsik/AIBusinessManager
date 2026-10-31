import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { LoadFailed } from '@/components/catalog/FormMessages';
import { MatrixRowForm } from '@/components/catalog/MatrixRowForm';
import { ScheduleExceptions } from '@/components/catalog/ScheduleExceptions';
import { WeeklySchedule } from '@/components/catalog/WeeklySchedule';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Pill } from '@/components/ui/Pill';
import {
  api,
  LUMEN_TENANT_ID,
  type MatrixRowView,
  type ServiceView,
  type SpecialistView,
} from '@/src/api/client';
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { currentLocale, t, useLocale } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';
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
  useLocale(); // re-render the texts, the forms included, when the language changes
  const { color, space, radius } = useThemeTokens();
  const { id } = useLocalSearchParams<{ id: string }>();
  const { width } = useWindowDimensions();
  const compact = width < 900;
  const { me, role } = useConsoleSession();
  const [rows, setRows] = useState<MatrixRowView[]>([]);
  const [services, setServices] = useState<ServiceView[]>([]);
  const [specialist, setSpecialist] = useState<SpecialistView | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const page = useRef<ScrollView>(null);

  const reloadMatrix = useCallback(() => {
    if (!id) return;
    api
      .matrix(LUMEN_TENANT_ID, id)
      .then((next) => {
        setRows(next);
        setLoaded(true);
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true));
  }, [id]);

  useEffect(() => {
    if (!id) return;
    reloadMatrix();
    // Both only add to the rows, which name their service themselves; a failed read leaves the name or the color out.
    api.services(LUMEN_TENANT_ID).then(setServices).catch(() => undefined);
    api
      .specialists(LUMEN_TENANT_ID)
      .then((all) => setSpecialist(all.find((s) => s.id === id) ?? null))
      .catch(() => undefined);
  }, [id, reloadMatrix]);

  const servicesById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services]);
  const enriched = useMemo(() => rows.map((r) => enrichRow(r, servicesById, color.primary)), [rows, servicesById, color.primary]);
  // Prices are read and typed in the tenant's currency: without it, no prices and no forms.
  const currency = me?.currencyCode;
  const canEdit = role === 'OWNER' && currency != null;
  const editingRow = rows.find((r) => r.serviceId === editing);

  const th = { fontSize: 11, fontWeight: '700' as const, color: color.muted, textTransform: 'uppercase' as const, letterSpacing: 0.3 };
  const td = { fontSize: 13, color: color.ink };
  const colService = { flex: 2.2, minWidth: 160 };
  const colOffered = { flex: 0.8, minWidth: 70 };
  const colNum = { flex: 1, minWidth: 80 };
  const colMoney = { flex: 1.1, minWidth: 90 };
  const colBuf = { flex: 1, minWidth: 80 };

  return (
    <ScrollView ref={page} contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Button label={t('specialists.back')} variant="ghost" href="/specialists" style={{ marginBottom: space.md }} />
      {specialist ? (
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginBottom: space.xs }}>
          <Text testID="specialist-name" style={{ fontSize: 18, fontWeight: '700', color: color.ink, fontFamily: DISPLAY_FONT, flexShrink: 1 }}>
            {specialist.displayName}
          </Text>
          {specialist.active === false ? <Pill active={false} onLabel={t('catalog.active')} offLabel={t('catalog.inactive')} /> : null}
        </View>
      ) : null}
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.sm, fontFamily: DISPLAY_FONT, color: color.ink }}>
        {t('matrix.title')}
      </Text>
      <Text style={{ color: color.muted, marginBottom: space.lg, maxWidth: 560 }}>{t('matrix.lead')}</Text>
      {loadFailed ? <LoadFailed onRetry={reloadMatrix} /> : null}
      {loaded && enriched.length === 0 ? <EmptyState title={t('matrix.empty')} /> : null}

      {/* Only the table scrolls sideways on a narrow screen: the title and the lead stay readable. */}
      <ScrollView testID="matrix-scroller" horizontal={compact}>
        {enriched.length > 0 ? (
          <View style={{ minWidth: compact ? 720 : undefined, width: compact ? undefined : '100%', borderWidth: 1, borderColor: color.line, borderRadius: radius.card, overflow: 'hidden', backgroundColor: color.surface }}>
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
                testID={`matrix-row-${r.name}`}
                style={{
                  flexDirection: 'row',
                  alignItems: 'stretch',
                  borderBottomWidth: 1,
                  borderBottomColor: color.line,
                  paddingVertical: space.md,
                  paddingHorizontal: space.sm,
                }}
              >
                <View style={colService}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
                    <View style={{ width: 10, height: 10, borderRadius: radius.pill, backgroundColor: r.color }} />
                    <Text style={{ fontWeight: '600', flexShrink: 1, color: color.ink }} numberOfLines={2}>
                      {r.name}
                    </Text>
                  </View>
                  {canEdit ? (
                    <Button label={t('action.edit')} variant="ghost" onPress={() => setEditing(r.serviceId)} style={{ marginTop: space.sm }} />
                  ) : null}
                </View>
                <Text style={[td, colOffered, { color: r.offered ? color.primary : color.muted, fontWeight: r.offered ? '700' : '400' }]}>
                  {r.offered ? t('catalog.yes') : t('catalog.no')}
                </Text>
                <Text style={[td, colNum]}>{fmtMinutes(r.defaultDuration)}</Text>
                <Text style={[td, colNum, r.durationOverride != null ? { color: color.secondary, fontWeight: '700' } : null]}>
                  {fmtMinutes(r.durationOverride)}
                </Text>
                <Text style={[td, colMoney]}>{r.defaultPrice != null && currency ? formatMoney(r.defaultPrice, currency, currentLocale()) : '—'}</Text>
                <Text style={[td, colMoney, r.priceOverride != null ? { color: color.secondary, fontWeight: '700' } : null]}>
                  {r.priceOverride != null && currency ? formatMoney(r.priceOverride, currency, currentLocale()) : '—'}
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
      </ScrollView>

      {/* Outside the table: the form stays in the width of the screen, and the page scrolls to it. */}
      {canEdit && currency && editingRow && id ? (
        <View onLayout={(e) => page.current?.scrollTo({ y: e.nativeEvent.layout.y, animated: false })}>
          <MatrixRowForm
            key={editingRow.serviceId}
            specialistId={id}
            row={editingRow}
            name={enriched.find((r) => r.serviceId === editing)?.name ?? ''}
            currency={currency}
            onSaved={() => {
              setEditing(null);
              reloadMatrix();
            }}
            onCancel={() => setEditing(null)}
          />
        </View>
      ) : null}

      {id ? <WeeklySchedule specialistId={id} editable={canEdit} /> : null}
      {id ? <ScheduleExceptions specialistId={id} editable={canEdit} timezone={me?.timezone} /> : null}
    </ScrollView>
  );
}

function fmtMinutes(n: number | null): string {
  if (n == null) return '—';
  return t('unit.minutes', { count: n });
}

function fmtBuf(before: number | null, after: number | null): string {
  if (before == null && after == null) return '—';
  return `${before ?? 0}/${after ?? 0}`;
}
