import { Link, Slot, usePathname } from 'expo-router';
import { useState } from 'react';
import { Pressable, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { MaterialIcon } from '@/components/ui/MaterialIcon';
import { api, LUMEN_TENANT_ID } from '@/src/api/client';
import { useAuth } from '@/src/auth/AuthContext';
import { ConsoleSessionProvider, useConsoleSession } from '@/src/auth/ConsoleSession';
import { AuthLoading, SignInScreen } from '@/src/auth/SignInScreen';
import { setLocale, t, useLocale, type AppLocale } from '@/src/i18n';
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
  const { mode, status } = useAuth();
  if (mode === 'oidc') {
    if (status === 'loading') return <AuthLoading />;
    if (status === 'unauthenticated') return <SignInScreen />;
  }
  return (
    <ConsoleSessionProvider>
      <ConsoleShell />
    </ConsoleSessionProvider>
  );
}

function ConsoleShell() {
  const { mode, signOut } = useAuth();
  const { me, role: sessionRole, switchRole } = useConsoleSession();
  const role = sessionRole ?? (mode === 'dev-headers' ? 'OWNER' : null);
  const path = usePathname();
  const { width } = useWindowDimensions();
  const desktop = width >= 768;
  const [, bump] = useState(0);
  const activeLocale = useLocale();
  const [railExpanded, setRailExpanded] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [search, setSearch] = useState('');
  const { theme, toggle } = useTheme();
  const { color, space, radius } = useThemeTokens();

  const switchLocale = (locale: AppLocale) => setLocale(locale);

  const visibleNav = NAV.filter((item) => role === 'OWNER' || !item.ownerOnly);
  const activeItem = visibleNav.find((item) => path.startsWith(item.href));
  const activeLabelKey =
    role === 'MASTER' && activeItem?.masterLabelKey ? activeItem.masterLabelKey : activeItem?.key ?? 'nav.dashboard';
  const tenantName = me?.displayName ?? '…';
  const tz = me?.timezone ?? '—';
  const currency = me?.currencyCode ?? '—';
  const userLabel = role === 'MASTER' ? t('role.master') : role === 'OWNER' ? t('role.owner') : '';
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

            {mode === 'dev-headers' ? (
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
            ) : null}

            <View style={{ flexDirection: 'row', backgroundColor: color.canvas, borderWidth: 1, borderColor: color.line, borderRadius: radius.pill, padding: 3, gap: 2 }}>
              {(['en', 'ru'] as AppLocale[]).map((loc) => (
                <Pressable
                  key={loc}
                  onPress={() => switchLocale(loc)}
                  style={{
                    paddingVertical: 5,
                    paddingHorizontal: space.md,
                    borderRadius: radius.pill,
                    backgroundColor: activeLocale === loc ? color.primary : 'transparent',
                  }}
                >
                  <Text style={{ fontSize: 12, fontWeight: '700', color: activeLocale === loc ? '#FFFFFF' : color.muted }}>
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

            {mode === 'oidc' ? (
              <Pressable
                accessibilityRole="button"
                onPress={() => {
                  signOut();
                }}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: space.xs,
                  paddingVertical: 6,
                  paddingHorizontal: space.md,
                  borderRadius: radius.pill,
                  borderWidth: 1,
                  borderColor: color.line,
                }}
              >
                <MaterialIcon name="logout" size={16} color={color.muted} />
                <Text style={{ fontSize: 12, fontWeight: '700', color: color.muted }}>{t('auth.signOut')}</Text>
              </Pressable>
            ) : null}
          </View>
        </View>

        <View style={{ flex: 1, padding: space.xl }}>
          <Slot />
        </View>
      </View>
    </View>
  );
}
