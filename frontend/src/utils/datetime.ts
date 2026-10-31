import { currentLocale } from '@/src/i18n';

/** Date and time for one instant: no seconds, the words of the current language. The AI cards use the same options. */
export const DATE_TIME_OPTIONS: Intl.DateTimeFormatOptions = {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
};

const checkedZones = new Map<string, string | undefined>();

/** The zone, or the browser's (undefined) when this Intl does not know it: a stored zone must not break the screen. */
function usableZone(timeZone: string | undefined): string | undefined {
  if (!timeZone) return undefined;
  if (!checkedZones.has(timeZone)) {
    try {
      new Intl.DateTimeFormat('en', { timeZone });
      checkedZones.set(timeZone, timeZone);
    } catch {
      console.warn(`Unknown time zone "${timeZone}": showing times in the browser's zone`);
      checkedZones.set(timeZone, undefined);
    }
  }
  return checkedZones.get(timeZone);
}

/** `timeZone` is the tenant's IANA zone; without it (profile not loaded yet) the browser's zone is used. */
export function formatDateTime(iso: string, timeZone: string | undefined): string {
  return new Date(iso).toLocaleString(currentLocale(), { ...DATE_TIME_OPTIONS, timeZone: usableZone(timeZone) });
}

export function formatTime(iso: string, timeZone: string | undefined): string {
  return new Date(iso).toLocaleTimeString(currentLocale(), { hour: '2-digit', minute: '2-digit', timeZone: usableZone(timeZone) });
}

export function formatDay(iso: string, timeZone: string | undefined): string {
  return new Date(iso).toLocaleDateString(currentLocale(), { weekday: 'short', day: 'numeric', month: 'short', timeZone: usableZone(timeZone) });
}

export function formatLongDay(iso: string, timeZone: string | undefined): string {
  return new Date(iso).toLocaleDateString(currentLocale(), { weekday: 'long', day: 'numeric', month: 'long', timeZone: usableZone(timeZone) });
}

/** `YYYY-MM-DD` of the instant in the zone: the key to group by day. */
export function dayKey(iso: string, timeZone: string | undefined): string {
  return new Date(iso).toLocaleDateString('en-CA', { timeZone: usableZone(timeZone) });
}

/** `YYYY-MM-DD` of today in the zone plus `days`. */
export function zoneDate(timeZone: string | undefined, days = 0): string {
  const [year, month, day] = dayKey(new Date().toISOString(), timeZone).split('-').map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

/** Milliseconds the zone is ahead of UTC at the instant. */
function zoneOffset(instant: number, timeZone: string | undefined): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
  }).formatToParts(instant);
  const part = (type: Intl.DateTimeFormatPartTypes): number => Number(parts.find((p) => p.type === type)?.value);
  return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second')) - Math.floor(instant / 1000) * 1000;
}

/** The instant the `YYYY-MM-DD` date starts in the zone, as ISO. */
export function startOfZoneDay(date: string, timeZone: string | undefined): string {
  const zone = usableZone(timeZone);
  const utc = Date.parse(`${date}T00:00:00Z`);
  const guess = utc - zoneOffset(utc, zone);
  return new Date(utc - zoneOffset(guess, zone)).toISOString();
}

/** The dates a client may pick a new slot from: tomorrow in the tenant's zone up to `days` ahead. */
export function slotWindow(days: number, timeZone: string | undefined): { from: string; to: string } {
  return { from: zoneDate(timeZone, 1), to: zoneDate(timeZone, days) };
}
