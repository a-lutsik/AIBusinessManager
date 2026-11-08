import * as Clipboard from 'expo-clipboard';
import { useLocalSearchParams, type Href } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Platform, Pressable, ScrollView, Text, TextInput, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import { MaterialIcon } from '@/components/ui/MaterialIcon';
import { api, API_BASE, ApiError } from '@/src/api/client';
import { currentLocale, setLocale, t, useLocale, type AppLocale } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';
import { dayKey, formatDateTime, formatDay, formatTime, slotWindow } from '@/src/utils/datetime';
import { toAbsoluteManageUrl } from '@/src/utils/ownerTask';

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
  const [chosenDay, setChosenDay] = useState<string | null>(null);
  const [slotsLoading, setSlotsLoading] = useState(false);
  const [slotsRequest, setSlotsRequest] = useState(0);
  const [slotsFailure, setSlotsFailure] = useState<'limit' | 'other' | null>(null);
  const [slotTaken, setSlotTaken] = useState(false);
  const [linkCopied, setLinkCopied] = useState(false);
  const [telegramBusy, setTelegramBusy] = useState(false);
  const activeLocale = useLocale();
  const timeZone: string | undefined = tenant?.timezone;

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
    // A new service invalidates the chosen specialist and slot.
    /* eslint-disable react-hooks/set-state-in-effect */
    setSpecialistId(null);
    setSlot(null);
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [slug, serviceId]);

  // Tomorrow..+7 days as the tenant counts them, so the request waits for the tenant.
  const windowRange = useMemo(() => (tenant ? slotWindow(7, tenant.timezone) : null), [tenant]);

  useEffect(() => {
    if (!slug || !serviceId || !specialistId || !windowRange) return;
    let current = true;
    /* eslint-disable react-hooks/set-state-in-effect */
    setSlots([]);
    setSlotsFailure(null);
    setSlotsLoading(true);
    /* eslint-enable react-hooks/set-state-in-effect */
    api
      .publicSlots(slug, serviceId, specialistId, windowRange.from, windowRange.to)
      .then((next) => {
        if (!current) return;
        setSlots(next);
        setSlot((chosen) => (chosen && next.includes(chosen) ? chosen : null));
      })
      .catch((e: unknown) => {
        if (current) setSlotsFailure(e instanceof ApiError && e.status === 429 ? 'limit' : 'other');
      })
      .finally(() => {
        if (current) setSlotsLoading(false);
      });
    return () => {
      current = false;
    };
  }, [slug, serviceId, specialistId, windowRange, slotsRequest]);

  const slotDays = useMemo(() => {
    const byDay = new Map<string, string[]>();
    for (const s of slots) {
      const key = dayKey(s, timeZone);
      byDay.set(key, [...(byDay.get(key) ?? []), s]);
    }
    return [...byDay.entries()].map(([key, daySlots]) => ({ key, slots: daySlots }));
  }, [slots, timeZone]);
  const activeDay = slotDays.find((d) => d.key === chosenDay) ?? slotDays[0];

  const submit = async () => {
    if (!slug || !serviceId || !specialistId || !slot) return;
    setError(null);
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
      if (e instanceof ApiError && (e.code === 'CONCURRENT_RETRY' || e.code === 'CLIENT_CONFLICT')) {
        // Nothing was saved and the slot may well be free: the client tries again with the same choice.
        setError(t('book.retry'));
      } else if (e instanceof ApiError && e.status === 409) {
        // Taken while the client was typing: what was typed stays, the slot goes, the list is fresh again.
        setSlot(null);
        setSlotTaken(true);
        setSlotsRequest((n) => n + 1);
      } else {
        setError(String(e.message ?? e));
      }
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
    const manageUrl = toAbsoluteManageUrl(result.managePath);
    return (
      <ScrollView style={{ flex: 1, backgroundColor: color.canvas, padding: space.xl }}>
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: DISPLAY_FONT }}>
          {pending ? t('book.pending') : t('book.success')}
        </Text>
        <Text style={{ color: color.ink, marginTop: space.sm }}>
          {result.serviceName} · {formatDateTime(result.serviceStart, timeZone)}
        </Text>
        <Text style={{ color: color.muted, marginVertical: space.md }}>
          {t('book.saveLink')}: {manageUrl}
        </Text>
        {error ? <Text style={{ color: color.status.noShow, marginBottom: space.sm }}>{error}</Text> : null}
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
          <Button label={t('book.openLink')} href={manageUrl as Href} />
          {Platform.OS === 'web' ? (
            <Button
              label={linkCopied ? t('book.linkCopied') : t('book.copyLink')}
              variant="ghost"
              onPress={() =>
                Clipboard.setStringAsync(manageUrl)
                  .then(() => setLinkCopied(true))
                  .catch(() => setError(t('task.copyFailed')))
              }
            />
          ) : null}
          {tenant?.telegramEnabled ? (
            <Button
              label={t('book.telegram.action')}
              variant="ghost"
              disabled={telegramBusy}
              onPress={() => {
                setTelegramBusy(true);
                setError(null);
                api
                  .publicTelegramLink(result.id, result.accessToken)
                  .then((link) => {
                    if (typeof window !== 'undefined') {
                      window.open(link.url, '_blank');
                    }
                  })
                  .catch((e: unknown) =>
                    setError(t(e instanceof ApiError && e.code === 'TELEGRAM_ALREADY_LINKED' ? 'book.telegram.linked' : 'book.telegram.error'))
                  )
                  .finally(() => setTelegramBusy(false));
              }}
            />
          ) : null}
          <Button
            label={t('book.addToCalendar')}
            variant="ghost"
            onPress={() => {
              const url = `${API_BASE}/api/public/bookings/${result.id}/calendar.ics?token=${encodeURIComponent(result.accessToken)}`;
              if (typeof window !== 'undefined') {
                window.open(url, '_blank');
              }
            }}
          />
        </View>
      </ScrollView>
    );
  }

  return (
    <ScrollView style={{ flex: 1, backgroundColor: color.canvas, padding: space.xl }}>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, alignItems: 'center', justifyContent: 'space-between' }}>
        <Text style={{ fontSize: 28, fontWeight: '700', color: color.ink, fontFamily: DISPLAY_FONT }}>
          {tenant?.displayName ?? slug}
        </Text>
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          {(['en', 'ru', 'ka', 'uk', 'cs'] as AppLocale[]).map((loc) => (
            <Pressable key={loc} onPress={() => setLocale(loc)}>
              <Text style={chip(activeLocale === loc)}>{loc.toUpperCase()}</Text>
            </Pressable>
          ))}
        </View>
      </View>
      {error ? <Text style={{ color: color.status.noShow, marginTop: space.sm }}>{error}</Text> : null}

      <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: DISPLAY_FONT }}>
        {t('book.service')}
      </Text>
      {services.map((s) => (
        <Pressable key={s.id} onPress={() => setServiceId(s.id)} style={card(serviceId === s.id)}>
          <Text style={{ color: color.ink }}>{s.name}</Text>
        </Pressable>
      ))}

      {serviceId ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: DISPLAY_FONT }}>
            {t('book.specialist')}
          </Text>
          {specialists.length === 0 ? <Text style={{ color: color.muted, marginVertical: space.md }}>{t('empty.specialistsForService')}</Text> : null}
          {specialists.map((s) => (
            <Pressable
              key={s.id}
              onPress={() => {
                setSpecialistId(s.id);
                setChosenDay(null);
                setSlot(null);
              }}
              style={card(specialistId === s.id)}
            >
              <Text style={{ color: color.ink }}>{s.displayName}</Text>
            </Pressable>
          ))}
        </>
      ) : null}

      {specialistId ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: DISPLAY_FONT }}>
            {t('book.slot')}
          </Text>
          {slotTaken ? <Text accessibilityRole="alert" style={{ color: color.status.noShow, marginBottom: space.sm }}>{t('book.slotTaken')}</Text> : null}
          {/* A refused or failed request is not "no slots": the client must not read it as a fully booked specialist. */}
          {slotsFailure ? (
            <View testID="slots-failure" style={{ marginVertical: space.md, gap: space.sm }}>
              <Text accessibilityRole="alert" style={{ color: color.status.noShow }}>
                {t(slotsFailure === 'limit' ? 'book.slotsRateLimited' : 'book.slotsFailed')}
              </Text>
              <Button label={t('action.retry')} variant="ghost" onPress={() => setSlotsRequest((n) => n + 1)} />
            </View>
          ) : null}
          {slots.length === 0 && !slotsLoading && !slotsFailure ? <Text style={{ color: color.muted, marginVertical: space.md }}>{t('empty.slots')}</Text> : null}
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm, marginBottom: space.md }}>
            {slotDays.map((d) => (
              <Pressable
                key={d.key}
                accessibilityRole="button"
                onPress={() => {
                  setChosenDay(d.key);
                  setSlot(null);
                }}
                style={{
                  paddingVertical: space.sm,
                  paddingHorizontal: space.md,
                  borderRadius: radius.pill,
                  borderWidth: 1,
                  borderColor: d.key === activeDay?.key ? color.primary : color.line,
                  backgroundColor: d.key === activeDay?.key ? color.primary : color.surface,
                }}
              >
                <Text style={{ fontWeight: '600', color: d.key === activeDay?.key ? '#FFFFFF' : color.ink }}>{formatDay(d.slots[0], timeZone)}</Text>
              </Pressable>
            ))}
          </View>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
            {activeDay?.slots.map((s) => (
              <Pressable key={s} accessibilityRole="button" onPress={() => {
                  setSlot(s);
                  setSlotTaken(false);
                }} style={[card(slot === s), { marginBottom: 0 }]}>
                <Text style={{ color: color.ink, fontVariant: ['tabular-nums'] }}>{formatTime(s, timeZone)}</Text>
              </Pressable>
            ))}
          </View>
        </>
      ) : null}

      {slot ? (
        <>
          <Text style={{ fontSize: 18, fontWeight: '600', marginTop: space.lg, marginBottom: space.sm, color: color.ink, fontFamily: DISPLAY_FONT }}>
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
