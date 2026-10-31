import { useState } from 'react';
import { Text, View } from 'react-native';
import { ColorField, DEFAULT_CATALOG_COLOR } from '@/components/catalog/ColorField';
import { FormButtons, FormMessages } from '@/components/catalog/FormMessages';
import { TextField } from '@/components/ui/TextField';
import { Toggle } from '@/components/ui/Toggle';
import { api, LUMEN_TENANT_ID, type SpecialistView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { catalogErrorText } from '@/src/utils/catalogErrors';

type Props = {
  /** Null creates a specialist. */
  specialist: SpecialistView | null;
  onSaved: (specialist: SpecialistView) => void;
  onCancel: () => void;
};

export function SpecialistForm({ specialist, onSaved, onCancel }: Props) {
  const { color, space, radius } = useThemeTokens();
  const [name, setName] = useState(specialist?.displayName ?? '');
  const [calendarColor, setCalendarColor] = useState(specialist?.calendarColor ?? DEFAULT_CATALOG_COLOR);
  const [active, setActive] = useState(specialist?.active ?? true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setError(null);
    setBusy(true);
    try {
      onSaved(
        await api.saveSpecialist(LUMEN_TENANT_ID, {
          id: specialist?.id ?? null,
          displayName: name,
          // Null keeps the stored sign-in link: it is the operator's, and this form may be older than it.
          keycloakUserId: null,
          calendarColor,
          active,
        })
      );
    } catch (e: unknown) {
      setError(catalogErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <View
      testID="specialist-form"
      style={{ backgroundColor: color.surface, padding: space.lg, borderRadius: radius.card, borderWidth: 1, borderColor: color.primary, flexBasis: '100%', maxWidth: 560 }}
    >
      <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, marginBottom: space.md }}>
        {specialist ? t('specialists.form.edit') : t('specialists.form.new')}
      </Text>
      <TextField label={t('field.name')} value={name} onChangeText={setName} />
      <ColorField value={calendarColor} onChange={setCalendarColor} />
      <Toggle label={t('field.active')} value={active} onChange={setActive} />
      {!active ? <Text style={{ color: color.muted, marginBottom: space.md }}>{t('specialists.inactiveWarning')}</Text> : null}
      <FormMessages error={error} />
      <FormButtons busy={busy} onSave={save} onCancel={onCancel} />
    </View>
  );
}
