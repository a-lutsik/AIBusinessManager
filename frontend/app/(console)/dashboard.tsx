import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
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
} from '@/src/api/client';
import { t } from '@/src/i18n';
import { statusColor, tokens } from '@/src/theme/tokens';

function readRole(): 'OWNER' | 'MASTER' {
  const stored = typeof localStorage !== 'undefined' ? localStorage.getItem('abm.role') : null;
  return stored === 'MASTER' ? 'MASTER' : 'OWNER';
}

export default function DashboardScreen() {
  const [data, setData] = useState<DashboardView | null>(null);
  const [defs, setDefs] = useState<Record<string, MetricDefinitionView>>({});
  const [error, setError] = useState<string | null>(null);
  const [role, setRole] = useState<'OWNER' | 'MASTER'>(readRole);

  const load = () => {
    const nextRole = readRole();
    setRole(nextRole);
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
    load();
  }, []);

  const isOwner = role === 'OWNER';
  const today = data?.today ?? [];
  const tasks = data?.tasks ?? [];
  const metrics = data?.metrics ?? [];
  const signals = data?.signals ?? [];

  return (
    <ScrollView contentContainerStyle={styles.pad}>
      <Text style={styles.h1}>{isOwner ? t('nav.dashboard') : t('nav.today')}</Text>
      {error ? <Text style={styles.err}>{error}</Text> : null}

      <Text style={styles.h2}>{t('dashboard.today')}</Text>
      {today.length === 0 ? <EmptyState title={t('empty.calendar')} /> : null}
      {today.map((v) => (
        <View key={v.id} style={[styles.visit, { borderLeftColor: statusColor(v.status) }]}>
          <View style={styles.visitTop}>
            <Text style={styles.k}>
              {v.serviceNameSnapshot ?? '—'} · {v.clientDisplayName ?? '—'}
            </Text>
            <TrustBadge level={v.trustLevel} />
          </View>
          <Text style={styles.muted}>
            {t(`status.${v.status}`)} ·{' '}
            {v.serviceStart ? new Date(v.serviceStart).toLocaleString() : ''}
          </Text>
        </View>
      ))}

      {isOwner ? (
        <>
          <Text style={styles.h2}>{t('dashboard.tasks')}</Text>
          {tasks.length === 0 ? <EmptyState title={t('empty.tasks')} /> : null}
          {tasks.map((task) => (
            <View key={task.id} style={styles.card}>
              <Text style={styles.k}>{task.title}</Text>
              {task.body ? <Text style={styles.body}>{task.body}</Text> : null}
              <Button
                label={t('action.done')}
                onPress={() =>
                  api
                    .completeTask(LUMEN_TENANT_ID, task.id)
                    .then(load)
                    .catch((e) => setError(String(e.message ?? e)))
                }
                style={styles.taskBtn}
              />
            </View>
          ))}

          <Text style={styles.h2}>{t('dashboard.growth')}</Text>
          <View style={styles.grid}>
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

          <Text style={styles.h2}>{t('dashboard.signals')}</Text>
          {signals.length === 0 ? <EmptyState title={t('empty.signals')} /> : null}
          {signals.map((s) => (
            <SignalCard
              key={s.id ?? s.key + s.title}
              title={s.title}
              evidence={s.evidence}
              suggestedAction={s.suggestedAction}
              severity={s.severity}
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
          ))}
        </>
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  pad: { paddingBottom: tokens.space.xxl },
  h1: {
    fontSize: 28,
    fontWeight: '700',
    color: tokens.color.ink,
    marginBottom: tokens.space.md,
    fontFamily: 'Plus Jakarta Sans',
  },
  h2: {
    fontSize: 18,
    fontWeight: '600',
    marginTop: tokens.space.xl,
    marginBottom: tokens.space.md,
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
  },
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.md, marginBottom: tokens.space.sm },
  card: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderRadius: tokens.radius.card,
    marginBottom: tokens.space.sm,
    borderWidth: 1,
    borderColor: tokens.color.line,
  },
  visit: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderRadius: tokens.radius.card,
    marginBottom: tokens.space.sm,
    borderLeftWidth: 4,
    borderWidth: 1,
    borderColor: tokens.color.line,
  },
  visitTop: { flexDirection: 'row', justifyContent: 'space-between', gap: tokens.space.sm, alignItems: 'center' },
  k: { fontWeight: '700', color: tokens.color.ink, flexShrink: 1 },
  body: { marginTop: 4, color: tokens.color.ink },
  muted: { color: tokens.color.muted, marginTop: 4 },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.md },
  taskBtn: { marginTop: tokens.space.md },
});
