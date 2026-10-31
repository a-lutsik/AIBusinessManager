import { Text, TextInput, View, type ViewStyle } from 'react-native';
import { useThemeTokens } from '@/src/theme/tokens';

type Props = {
  label: string;
  value: string;
  onChangeText: (text: string) => void;
  placeholder?: string;
  hint?: string;
  multiline?: boolean;
  keyboardType?: 'default' | 'number-pad' | 'decimal-pad' | 'numbers-and-punctuation';
  style?: ViewStyle;
};

/** A labelled text input; the value is the caller's, so a refused save leaves what was typed. */
export function TextField({ label, value, onChangeText, placeholder, hint, multiline, keyboardType, style }: Props) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View style={[{ marginBottom: space.md, minWidth: 0 }, style]}>
      <Text style={{ color: color.ink, fontWeight: '600', marginBottom: space.xs }}>{label}</Text>
      <TextInput
        aria-label={label}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={color.muted}
        multiline={multiline}
        keyboardType={keyboardType}
        style={{
          borderWidth: 1,
          borderColor: color.line,
          padding: space.md,
          borderRadius: radius.control,
          backgroundColor: color.surface,
          color: color.ink,
          minWidth: 0,
          width: '100%',
        }}
      />
      {hint ? <Text style={{ color: color.muted, fontSize: 12, marginTop: space.xs }}>{hint}</Text> : null}
    </View>
  );
}
