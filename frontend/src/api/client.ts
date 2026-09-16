export const API_BASE = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

export type TenantPublic = {
  id: string;
  slug: string;
  displayName: string;
  timezone: string;
  currencyCode: string;
  countryCode: string;
};

export type MeView = {
  actorId?: string;
  role?: 'OWNER' | 'MASTER' | string;
  specialistId?: string | null;
  tenantId?: string;
  displayName?: string;
  timezone?: string;
  currencyCode?: string;
  countryCode?: string;
  slug?: string;
};

export type BookingRulesView = {
  slotStepMinutes: number;
  minNoticeMinutes: number;
  horizonDays: number;
  clientRescheduleAllowed: boolean;
  lateCancellationHours: number;
  newClientRequiresConfirmation: boolean;
  deductPackageOnNoShow: boolean;
  weekStartsOn: number;
  timeFormat24h: boolean;
};

export type ServiceView = {
  id: string;
  name: string;
  description?: string;
  durationMinutes: number;
  priceMinor: number;
  bufferBeforeMinutes?: number;
  bufferAfterMinutes?: number;
  color?: string;
  active?: boolean;
  publicVisible?: boolean;
};

export type SpecialistView = {
  id: string;
  displayName: string;
  keycloakUserId?: string;
  calendarColor?: string;
  active?: boolean;
};

/** Current MasterServiceView shape; enriched MatrixRowView fields are optional until BE lands. */
export type MatrixRowView = {
  id?: string;
  specialistId?: string;
  serviceId: string;
  offered: boolean;
  durationMinutesOverride?: number | null;
  priceMinorOverride?: number | null;
  bufferBeforeMinutesOverride?: number | null;
  bufferAfterMinutesOverride?: number | null;
  // enriched (optional)
  name?: string;
  serviceName?: string;
  color?: string;
  defaultDurationMinutes?: number;
  defaultPriceMinor?: number;
  defaultBufferBeforeMinutes?: number;
  defaultBufferAfterMinutes?: number;
  service?: Partial<ServiceView>;
};

export type MetricView = {
  key: string;
  value?: number | null;
  sampleSize?: number;
  insufficientData?: boolean;
  explanation?: string;
  specialistId?: string | null;
  // P1 optional contract
  unit?: string | null;
  deltaMoM?: number | null;
  windowStart?: string | null;
  windowEnd?: string | null;
};

export type MetricDefinitionView = {
  key: string;
  title: string;
  unit?: string | null;
  formula?: string | null;
  minSampleSize?: number;
};

export type GrowthSignalView = {
  id?: string;
  key: string;
  title: string;
  evidence?: string;
  suggestedAction?: string;
  severity?: string;
  actionType?: string;
  actionPayload?: unknown;
  createdAt?: string | null;
  dismissedAt?: string | null;
};

export type OwnerTaskView = {
  id: string;
  title: string;
  body?: string;
  deepLink?: string;
};

export type AppointmentView = {
  id: string;
  specialistId?: string;
  serviceId?: string;
  clientId?: string;
  status: string;
  serviceStart?: string;
  serviceEnd?: string;
  occupiedStart?: string;
  occupiedEnd?: string;
  serviceNameSnapshot?: string;
  priceSnapshot?: number;
  durationSnapshot?: number;
  bufferBeforeSnapshot?: number;
  bufferAfterSnapshot?: number;
  currencyCode?: string;
  discountAmount?: number;
  amountReceived?: number | null;
  enteredName?: string | null;
  note?: string | null;
  source?: string;
  clientDisplayName?: string;
  clientPhone?: string;
  trustLevel?: string | null;
};

/** Calendar list row — same fields as AppointmentView when BE enriches. */
export type AppointmentSummary = AppointmentView;

export type AppointmentDetailView = {
  appointment: AppointmentView;
  trust?: { effectiveLevel?: string; level?: string } | null;
};

export type DashboardView = {
  today?: AppointmentSummary[];
  metrics?: MetricView[];
  tasks?: OwnerTaskView[];
  signals?: GrowthSignalView[];
  snapshotAt?: string;
};

