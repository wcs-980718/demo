export function visibleWorkspaceView<T extends { id: string }>(
  view: string,
  current: T | null,
  rows: T[],
): { view: string; selected: T | null } {
  if (view === 'table' || !current) {
    return { view: 'table', selected: null };
  }
  const selected = rows.find((item) => item.id === current.id) ?? null;
  if (!selected) {
    return { view: 'table', selected: null };
  }
  return { view, selected };
}
