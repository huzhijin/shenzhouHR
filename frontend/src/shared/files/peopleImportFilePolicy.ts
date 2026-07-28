export const peopleImportFilePolicy = Object.freeze({
  allowedExtensions: Object.freeze(['.xlsx'] as const),
  allowedTypes: Object.freeze([
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  ] as const),
  maxSizeBytes: 20 * 1024 * 1024,
});
