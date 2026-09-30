import { createUuid } from '../../createUuid';
import { useState } from 'react';
import { Alert, Input, Modal } from 'antd';
import { fusionApi } from './fusionApi';

/**
 * 新建智能体：创建独立的智能体（不绑定项目），保存后直接进入编排工作台；
 * 项目绑定是后续操作，离开画布未保存时由工作区拦截确认。
 */
export function FusionCreateAgent({ admin, onClose, onCreated }: {
  admin: boolean; onClose: () => void; onCreated: (agentId: string) => void;
}) {
  const [name, setName] = useState('');
  const [taskName, setTaskName] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [operation] = useState(() => createUuid());
  const valid = !!(name.trim() && taskName.trim() && admin);
  const create = async () => {
    if (!valid) return;
    setSaving(true); setError('');
    try {
      const agent = await fusionApi.createAgent({ name, taskKey: 'main', taskName, idempotencyKey: operation });
      onCreated(agent.id);
    } catch (failure) { setError((failure as Error).message); }
    finally { setSaving(false); }
  };
  return <Modal open title="新建智能体" okText="创建并开始编排" cancelText="取消" onCancel={onClose} onOk={create} confirmLoading={saving} okButtonProps={{ disabled: !valid }}>
    <div className="hub-form">
      <p className="hub-help">创建独立智能体并直接进入编排工作台，手动拖拽编辑；之后可随时绑定到业务项目或解除绑定。不保存可直接返回，不会留下多余草稿。</p>
      {!admin && <Alert type="info" title="当前账号没有创建智能体的权限。" />}
      {error && <Alert showIcon type="error" title={error} />}
      <label>智能体名称<Input aria-label="智能体名称" maxLength={128} value={name} onChange={event => setName(event.target.value)} placeholder="填写智能体名称" /></label>
      <label>首个任务名称<Input aria-label="首个任务名称" maxLength={128} value={taskName} onChange={event => setTaskName(event.target.value)} placeholder="这个智能体需要完成什么任务？" /></label>
    </div>
  </Modal>;
}
