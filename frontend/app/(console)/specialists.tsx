import { Link, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SpecialistForm } from '@/components/catalog/SpecialistForm';
import { LoadFailed } from '@/components/catalog/FormMessages';
import { Button } from '@/components/ui/Button';
import { EmptyState } from '@/components/ui/EmptyState';
import { Pill } from '@/components/ui/Pill';
import { api, LUMEN_TENANT_ID, type SpecialistView } from '@/src/api/client';
import { useConsoleSession } from '@/src/auth/ConsoleSession';
import { t, useLocale } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';

type Editing = { kind: 'none' } | { kind: 'new' } | { kind: 'edit'; id: string };

export default function SpecialistsScreen() {
  useLocale(); // re-render the texts, the forms included, when the language changes
  const { color, space, radius } = useThemeTokens();
  const { role } = useConsoleSession();
  const [rows, setRows] = useState<SpecialistView[]>([]);
  const [editing, setEditing] = useState<Editing>({ kind: 'none' });
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const reload = useCallback(() => {
    api
      .specialists(LUMEN_TENANT_ID)
      .then((next) => {
        setRows(next);
        setLoaded(true);
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true));
  }, []);

  useFocusEffect(reload);

  const canEdit = role === 'OWNER';

  const onSaved = () => {
    setEditing({ kind: 'none' });
    reload();
  };

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.lg, fontFamily: DISPLAY_FONT, color: color.ink }}>
        {t('nav.specialists')}
      </Text>
      {canEdit && editing.kind !== 'new' ? (
        <Button label={t('specialists.add')} onPress={() => setEditing({ kind: 'new' })} style={{ marginBottom: space.lg }} />
      ) : null}
      {editing.kind === 'new' ? (
        <View style={{ marginBottom: space.lg }}>
          <SpecialistForm specialist={null} onSaved={onSaved} onCancel={() => setEditing({ kind: 'none' })} />
        </View>
      ) : null}
      {loadFailed ? <LoadFailed onRetry={reload} /> : null}
      {loaded && rows.length === 0 ? <EmptyState title={t('empty.specialists')} /> : null}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md }}>
        {rows.map((s) =>
          editing.kind === 'edit' && editing.id === s.id ? (
            <SpecialistForm key={s.id} specialist={s} onSaved={onSaved} onCancel={() => setEditing({ kind: 'none' })} />
          ) : (
            <View
              key={s.id}
              testID={`specialist-card-${s.displayName}`}
              style={{
                backgroundColor: color.surface,
                padding: space.lg,
                borderRadius: radius.card,
                borderWidth: 1,
                borderColor: color.line,
                minWidth: 220,
                flexGrow: 1,
                flexBasis: 240,
                maxWidth: 340,
              }}
            >
              <Link href={`/specialists/${s.id}`} asChild>
                <Pressable>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, marginBottom: space.md }}>
                    <View style={{ width: 14, height: 14, borderRadius: radius.pill, backgroundColor: s.calendarColor || color.secondary }} />
                    <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, fontFamily: DISPLAY_FONT, flexShrink: 1 }}>
                      {s.displayName}
                    </Text>
                  </View>
                  <View style={{ flexDirection: 'row', marginBottom: space.md }}>
                    <Pill active={s.active !== false} onLabel={t('catalog.active')} offLabel={t('catalog.inactive')} />
                  </View>
                  <Text style={{ color: color.secondary, fontWeight: '600', fontSize: 13 }}>{t('specialists.openMatrix')}</Text>
                </Pressable>
              </Link>
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
