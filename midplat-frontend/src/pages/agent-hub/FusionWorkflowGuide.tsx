import { useEffect, useRef, useState } from 'react';
import { Button, Collapse, Modal, Tabs } from 'antd';
import { ArrowLeft, ArrowRight, BookOpen, CheckCircle2, GitBranch, GitFork, Layers, Maximize2, MessageSquare, Play, Sparkles } from 'lucide-react';
import { FusionWorkflowGuideIllustration, WorkflowPatternPicture } from './FusionWorkflowGuideIllustration';
import './workflowGuide.css';

const steps = [
  {
    title: '配置模型和角色', short: '模型与角色',
    purpose: '先告诉智能体：由哪个模型处理、扮演什么角色、遵守哪些要求。',
    actions: [
      ['打开“智能体配置”', '点击画布上方的“智能体配置”，右侧会切换到智能体的基础设置。'],
      ['选模型，写角色', '选择一个默认模型（必填）；填写“角色规则”，或把“角色来源”切换为“关联提示词模板”后选择模板。'],
    ],
    example: '角色规则示例：你是一名资料分析助手。依据用户提供的资料提炼要点、给出建议，信息不足时明确说明。',
    tip: '默认模型和角色会被所有任务继承，两者都是必填项。模型列表为空时，先到开发中心的“模型管理”登记并启用模型；“生成温度”越低，回答通常越稳定。',
  },
  {
    title: '定义任务与关联能力', short: '任务与能力',
    purpose: '一个智能体可以有多个任务；每个任务对应一件可单独执行的事情。',
    actions: [
      ['点击“用户输入”节点', '在右侧填写“任务名称”和“任务指令”。顶部的任务下拉框用于切换任务，旁边的“＋”可以新增任务。'],
      ['按需关联能力资产', '在“关联能力资产”中选择已经配置好的技能、工具、知识或数据内容。任务指令和这些能力会被该任务的模型节点共同使用。'],
    ],
    example: '任务名称：资料分析。任务指令：先列出主要事实，再给出建议，并标明不确定的信息。',
    tip: '没有额外能力资产也可以先做文本分析。“任务标识”用于业务调用，已经被业务使用的任务不要随意改标识。',
  },
  {
    title: '添加节点并写清指令', short: '添加与配置节点',
    purpose: '把一项任务拆成几个明确步骤，每个“模型处理”节点负责一个步骤。',
    actions: [
      ['从左侧添加节点', '将“模型处理”拖到画布，或直接点击添加。刚开始可以选“顺序处理”模板；有旧编排时会先确认是否替换当前任务的节点和连线。'],
      ['点选节点，在右侧配置', '填写“节点名称”和“节点指令”。节点默认继承模型，也可单独选择模型；一般选“文本”输出，需后续按字段判断时可选择模型支持的“JSON 对象”。'],
    ],
    example: '节点一“提炼要点”：从资料中提炼 3 个要点。节点二“生成建议”：根据前一步要点给出 2 条建议。',
    tip: '写清“要做什么、依据什么、输出什么”。每个任务最多 30 个处理节点；不需要的节点可选中后在右侧删除。',
  },
  {
    title: '用连线决定执行顺序', short: '连接执行流程',
    purpose: '节点放在哪里只是排版；连线才决定先做哪一步、结果传给谁。',
    actions: [
      ['从输出连接点拖出连线', '把鼠标放在节点右侧的小圆点，按住并拖向下一个节点左侧的小圆点，松开后完成连接。'],
      ['连通“用户输入”与“最终输出”', '示例顺序为：用户输入 → 提炼要点 → 生成建议 → 最终输出。不方便拖动时，点选起点节点，在右侧“关联后续节点”选择目标并点击“添加连线”。'],
    ],
    example: '条件节点必须分别连好“满足”和“不满足”出口；要同时执行多个步骤，先添加“并行分支”，完成后用“结果汇合”收集结果。',
    tip: '连错时选中连线按 Delete 删除，也可在右侧连线列表移除。“自动布局”“适应全部节点”和右上角展开按钮用于整理或查看画布。',
  },
  {
    title: '保存草稿，检查并生成', short: '保存与生成',
    purpose: '把画布上的模型、指令、连线和能力关联保存成可发布的智能体配置。',
    actions: [
      ['点击“生成智能体”', '系统会检查所有任务的模型、角色、能力及连线。根据提示补齐孤立节点、条件出口或其他缺项后，再次生成。'],
      ['检查通过后点击“生成并保存”', '在弹窗确认配置概览，生成并保存全部任务。看到“智能体配置已生成”后，可以点击“前往发布”。'],
    ],
    example: '还没编排完：点击“保存草稿”，下次继续编辑。已经编排好：点击“生成智能体”完成检查，再“生成并保存”。',
    tip: '保存草稿可以保留未完成的编排；生成会检查并保存配置。两者都不会立即运行任务，也不会替换当前已经上线的版本。',
  },
  {
    title: '发布、上线并试运行', short: '发布与运行',
    purpose: '先固定一个可追溯的版本，再用测试资料检查实际结果。',
    actions: [
      ['在“版本与发布”中发布', '点击“发布新版本”，填写发布说明并“确认发布”。发布完成后会得到 R1、R2 等固定版本，方便查看快照与追溯。'],
      ['在“运行记录”中上线并调试', '选择刚发布的版本，点击“上线此版本”；然后选择“执行任务”，填写“任务输入”并点击“运行任务”。在“执行结果”和“节点进度与输出”中检查每一步。'],
    ],
    example: '测试输入：请分析这段资料：本周新增 10 条咨询，已解决 8 条，另外 2 条还需要补充信息。',
    tip: '已有会话继续使用创建时的版本。想验证新版本，先点击“新会话”，再选择任务并输入内容。实际接入业务项目还需要配置相应的调用入口和数据能力。',
  },
] as const;

