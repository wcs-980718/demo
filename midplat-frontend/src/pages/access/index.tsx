import { useEffect, useState } from 'react';
import { useNavigate } from '@umijs/max';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Input, Modal, Select, Table, message } from 'antd';
import { AppWindow, Building2, Copy, KeyRound, Plus, Server, ShieldCheck } from 'lucide-react';
import { createUuid } from '../../createUuid';
import { midplatApi } from '@/api/midplatApi';
import { accessApi, type AccessClient, type Consumer, type CredentialSummary, type Entitlement } from './accessApi';
import './access.css';

const statusPill = (status: string) => {
  const s = (status || '').toLowerCase();
  const tone = s === 'active' ? 'ok' : s === 'suspended' ? 'warn' : s === 'revoked' || s === 'disabled' ? 'err' : 'muted-st';
  return <span className={`status access-status ${tone}`}>{status}</span>;
};
const envLabel: Record<string, string> = { development: '开发环境', staging: '预发布', production: '生产环境' };

/** W5 客户与凭证：Consumer → Client（固定项目/环境）→ 多枚凭证 + 权限上限。secret 只展示一次。 */
export default function AccessPage() {
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [consumerType, setConsumerType] = useState('organization');
  const [consumerId, setConsumerId] = useState<string>();
  const [clientId, setClientId] = useState<string>();
  const [creatingConsumer, setCreatingConsumer] = useState(false);
  const [creatingClient, setCreatingClient] = useState(false);
  const [issuing, setIssuing] = useState(false);
  const [granting, setGranting] = useState<CredentialSummary | null>(null);
  const [oneTime, setOneTime] = useState<{ label: string; keyId: string; secret: string } | null>(null);

  const consumers = useQuery({ queryKey: ['access-consumers'], queryFn: accessApi.consumers, retry: false });
  const clients = useQuery({ queryKey: ['access-clients'], queryFn: () => accessApi.clients(), retry: false });
  const projects = useQuery({ queryKey: ['access-projects'], queryFn: midplatApi.listPlatforms, retry: false });
  const activeConsumer = consumers.data?.find(item => item.id === consumerId);
  const scopedClients = clients.data?.filter(item => !consumerId || item.consumerId === consumerId) || [];
  const selectedClient = scopedClients.find(item => item.id === clientId);
  const entitlements = useQuery({ queryKey: ['access-entitlements', clientId], queryFn: () => accessApi.entitlements(clientId!), enabled: !!clientId, retry: false });
  const credentials = useQuery({ queryKey: ['access-credentials', clientId], queryFn: () => accessApi.credentials(clientId!), enabled: !!clientId, retry: false });

  const projectName = (id: string) => projects.data?.find(project => project.id === id)?.name || id;
  const refresh = () => {
    void cache.invalidateQueries({ queryKey: ['access-clients'] });
    void cache.invalidateQueries({ queryKey: ['access-entitlements'] });
    void cache.invalidateQueries({ queryKey: ['access-credentials'] });
    void cache.invalidateQueries({ queryKey: ['access-grants'] });
  };
  const reveal = (result: { credential: CredentialSummary; secretAvailable: boolean; secret?: string }, title: string) => {
    refresh();
    if (result.secretAvailable && result.secret) setOneTime({ label: title, keyId: result.credential.keyId, secret: result.secret });
    else message.info('该凭证没有可再次展示的 secret；如首次领取失败，请撤销后重新签发。');
  };
  const createConsumer = useMutation({ mutationFn: accessApi.createConsumer, onSuccess: () => { setCreatingConsumer(false); void cache.invalidateQueries({ queryKey: ['access-consumers'] }); message.success('客户已创建'); }, onError: failure => message.error((failure as Error).message) });
  const createClient = useMutation({ mutationFn: accessApi.createClient, onSuccess: () => { setCreatingClient(false); refresh(); message.success('接入应用已创建，默认没有任何业务权限'); }, onError: failure => message.error((failure as Error).message) });
  const issue = useMutation({
    mutationFn: (values: { label: string; expiresInDays: number }) => accessApi.issueCredential(clientId!, {
      label: values.label,
      expiresAt: values.expiresInDays > 0 ? new Date(Date.now() + values.expiresInDays * 86400000).toISOString() : null,
      idempotencyKey: createUuid(),
    }),
    onSuccess: result => { setIssuing(false); reveal(result, '凭证已签发'); },
    onError: failure => message.error((failure as Error).message),
  });
  const revoke = useMutation({ mutationFn: (id: string) => accessApi.revokeCredential(id), onSuccess: () => { refresh(); message.success('凭证已撤销，撤销不可逆'); }, onError: failure => message.error((failure as Error).message) });
  const rotate = useMutation({
    mutationFn: (record: CredentialSummary) => accessApi.rotateCredential(record.id, record.label),
    onSuccess: result => reveal(result, '轮换完成'),
    onError: failure => message.error((failure as Error).message),
  });

  return <div className="access-page">
    <header className="page-head access-hero">
      <div>
        <p className="eyebrow">ACCESS CONTROL</p>
        <h2><ShieldCheck size={19} /> 客户与凭证</h2>
        <p className="sub">客户（Consumer）→ 接入应用（Client，固定项目与环境）→ 多枚凭证。凭证权限是接入应用上限的子集，空白即无权限。</p>
      </div>
      <div className="page-head-actions">
        <Button icon={<Plus size={15} />} onClick={() => setCreatingConsumer(true)}>新建客户</Button>
        <Button type="primary" icon={<Plus size={15} />} disabled={!consumers.data?.length} onClick={() => setCreatingClient(true)}>新建接入应用</Button>
      </div>
    </header>

    {consumers.error ? <Alert type="error" showIcon title="无法读取客户数据" description={(consumers.error as Error).message} /> : null}
    {clients.error ? <Alert type="error" showIcon title="无法读取接入应用" description={(clients.error as Error).message} /> : null}
    {selectedClient && entitlements.error ? <Alert type="error" showIcon title="无法读取权限上限" description={(entitlements.error as Error).message} /> : null}
    {selectedClient && credentials.error ? <Alert type="error" showIcon title="无法读取凭证列表" description={(credentials.error as Error).message} /> : null}

    <div className="access-grid">
      <section className="access-card" aria-label="客户">
        <h2>客户<span className="access-count">{consumers.data?.length ?? 0}</span></h2>
        <div className="access-list">
          {(consumers.data || []).map(item => (
            <button key={item.id} className={`access-item${item.id === consumerId ? ' active' : ''}`} onClick={() => { setConsumerId(item.id); setClientId(undefined); }}>
              <span className="access-item-ic">{item.type === 'organization' ? <Building2 size={14} /> : <Server size={14} />}</span>
              <span className="access-item-main"><strong>{item.name}</strong><small>{item.code} · {item.type === 'organization' ? '外部公司' : '内部系统'}</small></span>
              {statusPill(item.status)}
            </button>
          ))}
          {!consumers.isPending && !consumers.data?.length && <p className="access-empty">还没有客户。先为 A/B 公司各建一个档案。</p>}
        </div>
      </section>

      <section className="access-card" aria-label="接入应用">
        <h2>接入应用{activeConsumer ? <span className="access-h2-sub">· {activeConsumer.name}</span> : ''}<span className="access-count">{scopedClients.length}</span></h2>
        <div className="access-list">
          {scopedClients.map(item => (
            <button key={item.id} className={`access-item${item.id === clientId ? ' active' : ''}`} onClick={() => setClientId(item.id)}>
              <span className="access-item-ic"><AppWindow size={14} /></span>
              <span className="access-item-main"><strong>{item.name}</strong><small>{projectName(item.projectId)} · {envLabel[item.environment] || item.environment}</small></span>
              {statusPill(item.status)}
            </button>
          ))}
          {!scopedClients.length && <p className="access-empty">{activeConsumer ? '该客户还没有接入应用。' : '选择左侧客户查看其接入应用。'}</p>}
        </div>
      </section>

      <section className="access-card access-card-wide" aria-label="凭证与权限">
        <h2>凭证与权限{selectedClient ? <span className="access-h2-sub">· {selectedClient.name}</span> : ''}{selectedClient ? <span className="access-count">{credentials.data?.length ?? 0} 枚</span> : ''}</h2>
        {selectedClient ? <>
          <div className="access-toolbar">
            <Button type="primary" icon={<KeyRound size={15} />} onClick={() => setIssuing(true)}>签发凭证</Button>
            <span className="access-hint">权限上限：{entitlements.data?.length ? `${entitlements.data.length} 条` : '未配置（等于无权限）'}</span>
          </div>
          <EntitlementEditor client={selectedClient} entitlements={entitlements.data || []} onChanged={refresh} />
          <Table<CredentialSummary>
            rowKey="id" size="small"
            dataSource={credentials.data || []}
            columns={[
              { title: '标签', dataIndex: 'label' },
              { title: 'keyId', dataIndex: 'keyId', render: (value: string) => <code>{value}</code> },
              { title: '状态', dataIndex: 'status', render: statusPill },
              { title: '到期', dataIndex: 'expiresAt', render: (value: string | null) => value ? new Date(value).toLocaleDateString('zh-CN') : '长期' },
              { title: '操作', render: (_, record) => <div className="access-row-actions">
                <Button size="small" loading={rotate.isPending && rotate.variables?.id === record.id} onClick={() => rotate.mutate(record)}>轮换</Button>
                <Button size="small" onClick={() => setGranting(record)}>权限</Button>
                {record.status !== 'revoked' && <Button size="small" danger loading={revoke.isPending && revoke.variables === record.id} onClick={() => revoke.mutate(record.id)}>撤销</Button>}
              </div> },
            ]}
          />
        </> : <p className="access-empty">选择一个接入应用后管理其凭证。</p>}
      </section>
    </div>

    <Modal open={creatingConsumer} title="新建客户" okText="创建" cancelText="取消" onCancel={() => setCreatingConsumer(false)} onOk={() => (document.getElementById('consumer-form') as HTMLFormElement)?.requestSubmit()} confirmLoading={createConsumer.isPending}>
      <form id="consumer-form" className="access-form" onSubmit={event => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        createConsumer.mutate({ code: String(form.get('code') || '').trim(), name: String(form.get('name') || '').trim(), type: consumerType });
      }}>
        <label>客户编码<Input name="code" placeholder="例如 company-a" required maxLength={64} /></label>
        <label>客户名称<Input name="name" placeholder="例如 A 公司" required maxLength={128} /></label>
        <label>类型<Select value={consumerType} options={[{ value: 'organization', label: '外部公司' }, { value: 'internal_system', label: '内部系统' }]} onChange={setConsumerType} /></label>
      </form>
    </Modal>

    <ClientModal open={creatingClient} consumers={consumers.data || []} projects={projects.data || []} onClose={() => setCreatingClient(false)} onSubmit={values => createClient.mutate(values)} submitting={createClient.isPending} />

    <Modal open={issuing} title="签发凭证" okText="签发" cancelText="取消" onCancel={() => setIssuing(false)} onOk={() => (document.getElementById('issue-form') as HTMLFormElement)?.requestSubmit()} confirmLoading={issue.isPending}>
      <form id="issue-form" className="access-form" onSubmit={event => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        issue.mutate({ label: String(form.get('label') || '').trim(), expiresInDays: Number(form.get('expiresInDays') || 90) });
      }}>
        <p className="access-note">secret 高熵随机、只在本次响应展示一次；关闭窗口后无法再次查看。</p>
        <label>凭证标签<Input name="label" placeholder="例如 生产网关" required maxLength={128} /></label>
        <label>有效期（天，0 表示长期）<Input name="expiresInDays" type="number" min={0} max={3650} defaultValue={90} /></label>
      </form>
    </Modal>

    <Modal open={!!oneTime} title={oneTime ? `${oneTime.label} · 请立即保存` : ''} cancelText="关闭" okButtonProps={{ style: { display: 'none' } }} afterClose={() => issue.reset()} onCancel={() => setOneTime(null)} width={560}>
      {oneTime && <div className="access-secret">
        <Alert type="warning" showIcon title="这是唯一一次展示机会" description="凭证原文不落库、不再回显；请复制保存到密码管理器。" />
        <pre className="access-secret-box">{oneTime.secret}</pre>
        <Button type="primary" icon={<Copy size={15} />} onClick={async () => { await navigator.clipboard.writeText(oneTime.secret); message.success('已复制'); }}>复制凭证</Button>
        <code>keyId: {oneTime.keyId}</code>
      </div>}
    </Modal>

    <GrantModal credential={granting} entitlements={entitlements.data || []} onClose={() => setGranting(null)} onChanged={refresh} />
  </div>;
}

