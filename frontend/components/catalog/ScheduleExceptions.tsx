import { useCallback, useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import { FormButtons, FormMessages, LoadFailed } from '@/components/catalog/FormMessages';
import { Button } from '@/components/ui/Button';
import { Segment } from '@/components/ui/Segment';
import { TextField } from '@/components/ui/TextField';
import { api, LUMEN_TENANT_ID, type ScheduleExceptionKind, type ScheduleExceptionView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { CatalogInputError, catalogErrorText } from '@/src/utils/catalogErrors';
import { isIsoDate, minuteToTime, timeToMinute, todayInZone } from '@/src/utils/schedule';

const KINDS: ScheduleExceptionKind[] = ['DAY_OFF', 'SHIFT'];

function minutesOf(start: string, end: string): { startMinute: number; endMinute: number } {
  const startMinute = timeToMinute(start);
  const endMinute = timeToMinute(end);
  if (startMinute === null || endMinute === null) throw new CatalogInputError('TIME_FORMAT');
  return { startMinute, endMinute };
}

export function ScheduleExceptions({ specialistId, editable, timezone }: { specialistId: string; editable: boolean; timezone?: string }) {
  const { color, space, radius } = useThemeTokens();
  const [rows, setRows] = useState<ScheduleExceptionView[]>([]);
  const [date, setDate] = useState('');
  const [kind, setKind] = useState<ScheduleExceptionKind>('DAY_OFF');
  const [start, setStart] = useState('10:00');
  const [end, setEnd] = useState('14:00');
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);
  // The form needs the list: it refuses a second entry for a date.
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const today = todayInZone(timezone);

  const reload = useCallback(
    () =>
      api
        .scheduleExceptions(LUMEN_TENANT_ID, specialistId, today)
        .then((next) => {
          setRows(next);
          setLoaded(true);
          setLoadFailed(false);
        })
        .catch(() => setLoadFailed(true)),
    [specialistId, today]
  );

  useEffect(() => {
    void reload();
  }, [reload]);

  const add = async () => {
    setError(null);
    setBusy(true);
    try {
      if (!isIsoDate(date)) throw new CatalogInputError('DATE_FORMAT');
      if (date < today) throw new CatalogInputError('DATE_PAST');
      if (rows.some((row) => row.date === date)) throw new CatalogInputError('EXCEPTION_DATE_TAKEN');
      await api.saveScheduleException(LUMEN_TENANT_ID, specialistId, {
        id: null,
        date,
        kind,
        ...(kind === 'SHIFT' ? minutesOf(start, end) : { startMinute: null, endMinute: null }),
        note: note.trim() ? note : null,
      });
      setDate('');
      setNote('');
      await reload();
    } catch (e: unknown) {
      setError(catalogErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: string) => {
    setError(null);
    setBusy(true);
    try {
      await api.deleteScheduleException(LUMEN_TENANT_ID, specialistId, id);
      await reload();
    } catch (e: unknown) {
      setError(catalogErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View testID="schedule-exceptions" style={{ marginTop: space.xl, maxWidth: 560 }}>
      <Text style={{ fontWeight: '700', fontSize: 18, color: color.ink, marginBottom: space.xs }}>{t('exceptions.title')}</Text>
      <Text style={{ color: color.muted, marginBottom: space.md }}>{t('exceptions.lead')}</Text>
      {loadFailed ? <LoadFailed onRetry={reload} /> : null}
      {loaded && rows.length === 0 ? <Text style={{ color: color.muted, marginBottom: space.md }}>{t('exceptions.empty')}</Text> : null}
      {rows.map((row) => {
        const hours = row.kind === 'SHIFT' && row.startMinute != null && row.endMinute != null
          ? ` · ${minuteToTime(row.startMinute)} – ${minuteToTime(row.endMinute)}`
          : '';
        const summary = `${row.date} · ${t(`exceptions.kind.${row.kind}`)}${hours}`;
        return (
        <View
          key={row.id}
          testID={`exception-${row.date}`}
          style={{
            flexDirection: 'row',
            flexWrap: 'wrap',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: space.sm,
            backgroundColor: color.surface,
            borderRadius: radius.control,
            borderWidth: 1,
            borderColor: color.line,
            padding: space.md,
            marginBottom: space.sm,
          }}
        >
          <View style={{ flexShrink: 1 }}>
            <Text style={{ color: color.ink, fontWeight: '600' }}>{summary}</Text>
            {row.note ? <Text style={{ color: color.muted, fontSize: 13 }}>{row.note}</Text> : null}
          </View>
          {editable ? <Button label={t('action.delete')} variant="ghost" onPress={() => remove(row.id)} disabled={busy} /> : null}
        </View>
        );
      })}
      {editable && loaded ? (
        <View style={{ backgroundColor: color.surface, borderRadius: radius.card, borderWidth: 1, borderColor: color.line, padding: space.lg, marginTop: space.sm }}>
          <TextField label={t('exceptions.date')} value={date} onChangeText={setDate} placeholder={today} />
          <View style={{ marginBottom: space.md }}>
            <Segment options={KINDS.map((value) => ({ value, label: t(`exceptions.kind.${value}`) }))} value={kind} onChange={setKind} />
          </View>
          {kind === 'SHIFT' ? (
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
              <TextField label={t('schedule.start')} value={start} onChangeText={setStart} style={{ width: 100 }} />
              <TextField label={t('schedule.end')} value={end} onChangeText={setEnd} style={{ width: 100 }} />
            </View>
          ) : null}
          <TextField label={t('exceptions.note')} value={note} onChangeText={setNote} />
          <FormMessages error={error} />
          <FormButtons busy={busy} onSave={add} saveLabel={t('exceptions.add')} />
        </View>
      ) : null}
    </View>
  );
}
