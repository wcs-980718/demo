import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Descriptions, Drawer, Popconfirm, Spin, message } from 'antd';
import { Archive, Check, Edit3, RefreshCw, X } from 'lucide-react';
import { evaluationApi } from './evaluationApi';
import {
  caseLifecycleStatusLabel,
  caseReviewStatusLabel,
  caseSeverityLabel,
  evaluationMutationErrorMessage,
} from './evaluationPresentation';
import type { EvaluationCaseDetail } from './evaluationTypes';

export function CaseDetailDrawer({
  caseId,
  onClose,
  onEdit,
}: {
  caseId: string | null;
  onClose: () => void;
  onEdit: (detail: EvaluationCaseDetail) => void;
}) {
  const queryClient = useQueryClient();
  const detailQuery = useQuery({
    queryKey: ['evaluation', 'case', caseId],
    queryFn: () => evaluationApi.getCase(caseId as string),
    enabled: Boolean(caseId),
  });

  const refreshCaseQueries = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'case', caseId] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'cases'] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'reviewed-cases'] }),
      queryClient.invalidateQueries({ queryKey: ['evaluation', 'overview'] }),
    ]);
  };

  const reviewMutation = useMutation({
    mutationFn: ({ detail, decision }: { detail: EvaluationCaseDetail; decision: 'APPROVE' | 'REJECT' }) =>
      evaluationApi.reviewCase(detail.id, { decision, expectedVersion: detail.version }),
    onSuccess: async (_, variables) => {
      message.success(variables.decision === 'APPROVE' ? '案例已审核通过' : '案例已驳回');
      await refreshCaseQueries();
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const archiveMutation = useMutation({
    mutationFn: (detail: EvaluationCaseDetail) =>
      evaluationApi.archiveCase(detail.id, { expectedVersion: detail.version }),
    onSuccess: async () => {
      message.success('案例已归档');
      await refreshCaseQueries();
      onClose();
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const detail = detailQuery.data;
  const busy = reviewMutation.isPending || archiveMutation.isPending;

  return (
    <Drawer
      title="案例详情"
      open={Boolean(caseId)}
      size={720}
      rootClassName="evaluation-drawer"
      destroyOnHidden
      onClose={busy ? undefined : onClose}
      extra={detail ? (
        <div className="evaluation-drawer-actions">
          {detail.lifecycleStatus === 'ACTIVE' && detail.reviewStatus !== 'REVIEWED' ? (
            <Button icon={<Edit3 size={15} />} disabled={busy} onClick={() => onEdit(detail)}>编辑草稿</Button>
          ) : null}
          {detail.reviewStatus === 'DRAFT' && detail.lifecycleStatus === 'ACTIVE' ? (
            <Button
              icon={<Check size={15} />}
              loading={reviewMutation.isPending && reviewMutation.variables?.decision === 'APPROVE'}
              disabled={busy}
              onClick={() => reviewMutation.mutate({ detail, decision: 'APPROVE' })}
            >
              审核通过
            </Button>
          ) : null}
        </div>
      ) : null}
    >
      {detailQuery.isLoading ? (
        <div className="evaluation-loading"><Spin size="large" /></div>
      ) : detailQuery.isError || !detail ? (
        <div className="evaluation-error" role="alert">
          <div><strong>案例详情加载失败</strong><p>正文没有被加载，请重新请求详情。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => void detailQuery.refetch()}>重试</Button>
        </div>
      ) : (
        <div className="evaluation-detail-stack">
          <div className="evaluation-detail-title">
            <div>
              <span className={`evaluation-severity severity-${detail.severity.toLowerCase()}`}>{caseSeverityLabel(detail.severity)}</span>
              <h3>{detail.name}</h3>
              <p>{detail.category}</p>
            </div>
            <div className="evaluation-status-stack">
              <span className={`evaluation-status review-${detail.reviewStatus.toLowerCase()}`}>{caseReviewStatusLabel(detail.reviewStatus)}</span>
              <span className={`evaluation-status lifecycle-${detail.lifecycleStatus.toLowerCase()}`}>{caseLifecycleStatusLabel(detail.lifecycleStatus)}</span>
            </div>
          </div>
          <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
            <Descriptions.Item label="所属平台">{detail.platformId ?? '全局案例'}</Descriptions.Item>
            <Descriptions.Item label="评分方式">{detail.evaluatorType}</Descriptions.Item>
            <Descriptions.Item label="来源类型">{detail.sourceType}</Descriptions.Item>
            <Descriptions.Item label="来源引用">{detail.sourceRef ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="数据版本">#{detail.version}</Descriptions.Item>
            <Descriptions.Item label="更新时间">{new Date(detail.updatedAt).toLocaleString('zh-CN')}</Descriptions.Item>
          </Descriptions>
          <section className="evaluation-content-block">
            <h4>案例输入</h4>
            <pre>{detail.inputText}</pre>
          </section>
          <section className="evaluation-content-block">
            <h4>期望结果</h4>
            <pre>{JSON.stringify(detail.expected, null, 2)}</pre>
          </section>
          {detail.reviewStatus === 'DRAFT' && detail.lifecycleStatus === 'ACTIVE' ? (
            <div className="evaluation-detail-secondary-actions">
              <Popconfirm
                title="确定驳回这个案例？"
                description="驳回后仍可编辑，保存会重新回到草稿状态。"
                okText="确认驳回"
                cancelText="取消"
                okButtonProps={{ danger: true, loading: reviewMutation.isPending }}
                onConfirm={() => reviewMutation.mutate({ detail, decision: 'REJECT' })}
              >
                <Button danger icon={<X size={15} />} disabled={busy}>驳回案例</Button>
              </Popconfirm>
            </div>
          ) : null}
          {detail.lifecycleStatus === 'ACTIVE' ? (
            <div className="evaluation-danger-zone">
              <div><strong>归档案例</strong><p>归档后不可再编辑、审核或加入新的冻结版本。</p></div>
              <Popconfirm
                title="确定归档这个案例？"
                description="归档属于危险操作，请确认不再用于新的评测集。"
                okText="确认归档"
                cancelText="取消"
                okButtonProps={{ danger: true, loading: archiveMutation.isPending }}
                onConfirm={() => archiveMutation.mutate(detail)}
              >
                <Button danger icon={<Archive size={15} />} disabled={busy}>归档</Button>
              </Popconfirm>
            </div>
          ) : null}
        </div>
      )}
    </Drawer>
  );
}
