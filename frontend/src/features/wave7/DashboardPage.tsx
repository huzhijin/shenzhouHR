import { IconChartBar, IconPresentationAnalytics } from '@tabler/icons-react';
import { Button, Space } from 'antd';
import { useNavigate } from 'react-router-dom';

import { PageHeader } from '../../shared/components/PagePrimitives';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type { DashboardProjection } from './wave7Contracts';
import type { Wave7ProjectionGateway } from './wave7Gateway';
import {
  DashboardMetricGrid,
  FrozenHistoryNotice,
  ProjectionMetadata,
  Wave7AsyncBoundary,
} from './Wave7Common';

export function DashboardRoute({
  gateway = wave7ProjectionGateway,
}: {
  gateway?: Wave7ProjectionGateway;
}) {
  const navigate = useNavigate();
  return (
    <Wave7AsyncBoundary loader={gateway.loadDashboard} isEmpty={(value) => value.metrics.length === 0}>
      {(projection) => (
        <DashboardView
          projection={projection}
          onDrillDown={() => navigate('/attendance/reports')}
          onOpenScreen={() => navigate('/attendance/screen')}
          onOpenReports={() => navigate('/attendance/reports')}
        />
      )}
    </Wave7AsyncBoundary>
  );
}

export function DashboardView({
  projection,
  onDrillDown,
  onOpenScreen,
  onOpenReports,
}: {
  projection: DashboardProjection;
  onDrillDown?: (reference: string, projectionVersion: string) => void;
  onOpenScreen?: () => void;
  onOpenReports?: () => void;
}) {
  return (
    <>
      <PageHeader
        title={projection.title}
        description="指标仅来自当前授权范围，不在浏览器扩大或重算。"
        actions={(
          <Space wrap>
            <Button
              icon={<IconPresentationAnalytics aria-hidden="true" />}
              onClick={onOpenScreen}
            >
              打开考勤大屏
            </Button>
            <Button
              type="primary"
              icon={<IconChartBar aria-hidden="true" />}
              onClick={onOpenReports}
            >
              查看报表中心
            </Button>
          </Space>
        )}
      />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      <section className="content-surface" aria-labelledby="wave7-dashboard-heading">
        <h2 id="wave7-dashboard-heading">授权汇总</h2>
        <DashboardMetricGrid
          metrics={projection.metrics}
          projectionVersion={projection.metadata.projectionVersion}
          canDrillDown={projection.metadata.allowedActions.includes('DASHBOARD_DRILL_DOWN')}
          onDrillDown={onDrillDown}
        />
      </section>
    </>
  );
}

export default DashboardRoute;
