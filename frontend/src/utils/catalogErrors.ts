import { ApiError } from '@/src/api/client';
import { t } from '@/src/i18n';

/** A value a form cannot even send; its code has a text next to the ones the API answers with. */
export class CatalogInputError extends Error {
  readonly code: string;

  constructor(code: string) {
    super(code);
    this.code = code;
  }
}

/** The text for a refused save: by code, with the code itself so that support can tell the cases apart. */
export function catalogErrorText(error: unknown): string {
  const code = error instanceof ApiError || error instanceof CatalogInputError ? error.code : undefined;
  if (!code) return t('catalog.error.generic');
  const key = `catalog.error.${code}`;
  const text = t(key);
  return `${text === key ? t('catalog.error.generic') : text} (${code})`;
}

/** Why a visit cannot be moved, by code: a visit of an inactive specialist or service can only be cancelled. */
export function rescheduleErrorText(error: unknown): string {
  if (error instanceof ApiError && (error.code === 'SERVICE_NOT_OFFERED' || error.code === 'SERVICE_INACTIVE')) {
    return t('visit.rescheduleNotOffered');
  }
  return String((error as Error).message ?? error);
}

/** A required whole number from a form field: the rules on its value are the server's. */
export function requiredWholeNumber(text: string): number {
  const value = optionalWholeNumber(text);
  if (value === null) throw new CatalogInputError('NUMBER_FORMAT');
  return value;
}

/** An empty field is null (an override that is not set). */
export function optionalWholeNumber(text: string): number | null {
  const typed = text.trim();
  if (!typed) return null;
  if (!/^-?\d{1,9}$/.test(typed)) throw new CatalogInputError('NUMBER_FORMAT');
  return Number(typed);
}
