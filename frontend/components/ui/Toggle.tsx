import { Switch, Text, View } from 'react-native';
import { useThemeTokens } from '@/src/theme/tokens';

type Props = {
  label: string;
  value: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
};

export function Toggle({ label, value, onChange, disabled }: Props) {
  const { color, space } = useThemeTokens();
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: space.md,
        marginBottom: space.lg,
      }}
    >
      <Text style={{ flex: 1, color: color.ink, fontWeight: '600' }}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        disabled={disabled}
        trackColor={{ false: color.line, true: `${color.primary}88` }}
        thumbColor={value ? color.primary : color.mist}
      />
    </View>
  );
}
