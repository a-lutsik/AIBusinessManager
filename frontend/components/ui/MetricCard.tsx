import { StyleSheet, Text, View } from 'react-native';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

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
  const display =
    insufficientData || value == null || value === ''
      ? t('metric.pending')
      : typeof value === 'number'
        ? formatMetricValue(value, unit)
        : String(value);

  return (
    <View style={styles.card}>
      <Text style={styles.label}>{humanizeKey(label)}</Text>
      <Text style={[styles.value, insufficientData ? styles.pending : null]}>{display}</Text>
      {deltaMoM != null && !insufficientData ? (
        <Text style={[styles.delta, deltaMoM >= 0 ? styles.up : styles.down]}>
          {deltaMoM >= 0 ? '+' : ''}
          {(deltaMoM * 100).toFixed(1)}% MoM
        </Text>
      ) : null}
      {windowLabel ? <Text style={styles.window}>{windowLabel}</Text> : null}
      {explanation ? <Text style={styles.explanation}>{explanation}</Text> : null}
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

const styles = StyleSheet.create({
  card: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderRadius: tokens.radius.card,
    minWidth: 200,
    flexGrow: 1,
    flexBasis: 200,
    borderWidth: 1,
    borderColor: tokens.color.line,
  },
  label: { fontSize: 13, fontWeight: '600', color: tokens.color.muted, marginBottom: 4 },
  value: { fontSize: 22, fontWeight: '700', color: tokens.color.primary, fontFamily: 'Plus Jakarta Sans' },
  pending: { color: tokens.color.muted, fontSize: 15, fontWeight: '500' },
  delta: { marginTop: 4, fontSize: 12, fontWeight: '600' },
  up: { color: tokens.color.primary },
  down: { color: tokens.color.status.noShow },
  window: { marginTop: 4, fontSize: 11, color: tokens.color.muted },
  explanation: { marginTop: tokens.space.sm, fontSize: 12, color: tokens.color.muted, lineHeight: 16 },
});
