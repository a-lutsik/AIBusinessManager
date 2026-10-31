import { useEffect, useMemo, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View, useWindowDimensions } from 'react-native';
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
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { t } from '@/src/i18n';
import { DISPLAY_FONT, statusColor, useThemeTokens } from '@/src/theme/tokens';
import { dayKey, formatDateTime, formatLongDay, formatTime, slotWindow, startOfZoneDay, zoneDate } from '@/src/utils/datetime';

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
  const { role, me, profileLoaded } = useConsoleSession();
  const timeZone = me?.timezone;
  // On a narrow screen the visit panel covers the feed instead of squeezing it next to a 320 px column.
  const narrow = useWindowDimensions().width < 768;
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

  // Days as the tenant counts them: the range waits for the tenant's zone (the feed groups by it as well).
  const range = useMemo(() => {
    if (!profileLoaded) return null;
    return {
      from: startOfZoneDay(zoneDate(timeZone), timeZone),
      to: startOfZoneDay(zoneDate(timeZone, mode === 'week' ? 7 : 1), timeZone),
    };
  }, [profileLoaded, timeZone, mode]);

  const load = () => {
    if (!range) return;
    api
      .calendar(LUMEN_TENANT_ID, range.from, range.to, filter ?? undefined)
      .then(setVisits)
      .catch((e) => setError(String(e.message ?? e)));
  };

  useEffect(() => {
    api.specialists(LUMEN_TENANT_ID).then(setSpecialists).catch(() => setSpecialists([]));
  }, []);

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range?.from, range?.to, filter]);

  const openVisit = async (summary: AppointmentView) => {
    setError(null);
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

  const actionsFor = (status: string, serviceStart?: string, hiddenFromMaster?: boolean) => {
    // A visit is completed or marked no-show only once it has started (the API answers 409 VISIT_NOT_STARTED).
    // The current time is read on purpose each time the action list is built.
    // eslint-disable-next-line react-hooks/purity
    const started = !serviceStart || new Date(serviceStart).getTime() <= Date.now();
    // A master's phone booking hidden until the owner confirms it: only the owner confirms (the API answers 403).
    if (status === 'PENDING' && role === 'MASTER' && hiddenFromMaster) return ['CANCELLED_BY_BUSINESS'];
    if (status === 'PENDING') return ['CONFIRMED', 'CANCELLED_BY_BUSINESS'];
    if (status === 'CONFIRMED') return started ? ['COMPLETED', 'NO_SHOW', 'CANCELLED_BY_BUSINESS'] : ['CANCELLED_BY_BUSINESS'];
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
      const window = slotWindow(14, timeZone);
      const next = await api.publicSlots(me?.slug ?? LUMEN_SLUG, selected.serviceId, selected.specialistId, window.from, window.to);
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

  // Days as the tenant counts them; the feed lists only days that have visits.
  const feedDays = useMemo(() => {
    const byDay = new Map<string, AppointmentView[]>();
    for (const v of visits) {
      if (!v.serviceStart) continue;
      const key = dayKey(v.serviceStart, timeZone);
      byDay.set(key, [...(byDay.get(key) ?? []), v]);
    }
    return [...byDay.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, dayVisits]) => ({
        key,
        visits: dayVisits.sort((a, b) => new Date(a.serviceStart!).getTime() - new Date(b.serviceStart!).getTime()),
      }));
  }, [visits, timeZone]);

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
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: DISPLAY_FONT }}>
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
          {feedDays.map(({ key, visits: dayVisits }) => (
            <View key={key} style={{ marginBottom: space.lg }}>
              <Text testID="calendar-day" style={{ fontSize: 13, fontWeight: '700', color: color.muted, textTransform: 'uppercase', letterSpacing: 0.4, marginBottom: space.sm }}>
                {formatLongDay(dayVisits[0].serviceStart!, timeZone)}
              </Text>
              {dayVisits.map((v) => (
                <Pressable
                  key={v.id}
                  testID={`visit-row-${v.id}`}
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
                    {v.serviceStart ? formatTime(v.serviceStart, timeZone) : ''}
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
          testID="visit-panel"
          style={{
            ...(narrow ? { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0 } : { width: 320, alignSelf: 'flex-start', maxHeight: '100%' }),
            backgroundColor: color.surface,
            borderRadius: radius.card,
            borderWidth: 1,
            borderColor: color.line,
          }}
        >
          <ScrollView contentContainerStyle={{ padding: space.lg, gap: space.xs }}>
            <Text style={{ fontSize: 20, fontWeight: '600', color: color.ink, fontFamily: DISPLAY_FONT, marginBottom: space.sm }}>
              {selected.serviceNameSnapshot}
            </Text>
            {/* The panel covers the feed and its error line on a narrow screen. */}
            {narrow && error ? (
              <Text accessibilityRole="alert" style={{ color: color.status.noShow, marginBottom: space.sm }}>
                {error}
              </Text>
            ) : null}
            <Text style={{ color: color.ink }}>{selected.clientDisplayName ?? '—'}</Text>
            <TrustBadge level={selectedTrust ?? selected.trustLevel} />
            <Text style={{ color: color.muted, marginTop: 4 }}>{t(`status.${selected.status}`)}</Text>
            <Text style={{ color: color.muted, marginTop: 4 }}>
              {selected.serviceStart ? formatDateTime(selected.serviceStart, timeZone) : ''}
            </Text>
            {selected.clientPhone ? <Text style={{ color: color.muted, marginTop: 4 }}>{selected.clientPhone}</Text> : null}
            {selected.note ? <Text style={{ color: color.ink, marginTop: 4 }}>{selected.note}</Text> : null}
            <View style={{ marginTop: space.md, gap: space.sm }}>
              {selected.clientHiddenFromMaster && role === 'MASTER' ? (
                <Text style={{ color: color.muted }}>{t('calendar.awaitingOwnerConfirmation')}</Text>
              ) : null}
              {actionsFor(selected.status, selected.serviceStart, selected.clientHiddenFromMaster).map((st) => (
                <Button
                  key={st}
                  label={t(`visit.action.${st}`)}
                  onPress={() =>
                    api
                      .transition(LUMEN_TENANT_ID, selected.id, st)
                      .then((next) => {
                        setSelected(next);
                        setSelectedTrust(next.trustLevel);
                        load();
                      })
                      .catch((e: unknown) => setError(String((e as Error).message ?? e)))
                  }
                />
              ))}
              {!String(selected.status).startsWith('CANCELLED') ? (
                <Button label={t('visit.reschedule')} variant="ghost" onPress={openRescheduleDialog} />
              ) : null}
              <Button label={t('calendar.closePanel')} variant="ghost" onPress={() => setSelected(null)} />
            </View>
          </ScrollView>
        </View>
      ) : null}

      <Modal visible={slotOpen} transparent animationType="fade" onRequestClose={() => setSlotOpen(false)}>
        <View style={{ flex: 1, backgroundColor: 'rgba(15,23,42,0.4)', justifyContent: 'center', alignItems: 'center', padding: space.xl }}>
          <View style={{ width: '100%', maxWidth: 420, backgroundColor: color.surface, borderRadius: radius.card, padding: space.xl, gap: space.sm }}>
            <Text style={{ fontSize: 20, fontWeight: '600', color: color.ink, fontFamily: DISPLAY_FONT, marginBottom: space.sm }}>
              {t('visit.pickSlot')}
            </Text>
            {slotsBusy && slots.length === 0 ? <Text style={{ color: color.muted }}>…</Text> : null}
            {!slotsBusy && slots.length === 0 ? <EmptyState title={t('empty.slots')} /> : null}
            <ScrollView style={{ maxHeight: 320 }}>
              {slots.map((s) => (
                <Button
                  key={s}
                  label={formatDateTime(s, timeZone)}
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
