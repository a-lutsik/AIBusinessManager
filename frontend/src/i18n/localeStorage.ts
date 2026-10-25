import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

const KEY = 'cadence.locale';

/**
 * The interface language the user picked by hand. Web: localStorage (a language is not a secret and must survive
 * tabs and restarts, unlike the refresh token in sessionStorage). Native: expo-secure-store, already a dependency
 * for the refresh token, so no extra storage package is added for one short string. Storage is best effort: when it
 * is unavailable the choice lives for the session only.
 */
export async function loadStoredLocale(): Promise<string | null> {
  try {
    if (Platform.OS === 'web') {
      return globalThis.localStorage?.getItem(KEY) ?? null;
    }
    return await SecureStore.getItemAsync(KEY);
  } catch {
    return null;
  }
}

export async function saveStoredLocale(locale: string): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      globalThis.localStorage?.setItem(KEY, locale);
      return;
    }
    await SecureStore.setItemAsync(KEY, locale);
  } catch {
    // Persistence is best effort: the in-memory choice keeps working.
  }
}
