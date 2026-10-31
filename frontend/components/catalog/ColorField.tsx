import { Pressable, View } from 'react-native';
import { TextField } from '@/components/ui/TextField';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

const SWATCHES = ['#0f766e', '#0369a1', '#7c3aed', '#c026d3', '#be123c', '#b45309', '#57534e'];

export const DEFAULT_CATALOG_COLOR = SWATCHES[0];

export function ColorField({ value, onChange }: { value: string; onChange: (color: string) => void }) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View>
      <TextField label={t('field.color')} value={value} onChangeText={onChange} hint={t('field.colorHint')} style={{ maxWidth: 200 }} />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginBottom: space.lg }}>
        {SWATCHES.map((swatch) => (
          <Pressable
            key={swatch}
            aria-label={swatch}
            accessibilityRole="button"
            onPress={() => onChange(swatch)}
            style={{
              width: 28,
              height: 28,
              borderRadius: radius.pill,
              backgroundColor: swatch,
              borderWidth: 2,
              borderColor: value.toLowerCase() === swatch ? color.ink : 'transparent',
            }}
          />
        ))}
      </View>
    </View>
  );
}
