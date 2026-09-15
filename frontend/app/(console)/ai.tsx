import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { currentLocale, t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function AiScreen() {
  const [message, setMessage] = useState('');
  const [log, setLog] = useState<any[]>([]);
  const [conversationId, setConversationId] = useState<string | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const send = async () => {
    if (!message.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api.aiChat(LUMEN_TENANT_ID, message, currentLocale(), conversationId);
      setConversationId(res.conversationId);
      setLog((prev) => [...prev, { role: 'user', content: message }, res]);
      setMessage('');
    } catch (e: any) {
      setError(String(e.message ?? e));
    } finally {
      setBusy(false);
    }
  };

  const confirm = async (id: string) => {
    try {
      await api.confirmDraft(LUMEN_TENANT_ID, id);
    } catch (e: any) {
      setError(String(e.message ?? e));
    }
  };

  return (
    <View style={{ flex: 1 }}>
      <Text style={styles.h1}>{t('nav.ai')}</Text>
      {error ? <Text style={styles.err}>{error}</Text> : null}
      <ScrollView style={{ flex: 1 }}>
        {log.map((row, i) => (
          <View key={i} style={styles.card}>
            <Text>{row.reply ?? row.content}</Text>
            {(row.drafts ?? []).map((d: any) => (
              <Pressable key={d.id} style={styles.btn} onPress={() => confirm(d.id)}>
                <Text style={styles.btnText}>Confirm {d.toolName}</Text>
              </Pressable>
            ))}
          </View>
        ))}
      </ScrollView>
      <TextInput value={message} onChangeText={setMessage} placeholder={t('ai.placeholder')} style={styles.input} />
      <Pressable style={styles.btn} onPress={send} disabled={busy}>
        <Text style={styles.btnText}>{t('ai.send')}</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  h1: { fontSize: 28, fontWeight: '700', marginBottom: tokens.space.md },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, marginBottom: tokens.space.sm, borderRadius: tokens.radius.control },
  input: { borderWidth: 1, borderColor: tokens.color.line, padding: tokens.space.md, borderRadius: tokens.radius.control, backgroundColor: tokens.color.surface, marginVertical: tokens.space.sm },
  btn: { backgroundColor: tokens.color.accent, padding: tokens.space.md, borderRadius: tokens.radius.control, alignSelf: 'flex-start' },
  btnText: { color: '#ecfdf5', fontWeight: '600' },
  err: { color: tokens.color.status.noShow, marginBottom: tokens.space.sm },
});
