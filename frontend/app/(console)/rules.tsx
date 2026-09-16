import { useEffect, useState, type ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { api, LUMEN_TENANT_ID, type BookingRulesView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

const SLOT_STEPS = [15, 30, 60] as const;
const WEEK_STARTS = [
  { value: 1, labelKey: 'rules.week.monday' },
  { value: 0, labelKey: 'rules.week.sunday' },
] as const;

const DEFAULT_RULES: BookingRulesView = {
  slotStepMinutes: 15,
  minNoticeMinutes: 60,
  horizonDays: 28,
  clientRescheduleAllowed: true,
  lateCancellationHours: 2,
  newClientRequiresConfirmation: false,
  deductPackageOnNoShow: false,
  weekStartsOn: 1,
  timeFormat24h: true,
};

export default function RulesScreen() {
  const [draft, setDraft] = useState<BookingRulesView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api
      .rules(LUMEN_TENANT_ID)
      .then((r) => setDraft({ ...DEFAULT_RULES, ...r }))
      .catch(() => setDraft({ ...DEFAULT_RULES }))
      .finally(() => setLoading(false));
  }, []);

  const patch = <K extends keyof BookingRulesView>(key: K, value: BookingRulesView[K]) => {
    setDraft((prev) => (prev ? { ...prev, [key]: value } : prev));
    setSaved(false);
  };

  const save = async () => {
    if (!draft) return;
    setError(null);
    setSaved(false);
    try {
      const body: BookingRulesView = {
        slotStepMinutes: draft.slotStepMinutes,
        minNoticeMinutes: Number(draft.minNoticeMinutes) || 0,
        horizonDays: Number(draft.horizonDays) || 0,
        clientRescheduleAllowed: !!draft.clientRescheduleAllowed,
        lateCancellationHours: Number(draft.lateCancellationHours) || 0,
        newClientRequiresConfirmation: !!draft.newClientRequiresConfirmation,
        deductPackageOnNoShow: !!draft.deductPackageOnNoShow,
        weekStartsOn: draft.weekStartsOn,
        timeFormat24h: !!draft.timeFormat24h,
      };
      const next = await api.saveRules(LUMEN_TENANT_ID, body);
      setDraft({ ...DEFAULT_RULES, ...next });
      setSaved(true);
    } catch (e: any) {
      setError(String(e.message ?? e));
    }
  };

  if (loading || !draft) {
    return (
      <ScrollView>
        <Text style={styles.h1}>{t('nav.rules')}</Text>
        <EmptyState title={t('rules.loading')} />
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.pad}>
      <Text style={styles.h1}>{t('nav.rules')}</Text>
      <Text style={styles.lead}>{t('rules.lead')}</Text>
      {error ? <Text style={styles.err}>{error}</Text> : null}
      {saved ? <Text style={styles.ok}>{t('rules.saved')}</Text> : null}

      <Field label={t('rules.slotStep')}>
        <View style={styles.segment}>
          {SLOT_STEPS.map((step) => {
            const active = draft.slotStepMinutes === step;
            return (
              <Pressable
                key={step}
                onPress={() => patch('slotStepMinutes', step)}
                style={[styles.segBtn, active ? styles.segActive : null]}
              >
                <Text style={[styles.segText, active ? styles.segTextActive : null]}>{step} min</Text>
              </Pressable>
            );
          })}
        </View>
      </Field>

      <Field label={t('rules.minNotice')}>
        <NumberInput value={draft.minNoticeMinutes} onChange={(n) => patch('minNoticeMinutes', n)} suffix="min" />
      </Field>

      <Field label={t('rules.horizon')}>
        <NumberInput value={draft.horizonDays} onChange={(n) => patch('horizonDays', n)} suffix="days" />
      </Field>

      <Field label={t('rules.lateCancel')}>
        <NumberInput
          value={draft.lateCancellationHours}
          onChange={(n) => patch('lateCancellationHours', n)}
          suffix="h"
        />
      </Field>

      <Toggle
        label={t('rules.clientReschedule')}
        value={draft.clientRescheduleAllowed}
        onChange={(v) => patch('clientRescheduleAllowed', v)}
      />
      <Toggle
        label={t('rules.newClientConfirm')}
        value={draft.newClientRequiresConfirmation}
        onChange={(v) => patch('newClientRequiresConfirmation', v)}
      />
      <Toggle
        label={t('rules.deductNoShow')}
        value={draft.deductPackageOnNoShow}
        onChange={(v) => patch('deductPackageOnNoShow', v)}
      />

      <Field label={t('rules.weekStarts')}>
        <View style={styles.segment}>
          {WEEK_STARTS.map((opt) => {
            const active = draft.weekStartsOn === opt.value;
            return (
              <Pressable
                key={opt.value}
                onPress={() => patch('weekStartsOn', opt.value)}
                style={[styles.segBtn, active ? styles.segActive : null]}
              >
                <Text style={[styles.segText, active ? styles.segTextActive : null]}>{t(opt.labelKey)}</Text>
              </Pressable>
            );
          })}
        </View>
      </Field>

      <Field label={t('rules.timeFormat')}>
        <View style={styles.segment}>
          <Pressable
            onPress={() => patch('timeFormat24h', true)}
            style={[styles.segBtn, draft.timeFormat24h ? styles.segActive : null]}
          >
            <Text style={[styles.segText, draft.timeFormat24h ? styles.segTextActive : null]}>24h</Text>
          </Pressable>
          <Pressable
            onPress={() => patch('timeFormat24h', false)}
            style={[styles.segBtn, !draft.timeFormat24h ? styles.segActive : null]}
          >
            <Text style={[styles.segText, !draft.timeFormat24h ? styles.segTextActive : null]}>12h</Text>
          </Pressable>
        </View>
      </Field>

      <Button label={t('action.save')} onPress={save} style={styles.save} />
    </ScrollView>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      {children}
    </View>
  );
}

