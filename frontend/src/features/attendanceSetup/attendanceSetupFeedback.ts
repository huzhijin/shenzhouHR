import {
  apiResponseMetadata,
  ApiRequestError,
} from '../../shared/api/apiClient';
import i18n from '../../shared/i18n/i18n';

export interface AttendanceSetupNotice {
  kind: 'success' | 'error' | 'warning' | 'info';
  message: string;
  correlationId?: string;
  state?:
    | 'success'
    | 'replay'
    | '403'
    | '404'
    | '409'
    | 'stale'
    | 'idempotency-conflict'
    | 'validation'
    | 'error';
}

export function mutationSuccessNotice(
  response: unknown,
  message: string,
): AttendanceSetupNotice {
  if (apiResponseMetadata(response).idempotencyReplayed === true) {
    return {
      kind: 'success',
      message: `${message} ${i18n.t('attendanceSetup.exactReplay')}`,
      state: 'replay',
    };
  }
  return {
    kind: 'success',
    message,
    state: 'success',
  };
}

export function mutationFailureNotice(caught: unknown): AttendanceSetupNotice {
  if (!(caught instanceof ApiRequestError)) {
    return {
      kind: 'error',
      message: i18n.t('error.serviceUnavailable'),
      state: 'error',
    };
  }
  if (caught.status === 409 || caught.status === 412) {
    if (['STALE_VERSION', 'VERSION_CONFLICT'].includes(caught.code)
        || caught.status === 412) {
      return {
        kind: 'warning',
        message: i18n.t('attendanceSetup.stale'),
        correlationId: caught.correlationId,
        state: 'stale',
      };
    }
    if (caught.code.startsWith('IDEMPOTENCY_')) {
      return {
        kind: 'warning',
        message: i18n.t('attendanceSetup.idempotencyConflict'),
        correlationId: caught.correlationId,
        state: 'idempotency-conflict',
      };
    }
    return {
      kind: 'warning',
      message: i18n.t('attendanceSetup.conflict'),
      correlationId: caught.correlationId,
      state: '409',
    };
  }
  if (caught.status === 403) {
    return {
      kind: 'error',
      message: i18n.t('attendanceSetup.forbidden'),
      correlationId: caught.correlationId,
      state: '403',
    };
  }
  if (caught.status === 404) {
    return {
      kind: 'error',
      message: i18n.t('attendanceSetup.notAvailable'),
      correlationId: caught.correlationId,
      state: '404',
    };
  }
  if (caught.status === 422 || caught.fieldErrors?.length) {
    return {
      kind: 'warning',
      message: caught.fieldErrors?.map((issue) => issue.message).join('；')
        ?? i18n.t('attendanceSetup.validationFailed'),
      correlationId: caught.correlationId,
      state: 'validation',
    };
  }
  return {
    kind: 'error',
    message: caught.message,
    correlationId: caught.correlationId,
    state: 'error',
  };
}
