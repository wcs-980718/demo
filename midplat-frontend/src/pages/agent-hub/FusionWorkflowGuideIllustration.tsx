import { useId, type ReactNode } from 'react';

type PictureProps = { step: number };
const ink = 'var(--text)';
const muted = 'var(--muted)';
const blue = 'var(--primary)';
const surface = 'var(--surface)';
const border = 'var(--border)';

function Label({ x, y, children, small = false, fill = ink, bold = false }: {
  x: number; y: number; children: ReactNode; small?: boolean; fill?: string; bold?: boolean;
}) {
  return <text x={x} y={y} fill={fill} fontSize={small ? 13 : 15} fontWeight={bold ? 650 : 400}>{children}</text>;
}

function Callout({ x, y, number, label, width = 176 }: { x: number; y: number; number: number; label: string; width?: number }) {
  return <g>
    <rect x={x} y={y} width={width} height={36} rx={18} fill={blue} />
    <circle cx={x + 19} cy={y + 18} r={12} fill="#fff" />
    <text x={x + 19} y={y + 23} textAnchor="middle" fill={blue} fontSize={14} fontWeight={700}>{number}</text>
    <text x={x + 39} y={y + 23} fill="#fff" fontSize={14} fontWeight={600}>{label}</text>
  </g>;
}

function Box({ x, y, width, height, active = false }: { x: number; y: number; width: number; height: number; active?: boolean }) {
  return <rect x={x} y={y} width={width} height={height} rx={9} fill={active ? 'var(--primary-lt)' : surface} stroke={active ? blue : border} strokeWidth={active ? 2 : 1} />;
}

function ExampleNode({ x, y, title, kind, active = false, width = 150 }: { x: number; y: number; title: string; kind: string; active?: boolean; width?: number }) {
  return <g>
    <Box x={x} y={y} width={width} height={70} active={active} />
    <Label x={x + 14} y={y + 24} fill={muted} small>{kind}</Label>
    <Label x={x + 14} y={y + 49} bold>{title}</Label>
    {kind !== '开始' && <circle cx={x} cy={y + 35} r={5} fill={surface} stroke={blue} strokeWidth={2} />}
    {kind !== '输出' && <circle cx={x + width} cy={y + 35} r={5} fill={surface} stroke={blue} strokeWidth={2} />}
  </g>;
}

function PublishPicture({ arrow }: { arrow: string }) {
  return <>
    <Box x={24} y={70} width={344} height={306} active />
    <Callout x={44} y={90} number={1} label="先发布配置版本" width={202} />
    <Label x={46} y={157} bold>版本与发布</Label>
    <Box x={46} y={179} width={298} height={59} />
    <Label x={61} y={204} small fill={muted}>发布说明</Label>
    <Label x={61} y={225} small>新增“提炼要点 → 生成建议”流程</Label>
    <rect x={46} y={258} width={135} height={36} rx={7} fill={blue} />
    <Label x={66} y={282} fill="#fff">确认发布</Label>
    <Label x={46} y={331} small fill={muted}>得到固定版本，例如 R1</Label>
    <path d="M 378 224 H 422" fill="none" stroke={blue} strokeWidth={2} markerEnd={arrow} />
    <Box x={438} y={70} width={378} height={306} active />
    <Callout x={458} y={90} number={2} label="再上线并试运行" width={202} />
    <Label x={460} y={157} bold>运行记录</Label>
    <Box x={460} y={175} width={168} height={38} />
    <Label x={475} y={200}>选择版本：R1</Label>
    <rect x={638} y={175} width={154} height={38} rx={7} fill={blue} />
    <Label x={662} y={200} fill="#fff">上线此版本</Label>
    <Label x={460} y={248} small>选择任务 → 输入测试资料</Label>
    <rect x={460} y={268} width={135} height={36} rx={7} fill={blue} />
    <Label x={480} y={292} fill="#fff">运行任务</Label>
    <Label x={460} y={345} small fill={muted}>查看结果与每个节点的执行进度</Label>
    <Label x={24} y={410} small fill={muted}>上线使版本可被新会话使用；点击“运行任务”才会开始处理这次输入。</Label>
  </>;
}

