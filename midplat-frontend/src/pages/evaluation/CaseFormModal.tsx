import { useEffect } from 'react';
import { Form, Input, Modal, Select } from 'antd';
import type { PlatformItem } from '@/api/midplatApi';
import type { EvaluationCaseDetail } from './evaluationTypes';
import { submitValidatedEvaluationForm, type CaseFormValues } from './evaluationPresentation';

const SEVERITY_OPTIONS = [
  { value: 'CRITICAL', label: '关键' },
  { value: 'HIGH', label: '严重' },
  { value: 'MEDIUM', label: '中等' },
  { value: 'LOW', label: '低风险' },
];

const EVALUATOR_OPTIONS = [
  { value: 'EXACT', label: '精确匹配' },
  { value: 'CONTAINS_ALL', label: '包含全部关键内容' },
  { value: 'JSON_VALID', label: 'JSON 结构有效' },
  { value: 'NUMERIC_RANGE', label: '数值范围' },
  { value: 'FORBIDDEN_TERMS', label: '禁用词检查' },
  { value: 'MANUAL', label: '人工评分' },
];

export function CaseFormModal({
  open,
  editing,
  platforms,
  submitting,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  editing: EvaluationCaseDetail | null;
  platforms: PlatformItem[];
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (values: CaseFormValues) => void;
}) {
  const [form] = Form.useForm<CaseFormValues>();

  useEffect(() => {
    if (!open) return;
    if (editing) {
      form.setFieldsValue({
        platformId: editing.platformId ?? undefined,
        name: editing.name,
        category: editing.category,
        severity: editing.severity,
        inputText: editing.inputText,
        expectedText: JSON.stringify(editing.expected, null, 2),
        evaluatorType: editing.evaluatorType,
      });
      return;
    }
    form.resetFields();
    form.setFieldsValue({
      severity: 'MEDIUM',
      evaluatorType: 'EXACT',
      expectedText: '""',
    });
  }, [editing, form, open]);

  return (
    <Modal
      title={editing ? '编辑案例草稿' : '创建评测案例'}
      open={open}
      width={760}
      okText={editing ? '保存为草稿' : '创建案例'}
      cancelText="取消"
      confirmLoading={submitting}
      okButtonProps={{ disabled: submitting }}
      cancelButtonProps={{ disabled: submitting }}
      mask={{ closable: !submitting }}
      keyboard={!submitting}
      destroyOnHidden
      onCancel={onCancel}
      onOk={() => void submitValidatedEvaluationForm(form.validateFields, onSubmit)}
    >
      <Form form={form} layout="vertical" requiredMark="optional" className="evaluation-form">
        {editing && editing.sourceType !== 'MANUAL' ? (
          <div className="evaluation-form-note">
            此案例来源于 {editing?.sourceType}，保存时保留原始来源标识 {editing?.sourceRef ?? '—'}。
          </div>
        ) : null}
        <div className="evaluation-form-grid">
          <Form.Item name="name" label="案例名称" rules={[
            { required: true, whitespace: true, message: '请填写案例名称' },
            { max: 128, message: '案例名称不能超过 128 个字符' },
          ]}>
            <Input autoFocus placeholder="例如：出院随访建议准确性" maxLength={128} showCount />
          </Form.Item>
          <Form.Item name="category" label="业务分类" rules={[
            { required: true, whitespace: true, message: '请填写业务分类' },
            { max: 64, message: '业务分类不能超过 64 个字符' },
          ]}>
            <Input placeholder="例如：医疗问答" maxLength={64} />
          </Form.Item>
          <Form.Item name="platformId" label="所属平台">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder="不限定平台"
              options={platforms.map((platform) => ({ value: platform.id, label: platform.name }))}
            />
          </Form.Item>
          <Form.Item name="severity" label="严重级别" rules={[{ required: true, message: '请选择严重级别' }]}>
            <Select options={SEVERITY_OPTIONS} />
          </Form.Item>
        </div>
        <Form.Item name="inputText" label="案例输入" rules={[
          { required: true, whitespace: true, message: '请填写模型输入' },
          { max: 20_000, message: '案例输入不能超过 20,000 个字符' },
        ]}>
          <Input.TextArea rows={6} placeholder="填写实际发送给模型或智能体的输入内容" maxLength={20_000} showCount />
        </Form.Item>
        <div className="evaluation-form-grid evaluation-form-grid-evaluator">
          <Form.Item name="evaluatorType" label="评分方式" rules={[{ required: true, message: '请选择评分方式' }]}>
            <Select options={EVALUATOR_OPTIONS} />
          </Form.Item>
          <div className="evaluation-field-help">
            <strong>评分提示</strong>
            <span>期望结果统一使用 JSON；人工评分会在任务结果中进入待复核队列。</span>
          </div>
        </div>
        <Form.Item
          name="expectedText"
          label="期望结果（JSON）"
          validateTrigger="onBlur"
          rules={[
            { required: true, whitespace: true, message: '请填写期望结果' },
            {
              validator: async (_, value: string | undefined) => {
                if (!value) return;
                try {
                  JSON.parse(value);
                } catch {
                  throw new Error('期望结果必须是合法 JSON');
                }
              },
            },
          ]}
        >
          <Input.TextArea
            rows={7}
            className="evaluation-code-input"
            placeholder={'例如：{"answer":"需要复诊"}'}
            maxLength={20_000}
            showCount
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
