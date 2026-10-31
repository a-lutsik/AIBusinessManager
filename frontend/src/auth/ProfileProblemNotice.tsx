import { Text, View } from 'react-native';
import { Button } from '@/components/ui/Button';
import type { ProfileProblem } from '@/src/auth/ConsoleSession';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';

/** The API refuses this actor as such: there is no screen to show behind the reason. */
export function isSessionRefusal(problem: ProfileProblem | null): boolean {
  return problem?.code === 'SPECIALIST_INACTIVE' || problem?.code === 'SPECIALIST_UNMAPPED';
}

type Props = { problem: ProfileProblem; onRetry: () => void; onSignOut?: () => void };

/** Why the profile is not there, by code, and what can be done from here. */
export function ProfileProblemNotice({ problem, onRetry, onSignOut }: Props) {
  const { color, space, radius } = useThemeTokens();
  const text = isSessionRefusal(problem) ? t(`catalog.error.${problem.code}`) : t('profile.error.generic');
  return (
    <View
      testID="profile-problem"
      style={{ backgroundColor: color.mist, borderColor: color.line, borderWidth: 1, borderRadius: radius.card, padding: space.lg, marginBottom: space.lg }}
    >
      <Text accessibilityRole="alert" style={{ color: color.ink, fontWeight: '600', marginBottom: space.md }}>
        {problem.code ? `${text} (${problem.code})` : text}
      </Text>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
        <Button label={t('action.retry')} variant="ghost" onPress={onRetry} />
        {onSignOut && isSessionRefusal(problem) ? <Button label={t('auth.signOut')} variant="ghost" onPress={onSignOut} /> : null}
      </View>
    </View>
  );
}