const nodeTypes = [
  { title: '开始 · 用户输入', Icon: Play, text: '接收本次运行输入的资料，也是任务设置的入口。', action: '点击它，配置任务名称、任务要求与关联能力。', tone: 'blue' },
  { title: '模型处理', Icon: Sparkles, text: '让模型理解、分析或生成内容，是实际完成工作的步骤。', action: '填写节点指令，选择模型和输出格式。', tone: 'blue' },
  { title: '条件分支', Icon: GitBranch, text: '按规则选择一条路径，另一条路径会标记为“已跳过”。', action: '选择判断内容、规则和比较值；两个出口都要连线。', tone: 'amber' },
  { title: '并行分支', Icon: GitFork, text: '把同一份上游结果交给多个独立步骤，同时开展处理。', action: '至少连出两个分支；同一任务最多同时执行 4 个模型节点，其余就绪节点排队。', tone: 'violet' },
  { title: '结果汇合', Icon: Layers, text: '等待前面的分支完成或被跳过，收集实际执行的结果。', action: '至少连接两个前置分支。单个结果直接传下去，多个结果按节点标识合并。', tone: 'teal' },
  { title: '输出 · 最终输出', Icon: MessageSquare, text: '返回这次任务的最终结果，不会再调用一次模型。', action: '将最后的处理节点或汇合节点连接到这里。', tone: 'teal' },
] as const;

