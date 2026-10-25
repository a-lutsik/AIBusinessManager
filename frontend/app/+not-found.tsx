import { Link, Stack } from 'expo-router';
import { Text, View } from 'react-native';

import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

export default function NotFoundScreen() {
  const { color, space } = useThemeTokens();
  return (
    <>
      <Stack.Screen options={{ title: t('notFound.title') }} />
      <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xl, backgroundColor: color.canvas }}>
        <Text style={{ fontSize: 20, fontWeight: '700', color: color.ink }}>{t('notFound.title')}</Text>
        <Link href="/dashboard" style={{ marginTop: space.lg, paddingVertical: space.md }}>
          <Text style={{ fontSize: 14, color: color.primary }}>{t('notFound.home')}</Text>
        </Link>
      </View>
    </>
  );
}
