import { Button, Input, Modal } from 'antd';
import { useEffect, useRef, useState } from 'react';
import './customerReports.css';

import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';

export type DayPunch = {
  rawFactId: string;
  punchedAt: string | null;
  method: string;
  sourceLabel: string;
  viewLocationAvailable: boolean;
  reasonRequired?: boolean;
};

export type PunchLocationView = {
  rawFactId: string;
  punchedAt: string | null;
  method: string;
  locationText: string | null;
  mapPointAvailable: boolean;
  mapUnavailableReason: string | null;
  mapLongitude: number | null;
  mapLatitude: number | null;
  mapImageDataUrl?: string | null;
};

export function isMobilePunch(method: string | null | undefined): boolean {
  const value = (method ?? '').trim().toLowerCase();
  return value === 'gps' || value === 'out_work';
}

export function punchClockLabel(punchedAt: string | null): string {
  if (!punchedAt) return '—';
  const parsed = new Date(punchedAt);
  if (Number.isNaN(parsed.getTime())) return punchedAt;
  return parsed.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Shanghai',
  });
}

type PanelProps = {
  companyId?: string;
  employeeId?: string;
  businessDate?: string;
  canViewLocation?: boolean;
  selfService?: boolean;
};

export function DayPunchLocationPanel(props: PanelProps) {
  return <DayPunchLocationContent key={`${props.selfService}-${props.companyId}-${props.employeeId}-${props.businessDate}`} {...props} />;
}

