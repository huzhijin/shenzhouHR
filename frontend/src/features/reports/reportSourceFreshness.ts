export type RealtimeAttendanceSource = 'DELI_CLOUD' | 'OA_ATTENDANCE';

const sourceVersionPrefixes: Readonly<Record<RealtimeAttendanceSource, string>> = {
  DELI_CLOUD: 'SOURCE.DELI_CLOUD:',
  OA_ATTENDANCE: 'SOURCE.OA_ATTENDANCE:',
};

/**
 * Extracts the committed cutoff carried by the backend's authenticated
 * source-version marker. ISO timestamps can contain colons, so the digest
 * delimiter is found from the end instead of splitting the marker.
 */
export function realtimeSourceCutoff(
  values: readonly string[],
  source: RealtimeAttendanceSource,
): string | undefined {
  const prefix = sourceVersionPrefixes[source];
  const cutoffs = values
    .filter((value) => value.startsWith(prefix))
    .map((version) => {
      const digestSeparator = version.lastIndexOf(':');
      if (digestSeparator <= prefix.length) return undefined;
      const digest = version.slice(digestSeparator + 1);
      if (!/^[a-f0-9]{64}$/i.test(digest)) return undefined;
      return version.slice(prefix.length, digestSeparator);
    })
    .filter((value): value is string => value !== undefined);
  if (cutoffs.length === 0) return undefined;
  if (cutoffs.every((value) => value === 'UNSYNCED')) return 'UNSYNCED';
  if (cutoffs.some((value) => value === 'UNSYNCED')) {
    return 'PARTIALLY_UNSYNCED';
  }
  const timestamped = cutoffs
    .map((value) => ({ value, timestamp: Date.parse(value) }))
    .filter((value) => Number.isFinite(value.timestamp));
  if (timestamped.length !== cutoffs.length) return 'PARTIALLY_UNSYNCED';
  return timestamped.reduce((oldest, current) => (
    current.timestamp < oldest.timestamp ? current : oldest
  )).value;
}
