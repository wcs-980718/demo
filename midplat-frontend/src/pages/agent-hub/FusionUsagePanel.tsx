import { useEffect, useState, type ReactNode } from 'react';
import { useNavigate } from '@umijs/max';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Collapse, Input, Modal, Segmented, Select, Tag, message } from 'antd';
import { ArrowRight, Copy, KeyRound, Plug, RefreshCw } from 'lucide-react';
import { getMidplatApiBaseURL } from '@/api/apiBaseURL';
import { midplatApi } from '@/api/midplatApi';
import { copyText } from '../../copyText';
import { createUuid } from '../../createUuid';
import { fusionApi, type FusionDeployment, type FusionRelease } from './fusionApi';
import { agentJavaExample, agentReactExample, agentRuntimeURL } from './agentUsage';
import './usage.css';

function UsageBlock({ index, title, hint, action, children }: {
  index: string; title: string; hint?: string; action?: ReactNode; children: ReactNode;
}) {
  return <section className="usage-block">
    <div className="usage-block-head">
      <div>
        <h3><span className="usage-index">{index}</span> · {title}</h3>
        {hint ? <p>{hint}</p> : null}
      </div>
      {action}
    </div>
    {children}
  </section>;
}

export function FusionUsagePanel({ project, projectName, deployment, releases, editable, admin, onPublish, onManage }: {
  project: string; projectName: string; deployment: FusionDeployment; releases: FusionRelease[];
  editable: boolean; admin: boolean; onPublish: () => void; onManage: () => void;
}) {
  const status = useQuery({ queryKey: ['fusion-status'], queryFn: fusionApi.status, retry: false, refetchInterval: 10000 });
  const active = releases.find(release => release.id === deployment.activeReleaseId);
  const [taskKey, setTaskKey] = useState(''); const [input, setInput] = useState('请根据我提供的信息进行分析，列出结论、依据和待补充的信息。');
  const [requestId, setRequestId] = useState(createUuid); const [language, setLanguage] = useState('java');
  useEffect(() => { if (!active?.snapshot.tasks.some(task => task.key === taskKey)) setTaskKey(active?.snapshot.tasks[0]?.key || ''); }, [active?.id]);
  const task = active?.snapshot.tasks.find(item => item.key === taskKey);
  const configured = status.data?.runtimeEnvironment === 'development' || status.data?.runtimeEnvironment === 'staging' || status.data?.runtimeEnvironment === 'production';
  const apiReady = !!active && !!status.data?.executionEnabled && configured && !status.isError;
  const baseURL = agentRuntimeURL(getMidplatApiBaseURL(), window.location.href);
  const example = language === 'react' ? agentReactExample(taskKey, input, requestId) : agentJavaExample(baseURL, taskKey, input, requestId);
  const copy = async (value: string) => { try { await copyText(value); message.success('已复制'); } catch { message.error('复制失败，请选择文本后手动复制'); } };
  // W1 安全改造后项目不再持有可回显的唯一凭证；接入应用与凭证统一在「客户与凭证」签发。
  const navigate = useNavigate();
  const availability = !active ? (deployment.activeReleaseId ? '运行版本信息暂不可用，请刷新后再试。' : '先发布并上线一个版本，即可在这里查看 API 接入方式。') : !status.data?.executionEnabled ? '执行服务暂不可用，请联系管理员确认服务状态。' : '';
  const availabilityAction = !active ? <Button onClick={releases.length ? onManage : onPublish}>{releases.length ? '前往上线' : '前往发布'}</Button> : <Button onClick={() => void status.refetch()}>重新检查</Button>;
  return <div className="hub-usage">
    <section className="usage-summary" aria-label="智能体接入状态">
      <div className="usage-summary-main"><span className="usage-symbol"><Plug size={25} /></span><div><div className="hub-inline"><h2>{deployment.name}</h2><Tag color={active ? 'green' : 'default'}>{active ? `已上线 R${active.sequence}` : '尚未就绪'}</Tag></div><p>{projectName} · {active ? `${active.snapshot.tasks.length} 项可用任务` : '等待发布与上线'}</p></div></div>
      <Button onClick={onManage}>上线版本与运行记录<ArrowRight size={14} /></Button>
    </section>
    <p className="usage-lifecycle">通过标准接口接入已上线智能体。修改草稿不会改变已上线运行。HTTP 202 只表示请求已受理，请继续查询结果。</p>
    {status.isError ? <Alert type="error" showIcon title="无法确认执行服务状态" description={status.error.message} action={<Button onClick={() => void status.refetch()}>重新读取</Button>} /> : status.isPending ? <Alert type="info" showIcon title="正在确认执行服务状态…" /> : availability ? <Alert type="warning" showIcon title={availability} action={availabilityAction} /> : null}
    {!status.isPending && !status.isError && !configured ? <Alert type="warning" showIcon title="工作台服务配置尚未就绪" description="请稍后重试，或联系管理员确认服务配置。" action={<Button onClick={() => void status.refetch()}>重试</Button>} /> : null}
    {active && deployment.publishedReleaseId !== active.id && <Alert type="info" showIcon title={`有新的已发布版本尚未上线，当前调用仍使用 R${active.sequence}。`} action={<Button onClick={onManage}>管理上线版本</Button>} />}
    <div className="usage-board">
      <div className="usage-board-main">
        <UsageBlock index="01" title="接入配置" hint="项目凭证决定调用哪个智能体，taskKey 决定执行哪个已上线任务。">
          <div className="usage-endpoint"><Tag color="blue">POST</Tag><code>{baseURL}/runs</code><Button aria-label="复制调用地址" icon={<Copy size={14} />} onClick={() => void copy(`${baseURL}/runs`)} /></div>
          <div className="usage-credential-row"><p>在业务系统后端保存项目凭证。Java 示例从服务端环境变量 MIDPLAT_TOKEN 读取；React 示例只调用你们自己的业务转发接口，不携带平台凭证。</p>{admin && editable ? <Button icon={<KeyRound size={14} />} onClick={() => navigate('/access')}>前往签发凭证</Button> : <Tag>请向项目管理员获取凭证</Tag>}</div>
        </UsageBlock>
        {apiReady && task ? <UsageBlock index="02" title="请求内容" hint="列表来自当前已上线版本；草稿中的新增任务需发布并上线后才可调用。">
          <div className="usage-request-fields"><label>选择调用任务<Select aria-label="选择调用任务" value={taskKey} onChange={value => { setTaskKey(value); setRequestId(createUuid()); }} options={active!.snapshot.tasks.map(item => ({ value: item.key, label: item.name, title: item.name }))} /></label><div className="usage-task-key"><span>任务标识 taskKey</span><code>{task.key}</code><Button size="small" aria-label="复制任务标识" icon={<Copy size={13} />} onClick={() => void copy(task.key)} /></div></div>
          <label className="usage-input-label">示例输入<Input.TextArea aria-label="API 示例输入" value={input} rows={3} maxLength={20000} onChange={event => { setInput(event.target.value); setRequestId(createUuid()); }} /></label>
        </UsageBlock> : <div className="usage-not-ready">当前尚不能生成可执行示例。完成上线后，这里将显示任务标识与调用代码。</div>}
      </div>
      {apiReady && task ? <div className="usage-board-example">
        <UsageBlock index="03" title="调用示例" hint="HTTP 202 只表示已受理，不代表任务完成。" action={<div className="usage-example-toolbar">
          <div className="usage-language" role="group" aria-label="示例语言"><span>示例语言</span><Segmented value={language} onChange={value => setLanguage(String(value))} options={[{ value: 'java', label: 'Java' }, { value: 'react', label: 'React' }]} /></div>
          <Button icon={<Copy size={14} />} disabled={!input.trim()} onClick={() => void copy(example)}>复制示例</Button>
        </div>}>
          <pre className="usage-code" aria-label="调用示例" tabIndex={0}>{example}</pre>
          {language === 'react' && <p className="hub-help">示例中的 /api/ai-agent/runs 只是调用你们已有 Java 后端转发接口的示意，需替换为实际业务接口；本平台没有新增该路由。</p>}
          <div className="usage-idempotency"><p>同一次提交重试时，保留相同的 idempotencyKey 和请求内容；发起新的任务时换一个键。HTTP 202 表示已受理，不代表任务完成。</p><Button size="small" icon={<RefreshCw size={13} />} onClick={() => setRequestId(createUuid())}>生成新请求键</Button></div>
        </UsageBlock>
      </div> : null}
    </div>
    {apiReady && task ? <section className="usage-reference">
      <div className="usage-block-head"><div><h3>获取结果、追踪过程与结束会话</h3><p>以上路径均接在接入地址后，并携带同一项目凭证。</p></div></div>
      <div className="hub-table-scroll"><table className="hub-table usage-api-table"><thead><tr><th>操作</th><th>方法与路径</th><th>用法</th></tr></thead><tbody>
        <tr><td>查询结果</td><td><code>GET /runs/{'{运行ID}'}</code></td><td>每秒查询一次，直到 succeeded / failed / cancelled。</td></tr>
        <tr><td>查看执行过程</td><td><code>GET /runs/{'{运行ID}'}/events?after=0</code></td><td>每页最多 200 条；将末条 sequence 作为下一页 after，继续读取。</td></tr>
        <tr><td>取消运行</td><td><code>POST /runs/{'{运行ID}'}/cancel</code></td><td>随后继续查询，确认最终状态。</td></tr>
        <tr><td>结束会话</td><td><code>POST /sessions/{'{会话ID}'}/close</code></td><td>单次调用结束后关闭；会取消该会话中尚未完成的运行。</td></tr>
      </tbody></table></div>
      <p className="hub-help">路径接在 {baseURL} 后。调用记录可在“运行记录”中按“业务平台”来源查看。</p>
    </section> : null}
    <Collapse items={[
      { key: 'session', label: '多轮对话和版本切换怎么处理？', children: <p>后续请求传入上次返回的 session_id，字段名使用 sessionId，同时提供新的 input 和 idempotencyKey。会话固定任务和版本，不能并发提交；切换任务或使用新上线版本时，省略 sessionId 创建新会话。会话有效期为 12 小时。</p> },
      { key: 'connect', label: '已有业务项目如何使用这个智能体？', children: <p>在业务后端的合适环节调用上述接口：准备输入 → 提交任务 → 查询结果 → 展示给用户或由业务逻辑处理。具体放在哪个环节、怎样使用结果，需要在应用场景确定后设计；此页面不会自动接入现有项目。</p> },
      { key: 'errors', label: '调用失败时先检查什么？', children: <p>401：检查项目凭证。403：无权限，或会话已关闭、过期、不属于当前身份；请检查调用身份，必要时新建会话。409：检查是否上线、会话是否已有运行、幂等键是否重复用于不同请求。422：检查 taskKey、输入内容及请求字段。429：项目运行队列已满。503：执行服务暂不可用。网络超时后先查询原运行，不要直接重复发起任务。</p> },
    ]} />
  </div>;
}
