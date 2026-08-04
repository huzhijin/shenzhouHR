import { IconDownload, IconSearch, IconUsersPlus } from '@tabler/icons-react';
import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Progress,
  Space,
  Table,
  Tag,
} from 'antd';
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type Key,
} from 'react';

import {
  ApiRequestError,
  createIdempotencyKey,
} from '../../shared/api/apiClient';
import { CompanySelect } from '../referenceData';
import {
  createEmployeeAccounts,
  listEmployeeAccountCandidates,
  type EmployeeAccountCandidate,
  type EmployeeAccountCandidatePage,
  type TemporaryCredential,
} from './accessApi';

const pageSize = 20;
const createChunkSize = 20;
const emptyPage: EmployeeAccountCandidatePage = {
  items: [],
  total: 0,
  available: 0,
  alreadyProvisioned: 0,
  usernameConflicts: 0,
  page: 0,
  size: pageSize,
};

interface PendingProvisioningRequest {
  employeeIds: string[];
  idempotencyKey: string;
  recoveryKey: string;
}

interface ProvisioningResultNotice {
  type: 'success' | 'warning';
  title: string;
}

export function EmployeeAccountProvisioningDialog({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [companyId, setCompanyId] = useState('');
  const [query, setQuery] = useState('');
  const [submittedQuery, setSubmittedQuery] = useState('');
  const [page, setPage] = useState(0);
  const [candidatePage, setCandidatePage] = useState(emptyPage);
  const [selectedEmployeeIds, setSelectedEmployeeIds] = useState<Key[]>([]);
  const [loading, setLoading] = useState(false);
  const [processing, setProcessing] = useState(false);
  const [progress, setProgress] = useState(0);
  const [credentials, setCredentials] = useState<TemporaryCredential[]>([]);
  const [resultNotice, setResultNotice] = useState<ProvisioningResultNotice>();
  const [pendingRequest, setPendingRequest] = useState<PendingProvisioningRequest>();
  const candidateLoadGeneration = useRef(0);

  const load = useCallback(async () => {
    const generation = ++candidateLoadGeneration.current;
    const requestedCompanyId = companyId;
    if (!open || !requestedCompanyId) {
      setCandidatePage(emptyPage);
      setSelectedEmployeeIds([]);
      setLoading(false);
      return;
    }
    setCandidatePage(emptyPage);
    setSelectedEmployeeIds([]);
    setLoading(true);
    try {
      const result = await listEmployeeAccountCandidates({
        companyId: requestedCompanyId,
        query: submittedQuery,
        page,
        size: pageSize,
      });
      if (candidateLoadGeneration.current !== generation) return;
      if (result.items.some((candidate) => candidate.companyId !== requestedCompanyId)) {
        setCandidatePage(emptyPage);
        void message.error('预检结果与当前公司不一致，已停止展示。请刷新后重试。');
        return;
      }
      setCandidatePage(result);
    } catch (caught: unknown) {
      if (candidateLoadGeneration.current !== generation) return;
      setCandidatePage(emptyPage);
      void message.error(errorMessage(caught, '员工账号预检加载失败，请重试。'));
    } finally {
      if (candidateLoadGeneration.current === generation) setLoading(false);
    }
  }, [companyId, open, page, submittedQuery]);

  useEffect(() => {
    void load();
    return () => {
      candidateLoadGeneration.current += 1;
    };
  }, [load]);

  useEffect(() => {
    if (open) return;
    setQuery('');
    setSubmittedQuery('');
    setPage(0);
    setSelectedEmployeeIds([]);
    setCredentials([]);
    setResultNotice(undefined);
    setProgress(0);
    setPendingRequest(undefined);
  }, [open]);

  const provision = async (employeeIds: string[]) => {
    if (employeeIds.length === 0) {
      void message.info('当前没有可开通的员工。');
      return;
    }
    setProcessing(true);
    setProgress(0);
    setCredentials([]);
    setResultNotice(undefined);
    const created: TemporaryCredential[] = [];
    try {
      for (let offset = 0; offset < employeeIds.length; offset += createChunkSize) {
        const chunk = employeeIds.slice(offset, offset + createChunkSize);
        const request = {
          employeeIds: chunk,
          idempotencyKey: createIdempotencyKey('employee-account-bulk'),
          recoveryKey: createProvisioningRecoveryKey(),
        };
        let result;
        try {
          result = await submitProvisioningRequest(request);
        } catch (caught: unknown) {
          const resultUncertain = !isDefinitiveFailure(caught);
          setProcessing(false);
          setPendingRequest(resultUncertain
            ? { ...request, employeeIds: [...request.employeeIds] }
            : undefined);
          if (created.length > 0) {
            setCredentials(created);
            setResultNotice({
              type: 'warning',
              title: `已开通 ${created.length} 个账号，后续批次未完成。已下载成功部分的账号清单，请保留后再刷新预检结果重试。`,
            });
            downloadCredentialCsv(created);
            onCreated();
          }
          void message.error(resultUncertain
            ? '网络持续异常，本批次结果尚未确认。请点击“重新确认上次批次”，不要重复选择员工。'
            : errorMessage(
              caught,
              created.length > 0
                ? '部分账号已开通，请保存已下载的账号清单，并刷新预检结果后继续。'
                : '批量开通未完成，请刷新预检结果后重试。',
            ));
          await load();
          return;
        }
        created.push(...result.credentials);
        setPendingRequest(undefined);
        setProgress(Math.round((Math.min(offset + chunk.length, employeeIds.length)
          / employeeIds.length) * 100));
      }
      setCredentials(created);
      setResultNotice({
        type: 'success',
        title: `已成功开通 ${created.length} 个员工账号。登录名为工号，首次登录必须修改密码。`,
      });
      setSelectedEmployeeIds([]);
      downloadCredentialCsv(created);
      onCreated();
      await load();
    } catch (caught: unknown) {
      setPendingRequest(undefined);
      if (created.length > 0) {
        setCredentials(created);
        setResultNotice({
          type: 'warning',
          title: `已开通 ${created.length} 个账号，后续批次未完成。已下载成功部分的账号清单，请保留后再刷新预检结果重试。`,
        });
        downloadCredentialCsv(created);
        onCreated();
      }
      void message.error(errorMessage(
        caught,
        created.length > 0
          ? '部分账号已开通，请保存已下载的账号清单，并刷新预检结果后继续。'
          : '批量开通未完成，请刷新预检结果后重试。',
      ));
      await load();
    } finally {
      setProcessing(false);
    }
  };

  const retryPendingRequest = async () => {
    if (!pendingRequest) return;
    setProcessing(true);
    try {
      const result = await submitProvisioningRequest(pendingRequest);
      const confirmed = [...credentials, ...result.credentials];
      setCredentials(confirmed);
      setPendingRequest(undefined);
      setProgress(100);
      setResultNotice({
        type: 'success',
        title: `已确认 ${result.credentials.length} 个账号并取得当前有效的临时密码。请保存账号清单，再继续处理剩余员工。`,
      });
      setSelectedEmployeeIds([]);
      downloadCredentialCsv(confirmed);
      onCreated();
      await load();
    } catch (caught: unknown) {
      if (isDefinitiveFailure(caught)) {
        setPendingRequest(undefined);
        void message.error(errorMessage(caught, '上次批次已无法恢复，请刷新预检结果后重新处理。'));
        await load();
      } else {
        void message.error(errorMessage(
          caught,
          '仍无法确认上次批次。请保留当前页面，网络恢复后再次点击“重新确认上次批次”。',
        ));
      }
    } finally {
      setProcessing(false);
    }
  };

  const confirmProvision = (employeeIds: string[]) => {
    if (employeeIds.length === 0) {
      void message.info('当前没有可开通的员工。');
      return;
    }
    Modal.confirm({
      title: `确认开通 ${employeeIds.length} 个员工账号？`,
      content: '登录名将使用工号，角色固定为“员工本人”。完成后请立即保存一次性账号清单。',
      okText: `确认开通 ${employeeIds.length} 人`,
      cancelText: '返回核对',
      onOk: () => provision(employeeIds),
    });
  };

  const provisionAll = async () => {
    if (!companyId) return;
    const requestedCompanyId = companyId;
    setProcessing(true);
    setProgress(0);
    try {
      const first = await listEmployeeAccountCandidates({
        companyId: requestedCompanyId,
        query: submittedQuery,
        page: 0,
        size: 100,
      });
      if (first.total > 5000) {
        void message.warning('待核对员工超过 5000 人，请先按姓名或工号筛选后分批开通。');
        setProcessing(false);
        return;
      }
      const pages = [first];
      for (let nextPage = 1; nextPage < Math.ceil(first.total / 100); nextPage += 1) {
        pages.push(await listEmployeeAccountCandidates({
          companyId: requestedCompanyId,
          query: submittedQuery,
          page: nextPage,
          size: 100,
        }));
      }
      if (pages.some((candidatePageResult) => candidatePageResult.items.some(
        (candidate) => candidate.companyId !== requestedCompanyId,
      ))) {
        void message.error('完整预检结果与当前公司不一致，已停止开通。请刷新后重试。');
        setProcessing(false);
        return;
      }
      const employeeIds = pages
        .flatMap((item) => item.items)
        .filter((item) => item.status === 'AVAILABLE')
        .map((item) => item.employeeId);
      setProcessing(false);
      confirmProvision(employeeIds);
    } catch (caught: unknown) {
      void message.error(errorMessage(caught, '完整预检未完成，请重试。'));
      setProcessing(false);
    }
  };

  const submitSearch = () => {
    const nextQuery = query.trim();
    candidateLoadGeneration.current += 1;
    setCandidatePage(emptyPage);
    setLoading(false);
    setPage(0);
    setSelectedEmployeeIds([]);
    if (page === 0 && submittedQuery === nextQuery) {
      void load();
      return;
    }
    setSubmittedQuery(nextQuery);
  };

  return (
    <Modal
      className="account-provisioning-modal"
      width={980}
      open={open}
      title="从员工批量开通账号"
      closable={!processing && !pendingRequest}
      mask={{ closable: !processing && !pendingRequest }}
      keyboard={!processing && !pendingRequest}
      onCancel={processing || pendingRequest ? undefined : onClose}
      footer={[
        <Button key="cancel" disabled={processing || Boolean(pendingRequest)} onClick={onClose}>关闭</Button>,
        credentials.length > 0 ? (
          <Button
            key="download"
            icon={<IconDownload stroke={2} />}
            onClick={() => downloadCredentialCsv(credentials)}
          >
            再次下载本次账号清单
          </Button>
        ) : null,
        pendingRequest ? (
          <Button
            key="retry-pending"
            type="primary"
            loading={processing}
            onClick={() => void retryPendingRequest()}
          >
            重新确认上次批次
          </Button>
        ) : null,
        <Button
          key="selected"
          disabled={loading || processing || Boolean(pendingRequest) || selectedEmployeeIds.length === 0}
          onClick={() => confirmProvision(selectedEmployeeIds.map(String))}
        >
          开通已选 {selectedEmployeeIds.length > 0 ? `${selectedEmployeeIds.length} 人` : ''}
        </Button>,
        <Button
          key="all"
          type="primary"
          icon={<IconUsersPlus stroke={2} />}
          loading={processing}
          disabled={loading || !companyId || Boolean(pendingRequest) || candidatePage.available === 0}
          onClick={() => void provisionAll()}
        >
          开通全部 {candidatePage.available > 0 ? `${candidatePage.available} 人` : ''}
        </Button>,
      ]}
    >
      <Alert
        showIcon
        type="info"
        title="默认规则已固定，避免误授权"
        description="只开通在职且有有效任职的员工；登录名默认为工号；角色固定为“员工本人”，只能查看本人数据。系统会为每人生成不同的临时密码，并在完成后下载一次性账号清单。"
      />
      <div className="account-provisioning-toolbar">
        <label>
          <span>公司</span>
          <CompanySelect
            value={companyId || undefined}
            disabled={processing || Boolean(pendingRequest)}
            onChange={(value) => {
              const nextCompanyId = value ?? '';
              candidateLoadGeneration.current += 1;
              setCandidatePage(emptyPage);
              setLoading(false);
              setCompanyId(nextCompanyId);
              setPage(0);
              setSelectedEmployeeIds([]);
              if (nextCompanyId === companyId && page === 0) void load();
            }}
          />
        </label>
        <label>
          <span>姓名、工号或部门</span>
          <Space.Compact block>
            <Input
              value={query}
              allowClear
              placeholder="输入后查询"
              disabled={processing || Boolean(pendingRequest)}
              onChange={(event) => setQuery(event.target.value)}
              onPressEnter={submitSearch}
            />
            <Button
              icon={<IconSearch stroke={2} />}
              disabled={processing || Boolean(pendingRequest)}
              onClick={submitSearch}
            >
              查询
            </Button>
          </Space.Compact>
        </label>
      </div>
      {companyId ? (
        <div className="account-provisioning-summary" aria-label="预检汇总">
          <span>共 {candidatePage.total} 人</span>
          <strong>可开通 {candidatePage.available} 人</strong>
          <span>已有账号 {candidatePage.alreadyProvisioned} 人</span>
          <span className={candidatePage.usernameConflicts > 0 ? 'text-warning' : undefined}>
            工号冲突 {candidatePage.usernameConflicts} 人
          </span>
        </div>
      ) : null}
      {processing ? (
        <div className="account-provisioning-progress">
          <Progress percent={progress} status="active" />
          <span>正在完成预检或逐批创建账号，请不要刷新或关闭窗口。</span>
        </div>
      ) : null}
      {resultNotice ? (
        <Alert
          showIcon
          type={resultNotice.type}
          title={resultNotice.title}
          description="临时密码仅在当前窗口临时保留，刷新或关闭窗口后会清除；不会写入数据库或操作记录。请妥善保存下载文件，并通过安全渠道分别交给员工。"
        />
      ) : null}
      {pendingRequest ? (
        <Alert
          showIcon
          type="warning"
          title="上次批次结果尚未确认"
          description="系统会使用当前窗口中的同一恢复密钥重新确认；如果账号已经创建，会返回同一份临时密码，不会重复创建或轮换密码。确认完成前请勿刷新或关闭窗口；刷新或关闭后恢复密钥会清除，只能到账号页执行密码重置。"
        />
      ) : null}
      <Table<EmployeeAccountCandidate>
        className="account-provisioning-table"
        rowKey="employeeId"
        loading={loading}
        dataSource={candidatePage.items}
        rowSelection={{
          selectedRowKeys: selectedEmployeeIds,
          getTitleCheckboxProps: () => ({
            'aria-label': '选择当前页全部可开通员工',
            disabled: loading || processing || Boolean(pendingRequest),
          }),
          getCheckboxProps: (row) => ({
            'aria-label': `选择 ${row.employeeNumber} ${row.displayName}`,
            disabled: row.status !== 'AVAILABLE' || loading || processing || Boolean(pendingRequest),
          }),
          onChange: (keys) => {
            if (keys.length > createChunkSize) {
              void message.info(`单次勾选最多 ${createChunkSize} 人；如需全部开通，请使用“开通全部”。`);
            }
            setSelectedEmployeeIds(keys.slice(0, createChunkSize));
          },
        }}
        columns={[
          { title: '工号（默认登录名）', dataIndex: 'employeeNumber' },
          { title: '姓名', dataIndex: 'displayName' },
          { title: '部门', dataIndex: 'organizationName', render: (value) => value || '未设置' },
          {
            title: '预检结果',
            dataIndex: 'status',
            render: (status: EmployeeAccountCandidate['status']) => candidateStatus(status),
          },
        ]}
        scroll={{ x: 720 }}
        pagination={{
          current: page + 1,
          pageSize,
          total: candidatePage.total,
          showSizeChanger: false,
          showTotal: (total) => `共 ${total} 人`,
          onChange: (nextPage) => {
            candidateLoadGeneration.current += 1;
            setCandidatePage(emptyPage);
            setLoading(false);
            setPage(nextPage - 1);
            setSelectedEmployeeIds([]);
          },
        }}
      />
    </Modal>
  );
}

async function submitProvisioningRequest(request: PendingProvisioningRequest) {
  try {
    return await createEmployeeAccounts(
      request.employeeIds,
      request.idempotencyKey,
      request.recoveryKey,
    );
  } catch (caught: unknown) {
    if (!isRetryableFailure(caught)) throw caught;
    return createEmployeeAccounts(
      request.employeeIds,
      request.idempotencyKey,
      request.recoveryKey,
    );
  }
}

export function createProvisioningRecoveryKey(): string {
  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return globalThis.btoa(String.fromCharCode(...bytes))
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/u, '');
}

