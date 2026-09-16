import { StyleSheet, Text, View } from 'react-native';
import { t } from '@/src/i18n';
import { tokens, trustColor } from '@/src/theme/tokens';

type Props = {
  level?: string | null;
};

export function TrustBadge({ level }: Props) {
  if (!level) return null;
  const color = trustColor(level);
  const label = t(`trust.${level}`) !== `trust.${level}` ? t(`trust.${level}`) : level;
  return (
    <View style={[styles.badge, { backgroundColor: `${color}18`, borderColor: color }]}>
      <Text style={[styles.text, { color }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    alignSelf: 'flex-start',
    paddingHorizontal: tokens.space.sm + 2,
    paddingVertical: 2,
    borderRadius: tokens.radius.pill,
    borderWidth: 1,
  },
  text: { fontSize: 12, fontWeight: '600' },
});
