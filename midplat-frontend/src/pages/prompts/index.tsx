import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Form, Input, Modal, Popconfirm, Select, Spin, message } from 'antd';
import { Info, Plus, Search } from 'lucide-react';
import { midplatApi, type PromptItem, type PromptPayload } from '@/api/midplatApi';
import { PageHeader } from '@/components/PageHeader';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';

const SLOT_LABEL: Record<string, string> = {
  'agent-role': '智能体角色',
  'rag-qa': '知识库问答',
  prelabel: '预标注',
  answer: '问数回答',
  app: '应用通用',
};

const SLOTS: Array<{ key: string; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'agent-role', label: '智能体角色' },
  { key: 'rag-qa', label: '知识库问答' },
  { key: 'prelabel', label: '预标注' },
  { key: 'answer', label: '问数回答' },
];

const CHIP_CLASS: Record<string, string> = {
  'agent-role': 'chip-llm',
  'rag-qa': 'chip-llm',
  prelabel: 'chip-emb',
  answer: 'chip-prompt',
  app: 'chip-prompt',
};

export default function PromptsPage() {
  const queryClient = useQueryClient();
  const promptsQuery = useQuery({ queryKey: ['prompts'], queryFn: midplatApi.listPrompts });
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<PromptItem | null>(null);
  const [form] = Form.useForm<PromptPayload>();
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');
  const [validationError, setValidationError] = useState('');

  const submit = () => {
    form.validateFields().then((values) => {
      setValidationError('');
      saveMutation.mutate(values);
    }).catch((info: { errorFields?: Array<{ errors: string[] }> }) => {
      const errors = (info.errorFields ?? []).flatMap((field) => field.errors).filter(Boolean);
      setValidationError(errors.join('；') || '请检查必填项');
    });
  };

  const dataSource = (promptsQuery.data ?? []).filter(
    (item) => filter === 'all' || item.slot === filter,
  ).filter(
    (item) => !search || item.name.toLowerCase().includes(search.toLowerCase()) || item.body.toLowerCase().includes(search.toLowerCase()),
  );

  const saveMutation = useMutation({
    mutationFn: async (payload: PromptPayload) => {
      if (editing) {
        return midplatApi.updatePrompt(editing.id, payload);
      }
      return midplatApi.createPrompt(payload);
    },
    onSuccess: async () => {
      message.success(editing ? '提示词已更新' : '提示词已创建');
      setOpen(false);
      setEditing(null);
      setValidationError('');
      await queryClient.invalidateQueries({ queryKey: ['prompts'] });
    },
    onError: (error: Error) => message.error(error.message),
  });
  const deleteMutation = useMutation({
    mutationFn: midplatApi.deletePrompt,
    onSuccess: async () => {
      message.success('已删除提示词');
      await queryClient.invalidateQueries({ queryKey: ['prompts'] });
    },
    onError: (error: Error) => message.error(error.message),
  });

  return (
    <div className="page-shell">
      <PageHeader
        title="提示词管理"
        description="统一维护可复用模板。智能体角色模板可导入项目草稿，模板后续修改不会自动覆盖演示发布快照。"
        actions={
          <Button
            type="primary"
            icon={<Plus size={14} />}
            onClick={() => {
              setEditing(null);
              setValidationError('');
              form.resetFields();
              form.setFieldsValue({ slot: 'app', version: 'v1' });
              setOpen(true);
            }}
          >
            新增提示词
          </Button>
        }
      />
      <div className="toolbar">
        <div className="tabs">
          {SLOTS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              className={filter === tab.key ? 'active' : ''}
              onClick={() => setFilter(tab.key)}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <Input
          prefix={<Search size={14} />}
          placeholder="搜索名称、正文"
          allowClear
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="toolbar-search"
          style={{ width: 260 }}
        />
      </div>
      {promptsQuery.isLoading ? (
        <div className="loading-panel"><Spin size="large" /></div>
      ) : dataSource.length === 0 ? (
        <div className="empty">{search ? '没有匹配的提示词，请调整搜索词。' : '这一槽位还没有提示词，点右上角新增。'}</div>
      ) : (
        <div className="entry-grid">
          {dataSource.map((row) => (
            <article key={row.id} className="entry-card">
              <div className="entry-top">
                <div>
                  <div style={{ fontSize: 14, fontWeight: 600, lineHeight: 1.3 }}>{row.name}</div>
                  <div style={{ display: 'flex', gap: 6, marginTop: 4, alignItems: 'center' }}>
                    <span className={`chip ${CHIP_CLASS[row.slot] ?? ''}`}>{SLOT_LABEL[row.slot] ?? row.slot}</span>
                    <span className="mono" style={{ fontSize: 11, color: 'var(--muted)' }}>{row.version}</span>
                  </div>
                </div>
              </div>
              <div className="mono" style={{ fontSize: 12, lineHeight: 1.5, overflow: 'hidden', textOverflow: 'ellipsis', display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical' }}>
                {row.body.slice(0, 60)}{row.body.length > 60 ? '…' : ''}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 'auto' }}>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(row);
                    setValidationError('');
                    form.setFieldsValue({
                      name: row.name,
                      slot: row.slot,
                      version: row.version,
                      body: row.body,
                    });
                    setOpen(true);
                  }}
                >
                  编辑
                </Button>
                <Popconfirm
                  title="确定删除该提示词？"
                  description="平台里已套用该提示词的位置需要重新选择。"
                  okText="删除"
                  cancelText="取消"
                  onConfirm={() => deleteMutation.mutate(row.id)}
                  okButtonProps={{ loading: deleteMutation.isPending && deleteMutation.variables === row.id }}
                >
                  <Button className="project-card-danger" size="small" danger ghost>删除</Button>
                </Popconfirm>
              </div>
            </article>
          ))}
        </div>
      )}
      <Modal
        title={editing ? '编辑提示词' : '新增提示词'}
        open={open}
        onCancel={() => { setOpen(false); setValidationError(''); }}
        onOk={submit}
        confirmLoading={saveMutation.isPending}
        okText={editing ? '保存修改' : '创建提示词'}
        cancelText="取消"
        width={720}
        destroyOnHidden
      >
        {validationError ? <Alert role="alert" type="error" showIcon title="请完善必填信息" description={validationError} style={{ marginBottom: 16 }} /> : null}
        <Form form={form} layout="vertical">
          <section className="settings-section">
            <SettingsSectionHeader step={1} title="用途与版本" description="先让使用者看懂提示词用于哪个业务环节，再维护名称和版本。" />
            <div className="settings-form-grid">
              <Form.Item name="name" label="提示词名称" rules={[{ required: true, message: '请填写提示词名称' }]}><Input placeholder="例如 知识库问答-默认" /></Form.Item>
              <Form.Item name="slot" label="业务槽位" rules={[{ required: true, message: '请选择业务槽位' }]}>
                <Select
                  disabled={Boolean(editing)}
                  options={Object.entries(SLOT_LABEL).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>
            </div>
            <Form.Item name="version" label="版本标识"><Input placeholder="v1" /></Form.Item>
          </section>
          <section className="settings-section">
            <SettingsSectionHeader step={2} title="提示词正文" description="正文是实际生效内容，应清楚写明角色、边界、输入要求和输出约束。" />
            <Form.Item name="body" label="正文内容" rules={[{ required: true, message: '请填写提示词正文' }]}>
              <Input.TextArea rows={9} style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 12, minHeight: 220 }} />
            </Form.Item>
            <div className="settings-form-impact"><Info size={15} /><span><strong>生效范围：</strong>保存后不会自动替换项目配置；智能体项目在“模型与提示词”中导入模板，再发布草稿。</span></div>
          </section>
        </Form>
      </Modal>
    </div>
  );
}
