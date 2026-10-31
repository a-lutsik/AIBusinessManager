import { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { FormButtons, FormMessages, LoadFailed } from '@/components/catalog/FormMessages';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { api, LUMEN_TENANT_ID, type WeeklyInterval } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { CatalogInputError, catalogErrorText } from '@/src/utils/catalogErrors';
import { minuteToTime, timeToMinute } from '@/src/utils/schedule';

const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7];
const NEW_INTERVAL = { start: '10:00', end: '19:00' };

type Draft = { weekday: number; start: string; end: string };

const toDraft = (intervals: WeeklyInterval[]): Draft[] =>
  intervals.map((i) => ({ weekday: i.weekday, start: minuteToTime(i.startMinute), end: minuteToTime(i.endMinute) }));

const intervalLabel = (draft: Draft): string => `${draft.start} – ${draft.end}`;

function toIntervals(drafts: Draft[]): WeeklyInterval[] {
  return drafts.map((draft) => {
    const startMinute = timeToMinute(draft.start);
    const endMinute = timeToMinute(draft.end);
    if (startMinute === null || endMinute === null) throw new CatalogInputError('TIME_FORMAT');
    return { weekday: draft.weekday, startMinute, endMinute };
  });
}

export function WeeklySchedule({ specialistId, editable }: { specialistId: string; editable: boolean }) {
  const { color, space, radius } = useThemeTokens();
  const [drafts, setDrafts] = useState<Draft[]>([]);
  // Until the stored week is here there is no form: an empty one would look like a week off, and Save would write it.
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let stale = false;
    api
      .schedule(LUMEN_TENANT_ID, specialistId)
      .then((intervals) => {
        if (stale) return;
        setDrafts(toDraft(intervals));
        setLoaded(true);
      })
      .catch(() => {
        if (!stale) setLoadFailed(true);
      });
    return () => {
      stale = true;
    };
  }, [specialistId, attempt]);

  const retry = () => {
    setLoadFailed(false);
    setAttempt((n) => n + 1);
  };

  const change = (next: Draft[]) => {
    setDrafts(next);
    setSaved(false);
  };

  const save = async () => {
    setError(null);
    setSaved(false);
    setBusy(true);
    try {
      setDrafts(toDraft(await api.saveSchedule(LUMEN_TENANT_ID, specialistId, toIntervals(drafts))));
      setSaved(true);
    } catch (e: unknown) {
      setError(catalogErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View testID="weekly-schedule" style={{ marginTop: space.xl, maxWidth: 560 }}>
      <Text style={{ fontWeight: '700', fontSize: 18, color: color.ink, marginBottom: space.xs }}>{t('schedule.title')}</Text>
      <Text style={{ color: color.muted, marginBottom: space.md }}>{t('schedule.lead')}</Text>
      {loadFailed ? <LoadFailed onRetry={retry} /> : null}
      {loaded ? (
        <>
      <View style={{ backgroundColor: color.surface, borderRadius: radius.card, borderWidth: 1, borderColor: color.line, padding: space.lg, marginBottom: space.md }}>
        {WEEKDAYS.map((weekday) => {
          const label = t(`schedule.weekday.${weekday}`);
          const ofDay = drafts.flatMap((draft, index) => (draft.weekday === weekday ? [{ draft, index }] : []));
          return (
            <View key={weekday} testID={`schedule-day-${weekday}`} style={{ marginBottom: space.md }}>
              <Text style={{ fontWeight: '600', color: color.ink, marginBottom: space.xs }}>{label}</Text>
              {ofDay.length === 0 ? <Text style={{ color: color.muted }}>{t('schedule.dayOff')}</Text> : null}
              {ofDay.map(({ draft, index }) =>
                editable ? (
                  <View key={index} style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'flex-end', gap: space.sm }}>
                    <TextField
                      label={t('schedule.start')}
                      value={draft.start}
                      onChangeText={(start) => change(drafts.map((d, i) => (i === index ? { ...d, start } : d)))}
                      style={{ width: 100 }}
                    />
                    <TextField
                      label={t('schedule.end')}
                      value={draft.end}
                      onChangeText={(end) => change(drafts.map((d, i) => (i === index ? { ...d, end } : d)))}
                      style={{ width: 100 }}
                    />
                    <Button label={t('action.remove')} variant="ghost" onPress={() => change(drafts.filter((_, i) => i !== index))} style={{ marginBottom: space.md }} />
                  </View>
                ) : (
                  <Text key={index} style={{ color: color.ink }}>{intervalLabel(draft)}</Text>
                )
              )}
              {editable ? (
                <Button label={t('schedule.add')} variant="ghost" onPress={() => change([...drafts, { weekday, ...NEW_INTERVAL }])} style={{ marginTop: space.xs }} />
              ) : null}
            </View>
          );
        })}
      </View>
      {editable ? (
        <>
          <FormMessages error={error} saved={saved ? t('schedule.saved') : null} />
          <FormButtons busy={busy} onSave={save} saveLabel={t('schedule.save')} />
        </>
      ) : null}
        </>
      ) : null}
    </View>
  );
}
