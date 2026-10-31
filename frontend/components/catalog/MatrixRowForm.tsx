import { useState } from 'react';
import { Text, View } from 'react-native';
import { FormButtons, FormMessages } from '@/components/catalog/FormMessages';
import { TextField } from '@/components/ui/TextField';
import { Toggle } from '@/components/ui/Toggle';
import { api, LUMEN_TENANT_ID, type MatrixRowView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { CatalogInputError, catalogErrorText, optionalWholeNumber } from '@/src/utils/catalogErrors';
import { minorToInput, parseMoneyInput } from '@/src/utils/money';

type Props = {
  specialistId: string;
  row: MatrixRowView;
  name: string;
  currency: string;
  onSaved: () => void;
  onCancel: () => void;
};

const numberText = (value: number | null | undefined): string => (value == null ? '' : String(value));

/** An empty field is no override: the service default applies. */
function priceOverride(text: string, currency: string): number | null {
  if (!text.trim()) return null;
  const money = parseMoneyInput(text, currency);
  if (money.error) throw new CatalogInputError(money.error);
  return money.minor;
}

export function MatrixRowForm({ specialistId, row, name, currency, onSaved, onCancel }: Props) {
  const { color, space, radius } = useThemeTokens();
  const [offered, setOffered] = useState(!!row.offered);
  const [duration, setDuration] = useState(numberText(row.durationMinutesOverride));
  const [price, setPrice] = useState(row.priceMinorOverride == null ? '' : minorToInput(row.priceMinorOverride, currency));
  const [before, setBefore] = useState(numberText(row.bufferBeforeMinutesOverride));
  const [after, setAfter] = useState(numberText(row.bufferAfterMinutesOverride));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setError(null);
    setBusy(true);
    try {
      await api.saveMatrixRow(LUMEN_TENANT_ID, specialistId, {
        serviceId: row.serviceId,
        offered,
        durationMinutesOverride: optionalWholeNumber(duration),
        priceMinorOverride: priceOverride(price, currency),
        bufferBeforeMinutesOverride: optionalWholeNumber(before),
        bufferAfterMinutesOverride: optionalWholeNumber(after),
      });
      onSaved();
    } catch (e: unknown) {
      setError(catalogErrorText(e));
    } finally {
      setBusy(false);
    }
  };

  const defaultOf = (value: number | null | undefined) => (value == null ? undefined : String(value));

  return (
    <View
      testID="matrix-form"
      style={{ backgroundColor: color.surface, padding: space.lg, borderRadius: radius.card, borderWidth: 1, borderColor: color.primary, marginTop: space.md, maxWidth: 560 }}
    >
      <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, marginBottom: space.xs }}>{name}</Text>
      <Text style={{ color: color.muted, marginBottom: space.md }}>{t('matrix.form.hint')}</Text>
      <Toggle label={t('matrix.form.offered')} value={offered} onChange={setOffered} />
      <TextField label={t('field.duration')} value={duration} onChangeText={setDuration} placeholder={defaultOf(row.defaultDurationMinutes)} keyboardType="number-pad" />
      <TextField
        label={t('field.price', { currency })}
        value={price}
        onChangeText={setPrice}
        placeholder={row.defaultPriceMinor == null ? undefined : minorToInput(row.defaultPriceMinor, currency)}
        keyboardType="decimal-pad"
      />
      <TextField label={t('field.bufferBefore')} value={before} onChangeText={setBefore} placeholder={defaultOf(row.defaultBufferBeforeMinutes)} keyboardType="number-pad" />
      <TextField label={t('field.bufferAfter')} value={after} onChangeText={setAfter} placeholder={defaultOf(row.defaultBufferAfterMinutes)} keyboardType="number-pad" />
      <FormMessages error={error} />
      <FormButtons busy={busy} onSave={save} onCancel={onCancel} />
    </View>
  );
}