/** A code-native illustration: labels stay sharp when enlarged and follow the app theme. */
export function FusionWorkflowGuideIllustration({ step }: PictureProps) {
  const id = useId().replace(/:/g, '');
  const arrow = `url(#guide-arrow-${id})`;
  const headings = ['选择默认模型与角色', '填写任务与关联能力', '添加节点并写清指令', '从输出点连到输入点', '检查并生成配置', '发布版本，上线后运行'];
  const field = (y: number, name: string, value: string, active = false) => <g key={name}>
    <Label x={619} y={y} small fill={muted}>{name}</Label>
    <Box x={616} y={y + 12} width={182} height={36} active={active} />
    <Label x={628} y={y + 36} small>{value}</Label>
  </g>;
  return <svg className="workflow-guide-picture" viewBox="0 0 840 438" role="img" aria-label={`第 ${step + 1} 步示意图：${headings[step]}`}>
    <title>{`第 ${step + 1} 步：${headings[step]}`}</title>
    <desc>蓝色编号标记对应下方的操作说明。这是说明用的示意界面，不是当前智能体的实际配置。</desc>
    <defs><marker id={`guide-arrow-${id}`} viewBox="0 0 10 10" refX={9} refY={5} markerWidth={6} markerHeight={6} orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill={blue} /></marker></defs>
    <rect x={1} y={1} width={838} height={436} rx={12} fill="var(--bg)" stroke={border} />
    <Label x={24} y={33} bold>示例 · 资料分析助手</Label><Label x={667} y={33} small fill={muted}>图中内容仅作说明</Label>
    {step === 5 ? <PublishPicture arrow={arrow} /> : <>
      <path d="M 1 52 H 839 M 1 110 H 839" stroke={border} />
      <Label x={21} y={86}>任务：资料分析</Label>
      <Box x={391} y={66} width={136} height={30} active={step === 0} /><Label x={417} y={87} small>智能体配置</Label>
      <Box x={537} y={66} width={118} height={30} active={step === 4} /><Label x={569} y={87} small>保存草稿</Label>
      <rect x={665} y={66} width={150} height={30} rx={7} fill={blue} /><Label x={704} y={87} small fill="#fff">生成智能体</Label>
      <path d="M 178 111 V 418 M 598 111 V 418" stroke={border} />
      <Label x={20} y={140} bold>添加节点</Label>
      {['模型处理', '条件分支', '并行分支', '结果汇合'].map((name, index) => <g key={name}><Box x={17} y={158 + index * 54} width={144} height={41} active={step === 2 && index === 0} /><Label x={31} y={184 + index * 54} small>{name}</Label><Label x={137} y={184 + index * 54} small fill={blue}>＋</Label></g>)}
      <Label x={21} y={403} small fill={muted}>也可以直接点击添加</Label>
      <ExampleNode x={205} y={145} title="用户输入" kind="开始" width={154} active={step === 1} />
      <ExampleNode x={413} y={145} title="提炼要点" kind="模型处理" active={step === 2} />
      <ExampleNode x={205} y={286} title="最终输出" kind="输出" width={154} />
      <ExampleNode x={413} y={286} title="生成建议" kind="模型处理" />
      <g fill="none" stroke={step === 3 ? blue : 'var(--faint)'} strokeWidth={step === 3 ? 3 : 1.5}>
        <path d="M 365 180 H 405" markerEnd={arrow} />
        <path d="M 569 180 H 581 V 251 H 390 V 321 H 405" markerEnd={arrow} />
        <path d="M 569 321 H 581 V 380 H 190 V 321 H 198" markerEnd={arrow} />
      </g>
      <Label x={619} y={140} bold>{step === 0 ? '智能体配置' : step === 1 ? '任务设置' : '节点配置'}</Label>
      {step === 0 ? <>{field(180, '默认模型', '选择模型（必填）', true)}{field(261, '角色规则', '你是一名资料分析助手', true)}<Label x={619} y={353} small fill={muted}>也可关联提示词模板</Label></> : step === 1 ? <>{field(180, '任务名称', '资料分析', true)}{field(255, '任务指令', '先列要点，再给建议')}{field(329, '关联能力资产（可选）', '技能 · 资料分析规范', true)}</> : <>{field(180, '使用模型', '继承默认模型')}{field(255, '节点指令', '提炼资料中的 3 个要点', step === 2)}{field(329, '输出格式', '文本')}</>}
      {step === 0 && <><Callout x={278} y={14} number={1} label="打开配置" width={139} /><path d="M 417 33 L 454 59" stroke={blue} strokeWidth={2} markerEnd={arrow} /><Callout x={611} y={389} number={2} label="选模型，写角色" width={201} /></>}
      {step === 1 && <><Callout x={191} y={226} number={1} label="点击“用户输入”" width={201} /><Callout x={611} y={389} number={2} label="填写任务要求" width={187} /></>}
      {step === 2 && <><Callout x={250} y={12} number={1} label="拖入或点击添加" width={216} /><path d="M 167 180 C 252 237 321 112 405 161" stroke={blue} strokeWidth={2} strokeDasharray="6 4" fill="none" markerEnd={arrow} /><Callout x={367} y={389} number={2} label="点选节点，在右侧写指令" width={273} /></>}
      {step === 3 && <><Callout x={244} y={13} number={1} label="从右侧圆点拖出连线" width={232} /><circle cx={359} cy={180} r={12} fill="none" stroke={blue} strokeWidth={2} /><circle cx={413} cy={180} r={12} fill="none" stroke={blue} strokeWidth={2} /><Callout x={199} y={389} number={2} label="松开在下个节点的左侧圆点" width={299} /></>}
      {step === 4 && <>
        <rect x={198} y={135} width={385} height={228} rx={12} fill={surface} stroke={blue} strokeWidth={2} />
        <Label x={219} y={169} bold fill={blue}>编排检查通过</Label>
        <Label x={219} y={204}>✓ 已选模型并填写角色</Label>
        <Label x={219} y={237}>✓ 节点已连通，条件出口完整</Label>
        <Label x={219} y={270}>✓ 生成全部任务的配置</Label>
        <rect x={221} y={295} width={163} height={39} rx={7} fill={blue} /><Label x={256} y={321} fill="#fff">生成并保存</Label>
        <Callout x={478} y={14} number={1} label="点击“生成智能体”" width={235} /><Callout x={267} y={381} number={2} label="检查通过后，生成并保存" width={287} />
      </>}
    </>}
  </svg>;
}

