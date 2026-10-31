import { Text, View } from 'react-native';
import { Toggle } from '@/components/ui/Toggle';
import type { AiSettingsView } from '@/src/api/client';
import { currentLocale, t } from '@/src/i18n';
import { DISPLAY_FONT, useThemeTokens } from '@/src/theme/tokens';

/** Start of the next budget period as a calendar date in the tenant's zone. */
function resetDate(settings: AiSettingsView, timeZone: string | undefined): string {
  return new Date(settings.periodResetsAt).toLocaleDateString(currentLocale(), {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone,
  });
}

type PanelProps = {
  settings: AiSettingsView;
  saving: boolean;
  timeZone: string | undefined;
  onNotesChange: (value: boolean) => void;
};

/** Owner's AI settings: whether masked client notes may reach the model, and this month's token usage. */
export function AiSettingsPanel({ settings, saving, timeZone, onNotesChange }: PanelProps) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View
      style={{
        marginBottom: space.md,
        padding: space.md,
        borderRadius: radius.card,
        borderWidth: 1,
        borderColor: color.line,
        backgroundColor: color.surface,
      }}
    >
      <Text style={{ fontWeight: '600', color: color.ink, marginBottom: space.sm, fontFamily: DISPLAY_FONT }}>
        {t('ai.settings.title')}
      </Text>
      <Text style={{ color: color.muted, fontSize: 13, marginBottom: space.md }}>
        {t('ai.settings.budget', {
          used: settings.monthTokensUsed,
          limit: settings.monthlyTokenBudget,
          date: resetDate(settings, timeZone),
        })}
      </Text>
      <Toggle label={t('ai.settings.notesToLlm')} value={settings.notesToLlm} onChange={onNotesChange} disabled={saving} />
      <Text style={{ color: settings.notesToLlm ? color.status.pending : color.muted, fontSize: 13, lineHeight: 18, marginTop: -space.sm }}>
        {t('ai.settings.notesWarning')}
      </Text>
    </View>
  );
}

/** Shown after the chat answered AI_BUDGET_EXCEEDED; without the settings it falls back to a message with no figures. */
export function BudgetBanner({ settings, timeZone }: { settings: AiSettingsView | null; timeZone: string | undefined }) {
  const { color, space, radius } = useThemeTokens();
  return (
    <View
      accessibilityRole="alert"
      style={{
        marginBottom: space.md,
        padding: space.md,
        borderRadius: radius.control,
        borderWidth: 1,
        borderColor: color.status.pending,
        backgroundColor: `${color.status.pending}18`,
      }}
    >
      <Text style={{ color: color.ink, lineHeight: 20 }}>
        {settings
          ? t('ai.budget.exceeded', {
              used: settings.monthTokensUsed,
              limit: settings.monthlyTokenBudget,
              date: resetDate(settings, timeZone),
            })
          : t('ai.budget.exceededNoData')}
      </Text>
    </View>
  );
}
