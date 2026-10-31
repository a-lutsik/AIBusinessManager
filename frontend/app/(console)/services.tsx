import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { ServiceForm } from '@/components/catalog/ServiceForm';
import { LoadFailed } from '@/components/catalog/FormMessages';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Pill } from '@/components/ui/Pill';
import { api, LUMEN_TENANT_ID, type ServiceView } from '@/src/api/client';
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { currentLocale, t, useLocale } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';
import { formatMoney } from '@/src/utils/money';

type Editing = { kind: 'none' } | { kind: 'new' } | { kind: 'edit'; id: string };

export default function ServicesScreen() {
  useLocale(); // re-render the texts, the forms included, when the language changes
  const { color, space, radius } = useThemeTokens();
  const { me, role } = useConsoleSession();
  const [rows, setRows] = useState<ServiceView[]>([]);
  const [editing, setEditing] = useState<Editing>({ kind: 'none' });
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const reload = useCallback(() => {
    api
      .services(LUMEN_TENANT_ID)
      .then((next) => {
        setRows(next);
        setLoaded(true);
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true));
  }, []);

  // Reading the list again does not touch an open form. Leaving the screen does: what was not saved is gone.
  useFocusEffect(reload);

  // Prices are read and typed in the tenant's currency: without it, no prices and no forms.
  const currency = me?.currencyCode;
  const canEdit = role === 'OWNER' && currency != null;

  const onSaved = () => {
    setEditing({ kind: 'none' });
    reload();
  };

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.lg, fontFamily: DISPLAY_FONT, color: color.ink }}>
        {t('nav.services')}
      </Text>
      {canEdit && editing.kind !== 'new' ? (
        <Button label={t('services.add')} onPress={() => setEditing({ kind: 'new' })} style={{ marginBottom: space.lg }} />
      ) : null}
      {editing.kind === 'new' && currency ? (
        <View style={{ marginBottom: space.lg }}>
          <ServiceForm service={null} currency={currency} onSaved={onSaved} onCancel={() => setEditing({ kind: 'none' })} />
        </View>
      ) : null}
      {loadFailed ? <LoadFailed onRetry={reload} /> : null}
      {loaded && rows.length === 0 ? <EmptyState title={t('empty.services')} /> : null}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md }}>
        {rows.map((s) =>
          editing.kind === 'edit' && editing.id === s.id && currency ? (
            <ServiceForm key={s.id} service={s} currency={currency} onSaved={onSaved} onCancel={() => setEditing({ kind: 'none' })} />
          ) : (
            <View
              key={s.id}
              testID={`service-card-${s.name}`}
              style={{
                backgroundColor: color.surface,
                padding: space.lg,
                borderRadius: radius.card,
                borderWidth: 1,
                borderColor: color.line,
                minWidth: 240,
                flexGrow: 1,
                flexBasis: 260,
                maxWidth: 360,
              }}
            >
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, marginBottom: space.sm }}>
                <View style={{ width: 14, height: 14, borderRadius: radius.pill, backgroundColor: s.color || color.primary }} />
                <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, fontFamily: DISPLAY_FONT, flexShrink: 1 }}>
                  {s.name}
                </Text>
              </View>
              {s.description ? (
                <Text style={{ color: color.muted, marginBottom: space.sm, fontSize: 13 }}>{s.description}</Text>
              ) : null}
              <Text style={{ fontSize: 18, fontWeight: '700', color: color.primary, marginBottom: 4 }}>
                {currency ? formatMoney(s.priceMinor, currency, currentLocale()) : '—'}
              </Text>
              <Text style={{ color: color.muted, fontSize: 13, marginBottom: space.md }}>
                {t('unit.minutes', { count: s.durationMinutes })}
                {s.bufferBeforeMinutes || s.bufferAfterMinutes
                  ? ` · ${t('service.buffer', { before: s.bufferBeforeMinutes ?? 0, after: s.bufferAfterMinutes ?? 0 })}`
                  : ''}
              </Text>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
                <Pill active={s.active !== false} onLabel={t('catalog.active')} offLabel={t('catalog.inactive')} />
                <Pill active={s.publicVisible !== false} onLabel={t('catalog.public')} offLabel={t('catalog.private')} />
              </View>
              {canEdit ? (
                <Button label={t('action.edit')} variant="ghost" onPress={() => setEditing({ kind: 'edit', id: s.id })} style={{ marginTop: space.md }} />
              ) : null}
            </View>
          )
        )}
      </View>
    </ScrollView>
  );
}
