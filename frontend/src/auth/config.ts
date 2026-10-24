export type AuthMode = 'oidc' | 'dev-headers';

/** `oidc` (default): Keycloak login + Bearer token. `dev-headers`: legacy role switcher + X-* headers (backend dev profile only). */
export const AUTH_MODE: AuthMode = process.env.EXPO_PUBLIC_AUTH_MODE === 'dev-headers' ? 'dev-headers' : 'oidc';

export const OIDC_ISSUER = process.env.EXPO_PUBLIC_OIDC_ISSUER ?? 'http://localhost:8081/realms/cadence';
export const OIDC_CLIENT_ID = process.env.EXPO_PUBLIC_OIDC_CLIENT_ID ?? 'cadence-app';
export const OIDC_SCOPES = ['openid', 'profile'];