export type MasterTodayItem = {
  appointment: AppointmentView;
  trust?: { effectiveLevel?: string; level?: string } | null;
  packages?: unknown[];
};

export type MasterTodayView = {
  asOf?: string;
  day?: string;
  specialistId?: string;
  appointments?: MasterTodayItem[];
};

export type AiDraftAction = {
  id: string;
  toolName?: string;
  summary?: string;
  payloadJson?: string;
  status?: string;
};

export type AiChatResponse = {
  reply?: string;
  drafts?: AiDraftAction[];
  conversationId?: string;
};

export type ConversationHistoryView = {
  conversationId: string;
  messages?: Array<{
    at?: string;
    role?: string;
    content?: string;
    provider?: string;
    model?: string;
  }>;
  drafts?: AiDraftAction[];
};

/** Short public ref = last 6 hex chars of UUID (no fake #LM- codes). */
export function shortRef(id: string | undefined | null): string {
  if (!id) return '————';
  const hex = id.replace(/-/g, '');
  return hex.slice(-6).toUpperCase();
}

async function request<T>(path: string, init: RequestInit = {}, tenantId?: string): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init.headers as Record<string, string> | undefined),
  };
  if (tenantId) {
    headers['X-Tenant-Id'] = tenantId;
  }
  const role = globalThis.localStorage?.getItem('cadence.role');
  const specialistId = globalThis.localStorage?.getItem('cadence.specialistId');
  if (role) {
    headers['X-Actor-Role'] = role;
  }
  if (specialistId) {
    headers['X-Specialist-Id'] = specialistId;
  }
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (!res.ok) {
    const text = await res.text();
    const err = new Error(text || res.statusText) as Error & { status?: number };
    err.status = res.status;
    throw err;
  }
  if (res.headers.get('content-type')?.includes('text/calendar')) {
    return (await res.text()) as T;
  }
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

/** Soft-fail helper for endpoints that may still be landing (404 / network). */
async function optionalRequest<T>(path: string, init: RequestInit = {}, tenantId?: string): Promise<T | null> {
  try {
    return await request<T>(path, init, tenantId);
  } catch (e: unknown) {
    const status = (e as { status?: number })?.status;
    if (status === 404 || status === 501 || status === 405) {
      return null;
    }
    throw e;
  }
}

