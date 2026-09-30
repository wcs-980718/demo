import { useEffect, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Radio, Select, Switch } from 'antd';
import { Plus, Trash2 } from 'lucide-react';
import type { CapabilityAssetContent, CapabilityAssetDetail, CapabilityAssetKind } from '@/api/midplatApi';

export const ASSET_KIND_LABEL: Record<CapabilityAssetKind, string> = {
  skill: '技能', tool: '工具', knowledge: '知识库', data: '数据源',
};

const KIND_OPTIONS = (Object.keys(ASSET_KIND_LABEL) as CapabilityAssetKind[])
  .map((value) => ({ value, label: ASSET_KIND_LABEL[value] }));

export type AssetFormPayload = {
  kind: CapabilityAssetKind;
  name: string;
  description?: string;
  status?: 'DRAFT' | 'ACTIVE';
  content: CapabilityAssetContent;
};

type FormValues = {
  kind: CapabilityAssetKind;
  name: string;
  description?: string;
  status: 'DRAFT' | 'ACTIVE';
  body?: string;
  toolName?: string;
  toolDescription?: string;
  url?: string;
  method?: 'GET' | 'POST';
  readOnly?: boolean;
  secretRef?: string;
  parametersText?: string;
  documents?: { title?: string; text?: string }[];
};

function initialValues(initial: CapabilityAssetDetail | null): Partial<FormValues> {
  if (!initial) return { kind: 'skill', status: 'DRAFT', method: 'GET', readOnly: true };
  const content = initial.content ?? {};
  return {
    kind: initial.kind,
    name: initial.name,
    description: initial.description ?? undefined,
    status: initial.status === 'ACTIVE' ? 'ACTIVE' : 'DRAFT',
    body: content.body,
    toolName: content.toolName,
    toolDescription: content.description,
    url: content.url,
    method: (content.method as 'GET' | 'POST') ?? 'GET',
    readOnly: content.readOnly ?? true,
    secretRef: typeof content.secretRef === 'string' ? content.secretRef : undefined,
    parametersText: content.parameters ? JSON.stringify(content.parameters, null, 2) : '{\n  "type": "object",\n  "properties": {}\n}',
    documents: (content.documents ?? []).map((doc) => ({ ...doc })),
  };
}