function Toggle({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={styles.toggleRow}>
      <Text style={styles.toggleLabel}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: tokens.color.line, true: `${tokens.color.primary}88` }}
        thumbColor={value ? tokens.color.primary : tokens.color.mist}
      />
    </View>
  );
}

function NumberInput({
  value,
  onChange,
  suffix,
}: {
  value: number;
  onChange: (n: number) => void;
  suffix?: string;
}) {
  return (
    <View style={styles.numRow}>
      <TextInput
        keyboardType="number-pad"
        style={styles.input}
        value={String(value)}
        onChangeText={(text) => {
          const n = Number(text.replace(/[^\d]/g, ''));
          onChange(Number.isFinite(n) ? n : 0);
        }}
      />
      {suffix ? <Text style={styles.suffix}>{suffix}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  pad: { paddingBottom: tokens.space.xxl },
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.sm, fontFamily: 'Plus Jakarta Sans', color: tokens.color.ink },
  lead: { color: tokens.color.muted, marginBottom: tokens.space.lg, maxWidth: 520 },
  field: { marginBottom: tokens.space.lg },
  label: { color: tokens.color.ink, fontWeight: '600', marginBottom: tokens.space.sm },
  input: {
    borderWidth: 1,
    borderColor: tokens.color.line,
    padding: tokens.space.md,
    borderRadius: tokens.radius.control,
    backgroundColor: tokens.color.surface,
    minWidth: 120,
    color: tokens.color.ink,
  },
  numRow: { flexDirection: 'row', alignItems: 'center', gap: tokens.space.sm },
  suffix: { color: tokens.color.muted },
  segment: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.sm },
  segBtn: {
    paddingVertical: tokens.space.sm,
    paddingHorizontal: tokens.space.md,
    borderRadius: tokens.radius.control,
    borderWidth: 1,
    borderColor: tokens.color.line,
    backgroundColor: tokens.color.surface,
  },
  segActive: { borderColor: tokens.color.primary, backgroundColor: `${tokens.color.primary}14` },
  segText: { color: tokens.color.muted, fontWeight: '500' },
  segTextActive: { color: tokens.color.primary, fontWeight: '700' },
  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: tokens.space.md,
    marginBottom: tokens.space.lg,
    paddingVertical: tokens.space.sm,
    maxWidth: 520,
  },
  toggleLabel: { flex: 1, color: tokens.color.ink, fontWeight: '600' },
  save: { marginTop: tokens.space.md },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.sm },
  ok: { color: tokens.color.primary, marginBottom: tokens.space.sm, fontWeight: '600' },
});
