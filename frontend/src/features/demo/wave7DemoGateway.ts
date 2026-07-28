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
  loadReportLegalEntities: (period) => Promise.resolve({
    period,
    legalEntities: [{
      legalEntityId: '30000000-0000-0000-0000-000000000001',
      name: '神州半导体',
    }],
  }),
  loadReport: () => Promise.resolve(createDemoReportProjection()),
};
