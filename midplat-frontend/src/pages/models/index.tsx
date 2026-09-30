import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Spin, message } from 'antd';
import { Copy, Info, Plus, Search, Wifi } from 'lucide-react';
import { midplatApi, type AiModel, type ModelPayload } from '@/api/midplatApi';
import { PageHeader } from '@/components/PageHeader';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';
import { formatModelPrice, normalizeModelPricing } from './modelPricing';

const KIND_LABEL: Record<AiModel['kind'], string> = {
  llm: 'LLM',
  embedding: 'Embedding',
  rerank: 'Rerank',
};

const KINDS: Array<{ key: string; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'llm', label: 'LLM' },
  { key: 'embedding', label: 'Embedding' },
  { key: 'rerank', label: 'Rerank' },
];

const CHIP_CLASS: Record<AiModel['kind'], string> = {
  llm: 'chip-llm',
  embedding: 'chip-emb',
  rerank: 'chip-prompt',
};

export default function ModelsPage() {
  const queryClient = useQueryClient();
  const modelsQuery = useQuery({ queryKey: ['models'], queryFn: midplatApi.listModels });
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<AiModel | null>(null);
  const [form] = Form.useForm<ModelPayload>();
  const [filter, setFilter] = useState('all');
  const [search, setSearch] = useState('');

  const dataSource = (modelsQuery.data ?? []).filter(
    (item) => filter === 'all' || item.kind === filter,
  ).filter(
    (item) => !search || item.name.toLowerCase().includes(search.toLowerCase()) || item.model.toLowerCase().includes(search.toLowerCase()) || item.baseUrl.toLowerCase().includes(search.toLowerCase()),
  );

  const saveMutation = useMutation({
    mutationFn: async (payload: ModelPayload) => {
      const normalizedPayload = normalizeModelPricing({ ...payload, capabilities: payload.capabilities ? { toolCalls: payload.capabilities.toolCalls ?? null, jsonObject: payload.capabilities.jsonObject ?? null } : undefined });
      if (editing) {
        return midplatApi.updateModel(editing.id, normalizedPayload);
      }
      return midplatApi.createModel(normalizedPayload);
    },
    onSuccess: async () => {
      message.success(editing ? '模型已更新' : '模型已创建');
      setOpen(false);
      setEditing(null);
      await queryClient.invalidateQueries({ queryKey: ['models'] });
    },
    onError: (error: Error) => message.error(error.message),
  });
  const pingMutation = useMutation({
    mutationFn: midplatApi.pingModel,
    onSuccess: async (model) => {
      message.success(model.pingStatus === 'ok' ? `${model.name} 连通性检查通过` : `${model.name} 连通性检查失败`);
      await queryClient.invalidateQueries({ queryKey: ['models'] });
    },
    onError: (error: Error) => message.error(error.message),
  });
  const deleteMutation = useMutation({
    mutationFn: midplatApi.deleteModel,
    onSuccess: async () => {
      message.success('已删除模型');
      await queryClient.invalidateQueries({ queryKey: ['models'] });
    },
    onError: (error: Error) => message.error(error.message),
  });

  return (
    <div className="page-shell">
      <PageHeader
        title="模型管理"
        description="集中维护 LLM、Embedding、Rerank。AI 工作台中的项目只能引用这里已经配置的模型。"
        actions={
          <Button
            type="primary"
            icon={<Plus size={14} />}
            onClick={() => {
              setEditing(null);
              form.resetFields();
              form.setFieldsValue({ kind: 'llm', baseUrl: 'http://127.0.0.1:4000/v1', capabilities: { toolCalls: null, jsonObject: null } });
              setOpen(true);
            }}
          >
            新增模型
          </Button>
        }
      />
      <div className="toolbar">
        <div className="tabs">
          {KINDS.map((tab) => (
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
          placeholder="搜索名称、模型名、Base URL"
          allowClear
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="toolbar-search"
          style={{ width: 260 }}
        />
      </div>
      {modelsQuery.isLoading ? (
        <div className="loading-panel"><Spin size="large" /></div>
      ) : dataSource.length === 0 ? (
        <div className="empty">{search ? '没有匹配的模型，请调整搜索词。' : '这一类还没有模型，点右上角新增。'}</div>
      ) : (
        <div className="entry-grid">
          {dataSource.map((row) => (
            <article key={row.id} className="entry-card">
              <div className="entry-top">
                <div>
                  <div style={{ fontSize: 14, fontWeight: 600, lineHeight: 1.3 }}>{row.name}</div>
                  <div style={{ fontSize: 13, color: 'var(--muted)', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 280 }}>
                    {row.model || row.baseUrl.slice(0, 40)}
                  </div>
                </div>
                <span className={`chip ${CHIP_CLASS[row.kind]}`}>{KIND_LABEL[row.kind]}</span>
              </div>
              <div className="codebox" style={{ position: 'relative' }}>
                <div>{row.baseUrl}</div>
                  <div style={{ opacity: 0.7 }}>{row.model}</div>
                <Button
                  size="small"
                  type="text"
                  icon={<Copy size={12} />}
                  aria-label={`复制${row.name}连接信息`}
                  style={{ position: 'absolute', top: 6, right: 6, width: 24, height: 24, minWidth: 24, padding: 0, color: 'var(--muted)' }}
                  onClick={() => {
                    navigator.clipboard.writeText(`${row.baseUrl}\n${row.model}`);
                    message.success('已复制');
                  }}
                />
              </div>
              <div style={{ display: 'grid', gap: 3, color: 'var(--muted)', fontSize: 12 }}>
                <span>输入：{formatModelPrice(row.inputPricePerMillion)}</span>
                <span>输出：{formatModelPrice(row.outputPricePerMillion)}</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 'auto' }}>
                <Button
                  size="small"
                  ghost
                  icon={<Wifi size={14} />}
                  loading={pingMutation.isPending && pingMutation.variables === row.id}
                  onClick={() => pingMutation.mutate(row.id)}
                >
                  测试连通
                </Button>
                <Button
                  size="small"
                  onClick={() => {
                    setEditing(row);
                    form.setFieldsValue({
                      name: row.name,
                      kind: row.kind,
                      model: row.model,
                      baseUrl: row.baseUrl,
                      inputPricePerMillion: row.inputPricePerMillion,
                      outputPricePerMillion: row.outputPricePerMillion,
                      capabilities: row.capabilities ?? { toolCalls: null, jsonObject: null },
                    });
                    setOpen(true);
                  }}
                >
                  编辑
                </Button>
                <Popconfirm
                  title="确定删除该模型？"
                  description="平台里已套用该模型的位置需要重新选择。"
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
        title={editing ? '编辑模型' : '新增模型'}
        open={open}
        onCancel={() => setOpen(false)}
        onOk={() => form.validateFields().then((values) => saveMutation.mutate(values))}
        confirmLoading={saveMutation.isPending}
        okText={editing ? '保存修改' : '创建模型'}
        cancelText="取消"
        width={720}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <section className="settings-section">
            <SettingsSectionHeader step={1} title="模型身份" description="先确认用户在项目配置中看到的名称、用途类型和服务端模型标识。" />
            <div className="settings-form-grid">
              <Form.Item name="name" label="显示名称" rules={[{ required: true, message: '请填写显示名称' }]}><Input placeholder="例如 院内通用对话模型" /></Form.Item>
              <Form.Item name="kind" label="用途类型" rules={[{ required: true, message: '请选择用途类型' }]}>
                <Select
                  options={[
                    { value: 'llm', label: 'LLM · 对话生成' },
                    { value: 'embedding', label: 'Embedding · 向量化' },
                    { value: 'rerank', label: 'Rerank · 结果重排' },
                  ]}
                />
              </Form.Item>
            </div>
            <Form.Item name="model" label="服务端模型标识" rules={[{ required: true, message: '请填写服务端模型标识' }]}><Input placeholder="例如 deepseek-v4-flash" /></Form.Item>
          </section>
          <section className="settings-section">
            <SettingsSectionHeader step={2} title="连接与认证" description="填写模型服务地址和调用凭证；保存后回到列表执行连通性测试。" />
            <Form.Item name="baseUrl" label="服务地址（Base URL）" rules={[{ required: true, message: '请填写服务地址' }]}><Input placeholder="http://127.0.0.1:4000/v1" /></Form.Item>
            <Form.Item name="apiKey" label="API Key"><Input.Password placeholder={editing ? '留空表示不修改现有密钥' : '如服务需要认证，请填写'} /></Form.Item>
            <Form.Item noStyle shouldUpdate={(before, after) => before.kind !== after.kind}>
              {({ getFieldValue }) => getFieldValue('kind') === 'llm' ? <div className="settings-form-grid">
                <Form.Item name={['capabilities', 'toolCalls']} getValueProps={value => ({ value: value == null ? undefined : String(value) })} normalize={value => value == null ? null : value === 'true'} label="工具调用能力" extra="按供应商能力或实际测试声明；未声明时不能发布包含工具的任务。">
                  <Select allowClear placeholder="尚未声明" options={[{ value: 'true', label: '已确认支持' }, { value: 'false', label: '不支持' }]} />
                </Form.Item>
                <Form.Item name={['capabilities', 'jsonObject']} getValueProps={value => ({ value: value == null ? undefined : String(value) })} normalize={value => value == null ? null : value === 'true'} label="JSON 对象输出" extra="未声明或不支持时，发布校验会拒绝 JSON 输出任务。">
                  <Select allowClear placeholder="尚未声明" options={[{ value: 'true', label: '已确认支持' }, { value: 'false', label: '不支持' }]} />
                </Form.Item>
              </div> : null}
            </Form.Item>
            <div className="settings-form-impact"><Info size={15} /><span><strong>保存后的下一步：</strong>返回模型列表点击“测试连通”，通过后再到 AI 工作台项目中套用。</span></div>
          </section>
          <section className="settings-section">
            <SettingsSectionHeader step={3} title="评测计价" description="价格用于评测任务成本核算；0 作为未计价哨兵。" />
            <div className="settings-form-grid">
              <Form.Item
                name="inputPricePerMillion"
                label="每百万输入 Token 单价（元）"
                extra="请填写供应商当前计价；留空或填 0 都会以“未计价”保存。"
              >
                <InputNumber min={0} precision={8} stringMode={false} placeholder="例如 1.00000000" style={{ width: '100%' }} />
              </Form.Item>
              <Form.Item
                name="outputPricePerMillion"
                label="每百万输出 Token 单价（元）"
                extra="请填写供应商当前计价；留空或填 0 都会以“未计价”保存。"
              >
                <InputNumber min={0} precision={8} stringMode={false} placeholder="例如 2.00000000" style={{ width: '100%' }} />
              </Form.Item>
            </div>
          </section>
        </Form>
      </Modal>
    </div>
  );
}
