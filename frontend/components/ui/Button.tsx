import { Pressable, Text, type ViewStyle } from 'react-native';
import { useTheme } from '@/src/theme/ThemeContext';
import { useThemeTokens } from '@/src/theme/tokens';

export type ButtonVariant = 'primary' | 'ai' | 'ghost';

type Props = {
  label: string;
  onPress?: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  style?: ViewStyle;
};

export function Button({ label, onPress, variant = 'primary', disabled, style }: Props) {
  const { theme } = useTheme();
  const { color, space, radius } = useThemeTokens();
  const base: ViewStyle = {
    paddingVertical: space.sm + 2,
    paddingHorizontal: space.lg,
    borderRadius: radius.control,
    alignSelf: 'flex-start',
  };
  const tone: ViewStyle =
    variant === 'ai'
      ? { backgroundColor: color.ai }
      : variant === 'ghost'
        ? { backgroundColor: 'transparent', borderWidth: 1, borderColor: color.line }
        : { backgroundColor: color.primary };
  // Dark-mode primary/ai are bright, so solid buttons need dark text; light-mode ones stay white-on-color.
  const solidLabelColor = theme === 'dark' ? color.canvas : '#FFFFFF';
  const labelTone =
    variant === 'ghost'
      ? { color: color.ink, fontWeight: '600' as const }
      : { color: solidLabelColor, fontWeight: '600' as const, fontFamily: 'Plus Jakarta Sans' };

  return (
    <Pressable
      accessibilityRole="button"
      disabled={disabled}
      onPress={onPress}
      style={[base, tone, disabled ? { opacity: 0.5 } : null, style]}
    >
      <Text style={labelTone}>{label}</Text>
    </Pressable>
  );
}
