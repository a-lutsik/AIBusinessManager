const MINUTES_IN_DAY = 1440;

/** Minutes from midnight as HH:MM, in the tenant's local time; the end of the day is 24:00. */
export function minuteToTime(minute: number): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(Math.floor(minute / 60))}:${pad(minute % 60)}`;
}

/** The reverse of minuteToTime: "9:30" and "09:30" are 570, "24:00" is 1440; anything else is null. */
export function timeToMinute(text: string): number | null {
  const parts = /^(\d{1,2}):(\d{2})$/.exec(text.trim());
  if (!parts) return null;
  const minute = Number(parts[1]) * 60 + Number(parts[2]);
  return Number(parts[2]) < 60 && minute <= MINUTES_IN_DAY ? minute : null;
}

/** A real calendar date in the form YYYY-MM-DD. */
export function isIsoDate(text: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const date = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === text;
}

/** Today in the tenant's zone as YYYY-MM-DD; the browser's zone when the given one is not known here. */
export function todayInZone(zone?: string): string {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  } catch {
    return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  }
}