export function AssetFormModal({ open, initial, submitting, onCancel, onSubmit }: {
  open: boolean;
  initial: CapabilityAssetDetail | null;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (payload: AssetFormPayload) => void;
}) {
  const [form] = Form.useForm<FormValues>();
  const editing = Boolean(initial);
  const [kind, setKind] = useState<CapabilityAssetKind>('skill');

  useEffect(() => {
    if (!open) return;
    const values = initialValues(initial);
    setKind(values.kind ?? 'skill');
    form.setFieldsValue(values);
  }, [open, initial, form]);

  const finish = (values: FormValues) => {
    let content: CapabilityAssetContent;
    if (values.kind === 'skill') {
      content = { body: (values.body ?? '').trim() };
    } else if (values.kind === 'tool') {
      let parameters: unknown;
      try {
        parameters = JSON.parse(values.parametersText ?? '');
      } catch {
        form.setFields([{ name: 'parametersText', errors: ['参数模式不是合法 JSON'] }]);
        return;
      }
      if (typeof parameters !== 'object' || parameters === null || Array.isArray(parameters)
        || (parameters as { type?: string }).type !== 'object') {
        form.setFields([{ name: 'parametersText', errors: ['参数模式必须是 type=object 的 JSON 对象'] }]);
        return;
      }
      content = {
        toolName: (values.toolName ?? '').trim(),
        description: (values.toolDescription ?? '').trim(),
        url: (values.url ?? '').trim(),
        method: values.method ?? 'GET',
        readOnly: Boolean(values.readOnly),
        parameters: parameters as Record<string, unknown>,
      };
      if (values.secretRef && values.secretRef.trim()) content.secretRef = values.secretRef.trim();
    } else {
      content = {
        documents: (values.documents ?? []).map((doc) => ({ title: (doc.title ?? '').trim(), text: (doc.text ?? '').trim() })),
      };
    }
    onSubmit({
      kind: values.kind,
      name: values.name.trim(),
      description: values.description?.trim() || undefined,
      status: values.status,
      content,
    });
  };

  return (
    <Modal
      open={open}
      title={editing ? `编辑${ASSET_KIND_LABEL[initial!.kind]}资产 · ${initial!.name}` : '新建能力资产'}
      width={760}
      okText={editing ? '保存新修订' : '创建资产'}
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      onCancel={onCancel}
      onOk={() => form.validateFields().then(finish)}
    >
      {editing ? (
        <Alert type="info" showIcon title="保存会创建新的修订" description="原修订与哈希保持不变；引用「跟随最新」的项目授权会镜像新修订，固定修订授权不受影响。" style={{ marginBottom: 12 }} />
      ) : (
        <Alert type="info" showIcon title="资产默认创建为草稿" description="知识库/数据源为兼容内嵌文档/数据资产：正文随中台修订管理，不代表已接入外部知识库或数据平台。请不要在正文保存任何密钥。" style={{ marginBottom: 12 }} />
      )}
      <Form form={form} layout="vertical" onValuesChange={(changed) => { if (changed.kind) setKind(changed.kind); }}>
        <div className="capability-asset-form-grid">
          <Form.Item name="kind" label="资产类型" rules={[{ required: true }]}>
            <Select options={KIND_OPTIONS} disabled={editing} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请填写名称' }, { max: 128 }]}>
            <Input placeholder="例如 病历书写规范" maxLength={128} />
          </Form.Item>
        </div>
        <Form.Item name="description" label="说明" rules={[{ max: 500, message: '说明不能超过 500 字' }]}>
          <Input.TextArea rows={2} placeholder="面向使用者的一句话说明（可选）" />
        </Form.Item>
        {!editing && (
          <Form.Item name="status" label="创建状态" rules={[{ required: true }]}>
            <Radio.Group options={[{ value: 'DRAFT', label: '草稿' }, { value: 'ACTIVE', label: '启用' }]} />
          </Form.Item>
        )}
        {kind === 'skill' && (
          <Form.Item name="body" label="技能指令" rules={[{ required: true, message: '请填写技能指令' }]}>
            <Input.TextArea rows={8} placeholder="运行时会注入到任务指令中的技能正文" />
          </Form.Item>
        )}
        {kind === 'tool' && (
          <>
            <div className="capability-asset-form-grid">
              <Form.Item name="toolName" label="工具名" rules={[
                { required: true, message: '请填写工具名' },
                { pattern: /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/, message: '仅限字母、数字、下划线，且不能以数字开头' },
              ]}>
                <Input placeholder="例如 get_lab_result" maxLength={64} />
              </Form.Item>
              <Form.Item name="method" label="请求方法" rules={[{ required: true }]}>
                <Select options={[{ value: 'GET', label: 'GET' }, { value: 'POST', label: 'POST（必须只读）' }]} />
              </Form.Item>
            </div>
            <Form.Item name="url" label="接口地址" rules={[
              { required: true, message: '请填写接口地址' },
              { pattern: /^https?:\/\/[^\s/?#]+[^\s?#]*$/, message: '必须是 http/https 地址，且不能带查询参数、锚点或账号信息' },
            ]}>
              <Input placeholder="https://service.example.com/tools/lookup" />
            </Form.Item>
            <Form.Item name="toolDescription" label="用途说明" rules={[{ required: true, message: '请填写用途说明' }, { max: 1000 }]}>
              <Input placeholder="告诉模型这个工具做什么、什么时候该调用" />
            </Form.Item>
            <div className="capability-asset-form-grid">
              <Form.Item name="readOnly" label="只读声明" valuePropName="checked" rules={[{ required: true }]}>
                <Switch checkedChildren="只读" unCheckedChildren="有写风险" />
              </Form.Item>
              <Form.Item name="secretRef" label="凭证引用（可选）" extra="只填写部署配置中的引用名；严禁在此或正文中填写密钥本身。">
                <Input placeholder="例如 deployment:lab-service-token" maxLength={128} />
              </Form.Item>
            </div>
            <Form.Item name="parametersText" label="参数模式（JSON Schema，type=object）" rules={[{ required: true, message: '请填写参数模式' }]}>
              <Input.TextArea rows={6} spellCheck={false} />
            </Form.Item>
          </>
        )}
        {(kind === 'knowledge' || kind === 'data') && (
          <Form.List name="documents">
            {(fields, { add, remove }) => (
              <>
                <div className="capability-asset-doc-head">
                  <span>内嵌文档（{fields.length}/200，兼容内嵌文档/数据资产，非外部知识库连接）</span>
                  <Button size="small" icon={<Plus size={13} />} disabled={fields.length >= 200} onClick={() => add({ title: '', text: '' })}>添加文档</Button>
                </div>
                {fields.map((field, index) => (
                  <div className="capability-asset-doc-row" key={field.key}>
                    <Form.Item name={[field.name, 'title']} label={`文档 ${index + 1} 标题`} rules={[{ required: true, message: '请填写标题' }, { max: 200 }]}>
                      <Input maxLength={200} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'text']} label="可检索内容" rules={[{ required: true, message: '请填写内容' }, { max: 50000 }]}>
                      <Input.TextArea rows={4} />
                    </Form.Item>
                    <Button className="capability-asset-doc-remove" size="small" danger ghost icon={<Trash2 size={13} />} disabled={fields.length <= 1} onClick={() => remove(field.name)}>移除</Button>
                  </div>
                ))}
                {fields.length === 0 && <div className="capability-asset-doc-empty">至少需要 1 条真实文档。</div>}
              </>
            )}
          </Form.List>
        )}
      </Form>
    </Modal>
  );
}
