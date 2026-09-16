import { Link, Slot, usePathname } from 'expo-router';
import { Image, Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { setLocale, t, type AppLocale } from '@/src/i18n';
import { tokens } from '@/src/theme/tokens';
import { useTheme } from '@/src/theme/ThemeContext';
import { LUMEN_TENANT_ID } from '@/src/api/client';
import { useState } from 'react';

const NAV = [
  { href: '/dashboard', key: 'nav.dashboard', ownerOnly: false },
  { href: '/calendar', key: 'nav.calendar', ownerOnly: false },
  { href: '/clients', key: 'nav.clients', ownerOnly: false },
  { href: '/services', key: 'nav.services', ownerOnly: true },
  { href: '/specialists', key: 'nav.specialists', ownerOnly: true },
  { href: '/rules', key: 'nav.rules', ownerOnly: true },
  { href: '/ai', key: 'nav.ai', ownerOnly: true },
  { href: '/book/lumen-studio', key: 'nav.book', ownerOnly: false },
] as const;

export default function ConsoleLayout() {
  const path = usePathname();
  const { width } = useWindowDimensions();
  const desktop = width >= 768;
  const [, bump] = useState(0);
  const [role, setRole] = useState<'OWNER' | 'MASTER'>('OWNER');
  const { theme, toggle } = useTheme();

  const switchLocale = (locale: AppLocale) => {
    setLocale(locale);
    bump((n) => n + 1);
  };

  const switchRole = (next: 'OWNER' | 'MASTER') => {
    setRole(next);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('cadence.role', next);
      localStorage.setItem('cadence.tenantId', LUMEN_TENANT_ID);
      if (next === 'MASTER') {
        localStorage.setItem('cadence.specialistId', '00000000-0000-4000-8000-000000000011');
      } else {
        localStorage.removeItem('cadence.specialistId');
      }
    }
    bump((n) => n + 1);
  };

  return (
    <View style={StyleSheet.flatten([styles.root, desktop ? styles.row : null])}>
      <View style={StyleSheet.flatten([styles.nav, desktop ? styles.side : styles.bottom])}>
        <View style={styles.brandRow}>
          <Image source={require('../../assets/images/android-icon-foreground.png')} style={styles.brandMark} />
          <Text style={styles.brand}>{t('app.name')}</Text>
        </View>
        {NAV.filter((item) => role === 'OWNER' || !item.ownerOnly).map((item) => (
          <Link key={item.href} href={item.href as any} asChild>
            <Pressable style={StyleSheet.flatten([styles.link, path.startsWith(item.href) ? styles.active : null])}>
              <Text style={styles.linkText}>{t(item.key)}</Text>
            </Pressable>
          </Link>
        ))}
        <View style={styles.rowGap}>
          <Pressable onPress={() => switchLocale('en')}><Text style={styles.chip}>EN</Text></Pressable>
          <Pressable onPress={() => switchLocale('ru')}><Text style={styles.chip}>RU</Text></Pressable>
          <Pressable onPress={() => switchLocale('ka')}><Text style={styles.chip}>KA</Text></Pressable>
          <Pressable onPress={() => switchLocale('uk')}><Text style={styles.chip}>UK</Text></Pressable>
          <Pressable onPress={() => switchLocale('cs')}><Text style={styles.chip}>CS</Text></Pressable>
          <Pressable onPress={() => switchRole(role === 'OWNER' ? 'MASTER' : 'OWNER')}>
            <Text style={styles.chip}>{role === 'OWNER' ? t('role.owner') : t('role.master')}</Text>
          </Pressable>
          <Pressable onPress={toggle}>
            <Text style={styles.chip}>{theme === 'dark' ? t('theme.light') : t('theme.dark')}</Text>
          </Pressable>
        </View>
      </View>
      <View style={styles.main}>
        <Slot />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: tokens.color.canvas },
  row: { flexDirection: 'row' },
  nav: { backgroundColor: tokens.color.surface, padding: tokens.space.md, borderColor: tokens.color.line, borderRightWidth: 1 },
  side: { width: 220 },
  bottom: { flexDirection: 'row', flexWrap: 'wrap' },
  brandRow: { flexDirection: 'row', alignItems: 'center', gap: tokens.space.sm, marginBottom: tokens.space.md },
  brandMark: { width: 28, height: 28 },
  brand: { fontWeight: '700', fontSize: 16, color: tokens.color.ink },
  link: { paddingVertical: tokens.space.sm },
  active: { borderBottomWidth: 2, borderBottomColor: tokens.color.accent },
  linkText: { color: tokens.color.ink },
  main: { flex: 1, padding: tokens.space.lg },
  rowGap: { flexDirection: 'row', gap: tokens.space.sm, marginTop: tokens.space.md, flexWrap: 'wrap' },
  chip: { color: tokens.color.accent, fontWeight: '600' },
});
