import type { ReactNode } from 'react';

export function SettingsSectionHeader({
  step,
  title,
  description,
  extra,
}: {
  step?: number;
  title: string;
  description: string;
  extra?: ReactNode;
}) {
  return (
    <div className="settings-section-head">
      <div className="settings-section-heading">
        {step ? <span className="settings-step" aria-hidden="true">{String(step).padStart(2, '0')}</span> : null}
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
      </div>
      {extra ? <div className="settings-section-extra">{extra}</div> : null}
    </div>
  );
}
