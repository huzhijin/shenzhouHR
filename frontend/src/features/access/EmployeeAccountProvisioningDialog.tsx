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
import { useCallback, useEffect, useState, type Key } from 'react';

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
  const [resultMessage, setResultMessage] = useState('');

  const load = useCallback(async () => {
    if (!open || !companyId) {
      setCandidatePage(emptyPage);
      return;
    }
    setLoading(true);
    try {
      const result = await listEmployeeAccountCandidates({
        companyId,
        query: submittedQuery,
        page,
        size: pageSize,
      });
      setCandidatePage(result);
    } catch (caught: unknown) {
      void message.error(errorMessage(caught, '员工账号预检加载失败，请重试。'));
    } finally {
      setLoading(false);
    }
  }, [companyId, open, page, submittedQuery]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (open) return;
    setQuery('');
    setSubmittedQuery('');
    setPage(0);
    setSelectedEmployeeIds([]);
    setCredentials([]);
    setResultMessage('');
    setProgress(0);
  }, [open]);

  const provision = async (employeeIds: string[]) => {
    if (employeeIds.length === 0) {
      void message.info('当前没有可开通的员工。');
      return;
    }
    setProcessing(true);
    setProgress(0);
    setCredentials([]);
    setResultMessage('');
    const created: TemporaryCredential[] = [];
    try {
      for (let offset = 0; offset < employeeIds.length; offset += createChunkSize) {
        const chunk = employeeIds.slice(offset, offset + createChunkSize);
        const result = await createEmployeeAccounts(chunk);
        created.push(...result.credentials);
        setProgress(Math.round((Math.min(offset + chunk.length, employeeIds.length)
          / employeeIds.length) * 100));
      }
      setCredentials(created);
      setResultMessage(`已成功开通 ${created.length} 个员工账号。登录名为工号，首次登录必须修改密码。`);
      setSelectedEmployeeIds([]);
      downloadCredentialCsv(created);
      onCreated();
      await load();
    } catch (caught: unknown) {
      if (created.length > 0) {
        setCredentials(created);
        setResultMessage(
          `已开通 ${created.length} 个账号，后续批次未完成。已下载成功部分的账号清单，请保留后再刷新预检结果重试。`,
        );
        downloadCredentialCsv(created);
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

  const provisionAll = async () => {
    if (!companyId) return;
    setProcessing(true);
    setProgress(0);
    try {
      const first = await listEmployeeAccountCandidates({
        companyId,
        query: submittedQuery,
        page: 0,
        size: 100,
      });
      if (first.total > 5000) {
        void message.warning('待核对员工超过 5000 人，请先按姓名或工号筛选后分批开通。');
        return;
      }
      const pages = [first];
      for (let nextPage = 1; nextPage < Math.ceil(first.total / 100); nextPage += 1) {
        pages.push(await listEmployeeAccountCandidates({
          companyId,
          query: submittedQuery,
          page: nextPage,
          size: 100,
        }));
      }
      const employeeIds = pages
        .flatMap((item) => item.items)
        .filter((item) => item.status === 'AVAILABLE')
        .map((item) => item.employeeId);
      setProcessing(false);
      await provision(employeeIds);
    } catch (caught: unknown) {
      void message.error(errorMessage(caught, '完整预检未完成，请重试。'));
      setProcessing(false);
    }
  };

  const submitSearch = () => {
    setPage(0);
    setSelectedEmployeeIds([]);
    setSubmittedQuery(query.trim());
  };

  return (
    <Modal
      width={980}
      open={open}
      title="从员工批量开通账号"
      onCancel={processing ? undefined : onClose}
      footer={[
        <Button key="cancel" disabled={processing} onClick={onClose}>关闭</Button>,
        credentials.length > 0 ? (
          <Button
            key="download"
            icon={<IconDownload stroke={2} />}
            onClick={() => downloadCredentialCsv(credentials)}
          >
            再次下载本次账号清单
          </Button>
        ) : null,
        <Button
          key="selected"
          disabled={processing || selectedEmployeeIds.length === 0}
          onClick={() => void provision(selectedEmployeeIds.map(String))}
        >
          开通已选 {selectedEmployeeIds.length > 0 ? `${selectedEmployeeIds.length} 人` : ''}
        </Button>,
        <Button
          key="all"
          type="primary"
          icon={<IconUsersPlus stroke={2} />}
          loading={processing}
          disabled={!companyId || candidatePage.available === 0}
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
            onChange={(value) => {
              setCompanyId(value ?? '');
              setPage(0);
              setSelectedEmployeeIds([]);
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
              onChange={(event) => setQuery(event.target.value)}
              onPressEnter={submitSearch}
            />
            <Button icon={<IconSearch stroke={2} />} onClick={submitSearch}>查询</Button>
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
          <span>正在逐批创建并加密账号，请不要关闭窗口。</span>
        </div>
      ) : null}
      {resultMessage ? (
        <Alert
          showIcon
          type="success"
          title={resultMessage}
          description="临时密码不会保存在页面或操作记录中。请妥善保存下载文件，并通过安全渠道分别交给员工。"
        />
      ) : null}
      <Table<EmployeeAccountCandidate>
        className="account-provisioning-table"
        rowKey="employeeId"
        loading={loading}
        dataSource={candidatePage.items}
        rowSelection={{
          selectedRowKeys: selectedEmployeeIds,
          getCheckboxProps: (row) => ({ disabled: row.status !== 'AVAILABLE' || processing }),
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
        pagination={{
          current: page + 1,
          pageSize,
          total: candidatePage.total,
          showSizeChanger: false,
          showTotal: (total) => `共 ${total} 人`,
          onChange: (nextPage) => {
            setPage(nextPage - 1);
            setSelectedEmployeeIds([]);
          },
        }}
      />
    </Modal>
  );
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
  const csv = `\uFEFF${rows.map((row) => row.map(csvCell).join(',')).join('\r\n')}`;
  const url = window.URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `员工账号开通清单-${new Date().toISOString().slice(0, 10)}.csv`;
  anchor.click();
  window.URL.revokeObjectURL(url);
}

function csvCell(value: string): string {
  const safe = /^[=+\-@]/.test(value) ? `'${value}` : value;
  return `"${safe.replaceAll('"', '""')}"`;
}

function errorMessage(caught: unknown, fallback: string): string {
  if (caught instanceof Error && caught.message.trim()) return caught.message;
  return fallback;
}
