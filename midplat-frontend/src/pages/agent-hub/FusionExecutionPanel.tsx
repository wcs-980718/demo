import { createUuid } from '../../createUuid';
import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Collapse, Input, Select, Tag, message } from 'antd';
import { Play, Square, RefreshCw } from 'lucide-react';
import { fusionApi, type FusionDeployment, type FusionRelease, type FusionRunEvent } from './fusionApi';
import { WorkspaceEmpty, WorkspacePanel } from './FusionWorkspaceViews';

const labels: Record<string, string> = { queued: '排队中', running: '运行中', cancelling: '正在取消', succeeded: '成功', failed: '失败', cancelled: '已取消' };
const running = (status?: string) => !!status && ['queued', 'running', 'cancelling'].includes(status);
const eventLabels: Record<string, string> = { started: '开始执行', completed: '执行完成', failed: '执行失败', cancelled: '已取消', 'node.started': '节点开始', 'node.completed': '节点完成', 'node.skipped': '跳过节点', 'node.failed': '节点失败', 'branch.selected': '选择条件分支', 'tool.started': '调用工具', 'tool.completed': '工具完成', usage: '模型用量' };
export function FusionExecutionPanel({ project, environment, deployment, releases, editable, onChanged, mode = 'manage' }: {
  project: string; environment: string; deployment: FusionDeployment; releases: FusionRelease[]; editable: boolean; mode?: 'manage' | 'use'; onChanged: () => void;
}) {
  const [releaseId, setReleaseId] = useState(deployment.publishedReleaseId || '');
  const [activating, setActivating] = useState(false); const [error, setError] = useState('');
  const [input, setInput] = useState(''); const [taskKey, setTaskKey] = useState(''); const [sessionId, setSessionId] = useState('');
  const [runId, setRunId] = useState(''); const [sending, setSending] = useState(false); const [events, setEvents] = useState<FusionRunEvent[]>([]);
  const cursor = useRef(0); const requestKey = useRef<{ payload: string; key: string }>();
  const list = useQuery({ queryKey: ['fusion-runs', project, environment], queryFn: () => fusionApi.runs(project, environment), enabled: mode === 'manage', refetchInterval: 3000, retry: false });
  const run = useQuery({ queryKey: ['fusion-run', project, environment, runId], queryFn: () => fusionApi.run(project, environment, runId), enabled: !!runId, refetchInterval: query => !query.state.data || running(query.state.data.status) ? 600 : false, retry: false });
  const latestRun = useRef(run.data); latestRun.current = run.data;
  const active = releases.find(release => release.id === deployment.activeReleaseId);
  const sessionRelease = sessionId && run.data?.session_id === sessionId ? releases.find(release => release.id === run.data?.release_id) || active : active;
  const tasks = sessionRelease?.snapshot.tasks || [];
  useEffect(() => { if (!tasks.some(task => task.key === taskKey)) setTaskKey(tasks.length === 1 ? tasks[0].key : ''); }, [sessionRelease?.id]);
  useEffect(() => {
    if (!runId) return; let stopped = false; let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try { const batch = await fusionApi.events(project, environment, runId, cursor.current); if (stopped) return;
        if (batch.length) { cursor.current = batch[batch.length - 1].sequence; setEvents(previous => [...previous, ...batch].slice(-2000)); }
        const state = latestRun.current;
        if (state?.id === runId && !running(state.status) && cursor.current >= state.last_event) return;
        timer = setTimeout(poll, 800);
      } catch (failure) { if (!stopped) setError((failure as Error).message); }
    };
    void poll(); return () => { stopped = true; clearTimeout(timer); };
  }, [runId, project, environment]);
  const selectRun = (id: string) => { cursor.current = 0; setEvents([]); setRunId(id); setError(''); };
  const activate = async () => {
    setActivating(true); setError('');
    try { await fusionApi.activate(project, environment, releaseId, deployment.activationRevision); onChanged(); message.success('运行版本已上线，新会话使用此版本'); }
    catch (failure) { setError((failure as Error).message); onChanged(); } finally { setActivating(false); }
  };
  const start = async () => {
    setSending(true); setError('');
    try {
      const body = { input, taskKey, ...(sessionId ? { sessionId } : {}) }; const payload = JSON.stringify(body);
      if (!requestKey.current || requestKey.current.payload !== payload) requestKey.current = { payload, key: createUuid() };
      const value = await fusionApi.startRun(project, environment, { ...body, idempotencyKey: requestKey.current.key }); selectRun(value.id); setSessionId(value.session_id); requestKey.current = undefined; onChanged(); if (mode === 'manage') void list.refetch(); }
    catch (failure) { setError((failure as Error).message); } finally { setSending(false); }
  };
  const newSession = async () => { if (sessionId) await fusionApi.closeSession(project, environment, sessionId); setSessionId(''); setInput(''); setTaskKey(active?.snapshot.tasks.length === 1 ? active.snapshot.tasks[0].key : ''); };
  const taskName = (releaseId: string, key: string) => releases.find(release => release.id === releaseId)?.snapshot.tasks.find(task => task.key === key)?.name || key;
  const eventItems = events.filter(event => event.type !== 'delta').map(event => ({ key: String(event.sequence), label: `${eventLabels[event.type] || event.type} · ${event.data.name || event.data.nodeId || ''}`, children: <pre className="hub-content-block">{JSON.stringify(event.data, null, 2)}</pre> }));
  const progress = new Map<string, { name: string; status: string; output: string }>();
  for (const event of events) {
    const id = event.data.nodeId; if (!id) continue;
    const previous = progress.get(id) || { name: event.data.name || id, status: '处理中', output: '' };
    if (event.data.name && (event.type.startsWith('node.') || event.type === 'branch.selected')) previous.name = event.data.name;
    if (event.type === 'delta') previous.output = (previous.output + (event.data.text || '')).slice(-200000);
    if (event.type === 'node.completed') { previous.status = '已完成'; previous.output = event.data.output || previous.output; }
    if (event.type === 'node.skipped') { previous.status = '已跳过'; previous.output = event.data.detail || ''; }
    if (event.type === 'node.failed') { previous.status = '失败'; previous.output = event.data.detail || ''; }
    if (event.type === 'branch.selected') previous.output = event.data.detail || '';
    progress.set(id, previous);
  }
  if (run.data && ['failed', 'cancelled'].includes(run.data.status)) for (const node of progress.values()) if (node.status === '处理中') node.status = run.data.status === 'cancelled' ? '已取消' : '已中断';
  return <div className="hub-form">
    {mode === 'manage' && <WorkspacePanel title="运行版本" subtitle="上线前校验发布包与独立运行单元；已有会话继续使用原版本。">
      <div className="hub-actions"><Tag color={active ? 'green' : 'default'}>{active ? `运行中 R${active.sequence}` : '尚未上线'}</Tag><Select aria-label="选择上线版本" style={{ flex: 1, minWidth: 240 }} value={releaseId || undefined} onChange={setReleaseId} options={releases.map(release => { const label = `R${release.sequence} · ${release.note}`; return { value: release.id, label, title: label }; })} placeholder="先发布配置版本" /><Button type="primary" loading={activating} disabled={!editable || !releaseId || releaseId === deployment.activeReleaseId} onClick={activate}>上线此版本</Button></div>
    </WorkspacePanel>}
    {error && <Alert type="error" showIcon title={error} />}
    <div className="hub-overview-columns"><WorkspacePanel title={mode === 'use' ? '使用智能体' : '调试智能体'} subtitle={sessionId ? '继续当前会话，使用创建会话时的固定版本。' : '选择已上线任务并输入真实内容。'}>
      <div className="hub-form"><label>执行任务<Select aria-label="执行任务" value={taskKey || undefined} disabled={!!sessionId} onChange={setTaskKey} placeholder="选择任务" options={tasks.map(task => ({ value: task.key, label: task.name }))} /></label>
        <label>任务输入<Input.TextArea aria-label="任务输入" rows={7} maxLength={20000} showCount value={input} onChange={event => setInput(event.target.value)} placeholder="输入需要处理的内容" /></label>
        <div className="hub-actions"><Button type="primary" icon={<Play size={15} />} loading={sending} disabled={!editable || !active || !taskKey || !input.trim() || running(run.data?.status)} onClick={start}>运行任务</Button><Button disabled={running(run.data?.status)} onClick={() => void newSession().catch(failure => setError(failure.message))}>新会话</Button>{running(run.data?.status) && <Button danger icon={<Square size={14} />} onClick={() => void fusionApi.cancelRun(project, environment, runId).then(() => run.refetch()).catch(failure => setError(failure.message))}>取消运行</Button>}</div>
      </div>
    </WorkspacePanel><WorkspacePanel title="执行结果" subtitle={run.data ? `${labels[run.data.status]} · ${releases.find(release => release.id === run.data.release_id)?.sequence ? `R${releases.find(release => release.id === run.data.release_id)!.sequence}` : run.data.release_id}` : '运行后实时展示输出。'}>
      {run.error && <Alert type="error" title={run.error.message} />}{run.data?.error && <Alert type="error" title={run.data.error} />}
      <pre className="hub-content-block" aria-live="polite" style={{ minHeight: 180, maxHeight: 420 }}>{run.data?.output || (running(run.data?.status) ? '正在执行流程，可在下方查看各节点输出…' : '暂无输出')}</pre>
      <p className="hub-help">{run.data ? `运行 ID：${run.data.id}` : '每次运行保留项目、会话和发布版本。'}</p>
      {!!progress.size && <Collapse items={[{ key: 'nodes', label: `节点进度与输出 · ${progress.size}`, children: <Collapse items={[...progress].map(([id, node]) => ({ key: id, label: `${node.name} · ${node.status}`, children: <pre className="hub-content-block">{node.output || '等待结果…'}</pre> }))} /> }]} />}
      {!!eventItems.length && <Collapse items={[{ key: 'steps', label: `执行步骤与用量 · ${eventItems.length}`, children: <Collapse items={eventItems} /> }]} />}
    </WorkspacePanel></div>
    {mode === 'manage' && <WorkspacePanel title="任务执行记录" action={<Button icon={<RefreshCw size={14} />} onClick={() => void list.refetch()}>刷新</Button>}>
      {list.error && <Alert type="error" title={list.error.message} />}
      <div className="hub-table-scroll"><table className="hub-table"><thead><tr><th>任务</th><th>来源</th><th>版本</th><th>结果</th><th>时间</th><th>操作</th></tr></thead><tbody>{list.data?.map(item => <tr key={item.id}><td>{taskName(item.release_id, item.task_key)}</td><td><Tag>{item.origin === 'business' ? '业务平台' : '调试'}</Tag></td><td>R{releases.find(release => release.id === item.release_id)?.sequence || '—'}</td><td><Tag color={item.status === 'succeeded' ? 'green' : item.status === 'failed' ? 'red' : 'blue'}>{labels[item.status]}</Tag></td><td>{new Date(item.created_at).toLocaleString('zh-CN')}</td><td><Button type="link" onClick={() => { selectRun(item.id); setSessionId(''); }}>查看结果</Button></td></tr>)}</tbody></table></div>
      {!list.data?.length && <WorkspaceEmpty>暂无运行记录。</WorkspaceEmpty>}
    </WorkspacePanel>}
  </div>;
}
