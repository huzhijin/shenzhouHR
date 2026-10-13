import { OperationFeedback } from '../../shared/components/FeedbackComponents';
import type { AttendanceSetupNotice as Notice } from './attendanceSetupFeedback';

export function AttendanceSetupNotice({ notice }: { notice?: Notice }) {
  if (!notice) return null;
  return (
    <div
      className="attendance-notice"
      data-state={notice.state ?? notice.kind}
      aria-live={notice.kind === 'error' ? 'assertive' : 'polite'}
    >
      <OperationFeedback kind={notice.kind} message={notice.message} />
      {/* notice.correlationId is diagnostic metadata and is intentionally omitted from business UI. */}
    </div>
  );
}
