import { AUTH_MODE } from '@/src/auth/config';
import { getAuthBridge } from './authBridge';

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
  description?: string | null;
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
  keycloakUserId?: string | null;
  calendarColor?: string;
  active?: boolean;
};

/** Current MasterServiceView shape; enriched MatrixRowView fields are optional until BE lands. */
export type MatrixRowView = {
  id?: string;
  matrixId?: string | null;
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

export type WeeklyInterval = { weekday: number; startMinute: number; endMinute: number };

export type ScheduleExceptionKind = 'DAY_OFF' | 'SHIFT';

export type ScheduleExceptionView = {
  id: string;
  date: string;
  kind: ScheduleExceptionKind;
  startMinute?: number | null;
  endMinute?: number | null;
  note?: string | null;
};

/** What a form sends: an id that is null creates. */
export type New<T extends { id: string }> = Omit<T, 'id'> & { id: string | null };

export type MatrixRowBody = {
  serviceId: string;
  offered: boolean;
  durationMinutesOverride: number | null;
  priceMinorOverride: number | null;
  bufferBeforeMinutesOverride: number | null;
  bufferAfterMinutesOverride: number | null;
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
  kind: string;
  title: string;
  body?: string | null;
  /** Ready-to-send message; NEW_BOOKING tasks contain the literal `{manageLink}` placeholder. */
  copyText?: string | null;
  link?: string | null;
  appointmentId?: string | null;
  createdAt?: string | null;
  completedAt?: string | null;
};

export type AccessLinkView = {
  /** Relative path, e.g. `/b/{id}?token=...`. Each issue call returns a fresh token. */
  managePath: string;
  expiresAt: string;
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
  /** Null for a master when the stored name is the client's phone. */
  clientDisplayName?: string | null;
  /** Null for a master: masters never receive client phones. */
  clientPhone?: string | null;
  trustLevel?: string | null;
  /**
   * A master booked this visit by phone onto a client outside their scope: until the owner confirms it, the master
   * sees only the name they typed and no trust (decision A).
   */
  clientHiddenFromMaster?: boolean;
};

/** Public manage-link view: visit facts and the name the visitor typed only (no stored client name, phone or trust). */
export type PublicVisitView = {
  id: string;
  specialistId?: string;
  serviceId?: string;
  status: string;
  serviceStart?: string;
  serviceEnd?: string;
  serviceNameSnapshot?: string;
  priceSnapshot?: number;
  currencyCode?: string;
  enteredName?: string | null;
  /** The tenant's rule: when false, the reschedule route answers 403 RESCHEDULE_DISABLED. */
  clientRescheduleAllowed?: boolean;
  /** The tenant's IANA zone: times of the visit and its slots are shown in it. */
  timezone?: string;
  /** Where the page asks for reschedule slots. */
  tenantSlug?: string;
};

/**
 * Trust of a client. `reasons` (texts with salon-wide counts) and `overrideReason` (owner text) are null for a
 * master, who gets the number-free `reasonCodes` instead (decision B).
 */
export type TrustView = {
  clientId?: string;
  level?: string;
  effectiveLevel?: string;
  overrideLevel?: string | null;
  overrideReason?: string | null;
  reasons?: string[] | null;
  reasonCodes?: string[] | null;
};

/** Calendar list row — same fields as AppointmentView when BE enriches. */
export type AppointmentSummary = AppointmentView;

export type AppointmentDetailView = {
  appointment: AppointmentView;
  /** Null for a master on a visit hidden from them (decision A). */
  trust?: TrustView | null;
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
  trust?: TrustView | null;
  packages?: unknown[];
};

export type MasterTodayView = {
  asOf?: string;
  day?: string;
  specialistId?: string;
  appointments?: MasterTodayItem[];
};

export type DraftType = 'draft_booking' | 'draft_reschedule' | 'draft_cancel';

export type DraftStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'FAILED' | 'EXPIRED';

/** What the owner confirms. Times are tenant-local `YYYY-MM-DDTHH:MM`; fields of the other draft types are null. */
export type DraftSummary = {
  clientName?: string | null;
  specialistName?: string | null;
  serviceName?: string | null;
  durationMinutes?: number | null;
  priceMinor?: number | null;
  currencyCode?: string | null;
  localStart?: string | null;
  localFrom?: string | null;
  localTo?: string | null;
  initiatedBy?: 'business' | 'client' | null;
  /** Plain text from the model: never render as HTML or a link. */
  reason?: string | null;
};

export type DraftAction = {
  id: string;
  type: DraftType;
  status: DraftStatus;
  createdAt: string;
  expiresAt: string;
  resolvedAt?: string | null;
  appliedEntityId?: string | null;
  failureCode?: string | null;
  failureMessage?: string | null;
  /** Null only for a stored payload this version cannot read. */
  summary?: DraftSummary | null;
};

export type AiChatResponse = {
  reply: string;
  drafts: DraftAction[];
  conversationId: string;
  usage: { monthTokensUsed: number; monthTokenBudget: number };
};

export type ConversationMessage = {
  id: string;
  at: string;
  role: 'user' | 'assistant';
  content: string;
  provider?: string | null;
  model?: string | null;
  /** Drafts created in the turn of a user message. */
  drafts: DraftAction[];
};

export type ConversationHistoryView = {
  conversationId: string;
  messages: ConversationMessage[];
  drafts: DraftAction[];
};

export type AiSettingsView = {
  notesToLlm: boolean;
  monthlyTokenBudget: number;
  monthTokensUsed: number;
  period: string;
  periodResetsAt: string;
};

/** A non-2xx answer. `code` and `detail` come from the backend's problem+json body; `message` is the detail. */
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly detail?: string;

  constructor(status: number, code: string | undefined, detail: string | undefined, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

/** Short public ref = last 6 hex chars of UUID (no fake #LM- codes). */
export function shortRef(id: string | undefined | null): string {
  if (!id) return '————';
  const hex = id.replace(/-/g, '');
  return hex.slice(-6).toUpperCase();
}

/** The API refuses this actor as such, whatever the request: the session has to show why instead of each screen failing. */
const SESSION_REFUSALS: readonly string[] = ['SPECIALIST_INACTIVE', 'SPECIALIST_UNMAPPED'];

let sessionRefusalListener: ((code: string) => void) | null = null;

export function setSessionRefusalListener(listener: ((code: string) => void) | null) {
  sessionRefusalListener = listener;
}

async function request<T>(path: string, init: RequestInit = {}, tenantId?: string): Promise<T> {
  const oidc = AUTH_MODE === 'oidc';
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init.headers as Record<string, string> | undefined),
  };
  if (!oidc) {
    // dev-headers mode only: the backend trusts these headers solely in its dev-headers profile.
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
  }

  const send = (token?: string | null) =>
    fetch(`${API_BASE}${path}`, {
      ...init,
      headers: token ? { ...headers, Authorization: `Bearer ${token}` } : headers,
    });

  let res: Response;
  const bridge = oidc && path.startsWith('/api/app/') ? getAuthBridge() : null;
  if (bridge) {
    res = await send(await bridge.getAccessToken());
    if (res.status === 401) {
      // One refresh attempt, then give up and sign out.
      const fresh = await bridge.refreshAccessToken();
      if (fresh) {
        res = await send(fresh);
      }
      if (!fresh || res.status === 401) {
        bridge.onUnauthorized();
      }
    }
  } else {
    res = await send();
  }
  if (!res.ok) {
    const text = await res.text();
    let problem: { code?: unknown; detail?: unknown } = {};
    try {
      problem = JSON.parse(text) ?? {};
    } catch {
      // Not problem+json (a proxy page, an empty body): fall back to the raw text below.
    }
    const code = typeof problem.code === 'string' ? problem.code : undefined;
    const detail = typeof problem.detail === 'string' ? problem.detail : undefined;
    if (code && SESSION_REFUSALS.includes(code) && path.startsWith('/api/app/')) {
      sessionRefusalListener?.(code);
    }
    throw new ApiError(res.status, code, detail, detail || text || res.statusText);
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
    request<PublicVisitView>(`/api/public/bookings/${id}?token=${encodeURIComponent(token)}`),
  publicCancel: (id: string, token: string) =>
    request<PublicVisitView>(`/api/public/bookings/${id}/cancel?token=${encodeURIComponent(token)}`, {
      method: 'POST',
    }),
  /** Withdraws the marketing consent of the client behind this manage link. */
  publicWithdrawConsent: (id: string, token: string) =>
    request<{ id: string; marketingConsent: boolean }>(
      `/api/public/bookings/${id}/consent/withdraw?token=${encodeURIComponent(token)}`,
      { method: 'POST' }
    ),
  /** Optional until BE lands; returns null on 404. */
  publicReschedule: (id: string, token: string, start: string) =>
    optionalRequest<PublicVisitView>(
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
  saveService: (tenantId: string, body: New<ServiceView>) =>
    request<ServiceView>('/api/app/services', { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  specialists: (tenantId: string) => request<SpecialistView[]>('/api/app/specialists', {}, tenantId),
  saveSpecialist: (tenantId: string, body: New<SpecialistView>) =>
    request<SpecialistView>('/api/app/specialists', { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  clients: (tenantId: string, q = '') =>
    request<unknown[]>(`/api/app/clients?q=${encodeURIComponent(q)}`, {}, tenantId),
  client: (tenantId: string, id: string) => request<unknown>(`/api/app/clients/${id}`, {}, tenantId),
  /** Owner only: withdraws a client's marketing consent on their request. */
  withdrawConsent: (tenantId: string, id: string) =>
    request<unknown>(`/api/app/clients/${id}/consent/withdraw`, { method: 'POST' }, tenantId),
  rules: (tenantId: string) => request<BookingRulesView>('/api/app/rules', {}, tenantId),
  saveRules: (tenantId: string, body: BookingRulesView) =>
    request<BookingRulesView>('/api/app/rules', { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  matrix: (tenantId: string, specialistId: string) =>
    request<MatrixRowView[]>(`/api/app/specialists/${specialistId}/matrix`, {}, tenantId),
  saveMatrixRow: (tenantId: string, specialistId: string, body: MatrixRowBody) =>
    request<unknown>(`/api/app/specialists/${specialistId}/matrix`, { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  schedule: (tenantId: string, specialistId: string) =>
    request<WeeklyInterval[]>(`/api/app/specialists/${specialistId}/schedule`, {}, tenantId),
  saveSchedule: (tenantId: string, specialistId: string, body: WeeklyInterval[]) =>
    request<WeeklyInterval[]>(`/api/app/specialists/${specialistId}/schedule`, { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  scheduleExceptions: (tenantId: string, specialistId: string, from: string) =>
    request<ScheduleExceptionView[]>(`/api/app/specialists/${specialistId}/exceptions?from=${from}`, {}, tenantId),
  saveScheduleException: (tenantId: string, specialistId: string, body: New<ScheduleExceptionView>) =>
    request<ScheduleExceptionView>(`/api/app/specialists/${specialistId}/exceptions`, { method: 'PUT', body: JSON.stringify(body) }, tenantId),
  deleteScheduleException: (tenantId: string, specialistId: string, id: string) =>
    request<void>(`/api/app/specialists/${specialistId}/exceptions/${id}`, { method: 'DELETE' }, tenantId),
  tasks: (tenantId: string) => request<OwnerTaskView[]>('/api/app/tasks', {}, tenantId),
  /** Issues a fresh manage-link token for the appointment (new token on every call). */
  issueAccessLink: (tenantId: string, appointmentId: string) =>
    request<AccessLinkView>(`/api/app/appointments/${appointmentId}/access-link`, { method: 'POST' }, tenantId),
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
    request<DraftAction>(`/api/app/ai/drafts/${id}/confirm`, { method: 'POST' }, tenantId),
  rejectDraft: (tenantId: string, id: string) =>
    request<DraftAction>(`/api/app/ai/drafts/${id}/reject`, { method: 'POST' }, tenantId),
  aiConversation: (tenantId: string, id: string) =>
    request<ConversationHistoryView>(`/api/app/ai/conversations/${id}`, {}, tenantId),
  aiDrafts: (tenantId: string, status?: DraftStatus) =>
    request<DraftAction[]>(`/api/app/ai/drafts${status ? `?status=${status}` : ''}`, {}, tenantId),
  aiSettings: (tenantId: string) => request<AiSettingsView>('/api/app/ai/settings', {}, tenantId),
  saveAiSettings: (tenantId: string, notesToLlm: boolean) =>
    request<AiSettingsView>('/api/app/ai/settings', { method: 'PUT', body: JSON.stringify({ notesToLlm }) }, tenantId),
};

/** dev-headers only: `request` sends it as X-Tenant-Id; with oidc the tenant comes from the token. */
export const LUMEN_TENANT_ID = '00000000-0000-4000-8000-000000000001';
