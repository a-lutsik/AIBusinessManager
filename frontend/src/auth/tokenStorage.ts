import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

const KEY = 'cadence.auth.refreshToken';

/** Refresh token persistence: SecureStore on native, sessionStorage (never localStorage) on web. */
export async function loadRefreshToken(): Promise<string | null> {
  try {
    if (Platform.OS === 'web') {
      return globalThis.sessionStorage?.getItem(KEY) ?? null;
    }
    return await SecureStore.getItemAsync(KEY);
  } catch {
    return null;
  }
}

export async function saveRefreshToken(token: string): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.setItem(KEY, token);
      return;
    }
    await SecureStore.setItemAsync(KEY, token);
  } catch {
    // Persistence is best effort: the in-memory session keeps working.
  }
}

export async function clearRefreshToken(): Promise<void> {
  try {
    if (Platform.OS === 'web') {
      globalThis.sessionStorage?.removeItem(KEY);
      return;
    }
    await SecureStore.deleteItemAsync(KEY);
  } catch {
    // ignore
  }
}
