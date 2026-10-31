import { Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

/** The refusal or the confirmation of a save, next to the button that caused it. */
export function FormMessages({ error, saved }: { error: string | null; saved?: string | null }) {
  const { color, space } = useThemeTokens();
  if (error) {
    return <Text accessibilityRole="alert" testID="form-error" style={{ color: color.status.noShow, marginBottom: space.sm }}>{error}</Text>;
  }
  return saved ? <Text style={{ color: color.primary, fontWeight: '600', marginBottom: space.sm }}>{saved}</Text> : null;
}

/** A read that failed: what is on screen stays, the owner can ask again. */
export function LoadFailed({ onRetry }: { onRetry: () => void }) {
  const { color, space } = useThemeTokens();
  return (
    <View testID="load-failed" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: space.sm, marginBottom: space.md }}>
      <Text accessibilityRole="alert" style={{ color: color.status.noShow }}>{t('catalog.loadFailed')}</Text>
      <Button label={t('action.retry')} variant="ghost" onPress={onRetry} />
    </View>
  );
}

export function FormButtons({ busy, onSave, onCancel, saveLabel }: { busy: boolean; onSave: () => void; onCancel?: () => void; saveLabel?: string }) {
  const { space } = useThemeTokens();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
      <Button label={saveLabel ?? t('action.save')} onPress={onSave} disabled={busy} />
      {onCancel ? <Button label={t('action.cancel')} variant="ghost" onPress={onCancel} disabled={busy} /> : null}
    </View>
  );
}