function ClientModal({ open, consumers, projects, onClose, onSubmit, submitting }: {
  open: boolean; consumers: Consumer[]; projects: { id: string; name: string }[];
  onClose: () => void; onSubmit: (values: { consumerId: string; projectId: string; environment: string; name: string; code: string }) => void; submitting: boolean;
}) {
  const [consumerId, setConsumerId] = useState<string>();
  const [projectId, setProjectId] = useState<string>();
  const [environment, setEnvironment] = useState('development');
  const [name, setName] = useState('');
  // 打开时重置上次输入，避免残留导致编码冲突或误提交。
  useEffect(() => { if (open) { setConsumerId(undefined); setProjectId(undefined); setEnvironment('development'); setName(''); } }, [open]);
  const valid = consumerId && projectId && name.trim();
  return <Modal open={open} title="新建接入应用" okText="创建" cancelText="取消" onCancel={onClose} okButtonProps={{ disabled: !valid, loading: submitting }} onOk={() => onSubmit({ consumerId: consumerId!, projectId: projectId!, environment, name: name.trim(), code: name.trim().toLowerCase().replace(/[^a-z0-9_-]+/g, '-').slice(0, 60) || createUuid().slice(0, 8) })}>
    <div className="access-form">
      <p className="access-note">一个接入应用固定一个客户、一个项目和运行环境；创建后不可迁移。新应用默认没有任何业务权限。</p>
      <label>客户<Select value={consumerId} options={consumers.map(item => ({ value: item.id, label: item.name, title: item.name }))} onChange={setConsumerId} placeholder="选择客户" /></label>
      <label>项目<Select value={projectId} options={projects.map(item => ({ value: item.id, label: item.name, title: item.name }))} onChange={setProjectId} placeholder="选择项目" /></label>
      <label>运行环境<Select value={environment} options={[{ value: 'development', label: '开发环境' }, { value: 'staging', label: '预发布环境' }, { value: 'production', label: '生产环境' }]} onChange={setEnvironment} /></label>
      <label>应用名称<Input value={name} onChange={event => setName(event.target.value)} maxLength={128} placeholder="例如 A 公司网关" /></label>
    </div>
  </Modal>;
}

