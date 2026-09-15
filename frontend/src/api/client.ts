export const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

export type TenantPublic = {
  id: string;
  slug: string;
  displayName: string;
  timezone: string;
  currencyCode: string;
  countryCode: string;
};

async function request<T>(path: string, init: RequestInit = {}, tenantId?: string): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init.headers as Record<string, string> | undefined),
  };
  if (tenantId) {
    headers['X-Tenant-Id'] = tenantId;
  }
  const role = globalThis.localStorage?.getItem('abm.role');
  const specialistId = globalThis.localStorage?.getItem('abm.specialistId');
  if (role) {
    headers['X-Actor-Role'] = role;
  }
  if (specialistId) {
    headers['X-Specialist-Id'] = specialistId;
  }
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || res.statusText);
  }
  if (res.headers.get('content-type')?.includes('text/calendar')) {
    return (await res.text()) as T;
  }
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export const api = {
  publicTenant: (slug: string) => request<TenantPublic>(`/api/public/tenants/${slug}`),
  publicServices: (slug: string) => request<any[]>(`/api/public/tenants/${slug}/services`),
  publicSpecialists: (slug: string, serviceId: string) =>
    request<any[]>(`/api/public/tenants/${slug}/services/${serviceId}/specialists`),
  publicSlots: (slug: string, serviceId: string, specialistId: string, from: string, to: string) =>
    request<string[]>(
      `/api/public/tenants/${slug}/slots?serviceId=${serviceId}&specialistId=${specialistId}&from=${from}&to=${to}`
    ),
  publicBook: (slug: string, body: unknown) =>
    request<any>(`/api/public/tenants/${slug}/bookings`, { method: 'POST', body: JSON.stringify(body) }),
  publicBooking: (id: string, token: string) => request<any>(`/api/public/bookings/${id}?token=${encodeURIComponent(token)}`),
  publicCancel: (id: string, token: string) =>
    request<any>(`/api/public/bookings/${id}/cancel?token=${encodeURIComponent(token)}`, { method: 'POST' }),
  me: (tenantId: string) => request<any>('/api/app/me', {}, tenantId),
  dashboard: (tenantId: string) => request<any>('/api/app/dashboard', {}, tenantId),
  calendar: (tenantId: string, from: string, to: string, specialistId?: string) =>
    request<any[]>(
      `/api/app/calendar?from=${from}&to=${to}${specialistId ? `&specialistId=${specialistId}` : ''}`,
      {},
      tenantId
    ),
  appointment: (tenantId: string, id: string) => request<any>(`/api/app/appointments/${id}`, {}, tenantId),
  transition: (tenantId: string, id: string, to: string) =>
    request<any>(`/api/app/appointments/${id}/transition?to=${to}`, { method: 'POST' }, tenantId),
  services: (tenantId: string) => request<any[]>('/api/app/services', {}, tenantId),
  specialists: (tenantId: string) => request<any[]>('/api/app/specialists', {}, tenantId),
  clients: (tenantId: string, q = '') => request<any[]>(`/api/app/clients?q=${encodeURIComponent(q)}`, {}, tenantId),
  client: (tenantId: string, id: string) => request<any>(`/api/app/clients/${id}`, {}, tenantId),
  rules: (tenantId: string) => request<any>('/api/app/rules', {}, tenantId),
  saveRules: (tenantId: string, body: unknown) =>
    request<any>('/api/app/rules', { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  matrix: (tenantId: string, specialistId: string) =>
    request<any[]>(`/api/app/specialists/${specialistId}/matrix`, {}, tenantId),
  tasks: (tenantId: string) => request<any[]>('/api/app/tasks', {}, tenantId),
  completeTask: (tenantId: string, id: string) =>
    request<void>(`/api/app/tasks/${id}/complete`, { method: 'POST' }, tenantId),
  metrics: (tenantId: string) => request<any[]>('/api/app/metrics', {}, tenantId),
  recalculate: (tenantId: string) => request<any[]>('/api/app/metrics/recalculate', { method: 'POST' }, tenantId),
  aiChat: (tenantId: string, message: string, locale: string, conversationId?: string) =>
    request<any>('/api/app/ai/chat', { method: 'POST', body: JSON.stringify({ message, locale, conversationId }) }, tenantId),
  confirmDraft: (tenantId: string, id: string) =>
    request<any>(`/api/app/ai/drafts/${id}/confirm`, { method: 'POST' }, tenantId),
};

export const LUMEN_TENANT_ID = '00000000-0000-4000-8000-000000000001';
