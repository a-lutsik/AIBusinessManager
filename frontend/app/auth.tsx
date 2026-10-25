import { Redirect, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Platform, Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { useAuth, type RedirectFailure } from '@/src/auth/AuthContext';
import { stripCallbackQuery } from '@/src/auth/webRedirect';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

/**
 * OIDC redirect target (`cadence://auth`, `<origin>/auth`). On native it only swallows the deep link
 * and returns to the app. On web it is the callback of the full-page redirect sign-in.
 */
export default function AuthRedirect() {
  return Platform.OS === 'web' ? <WebCallback /> : <Redirect href="/" />;
}

function failureMessage(failure: RedirectFailure): string {
  switch (failure.reason) {
    case 'provider':
      return `${t('auth.callback.providerError')} ${failure.detail}`;
    case 'exchange':
      return `${t('auth.signIn.error')} ${failure.detail}`;
    default:
      return t('auth.callback.stateMismatch');
  }
}

function WebCallback() {
  const router = useRouter();
  const { completeRedirect, signIn, signingIn } = useAuth();
  const { color, space, radius } = useThemeTokens();
  const [failure, setFailure] = useState<RedirectFailure | null>(null);
  const routerRef = useRef(router);
  // Latest-value ref so the one-shot redirect effect below does not depend on the router identity.
  // eslint-disable-next-line react-hooks/refs
  routerRef.current = router;
  const handled = useRef(false);

  useEffect(() => {
    let cancelled = false;
    // completeRedirect() runs at most once per callback URL, so re-running this effect (StrictMode) is safe.
    completeRedirect().then((result) => {
      if (cancelled || handled.current) return;
      handled.current = true;
      const router = routerRef.current;
      if (result.ok) {
        router.replace(result.returnTo as never);
      } else if (result.reason === 'missing') {
        // Opened /auth without an authorization response (reload after completion, manual visit).
        router.replace('/dashboard');
      } else {
        setFailure(result);
        // The router restores the original URL while mounting; strip the spent code/state once it has settled.
        stripCallbackQuery();
      }
    });
    return () => {
      cancelled = true;
    };
  }, [completeRedirect]);

  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.xl, backgroundColor: color.canvas }}>
      {failure ? (
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
          <Text accessibilityRole="alert" style={{ color: color.status.noShow, lineHeight: 20 }}>
            {failureMessage(failure)}
          </Text>
          <Button label={signingIn ? t('auth.signIn.waiting') : t('auth.callback.retry')} onPress={signIn} disabled={signingIn} />
        </View>
      ) : (
        <View style={{ alignItems: 'center', gap: space.md }}>
          <ActivityIndicator color={color.primary} accessibilityLabel={t('auth.loading')} />
          <Text style={{ color: color.muted }}>{t('auth.loading')}</Text>
        </View>
      )}
    </View>
  );
}
