import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Form, Input, InputNumber, Modal, Popconfirm, Select, Spin, Switch, message } from 'antd';
import { useLocation, useNavigate } from '@umijs/max';
import { Bot, Check, CheckCircle2, ChevronLeft, Copy, ExternalLink, Key, Plus, RefreshCw, Settings2, Trash2 } from 'lucide-react';
import { midplatApi, type AgentRuntimeView, type AiModel, type MenuItem, type PlatformItem, type PlatformPayload, type PromptItem, type RuntimeSettings } from '@/api/midplatApi';
import { PlatformDeliveryStatus } from './PlatformDeliveryStatus';
import { AgentConfigSyncCard } from './AgentConfigSyncCard';
import { fusionApi } from '@/pages/agent-hub/fusionApi';
import { agentPlatformManagementRoute } from '@/agentPlatformConfig';
import { copyText } from '@/copyText';
import { IconPicker } from '@/components/IconPicker';
import { LucideIcon } from '@/components/LucideIcon';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';
import { agentPromptSpec, hotUpdateStatus, isAgentManagedProject, projectConfigTabs, supportsHotUpdate, type ProjectConfigTab } from '@/pages/platforms/apiCatalog';
import { ProjectApiConfig } from '@/pages/platforms/ProjectApiConfig';
import { ExceptionHistoryPanel } from '@/pages/evaluation/exception/ExceptionHistoryPanel';
import { visibleWorkspaceView } from '@/pages/platforms/workspaceView';

type WizardValues = PlatformPayload;
type PlatformView = 'table' | ProjectConfigTab['key'];

function ManagedProjectSummary({ project, environment, ready, auxiliaryModels }: { project: string; environment: string; ready: boolean; auxiliaryModels: string }) {
  const key = ['fusion-project', project, environment];
  const config = useQuery({ queryKey: [...key, 'config'], queryFn: () => fusionApi.config(project, environment), enabled: ready, refetchInterval: 5000, retry: false });
  const releases = useQuery({ queryKey: [...key, 'releases'], queryFn: () => fusionApi.releases(project, environment), enabled: ready, refetchInterval: 5000, retry: false });
  const active = releases.data?.find(release => release.id === config.data?.activeReleaseId);
  const modelIds = active?.snapshot.tasks.flatMap(task => [task.modelRevisionId, ...task.nodes.flatMap(node => node.modelRevisionId ? [node.modelRevisionId] : [])]) || [];
  const names = Array.from(new Set(modelIds)).map(id => active?.snapshot.models[id]?.content.model || '模型修订不可用');
  const state = !ready ? '部署准备中' : config.error || releases.error ? '配置读取失败' : config.isPending || releases.isPending ? '读取运行配置…' : config.data?.activeReleaseId && !active ? '正在同步运行版本…' : '尚未上线';
  return <>
    <div><dt>模型</dt><dd>{[active ? `LLM → ${names.join(' / ')}` : state, auxiliaryModels].filter(Boolean).join('；')}</dd></div>
    <div><dt>运行配置</dt><dd>{active ? `R${active.sequence} · ${active.snapshot.tasks.length} 个任务 · 模型与提示词统一管理` : state}</dd></div>
  </>;
}

export type ProjectWorkspaceCategory = {
  menu: MenuItem;
  description: string;
};

