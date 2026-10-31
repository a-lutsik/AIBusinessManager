import { useState } from 'react';
import { Text, View } from 'react-native';
import { ColorField, DEFAULT_CATALOG_COLOR } from '@/components/catalog/ColorField';
import { FormButtons, FormMessages } from '@/components/catalog/FormMessages';
import { TextField } from '@/components/ui/TextField';
import { Toggle } from '@/components/ui/Toggle';
import { api, LUMEN_TENANT_ID, type ServiceView } from '@/src/api/client';
import { t } from '@/src/i18n';
import { useThemeTokens } from '@/src/theme/tokens';
import { CatalogInputError, catalogErrorText, requiredWholeNumber } from '@/src/utils/catalogErrors';
import { minorToInput, parseMoneyInput } from '@/src/utils/money';

type Props = {
  /** Null creates a service. */
  service: ServiceView | null;
  currency: string;
  onSaved: (service: ServiceView) => void;
  onCancel: () => void;
};

export function ServiceForm({ service, currency, onSaved, onCancel }: Props) {
  const { color, space, radius } = useThemeTokens();
  const [name, setName] = useState(service?.name ?? '');
  const [description, setDescription] = useState(service?.description ?? '');
  const [duration, setDuration] = useState(service ? String(service.durationMinutes) : '60');
  const [price, setPrice] = useState(service ? minorToInput(service.priceMinor, currency) : '');
  const [before, setBefore] = useState(String(service?.bufferBeforeMinutes ?? 0));
  const [after, setAfter] = useState(String(service?.bufferAfterMinutes ?? 0));
  const [serviceColor, setServiceColor] = useState(service?.color ?? DEFAULT_CATALOG_COLOR);
  const [active, setActive] = useState(service?.active ?? true);
  const [publicVisible, setPublicVisible] = useState(service?.publicVisible ?? true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setError(null);
    setBusy(true);
    try {
      const money = parseMoneyInput(price, currency);
      if (money.error) throw new CatalogInputError(money.error);
      onSaved(
        await api.saveService(LUMEN_TENANT_ID, {
          id: service?.id ?? null,
          name,
          description: description.trim() ? description : null,
          durationMinutes: requiredWholeNumber(duration),
          priceMinor: money.minor,
          bufferBeforeMinutes: requiredWholeNumber(before),
          bufferAfterMinutes: requiredWholeNumber(after),
          color: serviceColor,
          active,
          publicVisible,
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
      testID="service-form"
      style={{ backgroundColor: color.surface, padding: space.lg, borderRadius: radius.card, borderWidth: 1, borderColor: color.primary, flexBasis: '100%', maxWidth: 560 }}
    >
      <Text style={{ fontWeight: '700', fontSize: 16, color: color.ink, marginBottom: space.md }}>
        {service ? t('services.form.edit') : t('services.form.new')}
      </Text>
      <TextField label={t('field.name')} value={name} onChangeText={setName} />
      <TextField label={t('field.description')} value={description} onChangeText={setDescription} multiline />
      <TextField label={t('field.duration')} value={duration} onChangeText={setDuration} keyboardType="number-pad" />
      <TextField label={t('field.price', { currency })} value={price} onChangeText={setPrice} keyboardType="decimal-pad" />
      <TextField label={t('field.bufferBefore')} value={before} onChangeText={setBefore} keyboardType="number-pad" />
      <TextField label={t('field.bufferAfter')} value={after} onChangeText={setAfter} keyboardType="number-pad" />
      <ColorField value={serviceColor} onChange={setServiceColor} />
      <Toggle label={t('field.active')} value={active} onChange={setActive} />
      {!active ? <Text style={{ color: color.muted, marginBottom: space.md }}>{t('services.inactiveWarning')}</Text> : null}
      <Toggle label={t('field.public')} value={publicVisible} onChange={setPublicVisible} />
      <FormMessages error={error} />
      <FormButtons busy={busy} onSave={save} onCancel={onCancel} />
    </View>
  );
}
