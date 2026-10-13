import { Alert, Button, DatePicker, Input, Select, TimePicker, Upload, message } from 'antd';
import type { UploadFile } from 'antd/es/upload/interface';
import dayjs, { type Dayjs } from 'dayjs';
import { useEffect, useMemo, useRef, useState } from 'react';

import { requestJson } from '../../shared/api/apiClient';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { pickPreferredCompany } from '../../shared/preferredCompany';
import { loadCustomerReportScopes } from '../reports/customerReportApi';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import './paperOvertime.css';

interface Candidate {
  employeeId: string;
  employeeNumber: string;
  displayName: string;
  departmentName: string;
  employmentStatus: string;
}

interface DraftLine {
  key: string;
  employeeId?: string;
  employeeNumber?: string;
  departmentName?: string;
  ocrName?: string;
  ocrDepartment?: string;
  overtimeDate?: string;
  start?: string;
  end?: string;
  overtimeType?: string;
  reason?: string;
  candidates: Candidate[];
}

interface RecognizeResult {
  batchId: string;
  lines: Array<Omit<DraftLine, 'key'> & { lineId: string }>;
}

export default function PaperOvertimePage() {
  const scopes = useAsyncResource(
    () => loadCustomerReportScopes(dayjs().format('YYYY-MM')),
    () => false,
    [],
  );
  const companies = scopes.resource.status === 'ready' ? scopes.resource.data : [];
  const [companyId, setCompanyId] = useState('');
  useEffect(() => {
    if (companies.length === 0 || companyId) return;
    const preferred = pickPreferredCompany(
      companies.map((row) => ({
        companyId: row.reference,
        companyName: row.label,
      })),
      (row) => row.companyName,
      (row) => row.companyId,
    );
    setCompanyId(preferred?.companyId ?? companies[0]?.reference ?? '');
  }, [companies, companyId]);
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [batchId, setBatchId] = useState<string>();
  const [lines, setLines] = useState<DraftLine[]>([emptyLine()]);
  const [saving, setSaving] = useState(false);
  const [recognizing, setRecognizing] = useState(false);
  const [ocrNotice, setOcrNotice] = useState<string>();
  const [messageApi, messageContextHolder] = message.useMessage();
  const searchTimers = useRef<Record<string, number>>({});

  const canSave = useMemo(
    () => lines.some((line) => line.employeeId && line.overtimeDate && line.start && line.end && line.overtimeType),
    [lines],
  );

  const recognize = async () => {
    if (!companyId) {
      void messageApi.error('请先选择公司，再识别。');
      setOcrNotice('请先选择公司，再识别。');
      return;
    }
    if (files.length === 0) {
      void messageApi.error('请先上传加班单照片。');
      setOcrNotice('请先上传加班单照片。');
      return;
    }
    setRecognizing(true);
    setOcrNotice(undefined);
    try {
      const form = new FormData();
      files.forEach((file) => {
        const blob = uploadBlob(file);
        if (blob) form.append('files', blob, file.name || 'upload.jpg');
      });
      if (![...form.keys()].includes('files')) {
        const notice = '没有读到上传文件，请重新选择照片后再识别。';
        void messageApi.error(notice);
        setOcrNotice(notice);
        return;
      }
      const result = await requestJson<RecognizeResult>(
        `/api/v1/paper-overtime/recognize?companyId=${encodeURIComponent(companyId)}`,
        { method: 'POST', body: form },
      );
      setBatchId(result.batchId);
      const next = (result.lines ?? []).map((line) => toDraftLine(line));
      setLines(next.length > 0 ? next : [emptyLine()]);
      const filled = next.filter(lineHasFields);
      if (filled.length === 0) {
        const notice = '没有识别出姓名、日期或时间，请在下方手填后保存。';
        void messageApi.warning(notice);
        setOcrNotice(notice);
      } else {
        const notice = `已识别 ${filled.length} 行，请核对人员后保存。未匹配到花名册的请搜索工号。`;
        void messageApi.success(notice);
        setOcrNotice(notice);
      }
    } catch (error: unknown) {
      const notice = error instanceof Error ? error.message : '识别失败';
      void messageApi.error(notice);
      setOcrNotice(notice);
    } finally {
      setRecognizing(false);
    }
  };

  const save = async () => {
    if (!companyId) return;
    setSaving(true);
    try {
      const saved = await requestJson<{ savedCount?: number }>('/api/v1/paper-overtime/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          companyId,
          batchId,
          lines: lines
            .filter((line) => line.employeeId && line.overtimeDate && line.start && line.end && line.overtimeType)
            .map((line) => ({
              employeeId: line.employeeId,
              overtimeDate: line.overtimeDate,
              start: line.start,
              end: line.end,
              overtimeType: line.overtimeType,
              reason: line.reason,
            })),
        }),
      });
      void messageApi.success(`新增成功，已写入加班报表 ${saved.savedCount ?? 0} 行`);
      setLines([emptyLine()]);
      setBatchId(undefined);
      setFiles([]);
      setOcrNotice(undefined);
    } catch (error: unknown) {
      void messageApi.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const searchPeople = (index: string, keyword: string) => {
    if (!companyId) return;
    window.clearTimeout(searchTimers.current[index]);
    searchTimers.current[index] = window.setTimeout(() => {
      void requestJson<Candidate[]>(
        `/api/v1/paper-overtime/candidates?companyId=${encodeURIComponent(companyId)}`
        + `&name=${encodeURIComponent(keyword.trim())}`,
      ).then((candidates) => {
        setLines((current) => current.map((line) => (
          line.key === index ? { ...line, candidates: candidates ?? [] } : line
        )));
      }).catch(() => {
        /* keep last candidates */
      });
    }, 250);
  };

  return (
    <>
      {messageContextHolder}
      <PageHeader
        title="纸质加班单"
        description="可上传识别，也可直接手填。人员按姓名或工号搜索花名册；识别失败不影响保存。保存后进入加班报表。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '纸质加班单' }]}
      />
      <section className="content-card paper-overtime" style={{ padding: 16 }}>
        <div className="paper-overtime__toolbar">
          <Select
            showSearch
            placeholder="选择公司"
            value={companyId || undefined}
            options={companies.map((row) => ({
              value: row.reference,
              label: row.label,
            }))}
            optionFilterProp="label"
            onChange={(value) => setCompanyId(value)}
            style={{ minWidth: 240, maxWidth: 360 }}
          />
          <Upload
            multiple
            fileList={files}
            beforeUpload={(file) => {
              setFiles((current) => [...current, {
                uid: file.uid,
                name: file.name,
                status: 'done',
                originFileObj: file,
              }]);
              return false;
            }}
            onRemove={(file) => {
              setFiles((current) => current.filter((item) => item.uid !== file.uid));
            }}
            accept="image/*,.pdf,application/pdf"
          >
            <Button>上传 PDF / 照片</Button>
          </Upload>
          <Button
            loading={recognizing}
            disabled={!companyId || files.length === 0}
            onClick={() => { void recognize(); }}
          >
            识别
          </Button>
          <Button onClick={() => setLines((current) => [...current, emptyLine()])}>新增一行</Button>
          <Button type="primary" loading={saving} disabled={!canSave} onClick={() => { void save(); }}>
            保存
          </Button>
        </div>
        {ocrNotice ? (
          <Alert
            style={{ marginTop: 12, marginBottom: 12 }}
            type={ocrNotice.includes('已识别') ? 'success' : 'warning'}
            showIcon
            message={ocrNotice}
          />
        ) : null}
        {lines.map((line, index) => (
          <div key={line.key} className="paper-overtime__row">
            <Select
              showSearch
              allowClear
              placeholder={line.ocrName ? `识别到 ${line.ocrName}，请搜索确认` : '搜索姓名或工号'}
              value={line.employeeId}
              filterOption={false}
              options={(line.candidates ?? []).map((candidate) => ({
                value: candidate.employeeId,
                label: `${candidate.employeeNumber} ${candidate.displayName} ${candidate.departmentName}`,
              }))}
              onSearch={(value) => searchPeople(line.key, value)}
              onOpenChange={(open) => {
                if (open && (line.candidates ?? []).length === 0) {
                  searchPeople(line.key, line.ocrName ?? '');
                }
              }}
              onChange={(value) => {
                const selected = (line.candidates ?? []).find((item) => item.employeeId === value);
                patch(index, {
                  employeeId: value,
                  employeeNumber: selected?.employeeNumber ?? '',
                  departmentName: selected?.departmentName ?? '',
                });
              }}
            />
            <Input placeholder="工号" value={line.employeeNumber ?? ''} readOnly />
            <Input placeholder="部门" value={line.departmentName ?? line.ocrDepartment ?? ''} readOnly />
            <DatePicker
              value={line.overtimeDate ? dayjs(line.overtimeDate) : null}
              onChange={(value: Dayjs | null) => patch(index, { overtimeDate: value?.format('YYYY-MM-DD') })}
            />
            <TimePicker
              format="HH:mm"
              value={line.start ? dayjs(line.start, 'HH:mm') : null}
              onChange={(value) => patch(index, { start: value?.format('HH:mm') })}
            />
            <TimePicker
              format="HH:mm"
              value={line.end ? dayjs(line.end, 'HH:mm') : null}
              onChange={(value) => patch(index, { end: value?.format('HH:mm') })}
            />
            <Select
              allowClear
              placeholder="加班类型"
              value={line.overtimeType}
              options={[
                { value: 'PAID', label: '加班费' },
                { value: 'COMPENSATORY', label: '调休' },
                { value: 'VOLUNTARY', label: '义务加班' },
              ]}
              onChange={(value) => patch(index, { overtimeType: value })}
            />
            <Input
              placeholder="事由"
              value={line.reason}
              onChange={(event) => patch(index, { reason: event.target.value })}
            />
            <Button
              aria-label="删除本行"
              onClick={() => setLines((current) => (
                current.length <= 1 ? [emptyLine()] : current.filter((item) => item.key !== line.key)
              ))}
            >
              ×
            </Button>
          </div>
        ))}
      </section>
    </>
  );

  function patch(index: number, next: Partial<DraftLine>) {
    setLines((current) => current.map((line, cursor) => (cursor === index ? { ...line, ...next } : line)));
  }
}