function NodeReference() {
  return <div className="workflow-guide-reference">
    <div className="workflow-guide-section-heading"><h3>每种节点负责什么</h3><p>先用“开始 → 模型处理 → 输出”跑通，再按需要加入条件和并行。</p></div>
    <div className="workflow-guide-node-grid">{nodeTypes.map(({ title, Icon, text, action, tone }) => <article key={title} className="workflow-guide-node-card">
      <span className={`workflow-guide-node-symbol tone-${tone}`}><Icon size={20} aria-hidden="true" /></span><div><h4>{title}</h4><p>{text}</p><small>{action}</small></div>
    </article>)}</div>
    <div className="workflow-guide-section-heading"><h3>三种常见连接方式</h3><p>蓝色箭头表示执行方向；条件选择一条路径，并行执行多条路径。</p></div>
    <div className="workflow-guide-patterns">{([
      ['sequence', '顺序处理', '适合前后有依赖的步骤，例如先提炼要点，再生成建议。'],
      ['condition', '条件路由', '例如判断用户输入是否包含“报告”：满足时生成报告，不满足时简短回答。'],
      ['parallel', '并行分析', '例如同时提炼要点与分析风险，汇合后再交给一个模型生成总报告。'],
    ] as const).map(([kind, title, text]) => <article key={kind}><div><h4>{title}</h4><p>{text}</p></div><WorkflowPatternPicture kind={kind} /></article>)}</div>
    <div className="workflow-guide-note"><BookOpen size={17} aria-hidden="true" /><p>“判断内容”可以选用户原始输入或上游节点输出。若判断的是 JSON 中的某个字段，在“JSON 字段路径”填写如 <code>risk.score</code>；文本判断则留空。</p></div>
  </div>;
}

function CommonQuestions() {
  return <div className="workflow-guide-questions">
    <div className="workflow-guide-section-heading"><h3>保存、生成、发布、上线，有什么区别？</h3><p>按照下面的顺序，逐步把编排变成可运行的任务。</p></div>
    <div className="workflow-guide-lifecycle">{[
      ['保存草稿', '保存当前编辑进度，未完成的流程也能保存。'],
      ['生成智能体', '检查并保存完整配置，供后续发布。'],
      ['发布新版本', '固定一份配置快照，得到 R1、R2 等版本。'],
      ['上线此版本', '让新会话使用选中的已发布版本。'],
      ['运行任务', '提交这一次输入，真正开始执行并返回结果。'],
    ].map(([title, text], index) => <article key={title}><span>{index + 1}</span><div><h4>{title}</h4><p>{text}</p></div></article>)}</div>
    <Collapse defaultActiveKey={['generation']} items={[
      { key: 'generation', label: '点击“生成智能体”后提示无法生成，怎么处理？', children: <ul><li>先检查默认模型、角色规则是否填写，以及引用的能力是否可用。</li><li>每个节点都必须连通“用户输入”和“最终输出”，不能有孤立节点或循环连线。</li><li>条件的两个出口都要连接；并行至少两个后续分支；汇合至少两个前置分支。</li><li>生成会检查全部任务。切换到提示中对应的任务补齐配置后，再次生成。</li></ul> },
      { key: 'assets', label: '关联技能、工具、知识和数据后，分别能做什么？', children: <ul><li><strong>技能：</strong>补充处理规范与方法，例如“先列事实，再给结论”。</li><li><strong>工具：</strong>由模型按需调用已配置的只读业务接口。</li><li><strong>知识 / 数据：</strong>检索能力资产中已录入的内容，作为回答依据。</li><li>先在“能力资产”中配置，再回到任务关联。仅在画布中选中资产，不会自动建立数据库连接或自动执行写库清洗。</li></ul> },
      { key: 'skip', label: '为什么有的节点显示“已跳过”？', children: <p>条件分支每次只选择一条路径。没有被选中的路径会显示“已跳过”，属于正常结果；汇合节点只收集实际执行的分支。若节点显示“失败”，在“运行记录”中展开“节点进度与输出”查看原因。</p> },
      { key: 'saved', label: '查看教程或关闭弹窗，会改变当前编排吗？', children: <p>不会。本教程只展示示意图，不会替换节点、创建任务或保存配置。关闭后会回到原来的编辑状态；离开编排页面前，仍需按原流程保存草稿。</p> },
      { key: 'version', label: '编辑完了，为什么运行结果还是旧版本？', children: <p>草稿修改不会直接覆盖上线版本。请生成并保存、发布新版本，在“运行记录”中上线该版本，再点击“新会话”测试。已有会话继续使用创建时的固定版本。</p> },
    ]} />
  </div>;
}

