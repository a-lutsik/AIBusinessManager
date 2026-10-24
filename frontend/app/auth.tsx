import { Redirect } from 'expo-router';
import { Platform } from 'react-native';

/**
 * OIDC redirect target (`cadence://auth`, `<origin>/auth`). On web the sign-in popup is closed by
 * WebBrowser.maybeCompleteAuthSession() at startup, so nothing is rendered; on native this route
 * only swallows the deep link and returns to the app.
 */
export default function AuthRedirect() {
  return Platform.OS === 'web' ? null : <Redirect href="/" />;
}
