import { useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Form, Input, InputNumber, Modal, Select, Switch } from 'antd';
import { midplatApi } from '@/api/midplatApi';
import {
  submitValidatedEvaluationForm,
  toGatePolicyPayload,
  type GatePolicyFormValues,
} from './evaluationPresentation';
import type { EvaluationGatePolicy, EvaluationGatePolicyPayload } from './evaluationTypes';

type Props = {
  open: boolean;
  policy: EvaluationGatePolicy | null;
  pending: boolean;
  onClose: () => void;
  onSubmit: (payload: EvaluationGatePolicyPayload) => void;
};

export function GatePolicyModal({ open, policy, pending, onClose, onSubmit }: Props) {
  const [form] = Form.useForm<GatePolicyFormValues>();
  const platformsQuery = useQuery({
    queryKey: ['evaluation', 'gates', 'platforms'],
    queryFn: midplatApi.listPlatforms,
    enabled: open,
  });

  useEffect(() => {
    if (!open) return;
    if (policy) {
      form.setFieldsValue({
        name: policy.name,
        platformId: policy.platformId,
        category: policy.category,
        minPassRate: policy.minPassRate,
        maxCostGrowthPercent: policy.maxCostGrowthPercent,
        maxAverageLatencyMs: policy.maxAverageLatencyMs,
        maxP95LatencyMs: policy.maxP95LatencyMs,
        requireCriticalCasesPassed: policy.requireCriticalCasesPassed,
        enabled: policy.enabled,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({
        minPassRate: 95,
        maxCostGrowthPercent: 10,
        maxAverageLatencyMs: 3_000,
        maxP95LatencyMs: 6_000,
        requireCriticalCasesPassed: true,
        enabled: true,
      });
    }
  }, [form, open, policy]);

  return (
    <Modal
      title={policy ? '编辑发布门禁策略' : '创建发布门禁策略'}
      open={open}
      width={720}
      okText={policy ? '保存策略' : '创建策略'}
      cancelText="取消"
      confirmLoading={pending}
      okButtonProps={{ disabled: pending }}
      cancelButtonProps={{ disabled: pending }}
      mask={{ closable: !pending }}
      destroyOnHidden
      onCancel={() => { if (!pending) onClose(); }}
      onOk={() => void submitValidatedEvaluationForm(
        form.validateFields,
        (values) => onSubmit(toGatePolicyPayload(values)),
      )}
    >
      <Form form={form} layout="vertical" className="evaluation-form" requiredMark="optional">
        <Form.Item name="name" label="策略名称" rules={[{ required: true, whitespace: true, message: '请填写策略名称' }, { max: 128, message: '名称不能超过 128 个字符' }]}>
          <Input autoFocus maxLength={128} showCount placeholder="例如：医疗问答生产发布门禁" />
        </Form.Item>
        <div className="evaluation-form-grid">
          <Form.Item name="platformId" label="平台作用域" extra="留空表示不限平台。">
            <Select allowClear showSearch optionFilterProp="label" loading={platformsQuery.isLoading} options={(platformsQuery.data ?? []).map((platform) => ({ value: platform.id, label: platform.name }))} placeholder="全部平台" />
          </Form.Item>
          <Form.Item name="category" label="案例分类作用域" rules={[{ max: 64, message: '分类不能超过 64 个字符' }]} extra="留空表示不限分类。">
            <Input maxLength={64} placeholder="全部分类" />
          </Form.Item>
          <Form.Item name="minPassRate" label="最低通过率（%）" rules={[{ required: true, message: '请填写最低通过率' }]}>
            <InputNumber min={0} max={100} precision={2} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="maxCostGrowthPercent" label="最大成本增长（%）" rules={[{ required: true, message: '请填写最大成本增长' }]}>
            <InputNumber min={0} precision={8} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="maxAverageLatencyMs" label="最大平均延迟（ms）" rules={[{ required: true, message: '请填写最大平均延迟' }]}>
            <InputNumber min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="maxP95LatencyMs" label="最大 P95 延迟（ms）" rules={[{ required: true, message: '请填写最大 P95 延迟' }]}>
            <InputNumber min={0} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        </div>
        <div className="evaluation-switch-grid">
          <Form.Item name="requireCriticalCasesPassed" label="关键案例必须通过" valuePropName="checked" extra="开启后，CRITICAL 案例失败会阻断发布。"><Switch checkedChildren="是" unCheckedChildren="否" /></Form.Item>
          <Form.Item name="enabled" label="策略启用" valuePropName="checked" extra="停用策略不能生成新的门禁决策。"><Switch checkedChildren="启用" unCheckedChildren="停用" /></Form.Item>
        </div>
      </Form>
    </Modal>
  );
}
