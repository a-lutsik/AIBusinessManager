import { ActivityIndicator, Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { useAuth } from './AuthContext';

/** Shown by the console group in oidc mode while there is no session. */
export function SignInScreen() {
  const { color, space, radius } = useThemeTokens();
  const { signIn, signingIn, error } = useAuth();
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xl, backgroundColor: color.canvas }}>
      <View
        style={{
          width: '100%',
          maxWidth: 420,
          padding: space.xl,
          gap: space.md,
          borderRadius: radius.card,
          borderWidth: 1,
          borderColor: color.line,
          backgroundColor: color.surface,
        }}
      >
        <Text style={{ fontSize: 24, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
          {t('app.name')}
        </Text>
        <Text style={{ color: color.muted, lineHeight: 20 }}>{t('auth.signIn.body')}</Text>
        {error ? (
          <Text accessibilityRole="alert" style={{ color: color.status.noShow }}>
            {t('auth.signIn.error')} {error}
          </Text>
        ) : null}
        <Button label={signingIn ? t('auth.signIn.waiting') : t('auth.signIn.button')} onPress={signIn} disabled={signingIn} />
      </View>
    </View>
  );
}

export function AuthLoading() {
  const { color } = useThemeTokens();
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: color.canvas }}>
      <ActivityIndicator color={color.primary} accessibilityLabel={t('auth.loading')} />
    </View>
  );
}
