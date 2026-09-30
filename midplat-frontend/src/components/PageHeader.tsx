import type { ReactNode } from 'react';
import { Space } from 'antd';

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow?: string;
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-head">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h2>{title}</h2>
        <p className="sub">{description}</p>
      </div>
      {actions ? <Space wrap className="page-head-actions">{actions}</Space> : null}
    </div>
  );
}
