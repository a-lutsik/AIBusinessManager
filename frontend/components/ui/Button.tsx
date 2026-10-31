import { Link, type Href } from 'expo-router';
import { Pressable, StyleSheet, Text, type ViewStyle } from 'react-native';
import { useTheme } from '@/src/theme/ThemeContext';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';

export type ButtonVariant = 'primary' | 'ai' | 'ghost';

type Props = {
  label: string;
  onPress?: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  style?: ViewStyle;
  /** Renders a real link (the browser can open or copy it) instead of a button. */
  href?: Href;
};

export function Button({ label, onPress, variant = 'primary', disabled, style, href }: Props) {
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
      : { color: solidLabelColor, fontWeight: '600' as const, fontFamily: DISPLAY_FONT };

  const button = (
    <Pressable
      accessibilityRole={href ? undefined : 'button'}
      disabled={disabled}
      onPress={onPress}
      // Flat: `Link asChild` hands the style to the anchor as it is, and an array crashes there.
      style={StyleSheet.flatten([base, tone, disabled ? { opacity: 0.5 } : null, style])}
    >
      <Text style={labelTone}>{label}</Text>
    </Pressable>
  );
  return href ? (
    <Link href={href} asChild>
      {button}
    </Link>
  ) : (
    button
  );
}