function DayPunchLocationContent({
  companyId,
  employeeId,
  businessDate,
  canViewLocation = false,
  selfService = false,
}: PanelProps) {
  const [punches, setPunches] = useState<DayPunch[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [pendingPunch, setPendingPunch] = useState<DayPunch | null>(null);
  const [reason, setReason] = useState('');
  const [location, setLocation] = useState<PunchLocationView | null>(null);
  const [locationError, setLocationError] = useState<string | null>(null);

  const [locationLoading, setLocationLoading] = useState(false);
  const [mapFailed, setMapFailed] = useState(false);
  const requestVersion = useRef(0);
  useEffect(() => () => { requestVersion.current += 1; }, []);

  useEffect(() => {
    if (!businessDate || (!selfService && (!companyId || !employeeId))) {
      setPunches([]);
      return;
    }
    if (isDemoMode()) {
      setPunches(demoPunches(businessDate).map((punch) => ({ ...punch, reasonRequired: !selfService })));
      setError(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    void requestJson<{ punches: DayPunch[] }>(
      selfService
        ? `/api/v1/me/attendance/day-punches?businessDate=${encodeURIComponent(businessDate)}`
        : `/api/v1/attendance-report-queries/day-punches?companyId=${encodeURIComponent(companyId!)}&employeeId=${encodeURIComponent(employeeId!)}&businessDate=${encodeURIComponent(businessDate)}`,
    ).then((body) => {
      if (!cancelled) {
        setPunches(body.punches ?? []);
        setLoading(false);
      }
    }).catch((cause: unknown) => {
      if (!cancelled) {
        setPunches([]);
        setLoading(false);
        setError(cause instanceof Error ? cause.message : '无法加载当日打卡');
      }
    });
    return () => {
      cancelled = true;
    };
  }, [companyId, employeeId, businessDate, selfService]);

  const openLocation = async (punch: DayPunch, viewReason: string) => {
    if (locationLoading) return;
    const version = ++requestVersion.current;
    setLocationError(null);
    setMapFailed(false);
    if (isDemoMode()) {
      setLocation({
        rawFactId: punch.rawFactId,
        punchedAt: punch.punchedAt,
        method: punch.method,
        locationText: '辽宁省大连市甘井子区演示路1号',
        mapPointAvailable: false,
        mapUnavailableReason: 'UNKNOWN_SYSTEM',
        mapLongitude: null,
        mapLatitude: null,
      });
      setPendingPunch(null);
      return;
    }
    setLocationLoading(true);
    try {
      const query = viewReason.trim().length >= 2
        ? `?reason=${encodeURIComponent(viewReason.trim())}`
        : '';
      const view = await requestJson<PunchLocationView>(
        `/api/v1/checkins/${encodeURIComponent(punch.rawFactId)}/location${query}`,
      );
      if (version !== requestVersion.current) return;
      setLocation(view);
      setPendingPunch(null);
    } catch (cause: unknown) {
      if (version === requestVersion.current) {
        setLocationError(cause instanceof Error ? cause.message : '无法查看位置');
      }
    } finally {
      if (version === requestVersion.current) setLocationLoading(false);
    }
  };

  return (
    <section className="query-report__punches" aria-label="当日打卡">
      <h3>{businessDate} 当日打卡</h3>
      {!pendingPunch && locationError ? <p role="alert">{locationError}</p> : null}
      {locationLoading && !pendingPunch ? <p>正在加载位置…</p> : null}
      {loading ? <p>正在加载打卡…</p> : null}
      {error ? <p className="query-report__punch-error">{error}</p> : null}
      {punches.length === 0 && !loading && !error ? <p>当天没有原始打卡。</p> : null}
      {punches.length > 0 ? (
        <ul>
          {punches.map((punch) => (
            <li key={punch.rawFactId}>
              <span>{punchClockLabel(punch.punchedAt)}</span>
              <strong>{punch.sourceLabel}</strong>
              {isMobilePunch(punch.method) && punch.viewLocationAvailable
                && (selfService || canViewLocation || punch.reasonRequired === false) ? (
                <Button
                  size="small"
                  type="link"
                  disabled={locationLoading}
                  onClick={() => {
                    setReason('');
                    setLocationError(null);
                    if (punch.reasonRequired === false) {
                      void openLocation(punch, '');
                    } else {
                      setPendingPunch(punch);
                    }
                  }}
                >
                  查看位置
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      <Modal
        title="查看打卡位置"
        open={pendingPunch != null}
        onCancel={() => {
          requestVersion.current += 1;
          setPendingPunch(null);
          setLocationLoading(false);
        }}
        onOk={() => {
          if (pendingPunch) void openLocation(pendingPunch, reason);
        }}
        okText="查看"
        okButtonProps={{ disabled: reason.trim().length < 2 }}
        confirmLoading={locationLoading}
        destroyOnHidden
      >
        <p>请填写查看原因（至少两个字）。</p>
        <Input.TextArea
          rows={3}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={500}
        />
        {locationError ? <p className="query-report__punch-error">{locationError}</p> : null}
      </Modal>
      <Modal
        title="打卡位置"
        open={location != null}
        footer={null}
        onCancel={() => setLocation(null)}
        destroyOnHidden
      >
        {location ? (
          <div className="query-report__punch-map">
            <p>{punchClockLabel(location.punchedAt)} · {isMobilePunch(location.method) ? '手机' : '考勤机'}</p>
            <p>{location.locationText || '无地址文字'}</p>
            {location.mapPointAvailable
              && !mapFailed
              && location.mapImageDataUrl?.startsWith('data:image/png;base64,') ? (
              <img
                className="query-report__punch-point"
                alt="本次打卡位置单点地图"
                src={location.mapImageDataUrl}
                onError={() => setMapFailed(true)}
              />
            ) : (
              <p>地图暂不可用</p>
            )}
          </div>
        ) : null}
      </Modal>
    </section>
  );
}

function demoPunches(businessDate: string): DayPunch[] {
  return [
    {
      rawFactId: `demo-gps-${businessDate}`,
      punchedAt: `${businessDate}T00:32:00Z`,
      method: 'gps',
      sourceLabel: '手机',
      viewLocationAvailable: true,
    },
    {
      rawFactId: `demo-fp-${businessDate}`,
      punchedAt: `${businessDate}T09:41:00Z`,
      method: 'fp',
      sourceLabel: '考勤机',
      viewLocationAvailable: false,
    },
  ];
}