function EntitlementEditor({ client, entitlements, onChanged }: { client: AccessClient; entitlements: Entitlement[]; onChanged: () => void }) {
  const [resourceKind, setResourceKind] = useState('agent.task');
  const [resourceId, setResourceId] = useState('');
  const [action, setAction] = useState('execute');
  const save = useMutation({
    mutationFn: () => accessApi.setEntitlements(client.id, [...entitlements.map(item => ({ resourceKind: item.resourceKind, resourceId: item.resourceId, action: item.action })), { resourceKind, resourceId: resourceId.trim(), action }]),
    onSuccess: () => { setResourceId(''); onChanged(); message.success('权限上限已更新'); },
    onError: failure => message.error((failure as Error).message),
  });
  const remove = useMutation({
    // 后端是增量语义：省略不等于撤销，必须显式发送 revoked。
    mutationFn: (id: string) => {
      const target = entitlements.find(item => item.id === id);
      if (!target) throw new Error('权限条目已不存在，请刷新');
      return accessApi.setEntitlements(client.id, [{ resourceKind: target.resourceKind, resourceId: target.resourceId, action: target.action, status: 'revoked' }]);
    },
    onSuccess: () => { onChanged(); message.success('权限上限已撤销'); },
    onError: failure => message.error((failure as Error).message),
  });
  return <div className="access-entitlements">
    <h3>接入应用权限上限</h3>
    <ul>{entitlements.map(item => <li key={item.id}><code>{item.resourceKind}:{item.resourceId}:{item.action}</code><Button size="small" type="link" danger onClick={() => remove.mutate(item.id)}>移除</Button></li>)}
      {!entitlements.length && <li className="access-empty">空白即无权限：新任务、新接口不会自动授权。</li>}</ul>
    <div className="access-ent-form">
      <Select value={resourceKind} onChange={setResourceKind} options={[
        { value: 'agent.task', label: '智能体任务' }, { value: 'api', label: '开放接口' },
        { value: 'model.chat', label: '模型对话' }, { value: 'model.embed', label: '向量' }, { value: 'model.rerank', label: '重排' },
      ]} />
      <Input value={resourceId} onChange={event => setResourceId(event.target.value)} placeholder={resourceKind === 'agent.task' ? '任务键，例如 summary' : resourceKind === 'api' ? '接口 ID' : '模型 ID'} maxLength={128} />
      <Select value={action} onChange={setAction} options={[{ value: 'execute', label: '执行' }, { value: 'read', label: '查询' }, { value: 'cancel', label: '取消' }, { value: 'invoke', label: '调用' }]} />
      <Button disabled={!resourceId.trim() || save.isPending} onClick={() => save.mutate()}>添加</Button>
    </div>
  </div>;
}

