import { ApiRequestError } from '../../shared/api/apiClient';
import { peopleImportFilePolicy } from '../../shared/files/peopleImportFilePolicy';

export function validatePeopleImportFile(file: File): void {
  const normalizedName = file.name.trim().toLowerCase();
  const extensionAllowed = peopleImportFilePolicy.allowedExtensions
    .some((extension) => normalizedName.endsWith(extension));
  const mediaTypeAllowed = file.type.length === 0
    || peopleImportFilePolicy.allowedTypes.includes(
      file.type as (typeof peopleImportFilePolicy.allowedTypes)[number],
    );
  const sizeAllowed = file.size > 0 && file.size <= peopleImportFilePolicy.maxSizeBytes;

  if (!extensionAllowed || !mediaTypeAllowed || !sizeAllowed) {
    throw new ApiRequestError(415, {
      code: 'UNSUPPORTED_PEOPLE_IMPORT_FILE',
      retryable: false,
    });
  }
}

export const peopleImportFileValidator = Object.freeze({
  validate: validatePeopleImportFile,
});
