import { Link, Slot, usePathname } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { MaterialIcon } from '@/components/ui/MaterialIcon';
import { api, LUMEN_TENANT_ID, type MeView } from '@/src/api/client';
import { setLocale, t, type AppLocale } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';
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

export default function ConsoleLayout() {
  const path = usePathname();
  const { width } = useWindowDimensions();
  const desktop = width >= 768;
  const [, bump] = useState(0);
  const [role, setRole] = useState<'OWNER' | 'MASTER'>('OWNER');
  const [me, setMe] = useState<MeView | null>(null);
  const [debugOpen, setDebugOpen] = useState(false);
  const { theme, toggle } = useTheme();

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
  const tenantName = me?.displayName ?? '…';
  const tz = me?.timezone ?? '—';
  const currency = me?.currencyCode ?? '—';
  const userLabel = role === 'MASTER' ? t('role.master') : t('role.owner');

  return (
    <View style={[styles.root, desktop ? styles.row : null]}>
      <View style={[styles.nav, desktop ? styles.side : styles.bottom]}>
        <Text style={styles.brand}>{t('app.name')}</Text>
        {visibleNav.map((item) => {
          const active = path.startsWith(item.href);
          const labelKey = role === 'MASTER' && item.masterLabelKey ? item.masterLabelKey : item.key;
          return (
            <Link key={item.href} href={item.href as any} asChild>
              <Pressable style={StyleSheet.flatten([styles.link, active ? styles.active : null])}>
                <MaterialIcon name={item.icon} size={20} color={active ? tokens.color.primary : tokens.color.muted} filled={active} />
                <Text style={[styles.linkText, active ? styles.linkTextActive : null]}>{t(labelKey)}</Text>
              </Pressable>
            </Link>
          );
        })}

        <View style={styles.debugBlock}>
          <Pressable onPress={() => setDebugOpen((v) => !v)} style={styles.debugToggle}>
            <MaterialIcon name="settings" size={18} color={tokens.color.muted} />
            <Text style={styles.debugToggleText}>{t('shell.debug')}</Text>
          </Pressable>
          {debugOpen ? (
            <View style={styles.debugPanel}>
              <View style={styles.chipRow}>
                {(['en', 'ru', 'ka', 'uk', 'cs'] as AppLocale[]).map((loc) => (
                  <Pressable key={loc} onPress={() => switchLocale(loc)}>
                    <Text style={styles.chip}>{loc.toUpperCase()}</Text>
                  </Pressable>
                ))}
              </View>
              <View style={styles.chipRow}>
                <Pressable onPress={() => switchRole(role === 'OWNER' ? 'MASTER' : 'OWNER')}>
                  <Text style={styles.chip}>{role === 'OWNER' ? t('role.owner') : t('role.master')}</Text>
                </Pressable>
                <Pressable onPress={toggle}>
                  <Text style={styles.chip}>{theme === 'dark' ? t('theme.light') : t('theme.dark')}</Text>
                </Pressable>
              </View>
              {role === 'OWNER' ? (
                <Pressable
                  onPress={() =>
                    api.recalculate(LUMEN_TENANT_ID).catch(() => undefined).then(() => bump((n) => n + 1))
                  }
                >
                  <Text style={styles.chipMuted}>{t('metric.recalculate')}</Text>
                </Pressable>
              ) : null}
            </View>
          ) : null}
        </View>
      </View>

      <View style={styles.mainCol}>
        <View style={styles.topBar}>
          <Text style={styles.topMeta} numberOfLines={1}>
            {tenantName}
            <Text style={styles.topSep}> · </Text>
            {tz}
            <Text style={styles.topSep}> · </Text>
            {currency}
          </Text>
          <Text style={styles.topUser}>{userLabel}</Text>
        </View>
        <View style={styles.main}>
          <Slot />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.color.canvas },
  row: { flexDirection: 'row' },
  nav: {
    backgroundColor: tokens.color.surface,
    padding: tokens.space.lg,
    borderColor: tokens.color.line,
    borderRightWidth: 1,
  },
  side: {
    width: 268,
    minWidth: tokens.rail.min,
    maxWidth: tokens.rail.max,
    flexDirection: 'column',
  },
  bottom: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.sm },
  brand: {
    fontWeight: '700',
    fontSize: 17,
    color: tokens.color.ink,
    marginBottom: tokens.space.xl,
    fontFamily: 'Plus Jakarta Sans',
  },
  link: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: tokens.space.sm,
    paddingVertical: tokens.space.sm + 2,
    paddingHorizontal: tokens.space.sm,
    borderRadius: tokens.radius.control,
    marginBottom: 2,
  },
  active: { backgroundColor: `${tokens.color.primary}14` },
  linkText: { color: tokens.color.muted, fontWeight: '500', fontSize: 14 },
  linkTextActive: { color: tokens.color.primary, fontWeight: '600' },
  mainCol: { flex: 1 },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: tokens.space.xl,
    paddingVertical: tokens.space.md,
    borderBottomWidth: 1,
    borderBottomColor: tokens.color.line,
    backgroundColor: tokens.color.surface,
    gap: tokens.space.md,
  },
  topMeta: { color: tokens.color.ink, fontWeight: '600', flexShrink: 1, fontFamily: 'Plus Jakarta Sans' },
  topSep: { color: tokens.color.muted, fontWeight: '400' },
  topUser: { color: tokens.color.muted, fontSize: 13, fontWeight: '500' },
  main: { flex: 1, padding: tokens.space.xl },
  debugBlock: { marginTop: 'auto', paddingTop: tokens.space.xl },
  debugToggle: { flexDirection: 'row', alignItems: 'center', gap: tokens.space.sm },
  debugToggleText: { color: tokens.color.muted, fontSize: 12, fontWeight: '600' },
  debugPanel: { marginTop: tokens.space.sm, gap: tokens.space.sm },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: tokens.space.sm },
  chip: { color: tokens.color.accent, fontWeight: '600', fontSize: 12 },
  chipMuted: { color: tokens.color.muted, fontSize: 12, marginTop: tokens.space.sm },
});
