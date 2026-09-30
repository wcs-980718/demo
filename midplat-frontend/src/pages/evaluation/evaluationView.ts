export const EVALUATION_VIEWS = [
  'overview',
  'cases',
  'datasets',
  'runs',
  'experiments',
  'gates',
] as const;

export type EvaluationView = (typeof EVALUATION_VIEWS)[number];

const evaluationViewSet = new Set<string>(EVALUATION_VIEWS);

export function normalizeEvaluationView(value: string | null): EvaluationView {
  return value !== null && evaluationViewSet.has(value)
    ? value as EvaluationView
    : 'overview';
}

export function evaluationViewHref(view: EvaluationView): string {
  return `/evaluation?view=${view}`;
}

export type EvaluationPanelDescriptor = {
  view: EvaluationView;
  id: string;
  labelledBy: string;
  hidden: boolean;
};

export function evaluationPanelDescriptors(activeView: EvaluationView): EvaluationPanelDescriptor[] {
  return EVALUATION_VIEWS.map((view) => ({
    view,
    id: `evaluation-panel-${view}`,
    labelledBy: `evaluation-tab-${view}`,
    hidden: view !== activeView,
  }));
}