export function FusionWorkflowGuide({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [tab, setTab] = useState('steps'); const [step, setStep] = useState(0); const [zoomed, setZoomed] = useState(false);
  const content = useRef<HTMLDivElement>(null);
  useEffect(() => { if (open) { setTab('steps'); setStep(0); setZoomed(false); } }, [open]);
  const goTo = (index: number) => { setStep(index); content.current?.scrollTo({ top: 0 }); };
  const selected = steps[step];
  return <>
    <Modal open={open} onCancel={onClose} title={<div className="workflow-guide-modal-title"><BookOpen size={21} aria-hidden="true" /><span>可视化编排 · 图解教程</span></div>} width={1180} zIndex={1200} rootClassName="workflow-guide-modal" destroyOnHidden
      footer={<div className="workflow-guide-footer"><span>{tab === 'steps' ? `第 ${step + 1} 步 / 共 ${steps.length} 步` : '查看教程不会改动当前编排'}</span><div>{tab === 'steps' ? <><Button icon={<ArrowLeft size={14} />} disabled={step === 0} onClick={() => goTo(step - 1)}>上一步</Button>{step < steps.length - 1 ? <Button type="primary" onClick={() => goTo(step + 1)}>下一步 <ArrowRight size={14} /></Button> : <Button type="primary" onClick={onClose}>我知道了，返回编排</Button>}</> : <Button type="primary" onClick={onClose}>返回编排</Button>}</div></div>}>
      <p className="workflow-guide-intro">用“资料分析助手”作示例，按图完成第一次编排。蓝色编号对应操作说明，示意内容不会写入你的智能体。</p>
      <Tabs activeKey={tab} onChange={value => { setTab(value); content.current?.scrollTo({ top: 0 }); }} items={[
        { key: 'steps', label: '逐步操作', children: <div className="workflow-guide-walkthrough">
          <nav className="workflow-guide-step-nav" aria-label="教程步骤">{steps.map((item, index) => <button key={item.short} type="button" aria-current={index === step ? 'step' : undefined} className={index === step ? 'is-current' : ''} onClick={() => goTo(index)}><span>{index + 1}</span><strong>{item.short}</strong><ArrowRight size={14} aria-hidden="true" /></button>)}</nav>
          <div className="workflow-guide-step-content" ref={content}>
            <div className="workflow-guide-step-heading"><div><span>STEP 0{step + 1}</span><h3>{selected.title}</h3></div><Button size="small" icon={<Maximize2 size={14} />} onClick={() => setZoomed(true)}>查看大图</Button></div>
            <p className="workflow-guide-purpose">{selected.purpose}</p>
            <figure className="workflow-guide-figure"><FusionWorkflowGuideIllustration step={step} /><figcaption>蓝色编号 1、2 对应下面两项操作。图片可点击“查看大图”放大查看。</figcaption></figure>
            <ol className="workflow-guide-actions">{selected.actions.map(([title, text]) => <li key={title}><strong>{title}</strong><p>{text}</p></li>)}</ol>
            <div className="workflow-guide-example"><span>跟着试一试</span><p>{selected.example}</p></div>
            <div className="workflow-guide-note"><CheckCircle2 size={17} aria-hidden="true" /><p>{selected.tip}</p></div>
          </div>
        </div> },
        { key: 'nodes', label: '节点功能', children: <NodeReference /> },
        { key: 'questions', label: '常见问题', children: <CommonQuestions /> },
      ]} />
    </Modal>
    <Modal open={open && zoomed} onCancel={() => setZoomed(false)} title={`第 ${step + 1} 步 · ${selected.title}`} width={1480} zIndex={1400} rootClassName="workflow-guide-zoom-modal" destroyOnHidden footer={<Button onClick={() => setZoomed(false)}>返回教程</Button>}>
      <p className="workflow-guide-zoom-hint">窄屏下可左右滑动查看完整示意图。</p><div className="workflow-guide-zoom-scroll" role="region" aria-label="放大后的步骤示意图，可横向滚动" tabIndex={0}><FusionWorkflowGuideIllustration step={step} /></div>
    </Modal>
  </>;
}
