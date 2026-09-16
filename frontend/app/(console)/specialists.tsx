import { Link } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { EmptyState } from '@/components/ui/EmptyState';
import { Pill } from '@/components/ui/Pill';
import { api, LUMEN_TENANT_ID, type SpecialistView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

export default function SpecialistsScreen() {
  const { color, space, radius } = useThemeTokens();
  const [rows, setRows] = useState<SpecialistView[]>([]);
  useEffect(() => {
    api.specialists(LUMEN_TENANT_ID).then(setRows).catch(() => setRows([]));
  }, []);

  return (
    <ScrollView contentContainerStyle={{ paddingBottom: space.xxl }}>
      <Text style={{ fontSize: 28, fontWeight: '700', marginBottom: space.lg, fontFamily: 'Plus Jakarta Sans', color: color.ink }}>
        {t('nav.specialists')}
      </Text>
      {rows.length === 0 ? <EmptyState title={t('empty.specialists')} /> : null}
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.md }}>
        {rows.map((s) => (
          <Link key={s.id} href={`/specialists/${s.id}`} asChild>
            <Pressable
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
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, marginBottom: space.md }}>
                <View style={{ width: 14, height: 14, borderRadius: radius.pill, backgroundColor: s.calendarColor || color.secondary }} />
                <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, fontFamily: 'Plus Jakarta Sans', flexShrink: 1 }}>
                  {s.displayName}
                </Text>
              </View>
              <View style={{ flexDirection: 'row', marginBottom: space.md }}>
                <Pill active={s.active !== false} onLabel={t('catalog.active')} offLabel={t('catalog.inactive')} />
              </View>
              <Text style={{ color: color.secondary, fontWeight: '600', fontSize: 13 }}>{t('specialists.openMatrix')}</Text>
            </Pressable>
          </Link>
        ))}
      </View>
    </ScrollView>
  );
}
