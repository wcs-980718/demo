import { useQuery } from '@tanstack/react-query';
import { Button, Drawer, Spin, Tag } from 'antd';
import { useState } from 'react';
import { Copy } from 'lucide-react';
import { midplatApi } from '@/api/midplatApi';

function prettyJson(raw: string | null): string {
  if (!raw) return '（无请求体）';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

// 与列表页保持一致的类别配色
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

export function ExceptionReportDrawer({
  reportId,
  platformName,
  onClose,
}: {
  reportId: string | null;
  platformName: string | null;
  onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);

  const detailQuery = useQuery({
    queryKey: ['exception-report', reportId],
    queryFn: () => midplatApi.getExceptionReport(reportId!),
    enabled: reportId !== null,
  });
  const report = detailQuery.data ?? null;

  const copyBody = async () => {
    if (!report?.requestBody) return;
    await navigator.clipboard.writeText(prettyJson(report.requestBody));
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <Drawer
      title="异常调用详情"
      width={560}
      open={reportId !== null}
      onClose={onClose}
      extra={
        <Tag color="warning" style={{ marginInlineEnd: 0 }}>
          已脱敏
        </Tag>
      }
    >
      {detailQuery.isLoading ? (
        <div style={{ display: 'grid', placeItems: 'center', minHeight: 200 }}><Spin /></div>
      ) : report ? (
        <div className="health-drawer-grid">
          <div className="health-drawer-field">
            <span>请求</span>
            <pre className="health-drawer-code">{report.method} {report.url}</pre>
          </div>
          {report.queryParams && (
            <div className="health-drawer-field">
              <span>查询参数</span>
              <pre className="health-drawer-code">{report.queryParams}</pre>
            </div>
          )}
          <div className="health-drawer-field">
            <span>状态码</span>
            <div>
              {report.status === null ? (
                <Tag>无响应（网络/超时）</Tag>
              ) : (
                <Tag color={report.status >= 500 ? 'red' : 'orange'}>{report.status}</Tag>
              )}
            </div>
          </div>
          <div className="health-drawer-field">
            <span>错误类别</span>
            <div>
              <Tag color={CATEGORY_TAG_COLOR[report.category] ?? 'default'}>
                {report.categoryFault ? '' : '非故障 · '}
                {report.categoryLabel}
              </Tag>
            </div>
          </div>
          <div className="health-drawer-field">
            <span>错误说明</span>
            <p>{report.categoryDetail}</p>
          </div>
          <div className="health-drawer-field">
            <span>原始错误信息（排查用）</span>
            <pre className="health-drawer-code">{report.errorMessage}</pre>
          </div>
          <div className="health-drawer-field">
            <span>发生时间</span>
            <p>{new Date(report.occurredAt).toLocaleString()}</p>
          </div>
          <div className="health-drawer-field">
            <span>所属项目</span>
            <p>{platformName ?? report.platformId}</p>
          </div>
          <div className="health-drawer-field">
            <div className="health-drawer-field-head">
              <span>请求体参数（已脱敏）</span>
              {report.requestBody && (
                <Button size="small" icon={<Copy size={13} />} onClick={copyBody}>
                  {copied ? '已复制' : '复制'}
                </Button>
              )}
            </div>
            <pre className="health-drawer-code">{prettyJson(report.requestBody)}</pre>
          </div>
        </div>
      ) : (
        <p>记录加载失败。</p>
      )}
    </Drawer>
  );
}
