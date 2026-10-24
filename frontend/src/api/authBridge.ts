/**
 * Single injection point between the API client and the auth provider.
 * The provider registers it on mount; `request()` in client.ts is the only consumer.
 */
export type AuthBridge = {
  /** Current access token, refreshed first when it is about to expire. */
  getAccessToken: () => Promise<string | null>;
  /** Forces one refresh; resolves to the new access token or null when it failed. */
  refreshAccessToken: () => Promise<string | null>;
  /** Called when the API still answers 401 after a refresh attempt. */
  onUnauthorized: () => void;
};

let bridge: AuthBridge | null = null;

export function setAuthBridge(next: AuthBridge | null) {
  bridge = next;
}

export function getAuthBridge(): AuthBridge | null {
  return bridge;
}
