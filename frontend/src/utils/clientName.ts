import { t } from '@/src/i18n';

export type ClientRetirementKind = 'anonymized' | 'merged';

/** From the fields of the client card; a merged-away profile is anonymized as well, and says so differently. */
export function clientRetirement(card: { anonymizedAt?: string | null; mergedIntoClientId?: string | null }): ClientRetirementKind | null {
  if (card.mergedIntoClientId) return 'merged';
  return card.anonymizedAt ? 'anonymized' : null;
}

/** A client's name as the console shows it: a retired profile has no name left, only what happened to it. */
export function clientNameText(displayName: string | null | undefined, retirement: ClientRetirementKind | null = null): string {
  if (retirement === 'anonymized') return t('client.anonymizedName');
  if (retirement === 'merged') return t('client.mergedName');
  return displayName ?? '—';
}