export const api = {
  publicTenant: (slug: string) => request<TenantPublic>(`/api/public/tenants/${slug}`),
  publicServices: (slug: string) => request<ServiceView[]>(`/api/public/tenants/${slug}/services`),
  publicSpecialists: (slug: string, serviceId: string) =>
    request<SpecialistView[]>(`/api/public/tenants/${slug}/services/${serviceId}/specialists`),
  publicSlots: (slug: string, serviceId: string, specialistId: string, from: string, to: string) =>
    request<string[]>(
      `/api/public/tenants/${slug}/slots?serviceId=${serviceId}&specialistId=${specialistId}&from=${from}&to=${to}`
    ),
  publicBook: (slug: string, body: unknown) =>
    request<Record<string, unknown>>(`/api/public/tenants/${slug}/bookings`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  publicBooking: (id: string, token: string) =>
    request<AppointmentView>(`/api/public/bookings/${id}?token=${encodeURIComponent(token)}`),
  publicCancel: (id: string, token: string) =>
    request<AppointmentView>(`/api/public/bookings/${id}/cancel?token=${encodeURIComponent(token)}`, {
      method: 'POST',
    }),
  /** Optional until BE lands; returns null on 404. */
  publicReschedule: (id: string, token: string, start: string) =>
    optionalRequest<AppointmentView>(
      `/api/public/bookings/${id}/reschedule?token=${encodeURIComponent(token)}&start=${encodeURIComponent(start)}`,
      { method: 'POST' }
    ),
  me: (tenantId: string) => request<MeView>('/api/app/me', {}, tenantId),
  dashboard: (tenantId: string) => request<DashboardView>('/api/app/dashboard', {}, tenantId),
  /** Optional MASTER agenda; returns null on 404. */
  masterToday: (tenantId: string) =>
    optionalRequest<MasterTodayView>('/api/app/masters/me/today', {}, tenantId),
  calendar: (tenantId: string, from: string, to: string, specialistId?: string) =>
    request<AppointmentSummary[]>(
      `/api/app/calendar?from=${from}&to=${to}${specialistId ? `&specialistId=${specialistId}` : ''}`,
      {},
      tenantId
    ),
  appointment: (tenantId: string, id: string) =>
    request<AppointmentDetailView | AppointmentView>(`/api/app/appointments/${id}`, {}, tenantId),
  transition: (tenantId: string, id: string, to: string) =>
    request<AppointmentView>(`/api/app/appointments/${id}/transition?to=${to}`, { method: 'POST' }, tenantId),
  reschedule: (tenantId: string, id: string, start: string) =>
    request<AppointmentView>(
      `/api/app/appointments/${id}/reschedule?start=${encodeURIComponent(start)}`,
      { method: 'POST' },
      tenantId
    ),
  services: (tenantId: string) => request<ServiceView[]>('/api/app/services', {}, tenantId),
  specialists: (tenantId: string) => request<SpecialistView[]>('/api/app/specialists', {}, tenantId),
  clients: (tenantId: string, q = '') =>
    request<unknown[]>(`/api/app/clients?q=${encodeURIComponent(q)}`, {}, tenantId),
  client: (tenantId: string, id: string) => request<unknown>(`/api/app/clients/${id}`, {}, tenantId),
  rules: (tenantId: string) => request<BookingRulesView>('/api/app/rules', {}, tenantId),
  saveRules: (tenantId: string, body: BookingRulesView) =>
    request<BookingRulesView>('/api/app/rules', { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  matrix: (tenantId: string, specialistId: string) =>
    request<MatrixRowView[]>(`/api/app/specialists/${specialistId}/matrix`, {}, tenantId),
  tasks: (tenantId: string) => request<OwnerTaskView[]>('/api/app/tasks', {}, tenantId),
  completeTask: (tenantId: string, id: string) =>
    request<void>(`/api/app/tasks/${id}/complete`, { method: 'POST' }, tenantId),
  metrics: (tenantId: string) => request<MetricView[]>('/api/app/metrics', {}, tenantId),
  metricDefinitions: (tenantId: string) =>
    optionalRequest<MetricDefinitionView[]>('/api/app/metrics/definitions', {}, tenantId),
  recalculate: (tenantId: string) =>
    request<MetricView[]>('/api/app/metrics/recalculate', { method: 'POST' }, tenantId),
  dismissSignal: (tenantId: string, id: string) =>
    optionalRequest<GrowthSignalView>(`/api/app/signals/${id}/dismiss`, { method: 'POST' }, tenantId),
  aiChat: (tenantId: string, message: string, locale: string, conversationId?: string) =>
    request<AiChatResponse>(
      '/api/app/ai/chat',
      { method: 'POST', body: JSON.stringify({ message, locale, conversationId }) },
      tenantId
    ),
  confirmDraft: (tenantId: string, id: string) =>
    request<AiChatResponse>(`/api/app/ai/drafts/${id}/confirm`, { method: 'POST' }, tenantId),
  rejectDraft: (tenantId: string, id: string) =>
    optionalRequest<AiChatResponse>(`/api/app/ai/drafts/${id}/reject`, { method: 'POST' }, tenantId),
  aiConversation: (tenantId: string, id: string) =>
    optionalRequest<ConversationHistoryView>(`/api/app/ai/conversations/${id}`, {}, tenantId),
  aiDrafts: (tenantId: string, status?: string) =>
    optionalRequest<AiDraftAction[]>(
      `/api/app/ai/drafts${status ? `?status=${encodeURIComponent(status)}` : ''}`,
      {},
      tenantId
    ),
};

export const LUMEN_TENANT_ID = '00000000-0000-4000-8000-000000000001';
export const LUMEN_SLUG = 'lumen-studio';
