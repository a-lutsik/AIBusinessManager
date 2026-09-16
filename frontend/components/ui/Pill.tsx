import { Text, View } from 'react-native';
import { useThemeTokens } from '@/src/theme/tokens';

type Props = {
  active: boolean;
  onLabel: string;
  offLabel: string;
};

export function Pill({ active, onLabel, offLabel }: Props) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View
      style={{
        paddingHorizontal: space.sm + 2,
        paddingVertical: 2,
        borderRadius: radius.pill,
        backgroundColor: active ? `${color.primary}18` : color.mist,
      }}
    >
      <Text style={{ fontSize: 11, fontWeight: '600', color: active ? color.primary : color.muted }}>
        {active ? onLabel : offLabel}
      </Text>
    </View>
  );
}
