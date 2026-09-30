import { useMutation, useQuery } from '@tanstack/react-query';
import { Form, InputNumber, Modal, Select, Spin, message } from 'antd';
import { midplatApi } from '@/api/midplatApi';
import { evaluationApi } from './evaluationApi';
import {
  evaluationMutationErrorMessage,
  formatDatasetVersion,
  submitValidatedEvaluationForm,
} from './evaluationPresentation';
import type { EvaluationRunPayload, EvaluationRunSummary } from './evaluationTypes';

type FrozenVersionOption = {
  value: string;
  label: string;
};

type Props = {
  open: boolean;
  onClose: () => void;
  onCreated: (run: EvaluationRunSummary) => void;
};

export function RunCreateModal({ open, onClose, onCreated }: Props) {
  const [form] = Form.useForm<Omit<EvaluationRunPayload, 'modelId' | 'promptId'>>();
  const datasetsQuery = useQuery({
    queryKey: ['evaluation', 'run-create', 'frozen-datasets'],
    enabled: open,
    queryFn: async () => {
      const datasets = await evaluationApi.listDatasets();
      const details = await Promise.all(datasets.map((dataset) => evaluationApi.getDataset(dataset.id)));
      return details.flatMap<FrozenVersionOption>((dataset) => dataset.versions
        .filter((version) => version.status === 'FROZEN')
        .map((version) => {
          const label = `${dataset.name} · ${formatDatasetVersion(version)} · ${version.items.length} 题`;
          return { value: version.id, label, title: label };
        }));
    },
  });
  const platformsQuery = useQuery({
    queryKey: ['evaluation', 'run-create', 'platforms'],
    enabled: open,
    queryFn: midplatApi.listPlatforms,
    select: (platforms) => platforms.filter((platform) => platform.llmModelId && platform.promptId),
  });
  const createMutation = useMutation({
    mutationFn: (values: Omit<EvaluationRunPayload, 'modelId' | 'promptId'>) => {
      const platform = platformsQuery.data?.find((item) => item.id === values.platformId);
      if (!platform?.llmModelId || !platform.promptId) {
        throw new Error('所选平台未完整绑定 LLM 和 Prompt，请先完成平台配置。');
      }
      return evaluationApi.createRun({
        ...values,
        modelId: platform.llmModelId,
        promptId: platform.promptId,
      });
    },
    onSuccess: (run) => {
      message.success('评测任务已创建，正在排队执行');
      form.resetFields();
      onCreated(run);
    },
    onError: (error: unknown) => message.error(evaluationMutationErrorMessage(error)),
  });

  const loading = datasetsQuery.isLoading || platformsQuery.isLoading;
  const loadError = datasetsQuery.isError || platformsQuery.isError;

  return (
    <Modal
      title="创建评测任务"
      open={open}
      width={680}
      okText="创建并执行"
      cancelText="取消"
      confirmLoading={createMutation.isPending}
      okButtonProps={{ disabled: loading || loadError || createMutation.isPending }}
      cancelButtonProps={{ disabled: createMutation.isPending }}
      mask={{ closable: !createMutation.isPending }}
      destroyOnHidden
      onCancel={() => {
        if (!createMutation.isPending) onClose();
      }}
      onOk={() => void submitValidatedEvaluationForm(form.validateFields, (values) => createMutation.mutate(values))}
    >
      {loading ? (
        <div className="evaluation-loading evaluation-loading-compact" aria-label="正在加载运行配置"><Spin /></div>
      ) : loadError ? (
        <div className="evaluation-error" role="alert">
          <div><strong>运行配置加载失败</strong><p>关闭窗口后重试，或检查中台服务连接。</p></div>
        </div>
      ) : (
        <Form
          form={form}
          layout="vertical"
          className="evaluation-form"
          requiredMark="optional"
          initialValues={{ temperature: 0.2, maxTokens: 2_048, timeoutMs: 30_000 }}
        >
          <Form.Item
            name="datasetVersionId"
            label="冻结评测集版本"
            rules={[{ required: true, message: '请选择冻结评测集版本' }]}
            extra="运行始终基于内容不可变的冻结快照。"
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={datasetsQuery.data}
              placeholder={datasetsQuery.data?.length ? '选择评测集版本' : '暂无可用的冻结版本'}
              notFoundContent="暂无冻结版本，请先在评测集页完成冻结"
            />
          </Form.Item>
          <Form.Item
            name="platformId"
            label="评测平台"
            rules={[{ required: true, message: '请选择评测平台' }]}
            extra="仅显示同时绑定 LLM 和 Prompt 的平台，执行时会固化当前配置快照。"
          >
            <Select
              showSearch
              optionFilterProp="label"
              options={(platformsQuery.data ?? []).map((platform) => ({ value: platform.id, label: platform.name }))}
              placeholder={platformsQuery.data?.length ? '选择已配置平台' : '暂无完整绑定的平台'}
              notFoundContent="请先为平台绑定 LLM 和 Prompt"
            />
          </Form.Item>
          <div className="evaluation-form-grid evaluation-run-parameters">
            <Form.Item name="temperature" label="Temperature" rules={[{ required: true, message: '请填写 Temperature' }]}>
              <InputNumber min={0} max={2} step={0.1} precision={2} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="maxTokens" label="最大输出 Token" rules={[{ required: true, message: '请填写最大 Token' }]}>
              <InputNumber min={1} max={32_768} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="timeoutMs" label="单题超时（ms）" rules={[{ required: true, message: '请填写超时时间' }]}>
              <InputNumber min={1} max={120_000} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <div className="evaluation-form-note">创建后立即排队执行。模型输出只会在任务详情中按需展示。</div>
        </Form>
      )}
    </Modal>
  );
}
