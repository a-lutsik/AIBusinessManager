import * as Clipboard from 'expo-clipboard';
import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { ScrollView, Text, View } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { TrustBadge } from '@/components/ui/TrustBadge';
import { api, ApiError, LUMEN_TENANT_ID } from '@/src/api/client';
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { t } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';
import { clientNameText, clientRetirement } from '@/src/utils/clientName';
import { formatDateTime } from '@/src/utils/datetime';

export default function ClientDetail() {
  const { color, space, radius } = useThemeTokens();
  const { id } = useLocalSearchParams<{ id: string }>();
  const { me, role } = useConsoleSession();
  const [data, setData] = useState<any>(null);
  const [confirmingAnonymize, setConfirmingAnonymize] = useState(false);
  const [anonymizeBusy, setAnonymizeBusy] = useState(false);
  const [anonymizeError, setAnonymizeError] = useState<string | null>(null);
  const [telegramLink, setTelegramLink] = useState<string | null>(null);
  const [telegramError, setTelegramError] = useState<string | null>(null);
  const [telegramCopied, setTelegramCopied] = useState(false);
  useEffect(() => {
    if (id) {
      api.client(LUMEN_TENANT_ID, id).then(setData).catch(() => setData(null));
    }
  }, [id]);

  const reload = () => {
    if (id) api.client(LUMEN_TENANT_ID, id).then(setData).catch(() => undefined);
  };

  const anonymize = async () => {
    if (!id) return;
    setAnonymizeBusy(true);
    setAnonymizeError(null);
    try {
      await api.anonymizeClient(LUMEN_TENANT_ID, id);
      setConfirmingAnonymize(false);
    } catch (e: unknown) {
      const code = e instanceof ApiError ? e.code : undefined;
      if (code === 'CLIENT_ALREADY_ANONYMIZED') {
        // Done by another tab or an earlier request whose answer was lost: the card shows the result.
        setConfirmingAnonymize(false);
      } else {
        setAnonymizeError(t(code === 'CLIENT_HAS_UPCOMING_VISITS' ? 'client.anonymize.errorUpcoming' : 'client.anonymize.errorGeneric'));
        return;
      }
    } finally {
      setAnonymizeBusy(false);
    }
    // Outside the try: a card that fails to reload does not mean the erasure failed.
    reload();
  };

  const createTelegramLink = async () => {
    if (!id) return;
    setTelegramError(null);
    setTelegramCopied(false);
    try {
      setTelegramLink((await api.telegramLink(LUMEN_TENANT_ID, id)).url);
    } catch (e: unknown) {
      setTelegramLink(null);
      setTelegramError(t(e instanceof ApiError && e.code === 'TELEGRAM_DISABLED' ? 'client.telegram.disabled' : 'client.telegram.error'));
    }
  };

  if (!data) {
    return <EmptyState title={t('empty.clients')} />;
  }

  const retirement = clientRetirement(data);
  const remainingSessions: number = (data.packages ?? []).reduce((sum: number, p: { remainingSessions: number }) => sum + p.remainingSessions, 0);

  return (
    <ScrollView>
      <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: DISPLAY_FONT }}>
        {clientNameText(data.client?.displayName, retirement)}
      </Text>
      {retirement ? (
        <Text style={{ color: color.muted, marginTop: 4 }}>{t(retirement === 'merged' ? 'client.merged.done' : 'client.anonymize.done')}</Text>
      ) : null}
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
      {/* Owner only: a one-time link that binds the client's Telegram chat (confirmations and reminders go there). */}
      {role === 'OWNER' && !retirement ? (
        <View testID="telegram-link-card" style={{ marginTop: space.sm, gap: space.sm }}>
          {data.client?.telegramChatId ? <Text style={{ color: color.muted }}>{t('client.telegram.connected')}</Text> : null}
          <Button label={t('client.telegram.action')} variant="ghost" onPress={createTelegramLink} />
          {telegramLink ? (
            <>
              <Text style={{ color: color.muted }}>{t('client.telegram.hint')}</Text>
              <Text testID="telegram-link-url" selectable style={{ color: color.ink }}>
                {telegramLink}
              </Text>
              <Button
                label={telegramCopied ? t('book.linkCopied') : t('book.copyLink')}
                variant="ghost"
                onPress={() => Clipboard.setStringAsync(telegramLink).then(() => setTelegramCopied(true)).catch(() => undefined)}
              />
            </>
          ) : null}
          {telegramError ? (
            <Text accessibilityRole="alert" style={{ color: color.status.noShow }}>
              {telegramError}
            </Text>
          ) : null}
        </View>
      ) : null}
      {/* Owner only: erasure on the client's request; irreversible, so it asks in place before anything is sent. */}
      {role === 'OWNER' && !retirement && !confirmingAnonymize ? (
        <Button
          label={t('client.anonymize.action')}
          variant="ghost"
          onPress={() => {
            setAnonymizeError(null);
            setConfirmingAnonymize(true);
          }}
        />
      ) : null}
      {role === 'OWNER' && !retirement && confirmingAnonymize ? (
        <View
          testID="anonymize-confirm"
          style={{
            marginTop: space.sm,
            padding: space.lg,
            gap: space.sm,
            backgroundColor: color.surface,
            borderRadius: radius.card,
            borderWidth: 1,
            borderColor: color.status.noShow,
          }}
        >
          <Text style={{ color: color.ink, fontWeight: '600' }}>{t('client.anonymize.confirmTitle')}</Text>
          <Text style={{ color: color.muted }}>{t('client.anonymize.confirmBody')}</Text>
          {remainingSessions > 0 ? (
            <Text testID="anonymize-packages" style={{ color: color.status.noShow }}>
              {t('client.anonymize.packagesWarning', { count: remainingSessions })}
            </Text>
          ) : null}
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            <Button label={t('client.anonymize.confirmAction')} disabled={anonymizeBusy} onPress={anonymize} />
            <Button label={t('action.cancel')} variant="ghost" disabled={anonymizeBusy} onPress={() => setConfirmingAnonymize(false)} />
          </View>
        </View>
      ) : null}
      {anonymizeError ? (
        <Text accessibilityRole="alert" style={{ color: color.status.noShow, marginTop: space.sm }}>
          {anonymizeError}
        </Text>
      ) : null}
      {/* A master gets no phone (null), and neither does anyone for an anonymized client. */}
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
          <Text style={{ color: color.muted, marginTop: 4 }}>{v.serviceStart ? formatDateTime(v.serviceStart, me?.timezone) : ''}</Text>
        </View>
      ))}
    </ScrollView>
  );
}
