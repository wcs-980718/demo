import { Select } from 'antd';
import { ICON_OPTION_GROUPS, LucideIcon } from '@/components/LucideIcon';

export function IconPicker({ value, onChange }: { value?: string; onChange?: (value: string) => void }) {
  return (
    <Select
      value={value}
      onChange={onChange}
      showSearch
      placeholder="选择图标"
      filterOption={(input, option) => {
        const data = (option as { value?: string; label?: React.ReactNode; options?: unknown } | undefined) ?? {};
        // 分组节点不直接匹配（否则命中组名会整组带出），交给子项各自过滤
        if (data.options) return false;
        const label = typeof data.label === 'string' ? data.label : '';
        const haystack = `${label} ${data.value ?? ''}`.toLowerCase();
        return haystack.includes(input.trim().toLowerCase());
      }}
      options={ICON_OPTION_GROUPS.map((group) => ({
        label: group.label,
        options: group.options.map((option) => ({ value: option.value, label: option.label })),
      }))}
      optionRender={(option) => (
        <span className="icon-option">
          <LucideIcon name={String(option.value)} size={16} />
          {option.label}
        </span>
      )}
      labelRender={(option) => (
        <span className="icon-option">
          <LucideIcon name={String(option.value)} size={16} />
          {option.label}
        </span>
      )}
    />
  );
}
