import { Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

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
  const { color, space, radius } = useThemeTokens();
  const accent =
    severity === 'critical' ? color.status.noShow : severity === 'warn' ? color.tertiary : color.secondary;

  return (
    <View
      style={{
        backgroundColor: color.surface,
        padding: space.lg,
        borderRadius: radius.card,
        marginBottom: space.sm,
        borderWidth: 1,
        borderColor: color.line,
        gap: 4,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
        <View style={{ width: 8, height: 8, borderRadius: radius.pill, backgroundColor: accent }} />
        <Text style={{ fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans', flexShrink: 1 }}>
          {title}
        </Text>
      </View>
      {evidence ? <Text style={{ color: color.muted, fontSize: 13 }}>{evidence}</Text> : null}
      {suggestedAction ? <Text style={{ color: color.ink, fontSize: 14 }}>{suggestedAction}</Text> : null}
      {(onAction || onDismiss) && (
        <View style={{ flexDirection: 'row', gap: space.sm, marginTop: space.md, flexWrap: 'wrap' }}>
          {onAction ? <Button label={actionLabel ?? t('action.open')} variant="ai" onPress={onAction} /> : null}
          {onDismiss ? <Button label={t('action.dismiss')} variant="ghost" onPress={onDismiss} /> : null}
        </View>
      )}
    </View>
  );
}
