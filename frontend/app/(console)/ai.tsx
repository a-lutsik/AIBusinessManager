import { useEffect, useState } from 'react';
import { ScrollView, Text, TextInput, View } from 'react-native';
import { AiSettingsPanel, BudgetBanner } from '@/components/ai/AiSettingsPanel';
import { DraftCard, failureText, type DraftDecision } from '@/components/ai/DraftCard';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import {
  api,
  ApiError,
  LUMEN_TENANT_ID,
  type AiChatResponse,
  type AiSettingsView,
  type ConversationHistoryView,
  type DraftAction,
} from '@/src/api/client';
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { currentLocale, t, useLocale } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

const CONVERSATION_KEY = 'cadence.aiConversationId';

/** A chat entry; drafts are looked up by id in the screen's draft store, so one update refreshes every card. */
type LogRow = { kind: 'user'; content: string } | { kind: 'assistant'; reply: string; draftIds: string[] };

function storedConversation(): string | undefined {
  try {
    return globalThis.localStorage?.getItem(CONVERSATION_KEY) ?? undefined;
  } catch {
    return undefined;
  }
}

function storeConversation(id: string | undefined) {
  try {
    if (id) globalThis.localStorage?.setItem(CONVERSATION_KEY, id);
    else globalThis.localStorage?.removeItem(CONVERSATION_KEY);
  } catch {
    // Storage is unavailable (private mode): the conversation just is not remembered across reloads.
  }
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) return failureText(e.code) ?? e.message;
  return e instanceof Error ? e.message : String(e);
}

/** Drafts hang on the user message of their turn; they are shown with the assistant's answer to it. */
function toRows(history: ConversationHistoryView): LogRow[] {
  const rows: LogRow[] = [];
  let turnDraftIds: string[] = [];
  const flushDraftsWithoutReply = () => {
    if (turnDraftIds.length) rows.push({ kind: 'assistant', reply: '', draftIds: turnDraftIds });
    turnDraftIds = [];
  };
  for (const m of history.messages) {
    const ids = m.drafts.map((d) => d.id);
    if (m.role === 'user') {
      flushDraftsWithoutReply();
      rows.push({ kind: 'user', content: m.content });
      turnDraftIds = ids;
    } else {
      rows.push({ kind: 'assistant', reply: m.content, draftIds: [...turnDraftIds, ...ids] });
      turnDraftIds = [];
    }
  }
  flushDraftsWithoutReply();
  return rows;
}

export default function AiRoute() {
  const { role } = useConsoleSession();
  // The nav hides this screen from masters; the route itself is still reachable by URL, and the API answers them 403.
  return role === 'MASTER' ? <EmptyState title={t('ai.ownerOnly')} /> : <AiScreen />;
}

