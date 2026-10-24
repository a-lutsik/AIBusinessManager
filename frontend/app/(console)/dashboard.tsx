import { useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ScrollView, Text, View, useWindowDimensions } from 'react-native';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { MetricCard } from '@/components/ui/MetricCard';
import { SignalCard } from '@/components/ui/SignalCard';
import { TrustBadge } from '@/components/ui/TrustBadge';
import {
  api,
  LUMEN_TENANT_ID,
  type AppointmentSummary,
  type DashboardView,
  type MetricDefinitionView,
  type OwnerTaskView,
} from '@/src/api/client';
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { t } from '@/src/i18n';
import { statusColor, useThemeTokens } from '@/src/theme/tokens';
import { copyOwnerTaskMessage } from '@/src/utils/ownerTask';

type CopyState = 'busy' | 'copied' | 'error';

const SIGNAL_ROUTES: Record<string, string> = {
  OPEN_RULES: '/rules',
  OPEN_CLIENTS: '/clients',
  OPEN_SERVICES: '/services',
};

export default function DashboardScreen() {
  const router = useRouter();
  const { color, space, radius } = useThemeTokens();
  const { width } = useWindowDimensions();
  const twoCol = width >= 960;
  const [data, setData] = useState<DashboardView | null>(null);
  const [defs, setDefs] = useState<Record<string, MetricDefinitionView>>({});
  const [error, setError] = useState<string | null>(null);
  const { role, ready } = useConsoleSession();
  const [copyState, setCopyState] = useState<Record<string, CopyState>>({});
  const copyTimers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  const copyMessage = (task: OwnerTaskView) => {
    setCopyState((prev) => ({ ...prev, [task.id]: 'busy' }));
    copyOwnerTaskMessage(LUMEN_TENANT_ID, task)
      .then(() => setCopyState((prev) => ({ ...prev, [task.id]: 'copied' })))
      .catch(() => setCopyState((prev) => ({ ...prev, [task.id]: 'error' })))
      .finally(() => {
        clearTimeout(copyTimers.current[task.id]);
        copyTimers.current[task.id] = setTimeout(() => {
          setCopyState((prev) => {
            const { [task.id]: _drop, ...rest } = prev;
            return rest;
          });
        }, 3000);
      });
  };

  useEffect(() => {
    const timers = copyTimers.current;
    return () => Object.values(timers).forEach(clearTimeout);
  }, []);

  const load = () => {
    if (!role) return;
    const nextRole = role;
    api
      .dashboard(LUMEN_TENANT_ID)
      .then(async (dash) => {
        if (nextRole === 'MASTER') {
          const today = await api.masterToday(LUMEN_TENANT_ID);
          if (today?.appointments) {
            const mapped: AppointmentSummary[] = today.appointments.map((item) => {
              const a = item.appointment;
              const trust = item.trust?.effectiveLevel ?? item.trust?.level ?? a.trustLevel;
              return { ...a, trustLevel: trust ?? a.trustLevel };
            });
            setData({ ...dash, today: mapped });
            return;
          }
        }
        setData(dash);
      })
      .catch((e) => setError(String(e.message ?? e)));

    if (nextRole === 'OWNER') {
      api
        .metricDefinitions(LUMEN_TENANT_ID)
        .then((rows) => {
          if (!rows) return;
          const map: Record<string, MetricDefinitionView> = {};
          for (const row of rows) map[row.key] = row;
          setDefs(map);
        })
        .catch(() => undefined);
    }
  };

  useEffect(() => {
    if (!ready) return;
    setData(null);
    setError(null);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, role]);

  const isOwner = role === 'OWNER';
  const today = data?.today ?? [];
  const tasks = data?.tasks ?? [];
  const metrics = data?.metrics ?? [];
  const signals = data?.signals ?? [];

  const todayColumn = (
    <View style={{ flex: twoCol ? 1 : undefined }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: space.md }}>
        <Text style={{ fontSize: 18, fontWeight: '600', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
          {t('dashboard.today')}
        </Text>
        <Button label={`${t('nav.calendar')} →`} variant="ghost" onPress={() => router.push('/calendar')} />
      </View>
      {today.length === 0 ? <EmptyState title={t('empty.calendar')} /> : null}
      {today.map((v) => (
        <View
          key={v.id}
          style={{
            backgroundColor: color.surface,
            padding: space.lg,
            borderRadius: radius.card,
            marginBottom: space.sm,
            borderLeftWidth: 4,
            borderWidth: 1,
            borderColor: color.line,
            borderLeftColor: statusColor(v.status, color),
          }}
        >
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: space.sm, alignItems: 'center' }}>
            <Text style={{ fontWeight: '700', color: color.ink, flexShrink: 1 }}>
              {v.serviceNameSnapshot ?? '—'} · {v.clientDisplayName ?? '—'}
            </Text>
            <TrustBadge level={v.trustLevel} />
          </View>
          <Text style={{ color: color.muted, marginTop: 4 }}>
            {t(`status.${v.status}`)} ·{' '}
            {v.serviceStart ? new Date(v.serviceStart).toLocaleString() : ''}
          </Text>
        </View>
      ))}
    </View>
  );

  const sideColumn = isOwner ? (
    <View style={{ flex: twoCol ? 1 : undefined }}>
      <Text style={{ fontSize: 18, fontWeight: '600', marginBottom: space.md, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
        {t('dashboard.tasks')}
      </Text>
      {tasks.length === 0 ? <EmptyState title={t('empty.tasks')} /> : null}
      {tasks.map((task) => (
        <View
          key={task.id}
          style={{ backgroundColor: color.surface, padding: space.lg, borderRadius: radius.card, marginBottom: space.sm, borderWidth: 1, borderColor: color.line }}
        >
          <Text style={{ fontWeight: '700', color: color.ink }}>{task.title}</Text>
          {task.body ? <Text style={{ marginTop: 4, color: color.ink }}>{task.body}</Text> : null}
          {task.copyText ? (
            <Button
              label={copyLabel(copyState[task.id])}
              variant="ghost"
              disabled={copyState[task.id] === 'busy'}
              onPress={() => copyMessage(task)}
              style={{ marginTop: space.md }}
            />
          ) : null}
          <Button
            label={t('action.done')}
            onPress={() =>
              api
                .completeTask(LUMEN_TENANT_ID, task.id)
                .then(load)
                .catch((e) => setError(String(e.message ?? e)))
            }
            style={{ marginTop: space.md }}
          />
        </View>
      ))}

      <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.xl, marginBottom: space.md, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
        {t('dashboard.signals')}
      </Text>
      {signals.length === 0 ? <EmptyState title={t('empty.signals')} /> : null}
      {signals.map((s) => {
        const route = s.actionType ? SIGNAL_ROUTES[s.actionType] : undefined;
        return (
          <SignalCard
            key={s.id ?? s.key + s.title}
            title={s.title}
            evidence={s.evidence}
            suggestedAction={s.suggestedAction}
            severity={s.severity}
            actionLabel={route ? `${t('action.open')} ${t(NAV_LABEL[route] ?? '')}` : undefined}
            onAction={route ? () => router.push(route as any) : undefined}
            onDismiss={
              s.id
                ? () =>
                    api
                      .dismissSignal(LUMEN_TENANT_ID, s.id!)
                      .then((row) => {
                        if (row == null) return;
                        load();
                      })
                      .catch((e) => setError(String(e.message ?? e)))
                : undefined
            }
          />
        );
      })}
    </View>
  ) : null;

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, marginBottom: space.md, fontFamily: 'Plus Jakarta Sans' }}>
        {isOwner ? t('nav.dashboard') : t('nav.today')}
      </Text>
      {error ? <Text style={{ color: color.status.noShow, marginBottom: space.md }}>{error}</Text> : null}

      {isOwner ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginBottom: space.md, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
            {t('dashboard.growth')}
          </Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md, marginBottom: space.xl }}>
            {metrics.map((m) => {
              const def = defs[m.key];
              return (
                <MetricCard
                  key={m.key}
                  label={def?.title ?? m.key}
                  value={m.value}
                  explanation={m.explanation ?? def?.formula}
                  insufficientData={m.insufficientData}
                  deltaMoM={m.deltaMoM}
                  unit={m.unit ?? def?.unit}
                  windowLabel={
                    m.windowStart && m.windowEnd ? `${m.windowStart} → ${m.windowEnd}` : undefined
                  }
                />
              );
            })}
          </View>
          {metrics.length === 0 ? <EmptyState title={t('empty.metrics')} /> : null}
        </>
      ) : null}

      <View style={{ flexDirection: twoCol ? 'row' : 'column', gap: space.xl }}>
        {todayColumn}
        {sideColumn}
      </View>
    </ScrollView>
  );
}

function copyLabel(state: CopyState | undefined): string {
  if (state === 'busy') return t('task.copying');
  if (state === 'copied') return t('task.copied');
  if (state === 'error') return t('task.copyFailed');
  return t('task.copyMessage');
}

const NAV_LABEL: Record<string, string> = {
  '/rules': 'nav.rules',
  '/clients': 'nav.clients',
  '/services': 'nav.services',
};