function isRetryableFailure(caught: unknown): boolean {
  if (caught instanceof ApiRequestError) return caught.retryable;
  return typeof caught === 'object'
    && caught !== null
    && 'retryable' in caught
    && caught.retryable === true;
}

function isDefinitiveFailure(caught: unknown): boolean {
  if (caught instanceof ApiRequestError) return !caught.retryable;
  return typeof caught === 'object'
    && caught !== null
    && 'retryable' in caught
    && caught.retryable === false;
}

function candidateStatus(status: EmployeeAccountCandidate['status']) {
  if (status === 'AVAILABLE') return <Tag color="green">可开通</Tag>;
  if (status === 'ALREADY_PROVISIONED') return <Tag>已有账号</Tag>;
  return <Tag color="orange">工号被占用</Tag>;
}

function downloadCredentialCsv(credentials: TemporaryCredential[]) {
  if (credentials.length === 0) return;
  const rows = [
    ['工号', '姓名', '部门', '登录名', '临时密码', '首次登录要求'],
    ...credentials.map((credential) => [
      credential.employeeNumber,
      credential.displayName,
      credential.organizationName ?? '',
      credential.username,
      credential.temporaryPassword,
      '必须修改密码',
    ]),
  ];
  const csv = `\uFEFF${rows.map((row) => row.map(credentialCsvCell).join(',')).join('\r\n')}`;
  const url = window.URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `员工账号开通清单-${new Date().toISOString().slice(0, 10)}.csv`;
  anchor.click();
  window.URL.revokeObjectURL(url);
}

export function credentialCsvCell(value: string): string {
  const formulaLike = /^[=+\-@\t\r\n]/u.test(value) || /^\s+[=+\-@]/u.test(value);
  const safe = formulaLike ? `'${value}` : value;
  return `"${safe.replaceAll('"', '""')}"`;
}

function errorMessage(caught: unknown, fallback: string): string {
  if (caught instanceof Error && caught.message.trim()) return caught.message;
  return fallback;
}
