import { Text, View } from 'react-native';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

type Props = {
  label: string;
  value?: number | string | null;
  explanation?: string | null;
  insufficientData?: boolean;
  /** MoM delta as fraction (0.12 = +12%). Only show when provided. */
  deltaMoM?: number | null;
  unit?: string | null;
  windowLabel?: string | null;
};

export function MetricCard({ label, value, explanation, insufficientData, deltaMoM, unit, windowLabel }: Props) {
  const { color, space, radius } = useThemeTokens();
  const display =
    insufficientData || value == null || value === ''
      ? t('metric.pending')
      : typeof value === 'number'
        ? formatMetricValue(value, unit)
        : String(value);

  return (
    <View
      style={{
        backgroundColor: color.surface,
        paddingVertical: space.lg,
        paddingHorizontal: space.lg,
        borderRadius: radius.card,
        minWidth: 160,
        flexGrow: 1,
        flexBasis: 160,
        borderWidth: 1,
        borderColor: color.line,
        gap: 4,
      }}
    >
      <Text
        style={{
          fontSize: 11,
          fontWeight: '700',
          letterSpacing: 0.04 * 11,
          textTransform: 'uppercase',
          color: color.muted,
        }}
      >
        {humanizeKey(label)}
      </Text>
      <Text
        style={{
          fontSize: 24,
          fontWeight: '700',
          color: insufficientData ? color.muted : color.ink,
          fontFamily: 'Plus Jakarta Sans',
        }}
      >
        {display}
      </Text>
      {deltaMoM != null && !insufficientData ? (
        <Text style={{ fontSize: 12, fontWeight: '600', color: deltaMoM >= 0 ? color.primary : color.status.noShow }}>
          {deltaMoM >= 0 ? '+' : ''}
          {(deltaMoM * 100).toFixed(1)}% MoM
        </Text>
      ) : null}
      {windowLabel ? <Text style={{ fontSize: 11, color: color.muted }}>{windowLabel}</Text> : null}
      {explanation ? (
        <Text style={{ marginTop: space.xs, fontSize: 12, color: color.muted, lineHeight: 16 }}>{explanation}</Text>
      ) : null}
    </View>
  );
}

function humanizeKey(key: string): string {
  if (!key.includes('_') && !key.includes('.')) return key;
  return key
    .replace(/[._]/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function formatMetricValue(value: number, unit?: string | null): string {
  if (unit === 'percent' || unit === '%') {
    return `${(value * 100).toFixed(1)}%`;
  }
  if (unit === 'currency' || unit === 'money') {
    return value.toLocaleString(undefined, { maximumFractionDigits: 0 });
  }
  if (Number.isInteger(value)) return String(value);
  return value.toFixed(2);
}
