import { useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
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
import { tokens } from '@/src/theme/tokens';

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
      typeof localStorage !== 'undefined' ? localStorage.getItem('abm.aiConversationId') : null;
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
          localStorage.setItem('abm.aiConversationId', res.conversationId);
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
    <View key={d.id} style={styles.draft}>
      <Text style={styles.draftTitle}>{draftSummary(d)}</Text>
      {d.toolName ? <Text style={styles.draftMeta}>{d.toolName}</Text> : null}
      <View style={styles.draftActions}>
        <Button label={t('action.confirm')} variant="ai" onPress={() => confirm(d.id)} />
        <Button label={t('ai.reject')} variant="ghost" onPress={() => reject(d.id)} />
      </View>
    </View>
  );

  return (
    <View style={{ flex: 1 }}>
      <Text style={styles.h1}>{t('nav.ai')}</Text>
      {error ? <Text style={styles.err}>{error}</Text> : null}
      {historyNote ? <Text style={styles.muted}>{historyNote}</Text> : null}

      {openDrafts.length > 0 ? (
        <View style={styles.openDrafts}>
          <Text style={styles.h2}>{t('ai.openDrafts')}</Text>
          {openDrafts.map(renderDraftCard)}
        </View>
      ) : null}

      <ScrollView style={{ flex: 1 }}>
        {log.length === 0 ? <EmptyState title={t('ai.empty')} description={t('ai.emptyHint')} /> : null}
        {log.map((row, i) => {
          if (row.kind === 'user') {
            return (
              <View key={i} style={[styles.card, styles.userCard]}>
                <Text style={styles.bubble}>{row.content}</Text>
              </View>
            );
          }
          if (row.kind === 'system') {
            return (
              <Text key={i} style={styles.system}>
                {row.content}
              </Text>
            );
          }
          return (
            <View key={i} style={styles.card}>
              {row.reply ? <Text style={styles.bubble}>{row.reply}</Text> : null}
              {row.drafts.map(renderDraftCard)}
            </View>
          );
        })}
      </ScrollView>

      <TextInput
        value={message}
        onChangeText={setMessage}
        placeholder={t('ai.placeholder')}
        style={styles.input}
        onSubmitEditing={send}
      />
      <Button label={t('ai.send')} variant="ai" onPress={send} disabled={busy} />
    </View>
  );
}

const styles = StyleSheet.create({
  h1: {
    fontSize: 28,
    fontWeight: '700',
    marginBottom: tokens.space.md,
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
  },
  h2: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: tokens.space.sm,
    color: tokens.color.ink,
    fontFamily: 'Plus Jakarta Sans',
  },
  openDrafts: { marginBottom: tokens.space.md },
  card: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.md,
    marginBottom: tokens.space.sm,
    borderRadius: tokens.radius.card,
    borderWidth: 1,
    borderColor: tokens.color.line,
  },
  userCard: { backgroundColor: tokens.color.mist },
  bubble: { color: tokens.color.ink, lineHeight: 22 },
  draft: {
    marginTop: tokens.space.md,
    padding: tokens.space.md,
    borderRadius: tokens.radius.control,
    backgroundColor: `${tokens.color.ai}12`,
    borderWidth: 1,
    borderColor: `${tokens.color.ai}44`,
  },
  draftTitle: { fontWeight: '600', color: tokens.color.ink, fontFamily: 'Plus Jakarta Sans' },
  draftMeta: { color: tokens.color.muted, fontSize: 12, marginTop: 4 },
  draftActions: { flexDirection: 'row', gap: tokens.space.sm, marginTop: tokens.space.md, flexWrap: 'wrap' },
  input: {
    borderWidth: 1,
    borderColor: tokens.color.line,
    padding: tokens.space.md,
    borderRadius: tokens.radius.control,
    backgroundColor: tokens.color.surface,
    marginVertical: tokens.space.sm,
    color: tokens.color.ink,
  },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.sm },
  muted: { color: tokens.color.muted, marginBottom: tokens.space.sm, fontSize: 13 },
  system: { color: tokens.color.muted, fontSize: 13, marginBottom: tokens.space.sm, fontStyle: 'italic' },
});
