import { useLocalSearchParams } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { MaterialIcon } from '@/components/ui/MaterialIcon';
import { api, API_BASE } from '@/src/api/client';
import { currentLocale, setLocale, t, type AppLocale } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

const COUNTRIES = [
  { code: 'GE', dial: '+995' },
  { code: 'RU', dial: '+7' },
  { code: 'UA', dial: '+380' },
  { code: 'CZ', dial: '+420' },
];

export default function PublicBookScreen() {
  const { color, space, radius } = useThemeTokens();
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

  const card = (selected: boolean) => ({
    backgroundColor: color.surface,
    padding: space.md,
    borderRadius: radius.card,
    marginBottom: space.sm,
    borderWidth: selected ? 2 : 1,
    borderColor: selected ? color.primary : color.line,
  });
  const chip = (selected: boolean) => ({
    color: selected ? color.primary : color.muted,
    fontWeight: '600' as const,
    padding: 4,
  });
  const input = {
    borderWidth: 1,
    borderColor: color.line,
    padding: space.md,
    borderRadius: radius.control,
    marginBottom: space.sm,
    backgroundColor: color.surface,
    color: color.ink,
  };

  if (result) {
    const pending = result.status === 'PENDING';
    return (
      <ScrollView style={{ flex: 1, backgroundColor: color.canvas, padding: space.xl }}>
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
          {pending ? t('book.pending') : t('book.success')}
        </Text>
        <Text style={{ color: color.ink, marginTop: space.sm }}>
          {result.serviceName} · {result.serviceStart}
        </Text>
        <Text style={{ color: color.muted, marginVertical: space.md }}>
          {t('book.saveLink')}: {result.managePath}
        </Text>
        <Button
          label={t('book.addToCalendar')}
          onPress={() => {
            const url = `${API_BASE}/api/public/bookings/${result.id}/calendar.ics?token=${encodeURIComponent(result.accessToken)}`;
            if (typeof window !== 'undefined') {
              window.open(url, '_blank');
            }
          }}
        />
      </ScrollView>
    );
  }

  return (
    <ScrollView style={{ flex: 1, backgroundColor: color.canvas, padding: space.xl }}>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, alignItems: 'center', justifyContent: 'space-between' }}>
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
          {tenant?.displayName ?? slug}
        </Text>
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          {(['en', 'ru', 'ka', 'uk', 'cs'] as AppLocale[]).map((loc) => (
            <Pressable key={loc} onPress={() => { setLocale(loc); bump((n) => n + 1); }}>
              <Text style={chip(currentLocale() === loc)}>{loc.toUpperCase()}</Text>
            </Pressable>
          ))}
        </View>
      </View>
      {error ? <Text style={{ color: color.status.noShow, marginTop: space.sm }}>{error}</Text> : null}

      <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
        {t('book.service')}
      </Text>
      {services.map((s) => (
        <Pressable key={s.id} onPress={() => setServiceId(s.id)} style={card(serviceId === s.id)}>
          <Text style={{ color: color.ink }}>{s.name}</Text>
        </Pressable>
      ))}

      {serviceId ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
            {t('book.specialist')}
          </Text>
          {specialists.length === 0 ? <Text style={{ color: color.muted, marginVertical: space.md }}>{t('empty.specialistsForService')}</Text> : null}
          {specialists.map((s) => (
            <Pressable key={s.id} onPress={() => setSpecialistId(s.id)} style={card(specialistId === s.id)}>
              <Text style={{ color: color.ink }}>{s.displayName}</Text>
            </Pressable>
          ))}
        </>
      ) : null}

      {specialistId ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
            {t('book.slot')}
          </Text>
          {slots.length === 0 ? <Text style={{ color: color.muted, marginVertical: space.md }}>{t('empty.slots')}</Text> : null}
          {slots.map((s) => (
            <Pressable key={s} onPress={() => setSlot(s)} style={card(slot === s)}>
              <Text style={{ color: color.ink }}>{new Date(s).toLocaleString()}</Text>
            </Pressable>
          ))}
        </>
      ) : null}

      {slot ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
            {t('book.contact')}
          </Text>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginBottom: space.sm }}>
            {COUNTRIES.map((c) => (
              <Pressable key={c.code} onPress={() => setCountry(c.code)}>
                <Text style={chip(country === c.code)}>
                  {c.code} {c.dial}
                </Text>
              </Pressable>
            ))}
          </View>
          <TextInput value={name} onChangeText={setName} placeholder={t('book.name')} placeholderTextColor={color.muted} style={input} />
          <TextInput
            value={phone}
            onChangeText={setPhone}
            placeholder={`${t('book.phone')} (${country})`}
            placeholderTextColor={color.muted}
            style={input}
          />
          <Pressable
            onPress={() => setConsent(!consent)}
            style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, marginVertical: space.sm }}
          >
            <View
              style={{
                width: 20,
                height: 20,
                borderRadius: 4,
                borderWidth: 1,
                borderColor: consent ? color.primary : color.line,
                backgroundColor: consent ? color.primary : color.surface,
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              {consent ? <MaterialIcon name="check" size={14} color="#FFFFFF" /> : null}
            </View>
            <Text style={{ color: color.ink, flexShrink: 1 }}>{t('book.consent')}</Text>
          </Pressable>
          <Button label={t('book.submit')} onPress={submit} style={{ marginTop: space.md, alignSelf: 'stretch' }} />
        </>
      ) : null}
    </ScrollView>
  );
}
