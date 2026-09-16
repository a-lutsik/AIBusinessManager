import { Platform, Text, type TextStyle } from 'react-native';
import { tokens } from '@/src/theme/tokens';

type Props = {
  name: string;
  size?: number;
  color?: string;
  filled?: boolean;
};

/** Material Symbols Outlined on web; short text fallback on native. */
export function MaterialIcon({ name, size = 22, color = tokens.color.ink, filled = false }: Props) {
  if (Platform.OS === 'web') {
    const style: TextStyle = {
      fontSize: size,
      color,
      fontFamily: 'Material Symbols Outlined',
      // @ts-expect-error RN web fontVariationSettings
      fontVariationSettings: filled
        ? "'FILL' 1, 'wght' 400, 'GRAD' 0, 'opsz' 24"
        : "'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24",
    };
    return <Text style={style}>{name}</Text>;
  }
  return <Text style={{ fontSize: size * 0.75, color }}>{name.slice(0, 2)}</Text>;
}
