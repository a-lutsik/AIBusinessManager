import { Stack } from 'expo-router';
import { useFonts } from 'expo-font';
import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useState } from 'react';
import 'react-native-reanimated';
import '../global.css';
import { AuthProvider } from '@/src/auth/AuthContext';
import { GEORGIAN_FONT_FAMILY, georgianFontSource, installGeorgianFallback } from '@/src/i18n/fonts';
import { restoreLocale, t, useLocale } from '@/src/i18n';
import { ThemeProvider } from '@/src/theme/ThemeContext';

export { ErrorBoundary } from 'expo-router';

SplashScreen.preventAutoHideAsync();

export default function RootLayout() {
  const [loaded, error] = useFonts({
    Inter: require('../assets/fonts/Inter-Variable.ttf'),
    'Plus Jakarta Sans': require('../assets/fonts/PlusJakartaSans-Variable.ttf'),
    [GEORGIAN_FONT_FAMILY]: georgianFontSource,
  });
  const [localeReady, setLocaleReady] = useState(false);
  useLocale(); // re-render the stack titles when the language changes

  useEffect(() => {
    // The language the user picked by hand wins over the device language; wait for it so the first screen is right.
    void restoreLocale().finally(() => setLocaleReady(true));
  }, []);

  useEffect(() => {
    if (error) throw error;
  }, [error]);

  useEffect(() => {
    if (loaded) {
      installGeorgianFallback();
    }
  }, [loaded]);

  const ready = loaded && localeReady;

  useEffect(() => {
    if (ready) {
      SplashScreen.hideAsync();
    }
  }, [ready]);

  if (!ready) {
    return null;
  }

  return (
    <ThemeProvider>
      <AuthProvider>
        <Stack>
          <Stack.Screen name="(console)" options={{ headerShown: false }} />
          <Stack.Screen name="auth" options={{ headerShown: false }} />
          <Stack.Screen name="book/[slug]" options={{ title: t('route.book') }} />
          <Stack.Screen name="b/[id]" options={{ title: t('route.visit') }} />
        </Stack>
      </AuthProvider>
    </ThemeProvider>
  );
}
