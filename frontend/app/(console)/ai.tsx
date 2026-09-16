import { useEffect, useState } from 'react';
import { ScrollView, Text, TextInput, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  api,
  LUMEN_TENANT_ID,
  type AiChatResponse,
  type AiDraftAction,
  type ConversationHistoryView,
} from '@/src/api/client';
import { currentLocale, t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

type LogRow =
  | { kind: 'user'; content: string }
  | { kind: 'assistant'; reply: string; drafts: AiDraftAction[] }
  | { kind: 'system'; content: string };

function draftSummary(d: AiDraftAction): string {
  if (d.summary && d.summary.trim()) return d.summary;
  return d.toolName ?? t('ai.draftUntitled');
}

function applyHistory(
  hist: ConversationHistoryView,
  setLog: (rows: LogRow[]) => void,
  setOpenDrafts: (drafts: AiDraftAction[]) => void
) {
  const rows: LogRow[] = [];
  for (const m of hist.messages ?? []) {
    if (m.role === 'user') {
      rows.push({ kind: 'user', content: m.content ?? '' });
    } else {
      rows.push({ kind: 'assistant', reply: m.content ?? '', drafts: [] });
    }
  }
  const pending = (hist.drafts ?? []).filter((d) => d.status === 'PENDING' || !d.status);
  if (pending.length && rows.length) {
    const last = rows[rows.length - 1];
    if (last.kind === 'assistant') {
      last.drafts = pending;
    } else {
      rows.push({ kind: 'assistant', reply: '', drafts: pending });
    }
  }
  if (rows.length) setLog(rows);
  setOpenDrafts(pending);
}

export default function AiScreen() {
  const { color, space, radius } = useThemeTokens();
  const [message, setMessage] = useState('');
  const [log, setLog] = useState<LogRow[]>([]);
  const [openDrafts, setOpenDrafts] = useState<AiDraftAction[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [historyNote, setHistoryNote] = useState<string | null>(null);

  useEffect(() => {
    api
      .aiDrafts(LUMEN_TENANT_ID, 'PENDING')
      .then((rows) => {
        if (rows) setOpenDrafts(rows.filter((d) => d.status === 'PENDING' || !d.status));
      })
      .catch(() => undefined);

    const stored =
      typeof localStorage !== 'undefined' ? localStorage.getItem('cadence.aiConversationId') : null;
    if (!stored) return;
    setConversationId(stored);
    api
      .aiConversation(LUMEN_TENANT_ID, stored)
      .then((hist) => {
        if (!hist) {
          setHistoryNote(t('ai.historyUnavailable'));
          return;
        }
        setHistoryNote(null);
        applyHistory(hist, setLog, setOpenDrafts);
      })
      .catch(() => setHistoryNote(t('ai.historyUnavailable')));
  }, []);

  const send = async () => {
    if (!message.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const res: AiChatResponse = await api.aiChat(
        LUMEN_TENANT_ID,
        message,
        currentLocale(),
        conversationId
      );
      if (res.conversationId) {
        setConversationId(res.conversationId);
        if (typeof localStorage !== 'undefined') {
          localStorage.setItem('cadence.aiConversationId', res.conversationId);
        }
      }
      const drafts = res.drafts ?? [];
      setLog((prev) => [
        ...prev,
        { kind: 'user', content: message },
        { kind: 'assistant', reply: res.reply ?? '', drafts },
      ]);
      if (drafts.length) {
        setOpenDrafts((prev) => {
          const ids = new Set(prev.map((d) => d.id));
          return [...prev, ...drafts.filter((d) => !ids.has(d.id))];
        });
      }
      setMessage('');
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    } finally {
      setBusy(false);
    }
  };

  const confirm = async (id: string) => {
    try {
      await api.confirmDraft(LUMEN_TENANT_ID, id);
      setOpenDrafts((prev) => prev.filter((d) => d.id !== id));
      setLog((prev) =>
        prev.map((row) =>
          row.kind === 'assistant'
            ? { ...row, drafts: row.drafts.filter((d) => d.id !== id) }
            : row
        )
      );
      setLog((prev) => [...prev, { kind: 'system', content: t('ai.draftConfirmed') }]);
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    }
  };

  const reject = async (id: string) => {
    try {
      const res = await api.rejectDraft(LUMEN_TENANT_ID, id);
      if (res == null) {
        setError(t('ai.rejectUnavailable'));
        return;
      }
      setOpenDrafts((prev) => prev.filter((d) => d.id !== id));
      setLog((prev) =>
        prev.map((row) =>
          row.kind === 'assistant'
            ? { ...row, drafts: row.drafts.filter((d) => d.id !== id) }
            : row
        )
      );
      setLog((prev) => [...prev, { kind: 'system', content: t('ai.draftRejected') }]);
    } catch (e: unknown) {
      setError(String((e as Error).message ?? e));
    }
  };

  const renderDraftCard = (d: AiDraftAction) => (
    <View
      key={d.id}
      style={{
        marginTop: space.md,
        padding: space.md,
        borderRadius: radius.control,
        backgroundColor: `${color.ai}12`,
        borderWidth: 1,
        borderColor: `${color.ai}44`,
      }}
    >
      <Text style={{ fontWeight: '600', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>{draftSummary(d)}</Text>
      {d.toolName ? <Text style={{ color: color.muted, fontSize: 12, marginTop: 4 }}>{d.toolName}</Text> : null}
      <View style={{ flexDirection: 'row', gap: space.sm, marginTop: space.md, flexWrap: 'wrap' }}>
        <Button label={t('action.confirm')} variant="ai" onPress={() => confirm(d.id)} />
        <Button label={t('ai.reject')} variant="ghost" onPress={() => reject(d.id)} />
      </View>
    </View>
  );

  return (
    <View style={{ flex: 1 }}>
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.md, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
        {t('nav.ai')}
      </Text>
      {error ? <Text style={{ color: color.status.noShow, marginBottom: space.sm }}>{error}</Text> : null}
      {historyNote ? <Text style={{ color: color.muted, marginBottom: space.sm, fontSize: 13 }}>{historyNote}</Text> : null}

      {openDrafts.length > 0 ? (
        <View style={{ marginBottom: space.md }}>
          <Text style={{ fontSize: 16, fontWeight: '600', marginBottom: space.sm, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
            {t('ai.openDrafts')}
          </Text>
          {openDrafts.map(renderDraftCard)}
        </View>
      ) : null}

      <ScrollView style={{ flex: 1 }}>
        {log.length === 0 ? <EmptyState title={t('ai.empty')} description={t('ai.emptyHint')} /> : null}
        {log.map((row, i) => {
          if (row.kind === 'user') {
            return (
              <View
                key={i}
                style={{
                  backgroundColor: color.mist,
                  padding: space.md,
                  marginBottom: space.sm,
                  borderRadius: radius.card,
                  borderWidth: 1,
                  borderColor: color.line,
                  alignSelf: 'flex-end',
                  maxWidth: '80%',
                }}
              >
                <Text style={{ color: color.ink, lineHeight: 22 }}>{row.content}</Text>
              </View>
            );
          }
          if (row.kind === 'system') {
            return (
              <Text key={i} style={{ color: color.muted, fontSize: 13, marginBottom: space.sm, fontStyle: 'italic' }}>
                {row.content}
              </Text>
            );
          }
          return (
            <View
              key={i}
              style={{
                backgroundColor: color.surface,
                padding: space.md,
                marginBottom: space.sm,
                borderRadius: radius.card,
                borderWidth: 1,
                borderColor: color.line,
                alignSelf: 'flex-start',
                maxWidth: '80%',
              }}
            >
              {row.reply ? <Text style={{ color: color.ink, lineHeight: 22 }}>{row.reply}</Text> : null}
              {row.drafts.map(renderDraftCard)}
            </View>
          );
        })}
      </ScrollView>

      <TextInput
        value={message}
        onChangeText={setMessage}
        placeholder={t('ai.placeholder')}
        placeholderTextColor={color.muted}
        style={{
          borderWidth: 1,
          borderColor: color.line,
          padding: space.md,
          borderRadius: radius.control,
          backgroundColor: color.surface,
          marginVertical: space.sm,
          color: color.ink,
        }}
        onSubmitEditing={send}
      />
      <Button label={t('ai.send')} variant="ai" onPress={send} disabled={busy} />
    </View>
  );
}
