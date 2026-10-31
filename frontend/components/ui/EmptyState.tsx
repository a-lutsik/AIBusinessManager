import { Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';

type Props = {
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
};

export function EmptyState({ title, description, actionLabel, onAction }: Props) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View
      style={{
        paddingVertical: space.xl,
        paddingHorizontal: space.lg,
        borderRadius: radius.card,
        borderWidth: 1,
        borderColor: color.line,
        borderStyle: 'dashed',
        backgroundColor: color.mist,
        alignItems: 'flex-start',
      }}
    >
      <Text style={{ fontWeight: '600', color: color.ink, fontFamily: DISPLAY_FONT }}>{title}</Text>
      {description ? (
        <Text style={{ marginTop: space.sm, color: color.muted, lineHeight: 20 }}>{description}</Text>
      ) : null}
      {actionLabel && onAction ? (
        <Button label={actionLabel} variant="ghost" onPress={onAction} style={{ marginTop: space.md }} />
      ) : null}
    </View>
  );
}
