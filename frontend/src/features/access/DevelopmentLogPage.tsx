import { useEffect, useState, useRef } from 'react';
import { Typography, Spin } from 'antd';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import './DevelopmentLogPage.css';

const { Title, Paragraph, Text } = Typography;

interface DevelopmentLogEntry {
  deploymentId: string;
  date: string;
  version: string;
  title: string;
  description: string;
  details: string;
  isCurrent: boolean;
}

const DEMO_DATA: DevelopmentLogEntry[] = [
  {
    deploymentId: 'demo-deploy-001',
    date: '2026-10-10T10:30:00Z',
    version: 'v1.2.0',
    title: '十月HR修复（演示数据）',
    description: '修复员工状态、工龄计算、生效日期等问题',
    details: '- 员工状态按钮高亮响应\n- 生效日期清空要求明确选择\n- 工龄两位小数，365天/年\n- 离职后OA月请假逐日过滤',
    isCurrent: false,
  },
  {
    deploymentId: 'demo-deploy-002',
    date: '2026-09-28T15:20:00Z',
    version: 'v1.1.5',
    title: '考勤计算优化（演示数据）',
    description: '优化考勤月报计算性能',
    details: '- 优化SQL查询性能\n- 减少数据库往返次数\n- 添加缓存层',
    isCurrent: false,
  },
];

function DevelopmentLogPage() {
  const isDemo = isDemoMode();

  const [entries, setEntries] = useState<DevelopmentLogEntry[]>([]);
  const [initialLoading, setInitialLoading] = useState(true);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [search, setSearch] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const searchTimeoutRef = useRef<NodeJS.Timeout | null>(null);
  const sequenceRef = useRef(0);

  useEffect(() => {
    const currentSequence = ++sequenceRef.current;

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }

    if (searchTimeoutRef.current) {
      clearTimeout(searchTimeoutRef.current);
      searchTimeoutRef.current = null;
    }

    searchTimeoutRef.current = setTimeout(() => {
      loadEntries(currentSequence);
    }, 300);

    return () => {
      ++sequenceRef.current;
      if (searchTimeoutRef.current) {
        clearTimeout(searchTimeoutRef.current);
        searchTimeoutRef.current = null;
      }
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
        abortControllerRef.current = null;
      }
    };
  }, [search, isDemo]);

  async function loadEntries(expectedSequence: number) {
    if (expectedSequence !== sequenceRef.current) {
      return;
    }

    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }

    if (isDemo) {
      await new Promise((resolve) => setTimeout(resolve, 200));

      if (expectedSequence !== sequenceRef.current) {
        return;
      }

      const query = search.toLowerCase().trim();
      const filtered = query
        ? DEMO_DATA.filter(
            (entry) =>
              entry.version.toLowerCase().includes(query) ||
              entry.title.toLowerCase().includes(query) ||
              entry.description.toLowerCase().includes(query) ||
              entry.details.toLowerCase().includes(query)
          )
        : DEMO_DATA;

      if (expectedSequence === sequenceRef.current) {
        setEntries(filtered);
        setInitialLoading(false);
        setError(null);
      }
      return;
    }

    const controller = new AbortController();
    abortControllerRef.current = controller;

    if (initialLoading) {
      setInitialLoading(true);
    } else {
      setSearching(true);
    }
    setError(null);

    try {
      const params = search.trim() ? `?search=${encodeURIComponent(search.trim())}` : '';
      const data = await requestJson<DevelopmentLogEntry[]>(
        `/api/v1/access/development-log${params}`,
        {
          method: 'GET',
          signal: controller.signal,
        }
      );

      if (controller.signal.aborted || expectedSequence !== sequenceRef.current) {
        return;
      }

      setEntries(data);
      setInitialLoading(false);
    } catch (err) {
      if (err instanceof Error && err.name === 'AbortError') {
        return;
      }
      if (controller.signal.aborted || expectedSequence !== sequenceRef.current) {
        return;
      }
      setError(err instanceof Error ? err : new Error('加载失败'));
      setInitialLoading(false);
    } finally {
      if (!controller.signal.aborted && expectedSequence === sequenceRef.current) {
        setSearching(false);
      }
    }
  }

  function toggleExpand(deploymentId: string) {
    setExpandedId(expandedId === deploymentId ? null : deploymentId);
  }

  const retryLoad = () => {
    // Clear any pending debounce timeout before retry
    if (searchTimeoutRef.current) {
      clearTimeout(searchTimeoutRef.current);
      searchTimeoutRef.current = null;
    }
    const seq = ++sequenceRef.current;
    loadEntries(seq);
  };

  return (
    <section aria-labelledby="development-log-title" className="development-log-page">
      <PageHeader title="开发日志" />

      {/* Search box always visible */}
      <div className="development-log-search">
        <input
          type="text"
          placeholder="搜索版本、标题、描述或详情"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="search-input"
          autoFocus
        />
        {searching && <Spin size="small" className="search-spinner" />}
      </div>

      {/* Results area */}
      {initialLoading ? (
        <StatePanel state="loading" />
      ) : error ? (
        <StatePanel state="error" description={error.message} onRetry={retryLoad} />
      ) : entries.length === 0 ? (
        <StatePanel
          state="empty"
          description={search ? '未找到匹配的部署记录' : '暂无部署记录'}
        />
      ) : (
        <div className="development-log-list">
          {entries.map((entry) => (
            <div
              key={entry.deploymentId}
              className={`development-log-entry ${entry.isCurrent ? 'current' : ''}`}
            >
              <div className="entry-header">
                <div className="entry-meta">
                  <Text strong className="entry-version">
                    {entry.version}
                    {entry.isCurrent && (
                      <span className="current-badge">当前版本</span>
                    )}
                  </Text>
                  <Text type="secondary" className="entry-date">
                    {formatDate(entry.date)}
                  </Text>
                </div>
                <Title level={4} className="entry-title">
                  {entry.title}
                </Title>
                {entry.description && (
                  <Paragraph className="entry-description">
                    {entry.description}
                  </Paragraph>
                )}
              </div>

              {entry.details && (
                <div className="entry-details-section">
                  <button
                    className="details-toggle"
                    onClick={() => toggleExpand(entry.deploymentId)}
                  >
                    {expandedId === entry.deploymentId ? '收起详情' : '展开详情'}
                  </button>
                  {expandedId === entry.deploymentId && (
                    <div className="entry-details">
                      <pre>{entry.details}</pre>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function formatDate(dateString: string): string {
  try {
    const date = new Date(dateString);
    return date.toLocaleString('zh-CN', {
      timeZone: 'Asia/Shanghai',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    });
  } catch {
    return dateString;
  }
}

export default DevelopmentLogPage;