/** Small topology pictures for the node reference; the labels describe every route. */
export function WorkflowPatternPicture({ kind }: { kind: 'sequence' | 'condition' | 'parallel' }) {
  const id = useId().replace(/:/g, '');
  const arrow = `url(#pattern-arrow-${id})`;
  const names = { sequence: '顺序处理：先提炼要点，再生成建议', condition: '条件路由：满足和不满足各执行一条路径', parallel: '并行分析：两项分析同时执行，再汇合结果' };
  const pill = (x: number, y: number, name: string, active = false) => <g key={`${x}-${y}`}><Box x={x} y={y} width={120} height={42} active={active} /><Label x={x + 13} y={y + 27} small>{name}</Label></g>;
  return <svg viewBox="0 0 570 190" role="img" aria-label={names[kind]} className="workflow-pattern-picture">
    <title>{names[kind]}</title><defs><marker id={`pattern-arrow-${id}`} viewBox="0 0 10 10" refX={9} refY={5} markerWidth={6} markerHeight={6} orient="auto"><path d="M 0 0 L 10 5 L 0 10 z" fill={blue} /></marker></defs>
    <g stroke={blue} strokeWidth={2} fill="none" markerEnd={arrow}>
      {kind === 'sequence' ? <><path d="M 140 95 H 218" /><path d="M 344 95 H 422" /></> : <><path d="M 140 95 H 172 V 45 H 218" /><path d="M 140 95 H 172 V 145 H 218" /><path d="M 344 45 H 381 V 95 H 422" /><path d="M 344 145 H 381 V 95 H 422" /></>}
    </g>
    {kind === 'sequence' ? <>{pill(20, 74, '用户输入')}{pill(224, 74, '提炼要点', true)}{pill(428, 74, '生成建议')}</> : <>{pill(20, 74, kind === 'condition' ? '条件：含“报告”' : '并行分支', true)}{pill(224, 24, kind === 'condition' ? '生成报告' : '提炼要点')}{pill(224, 124, kind === 'condition' ? '简短回答' : '分析风险')}{pill(428, 74, '结果汇合', true)}{kind === 'condition' && <><Label x={177} y={32} small fill={muted}>满足</Label><Label x={167} y={176} small fill={muted}>不满足</Label></>}</>}
  </svg>;
}
