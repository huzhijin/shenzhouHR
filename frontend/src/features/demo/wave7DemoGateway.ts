import type { Wave7ProjectionGateway } from '../wave7/wave7Gateway';
import {
  createDemoAttendanceRecordsProjection,
  createDemoDashboardProjection,
  createDemoFeedbackProjection,
  createDemoLeaveProjection,
  createDemoReportProjection,
  createDemoTodayProjection,
} from './wave7Demo';

export const demoWave7ProjectionGateway: Wave7ProjectionGateway = {
  loadToday: () => Promise.resolve(createDemoTodayProjection()),
  loadRecords: () => Promise.resolve(createDemoAttendanceRecordsProjection()),
  loadLeave: () => Promise.resolve(createDemoLeaveProjection()),
  loadFeedback: () => Promise.resolve(createDemoFeedbackProjection()),
  loadDashboard: () => Promise.resolve(createDemoDashboardProjection()),
  loadReport: () => Promise.resolve(createDemoReportProjection()),
};
