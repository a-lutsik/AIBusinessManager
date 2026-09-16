import { useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  api,
  API_BASE,
  LUMEN_SLUG,
  shortRef,
  type AppointmentView,
} from '@/src/api/client';
import { t } from '@/src/i18n';
import { statusColor, tokens } from '@/src/theme/tokens';
import { formatMoney } from '@/src/utils/money';

function slotWindow() {
  const now = new Date();
  const from = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + 1));
  const to = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + 14));
  return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) };
}

export default function BookingLinkScreen() {
  const { id, token, slug: slugParam } = useLocalSearchParams<{
    id: string;
    token?: string;
    slug?: string;
  }>();
  const [visit, setVisit] = useState<AppointmentView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [rescheduleOpen, setRescheduleOpen] = useState(false);
  const [slots, setSlots] = useState<string[]>([]);
  const [slotsError, setSlotsError] = useState<string | null>(null);
  const [rescheduleUnavailable, setRescheduleUnavailable] = useState(false);

  const slug = (typeof slugParam === 'string' && slugParam) || LUMEN_SLUG;
  const ref = shortRef(typeof id === 'string' ? id : undefined);

  useEffect(() => {
    if (id && token) {
      api
        .publicBooking(id, token)
        .then(setVisit)
        .catch((e) => setError(String(e.message ?? e)));
    }
  }, [id, token]);

  const cancelled = visit ? String(visit.status).startsWith('CANCELLED') : false;
  const pending = visit?.status === 'PENDING';
  const headline = pending ? t('book.pending') : cancelled ? t(`status.${visit!.status}`) : t('book.success');

  const whenLabel = useMemo(() => {
    if (!visit?.serviceStart) return '—';
    try {
      return new Date(visit.serviceStart).toLocaleString();
    } catch {
      return visit.serviceStart;
    }
  }, [visit?.serviceStart]);

  const openReschedule = async () => {
    setRescheduleOpen(true);
    setSlotsError(null);
    setRescheduleUnavailable(false);
    if (!visit?.serviceId || !visit?.specialistId) {
      setSlotsError(t('visit.rescheduleNoSlots'));
      return;
    }
    const range = slotWindow();
    try {
      const next = await api.publicSlots(slug, visit.serviceId, visit.specialistId, range.from, range.to);
      setSlots(next);
      if (next.length === 0) {
        setSlotsError(t('empty.slots'));
      }
    } catch (e: unknown) {
      setSlots([]);
      setSlotsError(String((e as Error).message ?? e));
    }
  };

  const doReschedule = async (start: string) => {
    if (!id || !token || busy) return;
    setBusy(true);
    setError(null);
    try {
      const next = await api.publicReschedule(id, token, start);
      if (next == null) {
        setRescheduleUnavailable(true);
        return;
      }
      setVisit(next);
      setRescheduleOpen(false);
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    } finally {
      setBusy(false);
    }
  };

  const doCancel = async () => {
    if (!id || !token || busy) return;
    setBusy(true);
    setError(null);
    try {
      const next = await api.publicCancel(id, token);
      setVisit(next);
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    } finally {
      setBusy(false);
    }
  };

  if (error && !visit) {
    return (
      <View style={styles.page}>
        <Text style={styles.err}>{error}</Text>
      </View>
    );
  }

  if (!visit) {
    return (
      <View style={styles.page}>
        <Text style={styles.muted}>…</Text>
      </View>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.page}>
      <Text style={styles.ref}>#{ref}</Text>
      <Text style={styles.h1}>{headline}</Text>
      {pending ? <Text style={styles.lead}>{t('visit.pendingHint')}</Text> : null}
      {error ? <Text style={styles.err}>{error}</Text> : null}

      <View style={[styles.card, { borderLeftColor: statusColor(visit.status) }]}>
        <Text style={styles.service}>{visit.serviceNameSnapshot ?? '—'}</Text>
        <Text style={styles.rowLabel}>{t('visit.when')}</Text>
        <Text style={styles.rowValue}>{whenLabel}</Text>
        <Text style={styles.rowLabel}>{t('visit.status')}</Text>
        <Text style={styles.rowValue}>{t(`status.${visit.status}`)}</Text>
        {visit.clientDisplayName ? (
          <>
            <Text style={styles.rowLabel}>{t('book.name')}</Text>
            <Text style={styles.rowValue}>{visit.clientDisplayName}</Text>
          </>
        ) : null}
        {visit.priceSnapshot != null ? (
          <>
            <Text style={styles.rowLabel}>{t('visit.price')}</Text>
            <Text style={styles.rowValue}>
              {formatMoney(visit.priceSnapshot, visit.currencyCode ?? 'GEL')}
            </Text>
          </>
        ) : null}
      </View>

      {!cancelled && token ? (
        <View style={styles.actions}>
          <Button
            label={t('book.addToCalendar')}
            onPress={() => {
              if (typeof window !== 'undefined') {
                window.open(
                  `${API_BASE}/api/public/bookings/${id}/calendar.ics?token=${encodeURIComponent(token)}`,
                  '_blank'
                );
              }
            }}
          />
          <Button label={t('visit.reschedule')} variant="ghost" onPress={openReschedule} disabled={busy} />
          <Button label={t('action.cancel')} variant="ghost" onPress={doCancel} disabled={busy} />
        </View>
      ) : null}

      {rescheduleOpen ? (
        <View style={styles.dialog}>
          <Text style={styles.h2}>{t('visit.pickSlot')}</Text>
          {rescheduleUnavailable ? (
            <EmptyState title={t('visit.rescheduleUnavailable')} description={t('visit.rescheduleUnavailableHint')} />
          ) : null}
          {slotsError && !rescheduleUnavailable ? <Text style={styles.muted}>{slotsError}</Text> : null}
          <View style={styles.slotGrid}>
            {slots.map((s) => (
              <Button
                key={s}
                label={new Date(s).toLocaleString()}
                variant="ghost"
                disabled={busy}
                onPress={() => doReschedule(s)}
                style={styles.slotBtn}
              />
            ))}
          </View>
          <Button label={t('action.cancel')} variant="ghost" onPress={() => setRescheduleOpen(false)} />
        </View>
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: {
    flexGrow: 1,
    padding: tokens.space.xl,
    backgroundColor: tokens.color.canvas,
    maxWidth: 560,
    alignSelf: 'center',
    width: '100%',
  },
  ref: {
    fontFamily: 'Plus Jakarta Sans',
    fontWeight: '600',
    color: tokens.color.muted,
    letterSpacing: 1,
    marginBottom: tokens.space.sm,
  },
  h1: {
    fontSize: 28,
    fontWeight: '700',
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
  },
  h2: {
    fontSize: 18,
    fontWeight: '600',
    color: tokens.color.ink,
    marginBottom: tokens.space.md,
    fontFamily: 'Plus Jakarta Sans',
  },
  lead: { color: tokens.color.muted, marginTop: tokens.space.sm, marginBottom: tokens.space.md },
  muted: { color: tokens.color.muted },
  err: { color: tokens.color.status.noShow, marginVertical: tokens.space.sm },
  card: {
    marginTop: tokens.space.lg,
    backgroundColor: tokens.color.surface,
    borderRadius: tokens.radius.card,
    borderWidth: 1,
    borderColor: tokens.color.line,
    borderLeftWidth: 4,
    padding: tokens.space.lg,
  },
  service: {
    fontSize: 20,
    fontWeight: '700',
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
    marginBottom: tokens.space.md,
  },
  rowLabel: { color: tokens.color.muted, fontSize: 12, marginTop: tokens.space.sm },
  rowValue: { color: tokens.color.ink, fontSize: 16 },
  actions: { marginTop: tokens.space.xl, gap: tokens.space.sm, flexDirection: 'row', flexWrap: 'wrap' },
  dialog: {
    marginTop: tokens.space.xl,
    padding: tokens.space.lg,
    backgroundColor: tokens.color.surface,
    borderRadius: tokens.radius.card,
    borderWidth: 1,
    borderColor: tokens.color.line,
  },
  slotGrid: { gap: tokens.space.sm, marginBottom: tokens.space.md },
  slotBtn: { alignSelf: 'stretch' },
});
