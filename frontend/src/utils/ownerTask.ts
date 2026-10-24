import * as Clipboard from 'expo-clipboard';
import * as Linking from 'expo-linking';
import { Platform } from 'react-native';
import { api, type OwnerTaskView } from '@/src/api/client';

export const MANAGE_LINK_PLACEHOLDER = '{manageLink}';

/** Absolute URL for a backend-issued `managePath` (`/b/{id}?token=...`). */
export function toAbsoluteManageUrl(managePath: string): string {
  if (Platform.OS === 'web' && typeof window !== 'undefined') {
    return `${window.location.origin}${managePath}`;
  }
  const queryAt = managePath.indexOf('?');
  if (queryAt < 0) {
    return Linking.createURL(managePath);
  }
  const queryParams: Record<string, string> = {};
  new URLSearchParams(managePath.slice(queryAt + 1)).forEach((value, key) => {
    queryParams[key] = value;
  });
  return Linking.createURL(managePath.slice(0, queryAt), { queryParams });
}

/** Builds the message for a task; issues a fresh manage link when the text contains the placeholder. */
export async function buildOwnerTaskMessage(tenantId: string, task: OwnerTaskView): Promise<string> {
  const text = task.copyText ?? '';
  if (!text.includes(MANAGE_LINK_PLACEHOLDER)) {
    return text;
  }
  if (!task.appointmentId) {
    throw new Error('Task has no appointment to issue a link for');
  }
  const link = await api.issueAccessLink(tenantId, task.appointmentId);
  return text.split(MANAGE_LINK_PLACEHOLDER).join(toAbsoluteManageUrl(link.managePath));
}

export async function copyOwnerTaskMessage(tenantId: string, task: OwnerTaskView): Promise<void> {
  const message = await buildOwnerTaskMessage(tenantId, task);
  await Clipboard.setStringAsync(message);
}