function GrantModal({ credential, entitlements, onClose, onChanged }: {
  credential: CredentialSummary | null; entitlements: Entitlement[]; onClose: () => void; onChanged: () => void;
}) {
  const [selected, setSelected] = useState<string[]>([]);
  const grants = useQuery({ queryKey: ['access-grants', credential?.id], queryFn: () => accessApi.grants(credential!.id), enabled: !!credential, retry: false });
  const currentActive = () => new Set((grants.data || []).filter(item => (item.status || '').toLowerCase() === 'active').map(item => item.entitlementId));
  // 每次打开按服务端当前授权初始化勾选，清除上一次弹窗的残留。
  useEffect(() => { if (credential) setSelected([...(grants.data || []).filter(item => (item.status || '').toLowerCase() === 'active').map(item => item.entitlementId)]); }, [credential?.id, grants.data]);
  const save = useMutation({
    // 差集语义：勾选 → 生效；未勾选但当前已生效 → 显式 REVOKED（后端增量语义，省略不撤销）。
    mutationFn: () => {
      const active = currentActive();
      // 只提交“生效勾选”与“当前生效但被取消”两类；从未授权且未勾选的条目无需下发。
      const commands = entitlements
        .filter(item => selected.includes(item.id) || active.has(item.id))
        .map(item => selected.includes(item.id)
          ? { entitlementId: item.id }
          : { entitlementId: item.id, status: 'revoked' as const });
      return accessApi.setGrants(credential!.id, commands);
    },
    onSuccess: () => { onChanged(); onClose(); message.success('凭证权限已更新（不超过接入应用上限）'); },
    onError: failure => message.error((failure as Error).message),
  });
  const loading = grants.isPending;
  return <Modal open={!!credential} title={credential ? `凭证权限 · ${credential.label}` : ''} okText="保存" cancelText="取消" onCancel={onClose} onOk={() => save.mutate()} confirmLoading={save.isPending} okButtonProps={{ disabled: loading || !entitlements.length }}>
    <p className="access-note">凭证权限只能从接入应用上限中选择；上限撤销后，凭证上的对应授权立即失效。</p>
    {loading ? <p className="access-empty">读取当前授权…</p> : null}
    {!loading && grants.error ? <Alert type="error" showIcon title="无法读取当前授权" description={(grants.error as Error).message} /> : null}
    {!loading && !grants.error && entitlements.length ? entitlements.map(item => (
      <label key={item.id} className="access-grant-row">
        <input type="checkbox" checked={selected.includes(item.id)} onChange={event => {
          setSelected(values => event.target.checked ? [...values, item.id] : values.filter(id => id !== item.id));
        }} />
        <code>{item.resourceKind}:{item.resourceId}:{item.action}</code>
      </label>
    )) : null}
    {!loading && !grants.error && !entitlements.length ? <Alert type="info" showIcon title="该接入应用还没有权限上限，请先在列表中添加。" /> : null}
  </Modal>;
}
