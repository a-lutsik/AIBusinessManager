import { useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { api, API_BASE } from '@/src/api/client';
import { currentLocale, setLocale, t, type AppLocale } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';

const COUNTRIES = [
  { code: 'GE', dial: '+995' },
  { code: 'RU', dial: '+7' },
  { code: 'UA', dial: '+380' },
  { code: 'CZ', dial: '+420' },
];

export default function PublicBookScreen() {
  const { slug } = useLocalSearchParams<{ slug: string }>();
  const [tenant, setTenant] = useState<any>(null);
  const [services, setServices] = useState<any[]>([]);
  const [specialists, setSpecialists] = useState<any[]>([]);
  const [slots, setSlots] = useState<string[]>([]);
  const [serviceId, setServiceId] = useState<string | null>(null);
  const [specialistId, setSpecialistId] = useState<string | null>(null);
  const [slot, setSlot] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [country, setCountry] = useState('GE');
  const [consent, setConsent] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [, bump] = useState(0);

  useEffect(() => {
    if (!slug) return;
    api.publicTenant(slug).then((row) => {
      setTenant(row);
      if (row.countryCode) {
        setCountry(row.countryCode);
      }
    }).catch((e) => setError(String(e.message ?? e)));
    api.publicServices(slug).then(setServices).catch(() => setServices([]));
  }, [slug]);

  useEffect(() => {
    if (!slug || !serviceId) return;
    api.publicSpecialists(slug, serviceId).then(setSpecialists).catch(() => setSpecialists([]));
    setSpecialistId(null);
    setSlot(null);
  }, [slug, serviceId]);

  const windowRange = useMemo(() => {
    const now = new Date();
    const from = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + 1));
    const to = new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + 7));
    return { from: from.toISOString().slice(0, 10), to: to.toISOString().slice(0, 10) };
  }, []);

  useEffect(() => {
    if (!slug || !serviceId || !specialistId) return;
    api.publicSlots(slug, serviceId, specialistId, windowRange.from, windowRange.to).then(setSlots).catch(() => setSlots([]));
  }, [slug, serviceId, specialistId, windowRange.from, windowRange.to]);

  const submit = async () => {
    if (!slug || !serviceId || !specialistId || !slot) return;
    try {
      const dial = COUNTRIES.find((c) => c.code === country)?.dial ?? '';
      const normalized = phone.trim().startsWith('+') ? phone.trim() : `${dial}${phone.trim()}`;
      const booked = await api.publicBook(slug, {
        serviceId,
        specialistId,
        serviceStart: slot,
        phone: normalized,
        name,
        locale: currentLocale(),
        marketingConsent: consent,
        countryCode: country,
      });
      setResult(booked);
    } catch (e: any) {
      setError(String(e.message ?? e));
    }
  };

  if (result) {
    const pending = result.status === 'PENDING';
    return (
      <ScrollView style={styles.page}>
        <Text style={styles.h1}>{pending ? t('book.pending') : t('book.success')}</Text>
        <Text>{result.serviceName} · {result.serviceStart}</Text>
        <Text style={styles.muted}>{t('book.saveLink')}: {result.managePath}</Text>
        <Pressable
          style={styles.btn}
          onPress={() => {
            const url = `${API_BASE}/api/public/bookings/${result.id}/calendar.ics?token=${encodeURIComponent(result.accessToken)}`;
            if (typeof window !== 'undefined') {
              window.open(url, '_blank');
            }
          }}
        >
          <Text style={styles.btnText}>{t('book.addToCalendar')}</Text>
        </Pressable>
      </ScrollView>
    );
  }

  return (
    <ScrollView style={styles.page}>
      <View style={styles.row}>
        <Text style={styles.h1}>{tenant?.displayName ?? slug}</Text>
        {(['en', 'ru', 'ka', 'uk', 'cs'] as AppLocale[]).map((loc) => (
          <Pressable key={loc} onPress={() => { setLocale(loc); bump((n) => n + 1); }}>
            <Text style={styles.chip}>{loc.toUpperCase()}</Text>
          </Pressable>
        ))}
      </View>
      {error ? <Text style={styles.err}>{error}</Text> : null}
      <Text style={styles.h2}>{t('book.service')}</Text>
      {services.map((s) => (
        <Pressable key={s.id} onPress={() => setServiceId(s.id)} style={[styles.card, serviceId === s.id && styles.sel]}>
          <Text>{s.name}</Text>
        </Pressable>
      ))}
      {serviceId ? (
        <>
          <Text style={styles.h2}>{t('book.specialist')}</Text>
          {specialists.length === 0 ? <Text style={styles.muted}>{t('empty.specialists')}</Text> : null}
          {specialists.map((s) => (
            <Pressable key={s.id} onPress={() => setSpecialistId(s.id)} style={[styles.card, specialistId === s.id && styles.sel]}>
              <Text>{s.displayName}</Text>
            </Pressable>
          ))}
        </>
      ) : null}
      {specialistId ? (
        <>
          <Text style={styles.h2}>{t('book.slot')}</Text>
          {slots.length === 0 ? <Text style={styles.muted}>{t('empty.slots')}</Text> : null}
          {slots.map((s) => (
            <Pressable key={s} onPress={() => setSlot(s)} style={[styles.card, slot === s && styles.sel]}>
              <Text>{new Date(s).toLocaleString()}</Text>
            </Pressable>
          ))}
        </>
      ) : null}
      {slot ? (
        <>
          <Text style={styles.h2}>{t('book.contact')}</Text>
          <View style={styles.row}>
            {COUNTRIES.map((c) => (
              <Pressable key={c.code} onPress={() => setCountry(c.code)}>
                <Text style={[styles.chip, country === c.code && styles.sel]}>{c.code} {c.dial}</Text>
              </Pressable>
            ))}
          </View>
          <TextInput value={name} onChangeText={setName} placeholder={t('book.name')} style={styles.input} />
          <TextInput value={phone} onChangeText={setPhone} placeholder={`${t('book.phone')} (${country})`} style={styles.input} />
          <Pressable onPress={() => setConsent(!consent)}><Text>{consent ? '☑' : '☐'} {t('book.consent')}</Text></Pressable>
          <Pressable style={styles.btn} onPress={submit}><Text style={styles.btnText}>{t('book.submit')}</Text></Pressable>
        </>
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: tokens.color.canvas, padding: tokens.space.xl },
  h1: { fontSize: 28, fontWeight: '700', color: tokens.color.ink },
  h2: { fontSize: 18, fontWeight: '600', marginTop: tokens.space.lg, marginBottom: tokens.space.sm },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.sm, alignItems: 'center', justifyContent: 'space-between' },
  card: { backgroundColor: tokens.color.surface, padding: tokens.space.md, borderRadius: tokens.radius.control, marginBottom: tokens.space.sm },
  sel: { borderWidth: 2, borderColor: tokens.color.accent },
  input: { borderWidth: 1, borderColor: tokens.color.line, padding: tokens.space.md, borderRadius: tokens.radius.control, marginBottom: tokens.space.sm, backgroundColor: tokens.color.surface },
  btn: { marginTop: tokens.space.md, backgroundColor: tokens.color.accent, padding: tokens.space.md, borderRadius: tokens.radius.control },
  btnText: { color: '#ecfdf5', textAlign: 'center', fontWeight: '600' },
  muted: { color: tokens.color.muted, marginVertical: tokens.space.md },
  err: { color: tokens.color.status.noShow },
  chip: { color: tokens.color.accent, fontWeight: '600', padding: 4 },
});
