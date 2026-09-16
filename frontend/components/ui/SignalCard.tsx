import { StyleSheet, Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

type Props = {
  title: string;
  evidence?: string | null;
  suggestedAction?: string | null;
  severity?: 'info' | 'warn' | 'critical' | string | null;
  onDismiss?: () => void;
  onAction?: () => void;
  actionLabel?: string;
};

export function SignalCard({
  title,
  evidence,
  suggestedAction,
  severity,
  onDismiss,
  onAction,
  actionLabel,
}: Props) {
  const accent =
    severity === 'critical' ? tokens.color.status.noShow : severity === 'warn' ? tokens.color.tertiary : tokens.color.secondary;

  return (
    <View style={[styles.card, { borderLeftColor: accent }]}>
      <Text style={styles.title}>{title}</Text>
      {evidence ? <Text style={styles.evidence}>{evidence}</Text> : null}
      {suggestedAction ? <Text style={styles.action}>{suggestedAction}</Text> : null}
      {(onAction || onDismiss) && (
        <View style={styles.row}>
          {onAction ? <Button label={actionLabel ?? 'Act'} variant="ai" onPress={onAction} /> : null}
          {onDismiss ? (
            <Button label={t('action.dismiss')} variant="ghost" onPress={onDismiss} />
          ) : null}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderRadius: tokens.radius.card,
    marginBottom: tokens.space.sm,
    borderWidth: 1,
    borderColor: tokens.color.line,
    borderLeftWidth: 4,
  },
  title: { fontWeight: '700', color: tokens.color.ink, fontFamily: 'Plus Jakarta Sans', marginBottom: 4 },
  evidence: { color: tokens.color.muted, fontSize: 13, marginBottom: 4 },
  action: { color: tokens.color.ink, fontSize: 14 },
  row: { flexDirection: 'row', gap: tokens.space.sm, marginTop: tokens.space.md, flexWrap: 'wrap' },
});
