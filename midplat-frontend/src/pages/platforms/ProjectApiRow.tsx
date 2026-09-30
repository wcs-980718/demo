import { useState } from 'react';
import { Button, Popconfirm, Switch, Tooltip, message } from 'antd';
import { Copy, Eye, Pencil, Trash2 } from 'lucide-react';
import type { PlatformApiItem } from '@/api/midplatApi';
import { copyText } from '@/copyText';
import { ApiDetailDrawer } from '@/pages/platforms/ApiDetailDrawer';
import { openApiUrl, sourceApiUrl } from '@/pages/platforms/apiCatalog';

export function ProjectApiRow({
  platformId,
  platformName,
  entryUrl,
  token,
  api,
  saving,
  disabled,
  onToggle,
  onEdit,
  onDelete,
}: {
  platformId: string;
  platformName: string;
  entryUrl?: string | null;
  token?: string | null;
  api: PlatformApiItem;
  saving: boolean;
  disabled: boolean;
  onToggle: (external: boolean) => void;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const [detailOpen, setDetailOpen] = useState(false);
  const gatewayUrl = openApiUrl(platformId, api.id, api.path);
  const originUrl = sourceApiUrl(entryUrl, api.path);
  return (
    <div className={`project-api-row${api.external ? ' is-external' : ''}`} role="listitem">
      <span className={`method m-${api.method.toLowerCase()}`}>{api.method}</span>
      <div className="project-api-info">
        <h4>{api.name}</h4>
        <p className="project-api-url">
          <span>中台</span>
          <code title={gatewayUrl}>{gatewayUrl}</code>
        </p>
        <p className="project-api-url">
          <span>来源</span>
          <code title={originUrl}>{originUrl}</code>
        </p>
        {api.note ? <p>{api.note}</p> : null}
      </div>
      <div className="project-api-controls">
        <div className="project-api-toggle">
          <span>{api.external ? '允许其他项目' : '仅本项目'}</span>
          <Popconfirm
            title={api.external ? `禁止其他项目调用「${api.name}」？` : `允许其他项目调用「${api.name}」？`}
            description={api.external
              ? '关闭后，其他项目经中台调用会返回 403。本项目自己调用不受影响。'
              : '打开后，其他项目可携带中台凭证经网关调用这个接口。'}
            okText="确认"
            cancelText="取消"
            disabled={saving || disabled}
            onConfirm={() => onToggle(!api.external)}
          >
            <span>
              <Switch
                checked={api.external}
                loading={saving}
                disabled={disabled}
                aria-label={`${api.name}允许其他项目经中台调用`}
              />
            </span>
          </Popconfirm>
        </div>
        <div className="project-api-actions">
          <Tooltip title="查看请求地址和参数">
            <Button
              type="text"
              aria-label={`查看 ${api.name} 请求地址和参数`}
              icon={<Eye size={15} />}
              onClick={() => setDetailOpen(true)}
            />
          </Tooltip>
          <Tooltip title="复制中台请求地址">
            <Button
              type="text"
              aria-label={`复制 ${api.name} 中台地址`}
              icon={<Copy size={15} />}
              onClick={async () => {
                await copyText(gatewayUrl);
                message.success('中台请求地址已复制');
              }}
            />
          </Tooltip>
          <Tooltip title="修改接口">
            <Button type="text" aria-label={`修改 ${api.name}`} icon={<Pencil size={15} />} onClick={onEdit} />
          </Tooltip>
          <Popconfirm
            title="删除这个接口？"
            description="删除后其他项目不能再经中台调用它。可再手动新增回来。"
            okText="删除"
            cancelText="取消"
            onConfirm={onDelete}
          >
            <Button type="text" danger aria-label={`删除 ${api.name}`} icon={<Trash2 size={15} />} />
          </Popconfirm>
        </div>
      </div>
      <ApiDetailDrawer
        open={detailOpen}
        platformId={platformId}
        platformName={platformName}
        entryUrl={entryUrl}
        token={token}
        api={api}
        onClose={() => setDetailOpen(false)}
      />
    </div>
  );
}
