import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input, Modal, Spin, Table, type TableProps, message } from 'antd';
import { Database, Eye, Plus, RefreshCw } from 'lucide-react';
import { DatasetDetailDrawer } from './DatasetDetailDrawer';
import { evaluationApi } from './evaluationApi';
import {
  evaluationMutationErrorMessage,
  getEvaluationEmptyState,
  resetEvaluationFormWhenOpened,
  submitValidatedEvaluationForm,
} from './evaluationPresentation';
import type { EvaluationDatasetPayload, EvaluationDatasetSummary } from './evaluationTypes';

export function DatasetsPanel() {
  const queryClient = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [selectedDatasetId, setSelectedDatasetId] = useState<string | null>(null);
  const [form] = Form.useForm<EvaluationDatasetPayload>();
  const datasetsQuery = useQuery({
    queryKey: ['evaluation', 'datasets'],
    queryFn: evaluationApi.listDatasets,
  });
  const createMutation = useMutation({
    mutationFn: evaluationApi.createDataset,
    onSuccess: async (created) => {
      message.success('评测集已创建，并生成 V1 草稿');
      setCreateOpen(false);
      form.resetFields();
      setSelectedDatasetId(created.id);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['evaluation', 'datasets'] }),
        queryClient.invalidateQueries({ queryKey: ['evaluation', 'overview'] }),
      ]);
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const columns: TableProps<EvaluationDatasetSummary>['columns'] = [
    {
      title: '评测集',
      dataIndex: 'name',
      key: 'name',
      render: (_, row) => (
        <div className="evaluation-table-title">
          <strong>{row.name}</strong>
          <span>{row.description || '暂无说明'}</span>
        </div>
      ),
    },
    {
      title: '版本信息',
      key: 'versions',
      render: () => <span className="evaluation-muted">打开详情查看草稿、冻结版本和条目</span>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 112,
      render: (_, row) => (
        <Button icon={<Eye size={15} />} onClick={() => setSelectedDatasetId(row.id)}>查看详情</Button>
      ),
    },
  ];

  const openCreate = () => {
    setCreateOpen(true);
  };

  return (
    <section className="evaluation-section" aria-labelledby="evaluation-datasets-heading">
      <div className="evaluation-section-head">
        <div>
          <p className="evaluation-kicker">DATASET VERSIONS</p>
          <h3 id="evaluation-datasets-heading">评测集</h3>
          <p>把已审核案例组织为草稿，冻结后形成内容不可变的运行快照。</p>
        </div>
        <Button type="primary" icon={<Plus size={16} />} onClick={openCreate}>创建评测集</Button>
      </div>

      {datasetsQuery.isLoading ? (
        <div className="evaluation-loading" aria-label="正在加载评测集"><Spin size="large" /></div>
      ) : datasetsQuery.isError ? (
        <div className="evaluation-error" role="alert">
          <div><strong>评测集加载失败</strong><p>列表不会逐项请求版本详情，可以安全重试。</p></div>
          <Button icon={<RefreshCw size={15} />} onClick={() => void datasetsQuery.refetch()}>重新加载</Button>
        </div>
      ) : datasetsQuery.data?.length ? (
        <>
          <div className="evaluation-table-wrap">
            <Table<EvaluationDatasetSummary>
              rowKey="id"
              className="surface-table"
              columns={columns}
              dataSource={datasetsQuery.data}
              pagination={false}
            />
          </div>
          <div className="evaluation-mobile-list">
            {datasetsQuery.data.map((dataset) => (
              <article className="evaluation-mobile-card" key={dataset.id}>
                <div className="evaluation-mobile-card-head">
                  <div><strong>{dataset.name}</strong><span>{dataset.description || '暂无说明'}</span></div>
                </div>
                <p className="evaluation-muted">打开详情查看版本状态、快照和条目。</p>
                <Button icon={<Eye size={15} />} onClick={() => setSelectedDatasetId(dataset.id)}>查看详情</Button>
              </article>
            ))}
          </div>
        </>
      ) : (
        <DatasetEmpty onCreate={openCreate} />
      )}

      <Modal
        title="创建评测集"
        open={createOpen}
        okText="创建评测集"
        cancelText="取消"
        confirmLoading={createMutation.isPending}
        okButtonProps={{ disabled: createMutation.isPending }}
        cancelButtonProps={{ disabled: createMutation.isPending }}
        mask={{ closable: !createMutation.isPending }}
        destroyOnHidden
        afterOpenChange={(open) => resetEvaluationFormWhenOpened(open, form.resetFields)}
        onCancel={() => {
          if (!createMutation.isPending) setCreateOpen(false);
        }}
        onOk={() => {
          void submitValidatedEvaluationForm(form.validateFields, (values) => createMutation.mutate({
            name: values.name.trim(),
            description: values.description?.trim() || null,
          }));
        }}
      >
        <Form form={form} layout="vertical" requiredMark="optional" className="evaluation-form">
          <Form.Item name="name" label="评测集名称" rules={[
            { required: true, whitespace: true, message: '请填写评测集名称' },
            { max: 128, message: '名称不能超过 128 个字符' },
          ]}>
            <Input autoFocus maxLength={128} showCount placeholder="例如：医疗问答核心回归集" />
          </Form.Item>
          <Form.Item name="description" label="用途说明" rules={[{ max: 1_000, message: '说明不能超过 1,000 个字符' }]}>
            <Input.TextArea rows={4} maxLength={1_000} showCount placeholder="说明覆盖范围、使用场景和维护责任" />
          </Form.Item>
          <div className="evaluation-form-note">创建后自动生成 V1 草稿；只有加入已审核且启用中的案例后才能冻结。</div>
        </Form>
      </Modal>
      <DatasetDetailDrawer datasetId={selectedDatasetId} onClose={() => setSelectedDatasetId(null)} />
    </section>
  );
}

function DatasetEmpty({ onCreate }: { onCreate: () => void }) {
  const empty = getEvaluationEmptyState('datasets');
  return (
    <div className="evaluation-empty">
      <Database size={26} aria-hidden="true" />
      <div><strong>{empty.title}</strong><p>{empty.description}</p></div>
      <Button type="primary" onClick={onCreate}>{empty.actionLabel}</Button>
    </div>
  );
}