export default function PlatformsPage({ category }: { category: ProjectWorkspaceCategory }) {
  const queryClient = useQueryClient();
  const fusionStatus = useQuery({ queryKey: ['fusion-status'], queryFn: fusionApi.status, retry: false });
  const location = useLocation();
  const navigate = useNavigate();
  const searchParams = new URLSearchParams(location.search);
  const requestedPlatformId = searchParams.get('platformId');
  const requestedView = searchParams.get('view');
  // 环境以后端 runtimeEnvironment 为准，URL 参数只作覆盖，避免卡片状态与面板内容用不同环境判定
  const fusionEnvironment = searchParams.get('environment') || fusionStatus.data?.runtimeEnvironment || 'development';
  const fusionBindings = useQuery({ queryKey: ['fusion-bindings', fusionEnvironment], queryFn: () => fusionApi.bindings(fusionEnvironment), enabled: !!fusionStatus.data?.enabled, retry: false });
  const unifiedAgentConfig = (id: string) => fusionStatus.data?.enabled ? !!fusionBindings.data?.some(b => b.project_id === id) : false;
  const openedPlatformId = useRef<string | null>(null);
  const platformsQuery = useQuery({ queryKey: ['platforms'], queryFn: midplatApi.listPlatforms });
  const modelsQuery = useQuery({ queryKey: ['models'], queryFn: midplatApi.listModels });
  const promptsQuery = useQuery({ queryKey: ['prompts'], queryFn: midplatApi.listPrompts });
  const [wizardOpen, setWizardOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [created, setCreated] = useState<PlatformItem | null>(null);
  const [view, setView] = useState<PlatformView>('table');
  const [current, setCurrent] = useState<PlatformItem | null>(null);
  const [form] = Form.useForm<WizardValues>();
  const [modelForm] = Form.useForm<WizardValues>();
  const [runtimeForm] = Form.useForm<RuntimeSettings>();

  const models = modelsQuery.data ?? [];
  const prompts = promptsQuery.data ?? [];
  const allRows = platformsQuery.data ?? [];
  const categoryPlatformIds = category.menu.platformIds?.length
    ? category.menu.platformIds
    : category.menu.platformId ? [category.menu.platformId] : [];
  const rows = categoryPlatformIds
    .map((id) => allRows.find((item) => item.id === id))
    .filter((item): item is PlatformItem => Boolean(item));
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['platforms'] });

  const createMutation = useMutation({
    mutationFn: (payload: PlatformPayload) => midplatApi.createPlatformInMenu(category.menu.id, payload),
    onSuccess: async (platform) => {
      setCreated(platform);
      setStep(1);
      await Promise.all([
        invalidate(),
        queryClient.invalidateQueries({ queryKey: ['menus'] }),
      ]);
    },
    onError: (error: Error) => message.error(error.message),
  });
  const deleteMutation = useMutation({
    mutationFn: (platformId: string) => midplatApi.deletePlatformInMenu(category.menu.id, platformId),
    onSuccess: async () => {
      message.success('项目已删除');
      await Promise.all([
        invalidate(),
        queryClient.invalidateQueries({ queryKey: ['menus'] }),
      ]);
    },
    onError: (error: Error) => message.error(error.message),
  });
  const updateMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: PlatformPayload }) => midplatApi.updatePlatform(id, payload),
    onSuccess: async (platform) => {
      message.success('目标配置已保存，生效结果请查看下发状态');
      await queryClient.invalidateQueries({ queryKey: ['platform-delivery', platform.id] });
      setCurrent(platform);
      await invalidate();
    },
    onError: (error: Error) => message.error(error.message),
  });
  const runtimeMutation = useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: Partial<RuntimeSettings> }) => midplatApi.updateRuntime(id, payload),
    onSuccess: async () => { message.success('目标配置已保存，生效结果请查看下发状态'); await queryClient.invalidateQueries({ queryKey: ['platform-delivery', current?.id] }); },
    onError: (error: Error) => message.error(error.message),
  });
  const saveProjectProfile = async (row: PlatformItem) => {
    const values = await modelForm.validateFields();
    updateMutation.mutate({
      id: row.id,
      payload: {
        name: values.name,
        icon: values.icon,
        entryUrl: values.entryUrl,
        llmModelId: row.llmModelId ?? undefined,
        embeddingModelId: row.embeddingModelId ?? undefined,
        rerankModelId: row.rerankModelId ?? undefined,
        promptId: row.promptId ?? undefined,
      },
    });
  };
  const openView = async (next: PlatformView, row: PlatformItem) => {
    setCurrent(row);
    setView(next);
    if (next === 'models') {
      modelForm.setFieldsValue({
        name: row.name,
        icon: row.icon,
        entryUrl: row.entryUrl ?? undefined,
      });
    }
    if (next === 'runtime') {
      try {
        runtimeForm.setFieldsValue(await midplatApi.getRuntime(row.id));
      } catch (error) {
        message.error(error instanceof Error ? error.message : '读取检索配置失败');
      }
    }
  };

  useEffect(() => {
    if (!requestedPlatformId || platformsQuery.isLoading || openedPlatformId.current === requestedPlatformId) return;
    const requestedPlatform = rows.find((item) => item.id === requestedPlatformId);
    if (!requestedPlatform) return;
    openedPlatformId.current = requestedPlatformId;
    if (requestedView === 'grants' || requestedView === 'prompts') {
      navigate(`/agent-hub/agents?project=${encodeURIComponent(requestedPlatformId)}`, { replace: true });
      return;
    }
    const tabKeys = projectConfigTabs(requestedPlatform).map((tab) => tab.key as string);
    if (requestedView && tabKeys.includes(requestedView)) void openView(requestedView as PlatformView, requestedPlatform);
    else void openView('models', requestedPlatform);
  }, [platformsQuery.isLoading, requestedPlatformId, requestedView, rows]);

  const boundModels = (row: PlatformItem) => {
    const parts = [
      row.llmModelId ? `LLM → ${modelName(models, row.llmModelId)}` : null,
      row.embeddingModelId ? `Embedding → ${modelName(models, row.embeddingModelId)}` : null,
      row.rerankModelId ? `Rerank → ${modelName(models, row.rerankModelId)}` : null,
    ].filter(Boolean);
    return parts.length ? parts.join('；') : '—';
  };

  const { view: visibleView, selected } = visibleWorkspaceView(view, current, rows);
  const categoryIdRef = useRef(category.menu.id);

  useEffect(() => {
    if (categoryIdRef.current === category.menu.id) return;
    categoryIdRef.current = category.menu.id;
    setView('table');
    setCurrent(null);
    openedPlatformId.current = null;
  }, [category.menu.id]);

  return (
    <div className="page-shell">
      {visibleView === 'table' ? (
        <>
          <div className="page-head">
            <div>
              <h2>{category.menu.name}</h2>
              <p className="sub">{category.description} 这里维护项目入口、接口、凭证和异常。模型、提示词与规则提示词在智能体开发的「智能体配置」里直接选择。</p>
            </div>
            <Button
              type="primary"
              icon={<Plus size={16} />}
              onClick={() => {
                form.resetFields();
                form.setFieldsValue({ icon: 'Sparkles' });
                setCreated(null);
                setStep(0);
                setWizardOpen(true);
              }}
            >
              登记新项目
            </Button>
          </div>
          {platformsQuery.isLoading ? (
            <div className="loading-panel"><Spin size="large" /></div>
          ) : rows.length === 0 ? (
            <div className="empty">当前分类还没有项目，点击右上角“登记新项目”添加。</div>
          ) : (
            <div className="entry-grid">
              {rows.map((row) => {
                const hot = hotUpdateStatus(row);
                const promptSpec = agentPromptSpec(row.id);
                return (
                  <article key={row.id} className="entry-card application-entry-card">
                    <div className="application-entry-head">
                      <span className="application-entry-icon"><LucideIcon name={row.icon} size={20} /></span>
                      <div>
                        <h3>{row.name}</h3>
                        <p>{row.id}</p>
                      </div>
                      <em className={`application-runtime-pill ${hot.cls === 'ok' ? 'healthy' : 'warning'}`}>
                        <b />
                        {hot.text}
                      </em>
                    </div>
                    <dl className="application-entry-details">
                      <div>
                        <dt>入口</dt>
                        <dd className="mono">
                          {row.entryUrl ? (
                            <a
                              className="entry-url"
                              href={row.entryUrl}
                              target="_blank"
                              rel="noopener noreferrer"
                              onClick={(event) => event.stopPropagation()}
                            >
                              {row.entryUrl}
                            </a>
                          ) : '未配置'}
                        </dd>
                      </div>
                      {unifiedAgentConfig(row.id) ? <ManagedProjectSummary project={row.id} environment={fusionEnvironment}
                        ready={fusionBindings.data?.some(binding => binding.project_id === row.id && binding.status === 'ready') || false}
                        auxiliaryModels={[row.embeddingModelId ? `Embedding → ${modelName(models, row.embeddingModelId)}` : '', row.rerankModelId ? `Rerank → ${modelName(models, row.rerankModelId)}` : ''].filter(Boolean).join('；')} /> : <>
                      <div>
                        <dt>{promptSpec ? '智能体' : '模型'}</dt>
                        <dd>{promptSpec ? '项目独立部署 · 共用配置草稿' : boundModels(row)}</dd>
                      </div>
                      <div><dt>提示词</dt><dd>{promptSpec ? `SOUL + ${promptSpec.skillLabel}` : promptName(prompts, row.promptId) || '未绑定'}</dd></div>
                      </>}
                    </dl>
                    <div className={`application-entry-actions${row.entryUrl ? ' has-open' : ''}`}>
                      {row.entryUrl ? (
                        <Button
                          type="primary"
                          size="small"
                          icon={<ExternalLink size={14} />}
                          onClick={() => window.open(row.entryUrl!, '_blank', 'noopener,noreferrer')}
                        >
                          打开项目
                        </Button>
                      ) : null}
                      <Button type={row.entryUrl ? 'default' : 'primary'} size="small" icon={<Settings2 size={14} />} onClick={() => openView('models', row)}>项目配置</Button>
                      <Popconfirm
                        title="确认删除该项目？"
                        description={promptSpec
                          ? '仅删除数智大脑中的项目登记，不会删除智能体开发中的共享智能体。'
                          : '删除后该项目的凭证、模型与提示词绑定将一并失效。'}
                        okText="删除"
                        cancelText="取消"
                        onConfirm={() => deleteMutation.mutate(row.id)}
                        okButtonProps={{ loading: deleteMutation.isPending && deleteMutation.variables === row.id }}
                      >
                        <Button size="small" danger ghost icon={<Trash2 size={14} />}>删除</Button>
                      </Popconfirm>
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </>
      ) : null}

      {visibleView !== 'table' && selected ? (
        <>
          <button
            type="button"
            className="back"
            onClick={() => {
              setView('table');
              setCurrent(null);
              if (requestedPlatformId) {
                navigate(location.pathname, { replace: true });
              }
            }}
          >
            <ChevronLeft size={16} />
            返回{category.menu.name}
          </button>
          <nav className="settings-nav" aria-label={`${selected.name}配置导航`}>
            {projectConfigTabs(selected).map((tab) => (
              <button
                key={tab.key}
                type="button"
                className={view === tab.key ? 'active' : ''}
                aria-current={view === tab.key ? 'page' : undefined}
                onClick={() => openView(tab.key, selected)}
              >
                <LucideIcon name={tab.icon} size={15} />
                {tab.label}
              </button>
            ))}
          </nav>
          {(selected.id === 'plat-kb' && view === 'runtime') || (!unifiedAgentConfig(selected.id) && !isAgentManagedProject(selected)) ? <PlatformDeliveryStatus platformId={selected.id} /> : null}
          {view === 'models' ? (
            isAgentManagedProject(selected) ? (
              <AgentManagedConfig
                platform={selected}
                form={modelForm}
                saving={updateMutation.isPending}
                onManage={(agent) => navigate(agentPlatformManagementRoute(agent))}
                onSave={() => saveProjectProfile(selected)}
              />
            ) : (
              <ProjectBasics
                platform={selected}
                form={modelForm}
                saving={updateMutation.isPending}
                onSave={() => saveProjectProfile(selected)}
              />
            )
          ) : null}
          {view === 'runtime' ? (
            <RuntimeConfig
              platform={selected}
              form={runtimeForm}
              saving={runtimeMutation.isPending}
              onSave={async () => {
                const values = await runtimeForm.validateFields();
                runtimeMutation.mutate({ id: selected.id, payload: values });
              }}
              onGoModels={() => navigate(`/agent-hub/agents?project=${encodeURIComponent(selected.id)}`)}
            />
          ) : null}
          {view === 'apis' ? <ProjectApiConfig platform={selected} /> : null}
          {view === 'access' ? <AccessConfig platform={selected} /> : null}
          {view === 'exceptions' ? (
            <ExceptionHistoryPanel
              fixedPlatform={{ id: selected.id, name: selected.name }}
              embedded
            />
          ) : null}
        </>
      ) : null}

      <Modal title="登记新项目" open={wizardOpen} onCancel={() => setWizardOpen(false)} footer={null} width={640} destroyOnHidden>
        <div className="steps">
          {[0, 1].map((idx) => (
            <span key={idx} className={`steps-item ${idx < step ? 'done' : ''} ${idx === step ? 'on' : ''}`}>
              <span className="steps-circle">
                {idx < step ? <CheckCircle2 size={16} /> : idx + 1}
              </span>
              <span>{['建档', '完成'][idx]}</span>
            </span>
          ))}
        </div>
        <Form form={form} layout="vertical">
          {step === 0 ? (
            <>
              <Form.Item name="name" label="项目名称" rules={[{ required: true, message: '请填写名称' }]}><Input placeholder="例如 病历质控助手" /></Form.Item>
              <Form.Item name="icon" label="图标"><IconPicker /></Form.Item>
              <Form.Item name="entryUrl" label="入口地址（可选）"><Input placeholder="https://" /></Form.Item>
              <p className="note">项目创建后会自动加入“{category.menu.name}”分类。模型、提示词与规则提示词在智能体开发的「智能体配置」里直接选择。</p>
            </>
          ) : null}
          {step === 1 && created ? (
            <div className="access-stack">
              <p style={{ fontSize: 13, color: 'var(--muted)', margin: 0 }}>项目登记完成，暂未签发凭证。接入应用与凭证统一在「客户与凭证」页创建：凭证高熵随机、只展示一次、支持轮换与撤销。</p>
              <div className="codebox">{`POST /api/runtime/chat
Authorization: Bearer <在「客户与凭证」签发的凭证>

{ "input": "用户问题" }`}</div>
              <Button type="primary" onClick={() => { setWizardOpen(false); navigate('/access'); }}>前往客户与凭证签发</Button>
              <p className="note">项目已加入“{category.menu.name}”。入口、接口和凭证在当前分类维护；模型、提示词与规则提示词请到智能体开发的「智能体配置」里选择。</p>
            </div>
          ) : null}
        </Form>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
          {step === 0 ? (
            <Popconfirm
              title="确认登记项目？"
              description="只登记项目名称、图标和入口。模型与提示词稍后在智能体开发里配置。"
              okText="登记项目"
              cancelText="取消"
              onConfirm={async () => { await form.validateFields(['name']); createMutation.mutate(form.getFieldsValue(true)); }}
              okButtonProps={{ loading: createMutation.isPending }}
            >
              <Button type="primary" loading={createMutation.isPending}>登记项目</Button>
            </Popconfirm>
          ) : null}
          {step === 1 ? <Button type="primary" icon={<Check size={14} />} onClick={() => setWizardOpen(false)}>完成</Button> : null}
        </div>
      </Modal>
    </div>
  );
}

function modelName(models: AiModel[], id?: string | null) {
  return models.find((item) => item.id === id)?.name;
}

function promptName(prompts: PromptItem[], id?: string | null) {
  const item = prompts.find((prompt) => prompt.id === id);
  return item ? `${item.name}` : '';
}

function hotLabel(platform: PlatformItem) {
  return hotUpdateStatus(platform);
}

function AgentManagedConfig({
  platform, form, saving, onSave, onManage,
}: {
  platform: PlatformItem;
  form: ReturnType<typeof Form.useForm<WizardValues>>[0];
  saving: boolean;
  onSave: () => void;
  onManage: (agent: string) => void;
}) {
  const runtimeQuery = useQuery({
    queryKey: ['agent-runtime', platform.id],
    queryFn: () => midplatApi.getAgentRuntime(platform.id),
  });
  const runtime = runtimeQuery.data;
  const healthy = runtime?.status.toLowerCase() === 'ok';
  const statusText = runtimeQuery.isPending ? '读取中' : runtimeQuery.isError ? '读取失败' : healthy ? '运行正常' : runtime?.status || '状态未知';
  const statusClass = runtimeQuery.isError || (runtime && !healthy) ? 'warn' : runtime ? 'ok' : 'muted-st';

  return (
    <>
      <div className="page-head agent-managed-head">
        <div>
          <h2>{platform.name}</h2>
          <p className="sub">智能体托管项目 · 展示运行时实际使用的智能体、模型与当前任务。</p>
        </div>
        <div className="page-head-actions">
          <span className={`status ${statusClass}`}>{statusText}</span>
          <Button
            icon={<ExternalLink size={16} />}
            disabled={!platform.entryUrl}
            onClick={() => platform.entryUrl && window.open(platform.entryUrl, '_blank', 'noopener,noreferrer')}
          >
            打开业务页面
          </Button>
          <Button type="primary" icon={<Bot size={16} />} disabled={!runtime?.agent} onClick={() => runtime?.agent && onManage(runtime.agent)}>
            在智能体开发中管理
          </Button>
        </div>
      </div>
      <p className="note agent-managed-note">
        本页不再展示或修改中台模型库中的历史绑定。下方模型来自智能体运行时健康接口，模型、编排和运行参数请前往智能体开发中管理。
      </p>
      <Form form={form} layout="vertical">
        <div className="form-layout">
          <div className="form-main">
            <section className="comp">
              <SettingsSectionHeader step={1} title="项目身份与业务入口" description="项目资料仍由数智大脑维护，不影响智能体运行配置。" />
              <div className="form-grid">
                <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item name="icon" label="图标"><IconPicker /></Form.Item>
                <Form.Item name="entryUrl" label="业务入口地址"><Input placeholder="https://" /></Form.Item>
              </div>
              <div className="agent-managed-save">
                <Button loading={saving} onClick={onSave}>保存项目信息</Button>
              </div>
            </section>

            <section className="comp">
              <SettingsSectionHeader
                step={2}
                title="实际运行配置"
                description="直接读取智能体当前运行状态，不使用项目表中的模型绑定。"
                extra={<span className="chip chip-llm">运行时只读</span>}
              />
              {runtimeQuery.isPending ? (
                <div className="agent-runtime-loading"><Spin /></div>
              ) : runtimeQuery.isError ? (
                <div className="project-api-error">
                  <div>
                    <strong>智能体运行状态读取失败</strong>
                    <p>{runtimeQuery.error instanceof Error ? runtimeQuery.error.message : '请确认智能体运行时可访问。'}</p>
                  </div>
                  <Button icon={<RefreshCw size={15} />} onClick={() => runtimeQuery.refetch()}>重试</Button>
                </div>
              ) : runtime ? (
                <AgentRuntimeDetails runtime={runtime} />
              ) : null}
            </section>

            <section className="comp">
              <SettingsSectionHeader step={3} title="当前业务任务" description="两个项目复用同一个智能体，但分别调用独立任务技能。" />
              <dl className="agent-runtime-grid">
                <div><dt>任务</dt><dd className="mono">{runtime?.task || '—'}</dd></div>
                <div><dt>任务技能</dt><dd>{runtime?.skillTitle || '—'}</dd></div>
                <div className="wide"><dt>技能资源键</dt><dd className="mono">{runtime?.skillKey || '—'}</dd></div>
              </dl>
            </section>
          </div>

          <aside className="form-aside">
            <div className="aside-card">
              <h4>智能体概况</h4>
              <div className="aside-row"><span className="label">智能体</span><span className="value mono">{runtime?.agent || '读取中'}</span></div>
              <div className="aside-row"><span className="label">运行状态</span><span className={`status ${statusClass}`}>{statusText}</span></div>
              <div className="aside-row"><span className="label">已加载技能</span><span className="value">{runtime ? `${runtime.skills} 个` : '—'}</span></div>
            </div>
            <div className="aside-card">
              <h4>配置归属</h4>
              <div className="aside-model"><div className="name">项目名称、图标、业务入口</div><div className="sub">当前页面维护</div></div>
              <div className="aside-model"><div className="name">模型、提示词与编排</div><div className="sub">由智能体开发的智能体配置维护</div></div>
              <div className="aside-model"><div className="name">SOUL 与任务技能</div><div className="sub">智能体开发的智能体配置中编辑</div></div>
            </div>
            <div className="aside-hint">根因报告和鱼骨图共享同一智能体；切换项目时，任务和技能资源会随项目变化。</div>
          </aside>
        </div>
      </Form>
    </>
  );
}

function AgentRuntimeDetails({ runtime }: { runtime: AgentRuntimeView }) {
  return (
    <dl className="agent-runtime-grid">
      <div><dt>智能体</dt><dd className="mono">{runtime.agent || '未返回'}</dd></div>
      <div><dt>实际模型</dt><dd className="mono">{runtime.model || '未返回'}</dd></div>
      <div><dt>运行状态</dt><dd>{runtime.status}</dd></div>
      <div><dt>已加载技能</dt><dd>{runtime.skills} 个</dd></div>
      <div className="wide"><dt>运行时入口</dt><dd className="mono">{runtime.runtimeUrl}</dd></div>
    </dl>
  );
}

function ProjectBasics({
  platform, form, saving, onSave,
}: {
  platform: PlatformItem;
  form: ReturnType<typeof Form.useForm<WizardValues>>[0];
  saving: boolean;
  onSave: () => void;
}) {
  const navigate = useNavigate();
  const hot = hotLabel(platform);
  const canHotUpdate = supportsHotUpdate(platform);
  return (
    <>
      <div className="page-head">
        <div>
          <h2>{platform.name}</h2>
          <p className="sub">项目信息 · 名称、图标和入口</p>
        </div>
        <div className="page-head-actions">
          <span className={`status ${hot.cls}`}>{hot.text}</span>
          <Button
            ghost
            icon={<ExternalLink size={16} />}
            disabled={!platform.entryUrl}
            onClick={() => platform.entryUrl && window.open(platform.entryUrl, '_blank', 'noopener,noreferrer')}
          >
            新标签打开
          </Button>
          <Popconfirm
            title="保存项目信息？"
            description={canHotUpdate ? '保存名称、图标和入口，并排队下发。模型与提示词不在这里修改。' : '名称、图标和入口保存在中台。模型与提示词不在这里修改。'}
            okText="确认保存"
            cancelText="取消"
            onConfirm={onSave}
            okButtonProps={{ loading: saving }}
          >
            <Button type="primary" loading={saving}>保存项目信息</Button>
          </Popconfirm>
        </div>
      </div>
      <p className="note">这里只维护整个项目的登记信息。模型、提示词与规则提示词都在智能体开发的「智能体配置」里直接选择，不再需要按项目授权。</p>
      <Form form={form} layout="vertical">
        <div className="form-layout">
          <div className="form-main">
            <section className="comp">
              <SettingsSectionHeader step={1} title="项目身份与入口" description="确认项目名称、统一图标和外部工作台地址。" />
              <div className="form-grid">
                <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
                <Form.Item name="icon" label="图标"><IconPicker /></Form.Item>
                <Form.Item name="entryUrl" label="入口地址"><Input placeholder="https://" /></Form.Item>
              </div>
            </section>
            <AgentConfigSyncCard platformId={platform.id} />
          </div>
          <aside className="form-aside">
            <div className="aside-card">
              <h4>应用概况</h4>
              <div className="aside-row"><span className="label">配置下发能力</span><span className={`status ${hot.cls}`}>{hot.text}</span></div>
              <div className="aside-row"><span className="label">入口地址</span><span className="value mono">{platform.entryUrl || '未配置'}</span></div>
            </div>
            <div className="aside-card">
              <h4>配置归属</h4>
              <div className="aside-model"><div className="name">项目名称、图标、入口</div><div className="sub">当前页面维护</div></div>
              <div className="aside-model"><div className="name">模型、提示词与规则提示词</div><div className="sub">智能体开发 · 智能体配置</div></div>
            </div>
            <Button block icon={<Bot size={14} />} onClick={() => navigate(`/agent-hub/agents?project=${encodeURIComponent(platform.id)}`)}>配置智能体</Button>
            <div className="aside-hint">{canHotUpdate ? '名称、图标和入口保存在当前项目，下发结果看配置状态。' : '名称、图标和入口保存在中台；当前项目未接通配置热更新。'}</div>
          </aside>
        </div>
      </Form>
    </>
  );
}

function RuntimeConfig({
  platform, form, saving, onSave, onGoModels,
}: {
  platform: PlatformItem;
  form: ReturnType<typeof Form.useForm<RuntimeSettings>>[0];
  saving: boolean;
  onSave: () => void;
  onGoModels: () => void;
}) {
  const hot = hotUpdateStatus(platform);
  const searchMode = Form.useWatch('searchMode', form);
  const searchTopK = Form.useWatch('searchTopK', form);
  const ragTopK = Form.useWatch('ragTopK', form);
  const rewriteEnabled = Form.useWatch('rewriteEnabled', form);
  const rerankEnabled = Form.useWatch('rerankEnabled', form);
  const yesNo = (value?: boolean) => (value == null ? '—' : value ? '开启' : '关闭');
  return (
    <>
      <div className="page-head">
        <div>
          <h2>知识库</h2>
          <p className="sub">项目配置 · rerank、query rewrite、RAG 检索参数。对应知识库 PUT /api/settings，可热更新。</p>
        </div>
        <Popconfirm
          title="保存目标配置？"
          description="保存检索与重排的目标配置，下发完成并核对一致后才标记为生效。"
          okText="确认保存"
          cancelText="取消"
          onConfirm={onSave}
          okButtonProps={{ loading: saving }}
        >
          <Button type="primary" style={{ height: 32 }} loading={saving}>保存目标配置</Button>
        </Popconfirm>
      </div>
      <p className="note">这里只调整知识库的检索和重排行为。模型与提示词在智能体开发的智能体配置里绑定。</p>
      <Form form={form} layout="vertical">
        <div className="form-layout">
          <div className="form-main">
        <section className="comp">
          <SettingsSectionHeader step={1} title="默认检索行为" description="先设置日常查询最常用的检索方式和返回条数。" extra={<span className="chip">高频</span>} />
          <div className="form-grid">
            <Form.Item name="searchMode" label="默认检索模式">
              <Select options={[{ value: 'HYBRID', label: 'HYBRID 混合' }, { value: 'VECTOR', label: 'VECTOR 向量' }, { value: 'KEYWORD', label: 'KEYWORD 关键词' }]} />
            </Form.Item>
            <Form.Item name="searchTopK" label="检索 topK"><InputNumber min={1} max={50} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="ragTopK" label="RAG 上下文 topK"><InputNumber min={1} style={{ width: '100%' }} /></Form.Item>
          </div>
        </section>
        <section className="comp">
          <SettingsSectionHeader step={2} title="上下文与数据返回限制" description="控制上下文长度和单次数据量，防止响应过大或查询范围失控。" />
          <div className="form-grid">
            <Form.Item name="maxContextLength" label="maxContextLength"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="statsListingLimit" label="stats listing limit"><InputNumber min={1} max={500} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="dataQueryDefaultLimit" label="数据查询默认 limit"><InputNumber min={1} max={200} style={{ width: '100%' }} /></Form.Item>
            <Form.Item name="dataQueryMaxLimit" label="数据查询最大 limit"><InputNumber min={1} max={200} style={{ width: '100%' }} /></Form.Item>
          </div>
        </section>
        <section className="comp">
          <SettingsSectionHeader step={3} title="提问改写" description="高级选项：将用户问题改写为多条检索语句，提升召回覆盖。" extra={<span className="chip">高级</span>} />
          <Form.Item name="rewriteEnabled" label="启用 query rewrite" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="rewriteMaxQueries" label="maxQueries（0–5）"><InputNumber min={0} max={5} style={{ width: '100%' }} /></Form.Item>
        </section>
        <section className="comp">
          <SettingsSectionHeader step={4} title="结果重排" description="高级选项：在已有 Rerank 模型时分别控制检索和问答是否启用重排。" extra={<span className="chip">高级</span>} />
          <Form.Item name="rerankEnabled" label="启用 reranker 服务" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="searchEnableRerank" label="检索默认走 rerank" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="ragEnableRerank" label="RAG 默认走 rerank" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="rerankEvidenceThreshold" label="证据阈值 0–100"><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item>
        </section>
          </div>
          <aside className="form-aside">
            <div className="aside-card">
              <h4>平台状态</h4>
              <div className="aside-row"><span className="label">配置下发能力</span><span className={`status ${hot.cls}`}>{hot.text}</span></div>
              <div className="aside-row"><span className="label">入口地址</span><span className="value mono">{platform.entryUrl || '未配置'}</span></div>
            </div>
            <div className="aside-card">
              <h4>检索概览</h4>
              <div className="aside-row"><span className="label">检索模式</span><span className="value">{searchMode ?? '—'}</span></div>
              <div className="aside-row"><span className="label">检索 topK</span><span className="value">{searchTopK ?? '—'}</span></div>
              <div className="aside-row"><span className="label">RAG topK</span><span className="value">{ragTopK ?? '—'}</span></div>
              <div className="aside-row"><span className="label">Query rewrite</span><span className="value">{yesNo(rewriteEnabled)}</span></div>
              <div className="aside-row"><span className="label">Rerank 服务</span><span className="value">{yesNo(rerankEnabled)}</span></div>
            </div>
            <Button block ghost icon={<Key size={14} />} onClick={onGoModels}>前往智能体开发</Button>
            <div className="aside-hint">保存后排队下发，读取核对一致后显示已生效。</div>
          </aside>
        </div>
      </Form>
    </>
  );
}

function AccessConfig({ platform }: { platform: PlatformItem }) {
  const navigate = useNavigate();
  const caps = [
    platform.llmModelId ? 'POST /api/runtime/chat' : null,
    platform.embeddingModelId ? 'POST /api/runtime/embed' : null,
    platform.rerankModelId ? 'POST /api/runtime/rerank' : null,
  ].filter(Boolean);
  return (
    <>
      <div className="page-head">
        <div>
          <h2>{platform.name} · 接入凭证</h2>
          <p className="sub">项目不再持有唯一明文凭证：接入应用与多枚凭证统一在「客户与凭证」页签发、轮换与撤销。</p>
        </div>
        <Button type="primary" onClick={() => navigate('/access')}>前往客户与凭证</Button>
      </div>
      <div className="form-layout">
        <div className="form-main">
          <div className="card card-pad">
        <div className="field">
          <label>凭证管理</label>
          <pre className="codebox api-credential-value">安全改造后凭证原文只在签发时展示一次；请到「客户与凭证」为该项目创建接入应用并签发凭证。</pre>
        </div>
        <div className="codebox" style={{ marginTop: 12 }}>
          {platform.llmModelId
            ? `POST /api/runtime/chat
Authorization: Bearer <在「客户与凭证」签发的凭证>

{ "input": "用户问题" }

中台会套用已绑定的模型和提示词，新项目不要自己带 Key。`
            : `Authorization: Bearer <在「客户与凭证」签发的凭证>

还没绑 LLM。到智能体开发的智能体配置中绑定对话模型后即可调 /api/runtime/chat。`}
        </div>
        <p className="note">历史遗留凭证仍可按受保护摘要校验继续使用，但不再回显原文；轮换后旧凭证在过渡期内失效。</p>
          </div>
        </div>
        <aside className="form-aside">
          <div className="aside-card">
            <h4>平台状态</h4>
            <div className="aside-row"><span className="label">配置下发能力</span><span className={`status ${hotUpdateStatus(platform).cls}`}>{hotUpdateStatus(platform).text}</span></div>
            <div className="aside-row"><span className="label">入口地址</span><span className="value mono">{platform.entryUrl || '未配置'}</span></div>
          </div>
          <div className="aside-card">
            <h4>已开通调用</h4>
            {caps.length ? caps.map((cap) => (
              <div key={cap} className="aside-model"><div className="sub mono">{cap}</div></div>
            )) : (
              <div className="aside-model"><div className="sub">尚未开通 runtime（先在智能体开发里绑定 LLM）</div></div>
            )}
          </div>
          <div className="aside-hint">接入应用固定归属一个项目，凭证权限在其上限内收紧；凭证原文不落库、不回显。</div>
        </aside>
      </div>
    </>
  );
}
