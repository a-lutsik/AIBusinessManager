import { useEffect, useState } from 'react';
import { Link } from 'expo-router';
import { Pressable, ScrollView, Text, TextInput } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

export default function ClientsScreen() {
  const { color, space, radius } = useThemeTokens();
  const [q, setQ] = useState('');
  const [rows, setRows] = useState<any[]>([]);

  useEffect(() => {
    const timer = setTimeout(() => {
      api.clients(LUMEN_TENANT_ID, q).then(setRows).catch(() => setRows([]));
    }, 300);
    return () => clearTimeout(timer);
  }, [q]);

  return (
    <ScrollView>
      <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, marginBottom: space.md, fontFamily: 'Plus Jakarta Sans' }}>
        {t('nav.clients')}
      </Text>
      <TextInput
        value={q}
        onChangeText={setQ}
        placeholder={t('shell.search')}
        placeholderTextColor={color.muted}
        style={{
          borderWidth: 1,
          borderColor: color.line,
          padding: space.md,
          borderRadius: radius.control,
          marginBottom: space.md,
          backgroundColor: color.surface,
          color: color.ink,
        }}
      />
      {rows.length === 0 ? <EmptyState title={t('empty.clients')} /> : null}
      {rows.map((c) => (
        <Link key={c.id} href={`/clients/${c.id}`} asChild>
          <Pressable
            style={{
              backgroundColor: color.surface,
              padding: space.lg,
              borderRadius: radius.card,
              borderWidth: 1,
              borderColor: color.line,
              marginBottom: space.sm,
            }}
          >
            <Text style={{ fontWeight: '700', color: color.ink }}>{c.displayName}</Text>
            <Text style={{ color: color.muted, marginTop: 4 }}>
              {c.normalizedPhone} · {c.completedVisitCount} visits
            </Text>
          </Pressable>
        </Link>
      ))}
    </ScrollView>
  );
}
