import { expect, test } from '@playwright/test';
import { currencyFractionDigits, formatMoney, minorToInput, parseMoneyInput } from '../src/utils/money';
import { isIsoDate, minuteToTime, timeToMinute } from '../src/utils/schedule';

// Plain functions of the catalog forms; no browser and no stack is touched. GEL has two fraction digits, JPY none, KWD three.

test('a typed price becomes minor units in the fraction digits of the currency', () => {
  for (const [typed, currency, minor] of [
    ['80', 'GEL', 8000],
    ['80.5', 'GEL', 8050],
    ['80,50', 'GEL', 8050],
    ['80.', 'GEL', 8000],
    ['.5', 'GEL', 50],
    ['0', 'GEL', 0],
    ['1 000', 'GEL', 100000],
    ['0.07', 'GEL', 7],
    ['19.99', 'GEL', 1999],
    ['21474836.47', 'GEL', 2147483647],
    ['1500', 'JPY', 1500],
    ['1.234', 'KWD', 1234],
    ['1,234', 'KWD', 1234],
    ['2', 'KWD', 2000],
  ] as const) {
    expect(parseMoneyInput(typed, currency), `${typed} ${currency}`).toEqual({ minor });
  }
});

test('a price the currency cannot hold is refused, never rounded; zeros past its digits change nothing', () => {
  for (const [typed, currency] of [
    ['80.5555', 'GEL'],
    ['1.0051', 'GEL'],
    ['12.5', 'JPY'],
    ['1.2345', 'KWD'],
  ] as const) {
    expect(parseMoneyInput(typed, currency), `${typed} ${currency}`).toEqual({ error: 'PRICE_PRECISION' });
  }
  for (const [typed, currency, minor] of [
    ['80.5000', 'GEL', 8050],
    ['12.0', 'JPY', 12],
    ['1500.00', 'JPY', 1500],
    ['1.2340', 'KWD', 1234],
  ] as const) {
    expect(parseMoneyInput(typed, currency), `${typed} ${currency}`).toEqual({ minor });
  }
});

test('a space only groups thousands', () => {
  for (const [typed, minor] of [
    ['1 000', 100000],
    ['1 000 000', 100000000],
    ['1\u00A0000,50', 100050],
    [' 80 ', 8000],
  ] as const) {
    expect(parseMoneyInput(typed, 'GEL'), typed).toEqual({ minor });
  }
  for (const typed of ['80 50', '1 00', '1 0000', '1000 000', '80. 50']) {
    expect(parseMoneyInput(typed, 'GEL'), typed).toEqual({ error: 'PRICE_FORMAT' });
  }
});

test('an empty, negative or unreadable price is an error with its code', () => {
  for (const [typed, currency, error] of [
    ['', 'GEL', 'PRICE_REQUIRED'],
    ['   ', 'GEL', 'PRICE_REQUIRED'],
    ['-1', 'GEL', 'PRICE_NEGATIVE'],
    ['−1', 'GEL', 'PRICE_NEGATIVE'],
    ['abc', 'GEL', 'PRICE_FORMAT'],
    ['.', 'GEL', 'PRICE_FORMAT'],
    ['1.2.3', 'GEL', 'PRICE_FORMAT'],
    ['1,000', 'GEL', 'PRICE_FORMAT'],
    ['1,000', 'JPY', 'PRICE_FORMAT'],
    ['1.000', 'GEL', 'PRICE_FORMAT'],
    ['1.000', 'JPY', 'PRICE_FORMAT'],
    ['80.555', 'GEL', 'PRICE_FORMAT'],
    ['80 50', 'GEL', 'PRICE_FORMAT'],
    ['21474836.48', 'GEL', 'PRICE_TOO_LARGE'],
    ['99999999999', 'GEL', 'PRICE_TOO_LARGE'],
  ] as const) {
    expect(parseMoneyInput(typed, currency), `"${typed}" ${currency}`).toEqual({ error });
  }
});

test('the field text and the parser agree with formatMoney on the digits', () => {
  for (const [minor, currency, text] of [
    [8000, 'GEL', '80.00'],
    [5, 'GEL', '0.05'],
    [0, 'GEL', '0.00'],
    [1500, 'JPY', '1500'],
    [1234, 'KWD', '1.234'],
    [7, 'KWD', '0.007'],
  ] as const) {
    expect(minorToInput(minor, currency), `${minor} ${currency}`).toBe(text);
    expect(parseMoneyInput(text, currency), `${text} ${currency}`).toEqual({ minor });
    expect(formatMoney(minor, currency, 'en').replace(/[^\d.]/g, ''), `${minor} ${currency}`).toBe(text);
  }
});

test('an unknown currency code counts two digits, like the fallback of formatMoney', () => {
  expect(currencyFractionDigits('not a code')).toBe(2);
  expect(parseMoneyInput('1.5', 'not a code')).toEqual({ minor: 150 });
});

test('times are HH:MM of the local day and minutes from midnight', () => {
  for (const [text, minute] of [
    ['00:00', 0],
    ['09:30', 570],
    ['9:30', 570],
    ['23:59', 1439],
    ['24:00', 1440],
  ] as const) {
    expect(timeToMinute(text), text).toBe(minute);
  }
  for (const bad of ['', '9', '24:01', '25:00', '09:60', '9.30', 'ab:cd', '-1:00']) {
    expect(timeToMinute(bad), bad).toBeNull();
  }
  expect(minuteToTime(570)).toBe('09:30');
  expect(minuteToTime(1440)).toBe('24:00');
  expect(minuteToTime(0)).toBe('00:00');
});

test('only a real date in the form YYYY-MM-DD is a date', () => {
  expect(isIsoDate('2031-02-28')).toBe(true);
  for (const bad of ['2031-02-30', '31.12.2031', '2031-1-1', '', 'tomorrow']) {
    expect(isIsoDate(bad), bad).toBe(false);
  }
});
