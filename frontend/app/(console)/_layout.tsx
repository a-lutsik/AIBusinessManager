import { Link, Slot, usePathname } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { MaterialIcon } from '@/components/ui/MaterialIcon';
import { api, LUMEN_TENANT_ID, type MeView } from '@/src/api/client';
import { currentLocale, setLocale, t, type AppLocale } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { useTheme } from '@/src/theme/ThemeContext';

type NavItem = {
  href: string;
  key: string;
  icon: string;
  ownerOnly?: boolean;
  masterLabelKey?: string;
};

const NAV: NavItem[] = [
  { href: '/dashboard', key: 'nav.dashboard', icon: 'dashboard', masterLabelKey: 'nav.today' },
  { href: '/calendar', key: 'nav.calendar', icon: 'calendar_month' },
  { href: '/clients', key: 'nav.clients', icon: 'group' },
  { href: '/services', key: 'nav.services', icon: 'spa', ownerOnly: true },
  { href: '/specialists', key: 'nav.specialists', icon: 'badge', ownerOnly: true },
  { href: '/rules', key: 'nav.rules', icon: 'tune', ownerOnly: true },
  { href: '/ai', key: 'nav.ai', icon: 'auto_awesome', ownerOnly: true },
];

const RAIL_COLLAPSED = 72;
const RAIL_EXPANDED = 240;

