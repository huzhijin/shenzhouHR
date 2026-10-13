import dayjs, { type Dayjs } from 'dayjs';

/**
 * 月初仍在看上月账。1–7 日默认上月，避免 9 月 1 日打开查询报表去查空的当月。
 */
export function defaultQueryPeriod(now: Dayjs = dayjs()): Dayjs {
  if (now.date() <= 7) {
    return now.subtract(1, 'month').startOf('month');
  }
  return now.startOf('month');
}

export function monthDateRange(period: Dayjs): [Dayjs, Dayjs] {
  return [period.startOf('month'), period.endOf('month')];
}

/** Official month-matrix already keys on `period`; a full-month range is redundant. */
export function isWholeCalendarMonth(
  period: string,
  fromDate?: string,
  toDate?: string,
): boolean {
  if (!fromDate && !toDate) {
    return true;
  }
  if (!fromDate || !toDate) {
    return false;
  }
  const [start, end] = monthDateRange(dayjs(period));
  return fromDate === start.format('YYYY-MM-DD')
    && toDate === end.format('YYYY-MM-DD');
}