function clock(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return value.length >= 5 ? value.slice(0, 5) : value;
}

function toDraftLine(line: Omit<DraftLine, 'key'> & { lineId?: string }): DraftLine {
  const matched = line.candidates?.length === 1 ? line.candidates[0] : undefined;
  return {
    ...line,
    key: line.lineId ?? newLineKey(),
    employeeId: line.employeeId ?? matched?.employeeId,
    employeeNumber: matched?.employeeNumber,
    departmentName: matched?.departmentName ?? line.ocrDepartment,
    start: clock(line.start),
    end: clock(line.end),
  };
}

function lineHasFields(line: DraftLine): boolean {
  return Boolean(
    line.ocrName
    || line.ocrDepartment
    || line.departmentName
    || line.overtimeDate
    || line.start
    || line.end
    || line.overtimeType
    || line.reason
    || line.employeeId,
  );
}

function uploadBlob(file: UploadFile): File | Blob | undefined {
  if (file.originFileObj) return file.originFileObj;
  if (typeof File !== 'undefined' && file instanceof File) return file;
  return undefined;
}

function newLineKey(): string {
  const cryptoRef = globalThis.crypto;
  if (cryptoRef && typeof cryptoRef.randomUUID === 'function') {
    return `new-${cryptoRef.randomUUID()}`;
  }
  return `new-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function emptyLine(): DraftLine {
  return {
    key: newLineKey(),
    candidates: [],
  };
}
