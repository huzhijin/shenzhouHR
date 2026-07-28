import { ApiRequestError } from '../../shared/api/apiClient';
import type {
  AttendanceRecordsProjection,
  DashboardProjection,
  FeedbackProjection,
  LeaveProjection,
  ReportProjection,
  TodayProjection,
} from './wave7Contracts';

export interface Wave7ProjectionGateway {
  loadToday(): Promise<TodayProjection>;
  loadRecords(): Promise<AttendanceRecordsProjection>;
  loadLeave(): Promise<LeaveProjection>;
  loadFeedback(): Promise<FeedbackProjection>;
  loadDashboard(): Promise<DashboardProjection>;
  loadReport(): Promise<ReportProjection>;
}

const upstreamPending = (): Promise<never> => Promise.reject(new ApiRequestError(503, {
  code: 'WAVE7_UPSTREAM_PENDING',
  message: '上游考勤与工时投影接口尚未就绪。',
  retryable: false,
}));

export const wave7ProjectionGateway: Wave7ProjectionGateway = {
  loadToday: upstreamPending,
  loadRecords: upstreamPending,
  loadLeave: upstreamPending,
  loadFeedback: upstreamPending,
  loadDashboard: upstreamPending,
  loadReport: upstreamPending,
};
