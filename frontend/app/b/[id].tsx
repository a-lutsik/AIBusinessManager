import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { api, API_BASE } from '@/src/api/client';
import { t } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

export default function BookingLinkScreen() {
  const { id, token } = useLocalSearchParams<{ id: string; token?: string }>();
  const [visit, setVisit] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (id && token) {
      api.publicBooking(id, token).then(setVisit).catch((e) => setError(String(e.message ?? e)));
    }
  }, [id, token]);
  if (error) {
    return <Text style={{ padding: 24 }}>{error}</Text>;
  }
  if (!visit) {
    return <Text style={{ padding: 24 }}>…</Text>;
  }
  const cancelled = String(visit.status).startsWith('CANCELLED');
  return (
    <View style={styles.page}>
      <Text style={styles.h1}>{visit.serviceNameSnapshot}</Text>
      <Text>{t(`status.${visit.status}`)}</Text>
      <Text style={styles.muted}>{visit.serviceStart}</Text>
      <Pressable
        style={styles.btn}
        onPress={() => {
          if (typeof window !== 'undefined' && token) {
            window.open(`${API_BASE}/api/public/bookings/${id}/calendar.ics?token=${encodeURIComponent(token)}`, '_blank');
          }
        }}
      >
        <Text style={styles.btnText}>{t('book.addToCalendar')}</Text>
      </Pressable>
      {!cancelled && token ? (
        <Pressable
          style={styles.ghost}
          onPress={() => api.publicCancel(id, token).then(setVisit).catch((e) => setError(String(e.message ?? e)))}
        >
          <Text>{t('action.cancel')}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, padding: tokens.space.xl, backgroundColor: tokens.color.canvas },
  h1: { fontSize: 28, fontWeight: '700' },
  muted: { color: tokens.color.muted, marginVertical: tokens.space.md },
  btn: { backgroundColor: tokens.color.accent, padding: tokens.space.md, borderRadius: tokens.radius.control },
  btnText: { color: '#ecfdf5', textAlign: 'center' },
  ghost: { marginTop: tokens.space.md, padding: tokens.space.md },
});
