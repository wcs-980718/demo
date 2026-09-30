import { useEffect, useState } from 'react';
import { Form, Input, Modal, Select, Switch } from 'antd';
import type { PlatformApiItem, PlatformApiPayload } from '@/api/midplatApi';
import { newApiId, normalizeSourcePath, previewSourcePath, sourceApiUrl } from '@/pages/platforms/apiCatalog';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({ value, label: value }));

export type ApiFormValues = PlatformApiPayload & { platformId?: string };

export function ApiFormModal({
  open,
  title,
  submitting,
  initial,
  platformOptions,
  lockPlatform,
  lockId,
  entryUrl,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  title: string;
  submitting: boolean;
  initial: (Partial<PlatformApiItem> & { platformId?: string }) | null;
  platformOptions?: Array<{ value: string; label: string }>;
  lockPlatform?: boolean;
  lockId?: boolean;
  entryUrl?: string | null;
  onCancel: () => void;
  onSubmit: (payload: ApiFormValues) => void;
}) {
  const [form] = Form.useForm<ApiFormValues>();
  const [pending, setPending] = useState<ApiFormValues | null>(null);
  const path = Form.useWatch('path', form);
  const editing = Boolean(lockId);

  useEffect(() => {
    if (!open) return;
    setPending(null);
    form.setFieldsValue({
      id: initial?.id ?? (lockId ? undefined : newApiId()),
      platformId: initial?.platformId,
      name: initial?.name ?? '',
      method: initial?.method ?? 'POST',
      path: initial?.path ?? '/',
      note: initial?.note ?? '',
      external: initial?.external ?? false,
    });
    // Snapshot the opened record once; parent passes a new initial object each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <>
    <Modal
      title={title}
      open={open}
      okText="保存"
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      width={640}
      onCancel={() => {
        setPending(null);
        onCancel();
      }}
      onOk={() => void form.validateFields().then((values) => {
        setPending({
          ...values,
          id: (values.id ?? '').trim() || undefined,
          path: normalizeSourcePath(values.path),
          platformId: values.platformId ?? initial?.platformId,
        });
      })}
    >
      <Form form={form} layout="vertical" requiredMark="optional">
        {platformOptions?.length ? (
          <Form.Item name="platformId" label="所属项目" rules={[{ required: true, message: '请选择所属项目' }]}>
            <Select options={platformOptions} disabled={lockPlatform} placeholder="选择接口所属项目" />
          </Form.Item>
        ) : null}
        <Form.Item name="name" label="接口名称" rules={[{ required: true, message: '请填写接口名称' }]}>
          <Input placeholder="例如 知识库列表" />
        </Form.Item>
        <Form.Item
          name="id"
          label="接口编码"
          rules={[
            { required: true, message: '请填写接口编码' },
            { pattern: /^[A-Za-z][A-Za-z0-9._-]{1,63}$/, message: '以字母开头，仅含字母、数字、点、下划线和短横线' },
          ]}
          extra="会出现在对外调用地址里。修改已发布编码会让旧地址失效，因此保存后不可改。"
        >
          <Input placeholder="例如 kb-list" disabled={lockId} />
        </Form.Item>
        <div className="capability-form-grid">
          <Form.Item name="method" label="请求方法" rules={[{ required: true, message: '请选择方法' }]}>
            <Select options={METHODS} />
          </Form.Item>
          <Form.Item
            name="path"
            label="来源 URL"
            rules={[
              { required: true, message: '请填写来源 URL' },
              {
                validator: async (_, value) => {
                  if (!value) return;
                  const normalized = previewSourcePath(String(value));
                  if (!normalized.startsWith('/')) {
                    throw new Error('请填写路径或完整 URL');
                  }
                },
              },
            ]}
            extra={entryUrl ? `转发到 ${sourceApiUrl(entryUrl, previewSourcePath(path || '/'))}` : '可填路径或完整地址，保存时只保留路径。'}
          >
            <Input placeholder="/api/knowledge-bases/{id}" />
          </Form.Item>
        </div>
        <Form.Item name="note" label="用途说明">
          <Input placeholder="例如 标注发布中心下拉" />
        </Form.Item>
        <Form.Item name="external" label="允许其他项目经中台调用" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
    <Modal
      title={editing ? '确认修改这个接口？' : '确认新增这个接口？'}
      open={Boolean(pending)}
      okText="确认"
      cancelText="取消"
      confirmLoading={submitting}
      zIndex={1200}
      onCancel={() => setPending(null)}
      onOk={() => {
        if (pending) onSubmit(pending);
      }}
    >
      <p>
        {editing
          ? '保存后会立即更新中台接口目录和对外调用地址。'
          : '确认后会立即写入中台接口目录，其他项目可按开放范围经中台调用。'}
      </p>
      {pending ? (
        <p className="note">
          {pending.method} {pending.path}
          {pending.external ? ' · 允许其他项目' : ' · 仅本项目'}
        </p>
      ) : null}
    </Modal>
    </>
  );
}
