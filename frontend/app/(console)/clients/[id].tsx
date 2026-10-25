import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { ScrollView, Text, View } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { TrustBadge } from '@/components/ui/TrustBadge';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

export default function ClientDetail() {
  const { color, space, radius } = useThemeTokens();
  const { id } = useLocalSearchParams<{ id: string }>();
  const [data, setData] = useState<any>(null);
  useEffect(() => {
    if (id) {
      api.client(LUMEN_TENANT_ID, id).then(setData).catch(() => setData(null));
    }
  }, [id]);

  if (!data) {
    return <EmptyState title={t('empty.clients')} />;
  }

  return (
    <ScrollView>
      <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
        {data.client?.displayName ?? '—'}
      </Text>
      {/* Owner only (a master gets marketingConsent null): withdraw consent on the client's request. */}
      {data.client?.marketingConsent === true && id ? (
        <Button
          label={t('client.withdrawConsent')}
          variant="ghost"
          onPress={() =>
            api
              .withdrawConsent(LUMEN_TENANT_ID, id)
              .then(() => api.client(LUMEN_TENANT_ID, id))
              .then(setData)
              .catch(() => undefined)
          }
        />
      ) : null}
      {/* A master gets no phone (null). */}
      {data.client?.normalizedPhone ? (
        <Text style={{ color: color.muted, marginTop: 4 }}>{data.client.normalizedPhone}</Text>
      ) : null}
      <View style={{ marginVertical: space.md }}>
        <TrustBadge level={data.trust?.effectiveLevel ?? data.trust?.level} />
      </View>
      {/* The owner reads the texts; a master gets number-free codes only (decision B). */}
      {(data.trust?.reasons ?? (data.trust?.reasonCodes ?? []).map((code: string) => t(`trustReason.${code}`))).map(
        (r: string) => (
          <Text key={r} style={{ color: color.muted }}>
            {r}
          </Text>
        )
      )}
      {(data.history ?? []).map((v: any) => (
        <View
          key={v.id}
          style={{
            backgroundColor: color.surface,
            padding: space.lg,
            marginTop: space.sm,
            borderRadius: radius.card,
            borderWidth: 1,
            borderColor: color.line,
          }}
        >
          <Text style={{ color: color.ink, fontWeight: '600' }}>
            {v.serviceNameSnapshot} · {t(`status.${v.status}`)}
          </Text>
          <Text style={{ color: color.muted, marginTop: 4 }}>{v.serviceStart}</Text>
        </View>
      ))}
    </ScrollView>
  );
}
