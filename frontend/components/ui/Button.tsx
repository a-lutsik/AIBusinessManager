import { Pressable, StyleSheet, Text, type ViewStyle } from 'react-native';
import { tokens } from '@/src/theme/tokens';

export type ButtonVariant = 'primary' | 'ai' | 'ghost';

type Props = {
  label: string;
  onPress?: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  style?: ViewStyle;
};

export function Button({ label, onPress, variant = 'primary', disabled, style }: Props) {
  const tone = variant === 'ai' ? styles.ai : variant === 'ghost' ? styles.ghost : styles.primary;
  const labelTone = variant === 'ghost' ? styles.ghostLabel : styles.solidLabel;
  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={[styles.base, tone, disabled ? styles.disabled : null, style]}
    >
      <Text style={labelTone}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  base: {
    paddingVertical: tokens.space.sm + 2,
    paddingHorizontal: tokens.space.lg,
    borderRadius: tokens.radius.control,
    alignSelf: 'flex-start',
  },
  primary: { backgroundColor: tokens.color.primary },
  ai: { backgroundColor: tokens.color.ai },
  ghost: {
    backgroundColor: 'transparent',
    borderWidth: 1,
    borderColor: tokens.color.line,
  },
  solidLabel: { color: '#FFFFFF', fontWeight: '600', fontFamily: 'Plus Jakarta Sans' },
  ghostLabel: { color: tokens.color.ink, fontWeight: '600' },
  disabled: { opacity: 0.5 },
});