function AiScreen() {
  const { color, space, radius } = useThemeTokens();
  const { me } = useConsoleSession();
  useLocale();
  const [message, setMessage] = useState('');
  const [log, setLog] = useState<LogRow[]>([]);
  const [drafts, setDrafts] = useState<Record<string, DraftAction>>({});
  const [openIds, setOpenIds] = useState<string[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>(undefined);
  const [settings, setSettings] = useState<AiSettingsView | null>(null);
  const [budgetExceeded, setBudgetExceeded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [deciding, setDeciding] = useState(false);
  const [savingSettings, setSavingSettings] = useState(false);

  const mergeDrafts = (list: DraftAction[]) =>
    setDrafts((prev) => ({ ...prev, ...Object.fromEntries(list.map((d) => [d.id, d])) }));

  const forgetConversation = () => {
    storeConversation(undefined);
    setConversationId(undefined);
    setLog([]);
  };

  const loadConversation = async (id: string) => {
    try {
      const history = await api.aiConversation(LUMEN_TENANT_ID, id);
      mergeDrafts([...history.drafts, ...history.messages.flatMap((m) => m.drafts)]);
      setLog(toRows(history));
    } catch (e: unknown) {
      if (e instanceof ApiError && e.code === 'CONVERSATION_NOT_FOUND') forgetConversation();
      else setError(errorText(e));
    }
  };

  /** Pulls every recent draft so cards keep the true status; pending ones outside the chat go to the open list. */
  const loadOpenDrafts = async () => {
    try {
      const list = await api.aiDrafts(LUMEN_TENANT_ID);
      mergeDrafts(list);
      const pending = list.filter((d) => d.status === 'PENDING').map((d) => d.id);
      setOpenIds((prev) => [...new Set([...prev, ...pending])]);
    } catch (e: unknown) {
      setError(errorText(e));
    }
  };

  const loadSettings = async () => {
    try {
      setSettings(await api.aiSettings(LUMEN_TENANT_ID));
    } catch (e: unknown) {
      setError(errorText(e));
    }
  };

  const refresh = (id: string | undefined) =>
    Promise.all([id ? loadConversation(id) : undefined, loadOpenDrafts(), loadSettings()]);

  useEffect(() => {
    const stored = storedConversation();
    // Restores the remembered conversation id from browser storage once on mount.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setConversationId(stored);
    void refresh(stored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startConversation = () => {
    forgetConversation();
    setError(null);
    setBudgetExceeded(false);
    void loadOpenDrafts();
  };

  const send = async () => {
    const text = message.trim();
    if (!text || busy) return;
    setBusy(true);
    setError(null);
    try {
      let res: AiChatResponse;
      try {
        res = await api.aiChat(LUMEN_TENANT_ID, text, currentLocale(), conversationId);
      } catch (e: unknown) {
        if (!conversationId || !(e instanceof ApiError) || e.code !== 'CONVERSATION_NOT_FOUND') throw e;
        // The remembered conversation is gone (retention, another tenant): start a new one with the same message.
        forgetConversation();
        res = await api.aiChat(LUMEN_TENANT_ID, text, currentLocale());
      }
      storeConversation(res.conversationId);
      setConversationId(res.conversationId);
      mergeDrafts(res.drafts);
      setLog((prev) => [
        ...prev,
        { kind: 'user', content: text },
        { kind: 'assistant', reply: res.reply, draftIds: res.drafts.map((d) => d.id) },
      ]);
      setSettings((prev) =>
        prev && { ...prev, monthTokensUsed: res.usage.monthTokensUsed, monthlyTokenBudget: res.usage.monthTokenBudget }
      );
      setBudgetExceeded(false);
      setMessage('');
    } catch (e: unknown) {
      if (e instanceof ApiError && e.code === 'AI_BUDGET_EXCEEDED') {
        // The turn may have stored the message and some drafts before the budget ran out: show them. The text stays typed.
        setBudgetExceeded(true);
        await refresh(conversationId);
      } else {
        setError(errorText(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const decide = async (id: string, decision: DraftDecision) => {
    if (deciding) return;
    setDeciding(true);
    setError(null);
    try {
      const updated =
        decision === 'confirm'
          ? await api.confirmDraft(LUMEN_TENANT_ID, id)
          : await api.rejectDraft(LUMEN_TENANT_ID, id);
      mergeDrafts([updated]);
    } catch (e: unknown) {
      setError(errorText(e));
      // 409: the draft was resolved, expired or refused by the engine meanwhile; reload to show its real status.
      if (e instanceof ApiError && e.status === 409) await refresh(conversationId);
    } finally {
      setDeciding(false);
    }
  };

  const changeNotes = async (notesToLlm: boolean) => {
    setSavingSettings(true);
    setError(null);
    try {
      setSettings(await api.saveAiSettings(LUMEN_TENANT_ID, notesToLlm));
    } catch (e: unknown) {
      setError(errorText(e));
    } finally {
      setSavingSettings(false);
    }
  };

  const renderDrafts = (ids: string[]) =>
    ids.map((id) => drafts[id]).filter((d): d is DraftAction => d !== undefined)
      .map((d) => <DraftCard key={d.id} draft={d} busy={deciding} onDecide={decide} />);

  const loggedIds = new Set(log.flatMap((row) => (row.kind === 'assistant' ? row.draftIds : [])));
  const openOutsideChat = openIds.filter((id) => !loggedIds.has(id));

  return (
    <View style={{ flex: 1 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: space.md, flexWrap: 'wrap', marginBottom: space.md }}>
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
          {t('nav.ai')}
        </Text>
        <Button
          label={t('ai.newConversation')}
          variant="ghost"
          onPress={startConversation}
          disabled={busy || (!conversationId && log.length === 0)}
        />
      </View>
      {error ? <Text style={{ color: color.status.noShow, marginBottom: space.sm }}>{error}</Text> : null}
      {budgetExceeded ? <BudgetBanner settings={settings} timeZone={me?.timezone} /> : null}
      {settings ? (
        <AiSettingsPanel settings={settings} saving={savingSettings} timeZone={me?.timezone} onNotesChange={changeNotes} />
      ) : null}

      <ScrollView style={{ flex: 1 }}>
        {openOutsideChat.length > 0 ? (
          <View style={{ marginBottom: space.md }}>
            <Text style={{ fontSize: 16, fontWeight: '600', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
              {t('ai.openDrafts')}
            </Text>
            {renderDrafts(openOutsideChat)}
          </View>
        ) : null}
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
                alignSelf: row.draftIds.length ? 'stretch' : 'flex-start',
                maxWidth: row.draftIds.length ? '100%' : '80%',
              }}
            >
              {row.reply ? <Text style={{ color: color.ink, lineHeight: 22 }}>{row.reply}</Text> : null}
              {renderDrafts(row.draftIds)}
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
