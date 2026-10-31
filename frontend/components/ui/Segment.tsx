import { Pressable, Text, View } from 'react-native';
import { useThemeTokens } from '@/src/theme/tokens';

export function Segment<V>({ options, value, onChange }: { options: { value: V; label: string }[]; value: V; onChange: (v: V) => void }) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
      {options.map((opt) => {
        const active = value === opt.value;
        return (
          <Pressable
            key={String(opt.value)}
            onPress={() => onChange(opt.value)}
            style={{
              paddingVertical: space.sm,
              paddingHorizontal: space.md,
              borderRadius: radius.control,
              borderWidth: 1,
              borderColor: active ? color.primary : color.line,
              backgroundColor: active ? `${color.primary}14` : color.surface,
            }}
          >
            <Text style={{ color: active ? color.primary : color.muted, fontWeight: active ? '700' : '500' }}>{opt.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}
