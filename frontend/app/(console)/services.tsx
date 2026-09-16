import { useEffect, useState } from 'react';
import { ScrollView, Text, View } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { Pill } from '@/components/ui/Pill';
import { api, LUMEN_TENANT_ID, type MeView, type ServiceView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { formatMoney } from '@/src/utils/money';

export default function ServicesScreen() {
  const { color, space, radius } = useThemeTokens();
  const [rows, setRows] = useState<ServiceView[]>([]);
  const [me, setMe] = useState<MeView | null>(null);

  useEffect(() => {
    api.services(LUMEN_TENANT_ID).then(setRows).catch(() => setRows([]));
    api.me(LUMEN_TENANT_ID).then(setMe).catch(() => setMe(null));
  }, []);

  const currency = me?.currencyCode ?? 'GEL';

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.lg, fontFamily: 'Plus Jakarta Sans', color: color.ink }}>
        {t('nav.services')}
      </Text>
      {rows.length === 0 ? <EmptyState title={t('empty.services')} /> : null}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md }}>
        {rows.map((s) => (
          <View
            key={s.id}
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
              <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, fontFamily: 'Plus Jakarta Sans', flexShrink: 1 }}>
                {s.name}
              </Text>
            </View>
            {s.description ? (
              <Text style={{ color: color.muted, marginBottom: space.sm, fontSize: 13 }}>{s.description}</Text>
            ) : null}
            <Text style={{ fontSize: 18, fontWeight: '700', color: color.primary, marginBottom: 4 }}>
              {formatMoney(s.priceMinor, currency)}
            </Text>
            <Text style={{ color: color.muted, fontSize: 13, marginBottom: space.md }}>
              {s.durationMinutes} min
              {s.bufferBeforeMinutes || s.bufferAfterMinutes
                ? ` · +${s.bufferBeforeMinutes ?? 0}/${s.bufferAfterMinutes ?? 0} buffer`
                : ''}
            </Text>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
              <Pill active={s.active !== false} onLabel={t('catalog.active')} offLabel={t('catalog.inactive')} />
              <Pill active={s.publicVisible !== false} onLabel={t('catalog.public')} offLabel={t('catalog.private')} />
            </View>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}
