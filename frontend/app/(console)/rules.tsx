import { useEffect, useState, type ReactNode } from 'react';
import { Pressable, ScrollView, Switch, Text, TextInput, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { api, LUMEN_TENANT_ID, type BookingRulesView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

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
  const { color, space, radius } = useThemeTokens();
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

  const Field = ({ label, children }: { label: string; children: ReactNode }) => (
    <View style={{ marginBottom: space.lg }}>
      <Text style={{ color: color.ink, fontWeight: '600', marginBottom: space.sm }}>{label}</Text>
      {children}
    </View>
  );

  const Toggle = ({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) => (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: space.md,
        marginBottom: space.lg,
      }}
    >
      <Text style={{ flex: 1, color: color.ink, fontWeight: '600' }}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: color.line, true: `${color.primary}88` }}
        thumbColor={value ? color.primary : color.mist}
      />
    </View>
  );

  const Segment = <V,>({ options, value, onChange }: { options: { value: V; label: string }[]; value: V; onChange: (v: V) => void }) => (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
      {options.map((opt) => {
        const active = value === opt.value;
        return (
          <Pressable
            key={String(opt.value)}
            onPress={() => onChange(opt.value)}
            style={{
              paddingVertical: space.sm,
              paddingHorizontal: space.md,
              borderRadius: radius.control,
              borderWidth: 1,
              borderColor: active ? color.primary : color.line,
              backgroundColor: active ? `${color.primary}14` : color.surface,
            }}
          >
            <Text style={{ color: active ? color.primary : color.muted, fontWeight: active ? '700' : '500' }}>{opt.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );

  const NumberInput = ({ value, onChange, suffix }: { value: number; onChange: (n: number) => void; suffix?: string }) => (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
      <TextInput
        keyboardType="number-pad"
        style={{
          borderWidth: 1,
          borderColor: color.line,
          padding: space.md,
          borderRadius: radius.control,
          backgroundColor: color.surface,
          minWidth: 120,
          color: color.ink,
        }}
        value={String(value)}
        onChangeText={(text) => {
          const n = Number(text.replace(/[^\d]/g, ''));
          onChange(Number.isFinite(n) ? n : 0);
        }}
      />
      {suffix ? <Text style={{ color: color.muted }}>{suffix}</Text> : null}
    </View>
  );

  if (loading || !draft) {
    return (
      <ScrollView>
        <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.sm, fontFamily: 'Plus Jakarta Sans', color: color.ink }}>
          {t('nav.rules')}
        </Text>
        <EmptyState title={t('rules.loading')} />
      </ScrollView>
    );
  }

  const card = {
    backgroundColor: color.surface,
    padding: space.lg,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: color.line,
    marginBottom: space.lg,
    maxWidth: 560,
  };

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.sm, fontFamily: 'Plus Jakarta Sans', color: color.ink }}>
        {t('nav.rules')}
      </Text>
      <Text style={{ color: color.muted, marginBottom: space.lg, maxWidth: 520 }}>{t('rules.lead')}</Text>
      {error ? <Text style={{ color: color.status.noShow, marginBottom: space.sm }}>{error}</Text> : null}
      {saved ? <Text style={{ color: color.primary, marginBottom: space.sm, fontWeight: '600' }}>{t('rules.saved')}</Text> : null}

      <View style={card}>
        <Field label={t('rules.slotStep')}>
          <Segment
            options={SLOT_STEPS.map((step) => ({ value: step, label: `${step} min` }))}
            value={draft.slotStepMinutes}
            onChange={(v) => patch('slotStepMinutes', v)}
          />
        </Field>
        <Field label={t('rules.minNotice')}>
          <NumberInput value={draft.minNoticeMinutes} onChange={(n) => patch('minNoticeMinutes', n)} suffix="min" />
        </Field>
        <Field label={t('rules.horizon')}>
          <NumberInput value={draft.horizonDays} onChange={(n) => patch('horizonDays', n)} suffix="days" />
        </Field>
        <Field label={t('rules.lateCancel')}>
          <NumberInput value={draft.lateCancellationHours} onChange={(n) => patch('lateCancellationHours', n)} suffix="h" />
        </Field>
      </View>

      <View style={card}>
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
        <View style={{ marginBottom: 0 }}>
          <Toggle
            label={t('rules.deductNoShow')}
            value={draft.deductPackageOnNoShow}
            onChange={(v) => patch('deductPackageOnNoShow', v)}
          />
        </View>
      </View>

      <View style={card}>
        <Field label={t('rules.weekStarts')}>
          <Segment
            options={WEEK_STARTS.map((opt) => ({ value: opt.value, label: t(opt.labelKey) }))}
            value={draft.weekStartsOn}
            onChange={(v) => patch('weekStartsOn', v)}
          />
        </Field>
        <View style={{ marginBottom: 0 }}>
          <Field label={t('rules.timeFormat')}>
            <Segment
              options={[
                { value: true, label: '24h' },
                { value: false, label: '12h' },
              ]}
              value={draft.timeFormat24h}
              onChange={(v) => patch('timeFormat24h', v)}
            />
          </Field>
        </View>
      </View>

      <Button label={t('action.save')} onPress={save} style={{ marginTop: space.md }} />
    </ScrollView>
  );
}
