export function createComparisonSelectionHandlers(
  resetComparison: () => void,
  updateBaseline: (value: string) => void,
  updateCandidate: (value: string) => void,
) {
  const replace = (update: (value: string) => void, value: string) => {
    resetComparison();
    update(value);
  };
  return {
    selectBaseline: (value: string) => replace(updateBaseline, value),
    selectCandidate: (value: string) => replace(updateCandidate, value),
  };
}
