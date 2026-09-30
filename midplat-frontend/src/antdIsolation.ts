export const midplatRootClassName = 'midplat-frontend-root';
export const midplatRootSelector = `.${midplatRootClassName}`;
export const antPrefixCls = 'mp-ant';
export const antIconPrefixCls = 'mp-anticon';

type ClosestCapable = {
  closest: (selector: string) => unknown;
};

export function resolveMidplatContainer<TContainer>(
  triggerNode: ClosestCapable | null | undefined,
  fallbackContainer: TContainer,
): TContainer {
  const scopedRoot = triggerNode?.closest(midplatRootSelector);
  return (scopedRoot as TContainer | null | undefined) ?? fallbackContainer;
}
