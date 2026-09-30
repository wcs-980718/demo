import { useQuery } from '@tanstack/react-query';
import { Button, DatePicker, Empty, Form, Input, InputNumber, Select, Spin, Table, Tag } from 'antd';
import type { Dayjs } from 'dayjs';
import { RefreshCw, RotateCcw, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { midplatApi, type ExceptionReportFilters, type ExceptionReportSummary } from '@/api/midplatApi';
import { ExceptionReportDrawer } from './ExceptionReportDrawer';
import {
  EXCEPTION_METHOD_OPTIONS,
  exceptionHistoryPresentation,
  isExceptionRowActivationKey,
  normalizeExceptionFilters,
  type FixedExceptionPlatform,
} from './exceptionFilters';
import '../index.css';

const PAGE_SIZE = 20;

function statusTag(status: number | null) {
  if (status === null) return <Tag>无响应</Tag>;
  if (status >= 500) return <Tag color="red">{status}</Tag>;
  if (status >= 400) return <Tag color="orange">{status}</Tag>;
  return <Tag>{status}</Tag>;
}

// 类别标签配色：非故障灰、调用方问题橙、服务故障红
const CATEGORY_TAG_COLOR: Record<string, string> = {
  'client-disconnected': 'default',
  'smoke-test': 'default',
  'method-not-allowed': 'orange',
  'rate-limited': 'orange',
  'client-error': 'orange',
  credential: 'red',
  timeout: 'red',
  network: 'red',
  'server-error': 'red',
  unknown: 'default',
};

function categoryTag(row: ExceptionReportSummary) {
  return (
    <Tag color={CATEGORY_TAG_COLOR[row.category] ?? 'default'}>
      {row.categoryFault ? '' : '非故障 · '}
      {row.categoryLabel}
    </Tag>
  );
}

type ExceptionFilterFormValues = {
  platformId?: string;
  method?: string;
  status?: number | null;
  keyword?: string;
  occurredRange?: [Dayjs | null, Dayjs | null] | null;
};

export function ExceptionHistoryPanel({
  fixedPlatform,
  embedded = false,
}: {
  fixedPlatform?: FixedExceptionPlatform;
  embedded?: boolean;
}) {
  const [form] = Form.useForm<ExceptionFilterFormValues>();
  const [filters, setFilters] = useState<ExceptionReportFilters>(() =>
    normalizeExceptionFilters({}, fixedPlatform?.id));
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const presentation = exceptionHistoryPresentation(fixedPlatform);

  const platformsQuery = useQuery({
    queryKey: ['health', 'platforms'],
    queryFn: midplatApi.listPlatforms,
    enabled: presentation.showProjectFilter,
  });
  const reportsQuery = useQuery({
    queryKey: ['exception-reports', filters, page],
    queryFn: () => midplatApi.listExceptionReports({ ...filters, page, size: PAGE_SIZE }),
  });

  const platformNames = useMemo(
    () => new Map([
      ...(platformsQuery.data ?? []).map((platform): [string, string] => [platform.id, platform.name]),
      ...(fixedPlatform ? [[fixedPlatform.id, fixedPlatform.name] as [string, string]] : []),
    ]),
    [fixedPlatform, platformsQuery.data],
  );

  useEffect(() => {
    form.resetFields();
    setFilters(normalizeExceptionFilters({}, fixedPlatform?.id));
    setPage(0);
    setSelectedId(null);
  }, [fixedPlatform?.id, form]);

  const applyFilters = (values: ExceptionFilterFormValues) => {
    const range = values.occurredRange;
    setFilters(normalizeExceptionFilters({
      platformId: values.platformId,
      method: values.method,
      status: values.status,
      keyword: values.keyword,
      occurredFrom: range?.[0]?.startOf('day').toISOString(),
      occurredTo: range?.[1]?.endOf('day').toISOString(),
    }, fixedPlatform?.id));
    setPage(0);
  };

  const resetFilters = () => {
    form.resetFields();
    setFilters(normalizeExceptionFilters({}, fixedPlatform?.id));
    setPage(0);
  };

  const columns = [
    {
      title: '发生时间',
      dataIndex: 'occurredAt',
      width: 180,
      render: (value: string) => new Date(value).toLocaleString(),
    },
    ...(presentation.showProjectColumn ? [{
      title: '项目',
      dataIndex: 'platformId',
      width: 150,
      render: (value: string) => platformNames.get(value) ?? value,
    }] : []),
    {
      title: '请求',
      key: 'request',
      render: (_: unknown, row: ExceptionReportSummary) => (
        <span className="exception-request">
          <span className="exception-method">{row.method}</span>
          <span className="exception-url">{row.url}</span>
        </span>
      ),
    },
    {
      title: '状态码',
      dataIndex: 'status',
      width: 90,
      render: (value: number | null) => statusTag(value),
    },
    {
      title: '错误类别',
      dataIndex: 'categoryLabel',
      width: 140,
      render: (_: unknown, row: ExceptionReportSummary) => categoryTag(row),
    },
    {
      title: '错误说明',
      key: 'summary',
      width: 300,
      render: (_: unknown, row: ExceptionReportSummary) => (
        <span className="exception-summary" title={row.errorMessage}>{row.categoryDetail ?? row.errorMessage}</span>
      ),
    },
  ];

  return (
    <section className={`exception-history-panel${embedded ? ' is-embedded' : ''}`}>
      <div className="evaluation-section">
        <div className="evaluation-section-head">
          <div>
            <p className="evaluation-kicker">EXCEPTION HISTORY</p>
            <h3>{presentation.title}</h3>
            <p>{presentation.description}</p>
          </div>
          <div className="health-head-meta">
            <Button icon={<RefreshCw size={15} />} loading={reportsQuery.isFetching} onClick={() => reportsQuery.refetch()}>
              刷新
            </Button>
          </div>
        </div>

        <div className="exception-filter-card">
          <Form form={form} layout="vertical" className="exception-filter-form" onFinish={applyFilters}>
            {presentation.showProjectFilter ? (
              <Form.Item name="platformId" label="项目">
                <Select
                  allowClear
                  showSearch
                  optionFilterProp="label"
                  placeholder="全部项目"
                  options={(platformsQuery.data ?? []).map((platform) => ({ value: platform.id, label: platform.name }))}
                />
              </Form.Item>
            ) : null}
            <Form.Item name="method" label="请求方法">
              <Select allowClear placeholder="全部方法" options={EXCEPTION_METHOD_OPTIONS} />
            </Form.Item>
            <Form.Item name="status" label="状态码">
              <InputNumber min={100} max={599} precision={0} placeholder="全部状态码" style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="keyword" label="关键词">
              <Input allowClear placeholder="搜索 URL 或错误信息" />
            </Form.Item>
            <Form.Item name="occurredRange" label="发生时间">
              <DatePicker.RangePicker allowClear style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="操作" className="exception-filter-actions-item">
              <div className="exception-filter-actions">
                <Button type="primary" htmlType="submit" icon={<Search size={15} />} loading={reportsQuery.isFetching}>查询</Button>
                <Button icon={<RotateCcw size={15} />} onClick={resetFilters}>重置</Button>
              </div>
            </Form.Item>
          </Form>
        </div>

        {reportsQuery.isLoading ? (
          <div className="evaluation-loading" aria-label="正在加载异常历史"><Spin size="large" /></div>
        ) : reportsQuery.isError ? (
          <div className="evaluation-error" role="alert">
            <div>
              <strong>异常历史加载失败</strong>
              <p>请检查中台服务连接后重试。</p>
            </div>
            <Button icon={<RefreshCw size={15} />} onClick={() => reportsQuery.refetch()}>重新加载</Button>
          </div>
        ) : (
          <div className="evaluation-section exception-table-card">
            <Table<ExceptionReportSummary>
              rowKey="id"
              size="middle"
              columns={columns}
              dataSource={reportsQuery.data?.content ?? []}
              scroll={{ x: presentation.showProjectColumn ? 1220 : 1070 }}
              locale={{ emptyText: <Empty description="暂无异常记录，各项目运行正常" /> }}
              onRow={(row) => ({
                onClick: () => setSelectedId(row.id),
                onKeyDown: (event) => {
                  if (!isExceptionRowActivationKey(event.key)) return;
                  event.preventDefault();
                  setSelectedId(row.id);
                },
                tabIndex: 0,
                'aria-label': `查看${platformNames.get(row.platformId) ?? row.platformId}异常详情`,
                style: { cursor: 'pointer' },
              })}
              pagination={{
                current: page + 1,
                pageSize: PAGE_SIZE,
                total: reportsQuery.data?.totalElements ?? 0,
                showSizeChanger: false,
                showTotal: (total) => `共 ${total} 条`,
                onChange: (next) => setPage(next - 1),
              }}
            />
          </div>
        )}
      </div>

      <ExceptionReportDrawer
        reportId={selectedId}
        platformName={selectedId ? platformNames.get(reportsQuery.data?.content.find((r) => r.id === selectedId)?.platformId ?? '') ?? null : null}
        onClose={() => setSelectedId(null)}
      />
    </section>
  );
}
