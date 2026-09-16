import { useEffect, useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { TrustBadge } from '@/components/ui/TrustBadge';
import {
  api,
  LUMEN_SLUG,
  LUMEN_TENANT_ID,
  type AppointmentView,
  type SpecialistView,
} from '@/src/api/client';
import { t } from '@/src/i18n';
import { statusColor, tokens } from '@/src/theme/tokens';

const DAY_START_HOUR = 8;
const DAY_END_HOUR = 21;
const PX_PER_MIN = 1.2;

function startOfLocalDay(d: Date) {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}

function addDays(d: Date, n: number) {
  const x = new Date(d);
  x.setDate(x.getDate() + n);
  return x;
}

function minutesFromDayStart(iso: string | undefined, day: Date): number | null {
  if (!iso) return null;
  const t0 = new Date(iso).getTime();
  const base = startOfLocalDay(day).getTime();
  return Math.round((t0 - base) / 60000);
}

function dayKey(d: Date) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function unwrapAppointment(raw: unknown): AppointmentView {
  if (raw && typeof raw === 'object' && 'appointment' in (raw as object)) {
    return (raw as { appointment: AppointmentView }).appointment;
  }
  return raw as AppointmentView;
}

function trustFromDetail(raw: unknown, fallback?: string | null): string | null | undefined {
  if (raw && typeof raw === 'object' && 'trust' in (raw as object)) {
    const trust = (raw as { trust?: { effectiveLevel?: string; level?: string } }).trust;
    return trust?.effectiveLevel ?? trust?.level ?? fallback;
  }
  return fallback;
}

type TimelineBlock = {
  visit: AppointmentView;
  top: number;
  height: number;
  bufferBefore: number;
  bufferAfter: number;
  bodyTop: number;
  bodyHeight: number;
};

function layoutDay(visits: AppointmentView[], day: Date): TimelineBlock[] {
  const dayStartMin = DAY_START_HOUR * 60;
  const dayEndMin = DAY_END_HOUR * 60;
  return visits
    .map((visit) => {
      const occStart = minutesFromDayStart(visit.occupiedStart ?? visit.serviceStart, day);
      const occEnd = minutesFromDayStart(visit.occupiedEnd ?? visit.serviceEnd ?? visit.serviceStart, day);
      const svcStart = minutesFromDayStart(visit.serviceStart, day);
      const svcEnd = minutesFromDayStart(visit.serviceEnd ?? visit.serviceStart, day);
      if (occStart == null || occEnd == null || svcStart == null || svcEnd == null) return null;
      const clampedStart = Math.max(occStart, dayStartMin);
      const clampedEnd = Math.min(occEnd, dayEndMin);
      if (clampedEnd <= clampedStart) return null;
      const top = (clampedStart - dayStartMin) * PX_PER_MIN;
      const height = Math.max(24, (clampedEnd - clampedStart) * PX_PER_MIN);
      const bodyTop = Math.max(0, (svcStart - clampedStart) * PX_PER_MIN);
      const bodyHeight = Math.max(16, (Math.min(svcEnd, dayEndMin) - Math.max(svcStart, clampedStart)) * PX_PER_MIN);
      const bufferBefore = Math.max(0, bodyTop);
      const bufferAfter = Math.max(0, height - bodyTop - bodyHeight);
      return { visit, top, height, bufferBefore, bufferAfter, bodyTop, bodyHeight };
    })
    .filter(Boolean) as TimelineBlock[];
}

function hoursLabels() {
  const labels: string[] = [];
  for (let h = DAY_START_HOUR; h <= DAY_END_HOUR; h++) {
    labels.push(`${String(h).padStart(2, '0')}:00`);
  }
  return labels;
}

export default function CalendarScreen() {
  const [mode, setMode] = useState<'day' | 'week'>('week');
  const [visits, setVisits] = useState<AppointmentView[]>([]);
  const [specialists, setSpecialists] = useState<SpecialistView[]>([]);
  const [filter, setFilter] = useState<string | null>(null);
  const [selected, setSelected] = useState<AppointmentView | null>(null);
  const [selectedTrust, setSelectedTrust] = useState<string | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [slotOpen, setSlotOpen] = useState(false);
  const [slots, setSlots] = useState<string[]>([]);
  const [slotsBusy, setSlotsBusy] = useState(false);
  const [tenantSlug, setTenantSlug] = useState(LUMEN_SLUG);

  const anchor = useMemo(() => startOfLocalDay(new Date()), []);

  const days = useMemo(() => {
    const count = mode === 'week' ? 7 : 1;
    return Array.from({ length: count }, (_, i) => addDays(anchor, i));
  }, [mode, anchor]);

  const range = useMemo(() => {
    const from = days[0];
    const to = addDays(days[days.length - 1], 1);
    return { from: from.toISOString(), to: to.toISOString() };
  }, [days]);

  const load = () => {
    api
      .calendar(LUMEN_TENANT_ID, range.from, range.to, filter ?? undefined)
      .then(setVisits)
      .catch((e) => setError(String(e.message ?? e)));
  };

  useEffect(() => {
    api.specialists(LUMEN_TENANT_ID).then(setSpecialists).catch(() => setSpecialists([]));
    api
      .me(LUMEN_TENANT_ID)
      .then((m) => {
        if (m.slug) setTenantSlug(m.slug);
      })
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    load();
  }, [range.from, range.to, filter]);

  const openVisit = async (summary: AppointmentView) => {
    setSelected(summary);
    setSelectedTrust(summary.trustLevel);
    try {
      const detail = await api.appointment(LUMEN_TENANT_ID, summary.id);
      const appt = unwrapAppointment(detail);
      setSelected({ ...summary, ...appt });
      setSelectedTrust(trustFromDetail(detail, appt.trustLevel ?? summary.trustLevel));
    } catch {
      // keep summary
    }
  };

  const actionsFor = (status: string) => {
    if (status === 'PENDING') return ['CONFIRMED', 'CANCELLED_BY_BUSINESS'];
    if (status === 'CONFIRMED') return ['COMPLETED', 'NO_SHOW', 'CANCELLED_BY_BUSINESS'];
    if (status === 'NO_SHOW') return ['COMPLETED'];
    if (status === 'COMPLETED') return ['NO_SHOW'];
    return [];
  };

  const openRescheduleDialog = async () => {
    if (!selected?.serviceId || !selected?.specialistId) {
      setError(t('calendar.rescheduleNeedIds'));
      return;
    }
    setSlotOpen(true);
    setSlotsBusy(true);
    setSlots([]);
    try {
      const now = new Date();
      const from = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + 1));
      const to = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + 14));
      const next = await api.publicSlots(
        tenantSlug,
        selected.serviceId,
        selected.specialistId,
        from.toISOString().slice(0, 10),
        to.toISOString().slice(0, 10)
      );
      setSlots(next);
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    } finally {
      setSlotsBusy(false);
    }
  };

  const applyReschedule = async (start: string) => {
    if (!selected) return;
    setSlotsBusy(true);
    try {
      const next = await api.reschedule(LUMEN_TENANT_ID, selected.id, start);
      setSelected(next);
      setSelectedTrust(next.trustLevel);
      setSlotOpen(false);
      load();
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    } finally {
      setSlotsBusy(false);
    }
  };

  const dayHeight = (DAY_END_HOUR - DAY_START_HOUR) * 60 * PX_PER_MIN;
  const hours = hoursLabels();

  return (
    <View style={styles.wrap}>
      <View style={{ flex: 1 }}>
        <Text style={styles.h1}>{t('nav.calendar')}</Text>
        <View style={styles.row}>
          <Pressable onPress={() => setMode('day')}>
            <Text style={[styles.chip, mode === 'day' && styles.sel]}>{t('calendar.day')}</Text>
          </Pressable>
          <Pressable onPress={() => setMode('week')}>
            <Text style={[styles.chip, mode === 'week' && styles.sel]}>{t('calendar.week')}</Text>
          </Pressable>
          <Pressable onPress={() => setFilter(null)}>
            <Text style={[styles.chip, filter == null && styles.sel]}>{t('calendar.allMasters')}</Text>
          </Pressable>
          {specialists.map((s) => (
            <Pressable key={s.id} onPress={() => setFilter(s.id)}>
              <Text style={[styles.chip, filter === s.id && styles.sel]}>{s.displayName}</Text>
            </Pressable>
          ))}
        </View>
        {error ? <Text style={styles.err}>{error}</Text> : null}

        {visits.length === 0 ? <EmptyState title={t('empty.calendar')} /> : null}

        <ScrollView horizontal style={{ flexGrow: 0 }}>
          <View style={styles.timelineRow}>
            <View style={styles.gutter}>
              <View style={{ height: 28 }} />
              <View style={{ height: dayHeight, position: 'relative' }}>
                {hours.map((label, i) => (
                  <Text key={label} style={[styles.hourLabel, { top: i * 60 * PX_PER_MIN - 6 }]}>
                    {label}
                  </Text>
                ))}
              </View>
            </View>
            {days.map((day) => {
              const key = dayKey(day);
              const dayVisits = visits.filter((v) => {
                if (!v.serviceStart) return false;
                return dayKey(new Date(v.serviceStart)) === key;
              });
              const blocks = layoutDay(dayVisits, day);
              return (
                <View key={key} style={styles.dayCol}>
                  <Text style={styles.dayHead}>
                    {day.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}
                  </Text>
                  <View style={[styles.dayCanvas, { height: dayHeight }]}>
                    {hours.map((_, i) => (
                      <View key={i} style={[styles.hourLine, { top: i * 60 * PX_PER_MIN }]} />
                    ))}
                    {blocks.map((b) => (
                      <Pressable
                        key={b.visit.id}
                        onPress={() => openVisit(b.visit)}
                        style={[
                          styles.block,
                          {
                            top: b.top,
                            height: b.height,
                            borderLeftColor: statusColor(b.visit.status),
                          },
                        ]}
                      >
                        {b.bufferBefore > 2 ? (
                          <View style={[styles.bufferZone, { height: b.bufferBefore }]} />
                        ) : null}
                        <View style={[styles.bodyZone, { minHeight: b.bodyHeight }]}>
                          <Text style={styles.k} numberOfLines={1}>
                            {b.visit.serviceNameSnapshot}
                          </Text>
                          <Text style={styles.blockMeta} numberOfLines={1}>
                            {b.visit.clientDisplayName}
                          </Text>
                        </View>
                        {b.bufferAfter > 2 ? (
                          <View style={[styles.bufferZone, { height: b.bufferAfter }]} />
                        ) : null}
                      </Pressable>
                    ))}
                  </View>
                </View>
              );
            })}
          </View>
        </ScrollView>
      </View>

      {selected ? (
        <View style={styles.panel}>
          <Text style={styles.h2}>{selected.serviceNameSnapshot}</Text>
          <Text>{selected.clientDisplayName}</Text>
          <TrustBadge level={selectedTrust ?? selected.trustLevel} />
          <Text style={styles.muted}>{t(`status.${selected.status}`)}</Text>
          <Text style={styles.muted}>
            {selected.serviceStart ? new Date(selected.serviceStart).toLocaleString() : ''}
          </Text>
          {selected.clientPhone ? <Text style={styles.muted}>{selected.clientPhone}</Text> : null}
          {selected.note ? <Text>{selected.note}</Text> : null}
          <View style={styles.panelActions}>
            {actionsFor(selected.status).map((st) => (
              <Button
                key={st}
                label={t(`status.${st}`)}
                onPress={() =>
                  api.transition(LUMEN_TENANT_ID, selected.id, st).then((next) => {
                    setSelected(next);
                    setSelectedTrust(next.trustLevel);
                    load();
                  })
                }
              />
            ))}
            {!String(selected.status).startsWith('CANCELLED') ? (
              <Button label={t('visit.reschedule')} variant="ghost" onPress={openRescheduleDialog} />
            ) : null}
            <Button label={t('calendar.closePanel')} variant="ghost" onPress={() => setSelected(null)} />
          </View>
        </View>
      ) : null}

      <Modal visible={slotOpen} transparent animationType="fade" onRequestClose={() => setSlotOpen(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.h2}>{t('visit.pickSlot')}</Text>
            {slotsBusy && slots.length === 0 ? <Text style={styles.muted}>…</Text> : null}
            {!slotsBusy && slots.length === 0 ? <EmptyState title={t('empty.slots')} /> : null}
            <ScrollView style={{ maxHeight: 320 }}>
              {slots.map((s) => (
                <Button
                  key={s}
                  label={new Date(s).toLocaleString()}
                  variant="ghost"
                  disabled={slotsBusy}
                  onPress={() => applyReschedule(s)}
                  style={styles.slotBtn}
                />
              ))}
            </ScrollView>
            <Button label={t('action.cancel')} variant="ghost" onPress={() => setSlotOpen(false)} />
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { flex: 1, flexDirection: 'row', gap: tokens.space.lg },
  h1: {
    fontSize: 28,
    fontWeight: '700',
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
  },
  h2: {
    fontSize: 20,
    fontWeight: '600',
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
    marginBottom: tokens.space.sm,
  },
  row: {
    flexDirection: 'row',
    gap: tokens.space.md,
    marginVertical: tokens.space.md,
    flexWrap: 'wrap',
  },
  chip: { color: tokens.color.muted, fontWeight: '600' },
  sel: { color: tokens.color.primary, textDecorationLine: 'underline' },
  muted: { color: tokens.color.muted, marginTop: 4 },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.sm },
  timelineRow: { flexDirection: 'row', paddingBottom: tokens.space.xl },
  gutter: { width: 52 },
  hourLabel: {
    position: 'absolute',
    left: 0,
    fontSize: 11,
    color: tokens.color.muted,
    width: 48,
  },
  dayCol: { width: 160, marginRight: tokens.space.sm },
  dayHead: {
    height: 28,
    fontWeight: '600',
    color: tokens.color.ink,
    fontSize: 13,
    fontFamily: 'Plus Jakarta Sans',
  },
  dayCanvas: {
    position: 'relative',
    backgroundColor: tokens.color.surface,
    borderWidth: 1,
    borderColor: tokens.color.line,
    borderRadius: tokens.radius.control,
    overflow: 'hidden',
  },
  hourLine: {
    position: 'absolute',
    left: 0,
    right: 0,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: tokens.color.line,
  },
  block: {
    position: 'absolute',
    left: 4,
    right: 4,
    backgroundColor: tokens.color.mist,
    borderRadius: 6,
    borderLeftWidth: 3,
    overflow: 'hidden',
  },
  bufferZone: { backgroundColor: `${tokens.color.secondary}22` },
  bodyZone: { paddingHorizontal: 6, paddingVertical: 2, justifyContent: 'center' },
  k: { fontWeight: '700', fontSize: 12, color: tokens.color.ink },
  blockMeta: { fontSize: 11, color: tokens.color.muted },
  panel: {
    width: 320,
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderRadius: tokens.radius.card,
    borderWidth: 1,
    borderColor: tokens.color.line,
    gap: tokens.space.xs,
  },
  panelActions: { marginTop: tokens.space.md, gap: tokens.space.sm },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(15,23,42,0.4)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: tokens.space.xl,
  },
  modalCard: {
    width: '100%',
    maxWidth: 420,
    backgroundColor: tokens.color.surface,
    borderRadius: tokens.radius.card,
    padding: tokens.space.xl,
    gap: tokens.space.sm,
  },
  slotBtn: { alignSelf: 'stretch', marginBottom: tokens.space.xs },
});
