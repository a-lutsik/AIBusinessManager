import { Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import type { DraftAction, DraftStatus, DraftType } from '@/src/api/client';
import { currentLocale, t } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens, type Palette } from '@/src/theme/tokens';
import { DATE_TIME_OPTIONS } from '@/src/utils/datetime';
import { formatMoney } from '@/src/utils/money';

export type DraftDecision = 'confirm' | 'reject';

const TITLE_KEYS: Record<DraftType, string> = {
  draft_booking: 'ai.draft.booking',
  draft_reschedule: 'ai.draft.reschedule',
  draft_cancel: 'ai.draft.cancel',
};

/** Codes of draft and booking-engine refusals that have a translated message; any other code gets the generic one. */
const FAILURE_CODES: readonly string[] = [
  'DRAFT_STALE',
  'DRAFT_EXPIRED',
  'DRAFT_INVALID',
  'DRAFT_NOT_PENDING',
  'DRAFT_NOT_FOUND',
  'SLOT_OCCUPIED',
  'MIN_NOTICE',
  'BEYOND_HORIZON',
  'SERVICE_INACTIVE',
  'SERVICE_NOT_OFFERED',
  'APPOINTMENT_CANCELLED',
  'INVALID_TRANSITION',
];

/** Translated message for a draft or engine error code, or null when the code has none. */
export function failureText(code: string | null | undefined): string | null {
  return code && FAILURE_CODES.includes(code) ? t(`ai.failure.${code}`) : null;
}

function statusTone(status: DraftStatus, color: Palette): string {
  switch (status) {
    case 'CONFIRMED':
      return color.status.confirmed;
    case 'FAILED':
      return color.status.noShow;
    case 'PENDING':
      return color.status.pending;
    default:
      return color.status.cancelled;
  }
}

/** Tenant-local `YYYY-MM-DDTHH:MM` as written, without any time zone conversion. */
function formatLocal(local: string | null | undefined): string | null {
  const match = local?.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/);
  if (!match) return local ?? null;
  const [year, month, day, hour, minute] = match.slice(1).map(Number);
  // UTC on both sides: the browser's own daylight saving change must not move a wall clock time.
  return new Date(Date.UTC(year, month - 1, day, hour, minute)).toLocaleString(currentLocale(), { ...DATE_TIME_OPTIONS, timeZone: 'UTC' });
}

function detailRows(draft: DraftAction): [string, string | null | undefined][] {
  const s = draft.summary;
  if (!s) return [];
  const rows: [string, string | null | undefined][] = [
    [t('ai.draft.client'), s.clientName],
    [t('ai.draft.master'), s.specialistName],
    [t('ai.draft.service'), s.serviceName],
  ];
  switch (draft.type) {
    case 'draft_booking':
      rows.push(
        [t('ai.draft.time'), formatLocal(s.localStart)],
        [t('ai.draft.duration'), s.durationMinutes != null ? t('unit.minutes', { count: s.durationMinutes }) : null],
        [t('ai.draft.price'), s.priceMinor != null ? formatMoney(s.priceMinor, s.currencyCode ?? undefined, currentLocale()) : null]
      );
      break;
    case 'draft_reschedule':
      rows.push([
        t('ai.draft.time'),
        s.localFrom || s.localTo ? t('ai.draft.fromTo', { from: formatLocal(s.localFrom) ?? '—', to: formatLocal(s.localTo) ?? '—' }) : null,
      ]);
      break;
    case 'draft_cancel':
      rows.push(
        [t('ai.draft.time'), formatLocal(s.localStart)],
        [t('ai.draft.initiatedBy'), s.initiatedBy ? t(`ai.draft.initiator.${s.initiatedBy}`) : null],
        [t('ai.draft.reason'), s.reason]
      );
      break;
  }
  return rows;
}

type Props = {
  draft: DraftAction;
  /** A confirm or reject request is running: the buttons stay disabled. */
  busy: boolean;
  onDecide: (id: string, decision: DraftDecision) => void;
};

/** One drafted action. Everything from the model (names, reason) is shown as plain text, never as HTML or a link. */
export function DraftCard({ draft, busy, onDecide }: Props) {
  const { color, space, radius } = useThemeTokens();
  const tone = statusTone(draft.status, color);
  const failure =
    draft.status === 'FAILED'
      ? (failureText(draft.failureCode) ?? t('ai.failure.generic'))
      : draft.status === 'EXPIRED'
        ? t('ai.failure.DRAFT_EXPIRED')
        : null;

  return (
    <View
      testID={`draft-card-${draft.id}`}
      style={{
        marginTop: space.md,
        padding: space.md,
        borderRadius: radius.control,
        backgroundColor: `${color.ai}12`,
        borderWidth: 1,
        borderColor: `${color.ai}44`,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.sm, flexWrap: 'wrap' }}>
        <Text style={{ fontWeight: '600', color: color.ink, fontFamily: DISPLAY_FONT }}>
          {t(TITLE_KEYS[draft.type] ?? 'ai.draftUntitled')}
        </Text>
        <View style={{ paddingHorizontal: space.sm + 2, paddingVertical: 2, borderRadius: radius.pill, backgroundColor: `${tone}22` }}>
          <Text style={{ fontSize: 11, fontWeight: '700', color: tone }}>{t(`ai.status.${draft.status}`)}</Text>
        </View>
      </View>
      {draft.summary ? null : <Text style={{ color: color.muted, marginTop: space.sm }}>{t('ai.draft.unreadable')}</Text>}
      {detailRows(draft)
        .filter(([, value]) => value)
        .map(([label, value]) => (
          <View key={label} style={{ flexDirection: 'row', gap: space.sm, marginTop: space.xs + 2 }}>
            <Text style={{ width: 110, color: color.muted, fontSize: 13 }}>{label}</Text>
            <Text style={{ flex: 1, color: color.ink, fontSize: 13, lineHeight: 18 }}>{value}</Text>
          </View>
        ))}
      {failure ? <Text style={{ color: color.status.noShow, marginTop: space.sm, fontSize: 13 }}>{failure}</Text> : null}
      {draft.status === 'PENDING' ? (
        <View style={{ flexDirection: 'row', gap: space.sm, marginTop: space.md, flexWrap: 'wrap' }}>
          <Button label={t('action.confirm')} variant="ai" disabled={busy} onPress={() => onDecide(draft.id, 'confirm')} />
          <Button label={t('ai.reject')} variant="ghost" disabled={busy} onPress={() => onDecide(draft.id, 'reject')} />
        </View>
      ) : null}
    </View>
  );
}
