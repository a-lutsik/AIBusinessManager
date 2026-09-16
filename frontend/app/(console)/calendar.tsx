import { useEffect, useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
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
import { statusColor, useThemeTokens } from '@/src/theme/tokens';

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

export default function CalendarScreen() {
  const { color, space, radius } = useThemeTokens();
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  const feedDays = useMemo(() => {
    return days
      .map((day) => {
        const key = dayKey(day);
        const dayVisits = visits
          .filter((v) => v.serviceStart && dayKey(new Date(v.serviceStart)) === key)
          .sort((a, b) => new Date(a.serviceStart!).getTime() - new Date(b.serviceStart!).getTime());
        return { day, key, visits: dayVisits };
      })
      .filter((d) => d.visits.length > 0);
  }, [days, visits]);

  const pill = (active: boolean) => ({
    paddingVertical: 6,
    paddingHorizontal: space.md,
    borderRadius: radius.pill,
    backgroundColor: active ? color.primary : color.surface,
    borderWidth: 1,
    borderColor: active ? color.primary : color.line,
  });
  const pillText = (active: boolean) => ({ fontSize: 12, fontWeight: '700' as const, color: active ? '#FFFFFF' : color.muted });

  return (
    <View style={{ flex: 1, flexDirection: 'row', gap: space.lg }}>
      <View style={{ flex: 1 }}>
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
          {t('nav.calendar')}
        </Text>
        <View style={{ flexDirection: 'row', gap: space.sm, marginVertical: space.md, flexWrap: 'wrap' }}>
          <Pressable onPress={() => setMode('day')} style={pill(mode === 'day')}>
            <Text style={pillText(mode === 'day')}>{t('calendar.day')}</Text>
          </Pressable>
          <Pressable onPress={() => setMode('week')} style={pill(mode === 'week')}>
            <Text style={pillText(mode === 'week')}>{t('calendar.week')}</Text>
          </Pressable>
          <Pressable onPress={() => setFilter(null)} style={pill(filter == null)}>
            <Text style={pillText(filter == null)}>{t('calendar.allMasters')}</Text>
          </Pressable>
          {specialists.map((s) => (
            <Pressable key={s.id} onPress={() => setFilter(s.id)} style={pill(filter === s.id)}>
              <Text style={pillText(filter === s.id)}>{s.displayName}</Text>
            </Pressable>
          ))}
        </View>
        {error ? <Text style={{ color: color.status.noShow, marginBottom: space.sm }}>{error}</Text> : null}

        <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
          {feedDays.length === 0 ? <EmptyState title={t('empty.calendar')} /> : null}
          {feedDays.map(({ day, key, visits: dayVisits }) => (
            <View key={key} style={{ marginBottom: space.lg }}>
              <Text style={{ fontSize: 13, fontWeight: '700', color: color.muted, textTransform: 'uppercase', letterSpacing: 0.4, marginBottom: space.sm }}>
                {day.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' })}
              </Text>
              {dayVisits.map((v) => (
                <Pressable
                  key={v.id}
                  onPress={() => openVisit(v)}
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: space.md,
                    backgroundColor: selected?.id === v.id ? `${color.primary}0F` : color.surface,
                    padding: space.lg,
                    borderRadius: radius.card,
                    marginBottom: space.sm,
                    borderLeftWidth: 4,
                    borderWidth: 1,
                    borderColor: selected?.id === v.id ? color.primary : color.line,
                    borderLeftColor: statusColor(v.status, color),
                  }}
                >
                  <Text style={{ width: 56, fontWeight: '700', color: color.ink, fontVariant: ['tabular-nums'] }}>
                    {v.serviceStart ? new Date(v.serviceStart).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : ''}
                  </Text>
                  <View style={{ flex: 1 }}>
                    <Text style={{ fontWeight: '700', color: color.ink }} numberOfLines={1}>
                      {v.serviceNameSnapshot ?? '—'} · {v.clientDisplayName ?? '—'}
                    </Text>
                    <Text style={{ color: color.muted, marginTop: 2 }}>{t(`status.${v.status}`)}</Text>
                  </View>
                  <TrustBadge level={v.trustLevel} />
                </Pressable>
              ))}
            </View>
          ))}
        </ScrollView>
      </View>

      {selected ? (
        <View
          style={{
            width: 320,
            backgroundColor: color.surface,
            padding: space.lg,
            borderRadius: radius.card,
            borderWidth: 1,
            borderColor: color.line,
            gap: space.xs,
            alignSelf: 'flex-start',
          }}
        >
          <Text style={{ fontSize: 20, fontWeight: '600', color: color.ink, fontFamily: 'Plus Jakarta Sans', marginBottom: space.sm }}>
            {selected.serviceNameSnapshot}
          </Text>
          <Text style={{ color: color.ink }}>{selected.clientDisplayName}</Text>
          <TrustBadge level={selectedTrust ?? selected.trustLevel} />
          <Text style={{ color: color.muted, marginTop: 4 }}>{t(`status.${selected.status}`)}</Text>
          <Text style={{ color: color.muted, marginTop: 4 }}>
            {selected.serviceStart ? new Date(selected.serviceStart).toLocaleString() : ''}
          </Text>
          {selected.clientPhone ? <Text style={{ color: color.muted, marginTop: 4 }}>{selected.clientPhone}</Text> : null}
          {selected.note ? <Text style={{ color: color.ink, marginTop: 4 }}>{selected.note}</Text> : null}
          <View style={{ marginTop: space.md, gap: space.sm }}>
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
        <View style={{ flex: 1, backgroundColor: 'rgba(15,23,42,0.4)', justifyContent: 'center', alignItems: 'center', padding: space.xl }}>
          <View style={{ width: '100%', maxWidth: 420, backgroundColor: color.surface, borderRadius: radius.card, padding: space.xl, gap: space.sm }}>
            <Text style={{ fontSize: 20, fontWeight: '600', color: color.ink, fontFamily: 'Plus Jakarta Sans', marginBottom: space.sm }}>
              {t('visit.pickSlot')}
            </Text>
            {slotsBusy && slots.length === 0 ? <Text style={{ color: color.muted }}>…</Text> : null}
            {!slotsBusy && slots.length === 0 ? <EmptyState title={t('empty.slots')} /> : null}
            <ScrollView style={{ maxHeight: 320 }}>
              {slots.map((s) => (
                <Button
                  key={s}
                  label={new Date(s).toLocaleString()}
                  variant="ghost"
                  disabled={slotsBusy}
                  onPress={() => applyReschedule(s)}
                  style={{ alignSelf: 'stretch', marginBottom: space.xs }}
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
