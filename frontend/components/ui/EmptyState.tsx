import { StyleSheet, Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { tokens } from '@/src/theme/tokens';

type Props = {
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
};

export function EmptyState({ title, description, actionLabel, onAction }: Props) {
  return (
    <View style={styles.wrap}>
      <Text style={styles.title}>{title}</Text>
      {description ? <Text style={styles.description}>{description}</Text> : null}
      {actionLabel && onAction ? <Button label={actionLabel} variant="ghost" onPress={onAction} style={styles.btn} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    paddingVertical: tokens.space.xl,
    paddingHorizontal: tokens.space.lg,
    borderRadius: tokens.radius.card,
    borderWidth: 1,
    borderColor: tokens.color.line,
    borderStyle: 'dashed',
    backgroundColor: tokens.color.mist,
    alignItems: 'flex-start',
  },
  title: { fontWeight: '600', color: tokens.color.ink, fontFamily: 'Plus Jakarta Sans' },
  description: { marginTop: tokens.space.sm, color: tokens.color.muted, lineHeight: 20 },
  btn: { marginTop: tokens.space.md },
});
