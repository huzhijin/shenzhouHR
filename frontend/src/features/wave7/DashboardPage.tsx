import { PageHeader } from '../../shared/components/PagePrimitives';
import type { DashboardProjection } from './wave7Contracts';
import { wave7ProjectionGateway, type Wave7ProjectionGateway } from './wave7Gateway';
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
  return (
    <Wave7AsyncBoundary loader={gateway.loadDashboard} isEmpty={(value) => value.metrics.length === 0}>
      {(projection) => <DashboardView projection={projection} />}
    </Wave7AsyncBoundary>
  );
}

export function DashboardView({
  projection,
  onDrillDown,
}: {
  projection: DashboardProjection;
  onDrillDown?: (reference: string, projectionVersion: string) => void;
}) {
  return (
    <>
      <PageHeader title={projection.title} description="指标仅来自当前授权范围，不在浏览器扩大或重算。" />
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
