import { Text, View } from 'react-native';
import { t } from '@/src/i18n';
import { trustColor, useThemeTokens } from '@/src/theme/tokens';

type Props = {
  level?: string | null;
};

export function TrustBadge({ level }: Props) {
  const { color, space, radius } = useThemeTokens();
  if (!level) return null;
  const badgeColor = trustColor(level, color);
  const label = t(`trust.${level}`) !== `trust.${level}` ? t(`trust.${level}`) : level;
  return (
    <View
      style={{
        alignSelf: 'flex-start',
        paddingHorizontal: space.sm + 2,
        paddingVertical: 2,
        borderRadius: radius.pill,
        borderWidth: 1,
        backgroundColor: `${badgeColor}18`,
        borderColor: badgeColor,
      }}
    >
      <Text style={{ fontSize: 12, fontWeight: '600', color: badgeColor }}>{label}</Text>
    </View>
  );
}