export default function ConsoleLayout() {
  const path = usePathname();
  const { width } = useWindowDimensions();
  const desktop = width >= 768;
  const [, bump] = useState(0);
  const [role, setRole] = useState<'OWNER' | 'MASTER'>('OWNER');
  const [me, setMe] = useState<MeView | null>(null);
  const [railExpanded, setRailExpanded] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [search, setSearch] = useState('');
  const { theme, toggle } = useTheme();
  const { color, space, radius } = useThemeTokens();

  useEffect(() => {
    const stored = typeof localStorage !== 'undefined' ? localStorage.getItem('abm.role') : null;
    if (stored === 'OWNER' || stored === 'MASTER') {
      setRole(stored);
    }
  }, []);

  useEffect(() => {
    api.me(LUMEN_TENANT_ID)
      .then(setMe)
      .catch(() => setMe(null));
  }, [role]);

  const switchLocale = (locale: AppLocale) => {
    setLocale(locale);
    bump((n) => n + 1);
  };

  const switchRole = (next: 'OWNER' | 'MASTER') => {
    setRole(next);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('abm.role', next);
      localStorage.setItem('abm.tenantId', LUMEN_TENANT_ID);
      if (next === 'MASTER') {
        localStorage.setItem('abm.specialistId', '00000000-0000-4000-8000-000000000011');
      } else {
        localStorage.removeItem('abm.specialistId');
      }
    }
    bump((n) => n + 1);
  };

  const visibleNav = NAV.filter((item) => role === 'OWNER' || !item.ownerOnly);
  const activeItem = visibleNav.find((item) => path.startsWith(item.href));
  const activeLabelKey =
    role === 'MASTER' && activeItem?.masterLabelKey ? activeItem.masterLabelKey : activeItem?.key ?? 'nav.dashboard';
  const tenantName = me?.displayName ?? '…';
  const tz = me?.timezone ?? '—';
  const currency = me?.currencyCode ?? '—';
  const userLabel = role === 'MASTER' ? t('role.master') : t('role.owner');
  const initials =
    tenantName
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map((w) => w[0]?.toUpperCase())
      .join('') || '?';
  const railWidth = railExpanded ? RAIL_EXPANDED : RAIL_COLLAPSED;

  return (
    <View style={{ flex: 1, backgroundColor: color.canvas, flexDirection: desktop ? 'row' : 'column' }}>
      <View
        style={{
          width: desktop ? railWidth : undefined,
          backgroundColor: color.surface,
          borderColor: color.line,
          borderRightWidth: desktop ? 1 : 0,
          borderBottomWidth: desktop ? 0 : 1,
          flexDirection: desktop ? 'column' : 'row',
          flexWrap: desktop ? 'nowrap' : 'wrap',
          alignItems: desktop ? 'stretch' : 'center',
          padding: space.sm,
          gap: space.xs,
        }}
      >
        <Pressable
          onPress={() => setRailExpanded((v) => !v)}
          style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, padding: space.sm, marginBottom: space.sm }}
        >
          <View
            style={{
              width: 32,
              height: 32,
              borderRadius: radius.control,
              backgroundColor: color.primary,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <MaterialIcon name="monitor_heart" size={18} color="#FFFFFF" filled />
          </View>
          {railExpanded ? (
            <Text style={{ fontWeight: '700', fontSize: 15, color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
              {t('app.name')}
            </Text>
          ) : null}
        </Pressable>

        <View style={{ flex: desktop ? 1 : undefined, flexDirection: desktop ? 'column' : 'row', gap: 2 }}>
          {visibleNav.map((item) => {
            const active = path.startsWith(item.href);
            const labelKey = role === 'MASTER' && item.masterLabelKey ? item.masterLabelKey : item.key;
            return (
              <Link key={item.href} href={item.href as any} asChild>
                <Pressable
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    gap: space.sm,
                    paddingVertical: space.sm + 2,
                    paddingHorizontal: space.sm,
                    borderRadius: radius.control,
                    backgroundColor: active ? `${color.primary}14` : 'transparent',
                  }}
                >
                  <MaterialIcon name={item.icon} size={20} color={active ? color.primary : color.muted} filled={active} />
                  {railExpanded ? (
                    <Text style={{ color: active ? color.primary : color.muted, fontWeight: active ? '600' : '500', fontSize: 14 }}>
                      {t(labelKey)}
                    </Text>
                  ) : null}
                </Pressable>
              </Link>
            );
          })}
        </View>

        <View style={{ gap: 2, marginTop: desktop ? 'auto' : 0 }}>
          <Pressable
            onPress={toggle}
            style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingVertical: space.sm + 2, paddingHorizontal: space.sm, borderRadius: radius.control }}
          >
            <MaterialIcon name={theme === 'dark' ? 'light_mode' : 'dark_mode'} size={20} color={color.muted} />
            {railExpanded ? <Text style={{ color: color.muted, fontSize: 14, fontWeight: '500' }}>{theme === 'dark' ? t('theme.light') : t('theme.dark')}</Text> : null}
          </Pressable>
          <Pressable
            onPress={() => setSettingsOpen((v) => !v)}
            style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingVertical: space.sm + 2, paddingHorizontal: space.sm, borderRadius: radius.control }}
          >
            <MaterialIcon name="settings" size={20} color={color.muted} />
            {railExpanded ? <Text style={{ color: color.muted, fontSize: 14, fontWeight: '500' }}>{t('shell.debug')}</Text> : null}
          </Pressable>
          {settingsOpen && railExpanded ? (
            <View style={{ paddingHorizontal: space.sm, paddingTop: space.xs, gap: space.sm }}>
              <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
                {(['ka', 'uk', 'cs'] as AppLocale[]).map((loc) => (
                  <Pressable key={loc} onPress={() => switchLocale(loc)}>
                    <Text style={{ color: color.accent, fontWeight: '600', fontSize: 12 }}>{loc.toUpperCase()}</Text>
                  </Pressable>
                ))}
              </View>
              {role === 'OWNER' ? (
                <Pressable onPress={() => api.recalculate(LUMEN_TENANT_ID).catch(() => undefined).then(() => bump((n) => n + 1))}>
                  <Text style={{ color: color.muted, fontSize: 12 }}>{t('metric.recalculate')}</Text>
                </Pressable>
              ) : null}
            </View>
          ) : null}
        </View>
      </View>

      <View style={{ flex: 1 }}>
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'space-between',
            flexWrap: 'wrap',
            gap: space.md,
            paddingHorizontal: space.xl,
            paddingVertical: space.md,
            borderBottomWidth: 1,
            borderBottomColor: color.line,
            backgroundColor: color.surface,
          }}
        >
          <View>
            <Text style={{ fontSize: 20, fontWeight: '700', color: color.ink, fontFamily: 'Plus Jakarta Sans' }}>
              {t(activeLabelKey)}
            </Text>
            <Text style={{ fontSize: 12, color: color.muted }} numberOfLines={1}>
              {tenantName} · {tz} · {currency}
            </Text>
          </View>

          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm, flexWrap: 'wrap' }}>
            <TextInput
              value={search}
              onChangeText={setSearch}
              placeholder={t('shell.search')}
              placeholderTextColor={color.muted}
              style={{
                minWidth: 160,
                paddingVertical: 8,
                paddingHorizontal: space.md,
                borderRadius: radius.pill,
                borderWidth: 1,
                borderColor: color.line,
                color: color.ink,
                backgroundColor: color.canvas,
                fontSize: 13,
              }}
            />

            <View style={{ flexDirection: 'row', backgroundColor: color.canvas, borderWidth: 1, borderColor: color.line, borderRadius: radius.pill, padding: 3, gap: 2 }}>
              {(['OWNER', 'MASTER'] as const).map((r) => (
                <Pressable
                  key={r}
                  onPress={() => switchRole(r)}
                  style={{
                    paddingVertical: 5,
                    paddingHorizontal: space.md,
                    borderRadius: radius.pill,
                    backgroundColor: role === r ? color.primary : 'transparent',
                  }}
                >
                  <Text style={{ fontSize: 12, fontWeight: '700', color: role === r ? '#FFFFFF' : color.muted }}>
                    {r === 'OWNER' ? t('role.owner') : t('role.master')}
                  </Text>
                </Pressable>
              ))}
            </View>

            <View style={{ flexDirection: 'row', backgroundColor: color.canvas, borderWidth: 1, borderColor: color.line, borderRadius: radius.pill, padding: 3, gap: 2 }}>
              {(['en', 'ru'] as AppLocale[]).map((loc) => (
                <Pressable
                  key={loc}
                  onPress={() => switchLocale(loc)}
                  style={{
                    paddingVertical: 5,
                    paddingHorizontal: space.md,
                    borderRadius: radius.pill,
                    backgroundColor: currentLocale() === loc ? color.primary : 'transparent',
                  }}
                >
                  <Text style={{ fontSize: 12, fontWeight: '700', color: currentLocale() === loc ? '#FFFFFF' : color.muted }}>
                    {loc.toUpperCase()}
                  </Text>
                </Pressable>
              ))}
            </View>

            <View
              style={{
                width: 32,
                height: 32,
                borderRadius: radius.pill,
                backgroundColor: color.primary,
                alignItems: 'center',
                justifyContent: 'center',
              }}
              accessibilityLabel={userLabel}
            >
              <Text style={{ color: '#FFFFFF', fontSize: 12, fontWeight: '700' }}>{initials}</Text>
            </View>
          </View>
        </View>

        <View style={{ flex: 1, padding: space.xl }}>
          <Slot />
        </View>
      </View>
    </View>
  );
}
