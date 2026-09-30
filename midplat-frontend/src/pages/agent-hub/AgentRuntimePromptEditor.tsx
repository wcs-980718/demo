import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Drawer, Form, Input, Popconfirm, Spin, message } from 'antd';
import { midplatApi, type AgentPromptBundle } from '@/api/midplatApi';
import { SettingsSectionHeader } from '@/components/SettingsSectionHeader';

type PromptValues = Pick<AgentPromptBundle, 'soulContent' | 'skillContent'>;

/** 根因报告 / 鱼骨图的运行时提示词。只在智能体开发的智能体配置里编辑。 */
export function AgentRuntimePromptButton({ platformId, platformName, disabled }: { platformId: string; platformName: string; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  return <>
    <Button size="small" disabled={disabled} onClick={() => setOpen(true)}>编辑{platformName}提示词</Button>
    <Drawer open={open} title={`${platformName} · 智能体提示词`} width={860} onClose={() => setOpen(false)} destroyOnHidden>
      {open ? <AgentRuntimePromptEditor platformId={platformId} platformName={platformName} /> : null}
    </Drawer>
  </>;
}

function AgentRuntimePromptEditor({ platformId, platformName }: { platformId: string; platformName: string }) {
  const cache = useQueryClient();
  const [form] = Form.useForm<PromptValues>();
  const bundle = useQuery({
    queryKey: ['agent-runtime-prompt', platformId],
    queryFn: () => midplatApi.getAgentPrompt(platformId),
  });
  const save = useMutation({
    mutationFn: (payload: PromptValues) => midplatApi.updateAgentPrompt(platformId, payload),
    onSuccess: (next) => {
      form.setFieldsValue({ soulContent: next.soulContent, skillContent: next.skillContent });
      void cache.setQueryData(['agent-runtime-prompt', platformId], next);
      message.success('完整提示词已保存并热更新到智能体');
    },
    onError: (error: Error) => message.error(error.message),
  });
  const soulContent = Form.useWatch('soulContent', form) ?? bundle.data?.soulContent ?? '';
  const skillContent = Form.useWatch('skillContent', form) ?? bundle.data?.skillContent ?? '';

  if (bundle.isPending) return <div className="loading-panel"><Spin size="large" /></div>;
  if (bundle.isError || !bundle.data) {
    return <div className="empty">完整提示词读取失败。<Button onClick={() => void bundle.refetch()}>重试</Button></div>;
  }
  const current = bundle.data;
  return (
    <>
      <p className="note">
        {current.writable
          ? '保存会同时持久化资源文件并刷新智能体内存，无需重启。共享 SOUL 的修改会同时影响根因报告和鱼骨图。'
          : '当前运行时版本尚未开放资源写入；页面可查看完整提示词，部署新版运行时后会自动启用保存。'}
      </p>
      <Form form={form} layout="vertical" initialValues={{ soulContent: current.soulContent, skillContent: current.skillContent }}>
        <section className="comp agent-prompt-editor">
          <SettingsSectionHeader
            step={1}
            title="共享角色与边界（SOUL.md）"
            description={`${platformName} 与共用该智能体的项目一起使用这份角色边界。`}
            extra={<span className="chip chip-prompt">共享资源</span>}
          />
          <Form.Item name="soulContent" label="SOUL 内容" rules={[{ required: true, whitespace: true, message: 'SOUL 不能为空' }]}>
            <Input.TextArea autoSize={{ minRows: 12, maxRows: 28 }} spellCheck={false} />
          </Form.Item>
          <div className="agent-prompt-meta">
            <span className="agent-prompt-warning">修改后会同时影响两个根因分析任务</span>
            <span>{soulContent.length} 字符</span>
          </div>
        </section>
        <section className="comp agent-prompt-editor">
          <SettingsSectionHeader
            step={2}
            title={current.skillTitle}
            description={`仅用于 ${current.task}，包含该任务的步骤、工具使用、输出结构和质量要求。`}
            extra={<span className="chip chip-prompt">当前任务</span>}
          />
          <Form.Item name="skillContent" label={`${current.skillTitle}（SKILL.md）`} rules={[{ required: true, whitespace: true, message: '任务技能提示词不能为空' }]}>
            <Input.TextArea autoSize={{ minRows: 18, maxRows: 40 }} spellCheck={false} />
          </Form.Item>
          <div className="agent-prompt-meta">
            <span>资源键：<span className="mono">{current.skillKey}</span></span>
            <span>{skillContent.length} 字符</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 16 }}>
            {current.writable ? (
              <Popconfirm
                title="保存并热更新完整提示词？"
                description="将覆盖运行时的 SOUL 和当前任务技能文件，并立即刷新智能体。"
                okText="确认保存"
                cancelText="取消"
                onConfirm={async () => save.mutate(await form.validateFields())}
                okButtonProps={{ loading: save.isPending }}
              >
                <Button type="primary" loading={save.isPending}>保存并热更新</Button>
              </Popconfirm>
            ) : (
              <Button type="primary" disabled>运行时升级后可保存</Button>
            )}
          </div>
        </section>
      </Form>
    </>
  );
}
