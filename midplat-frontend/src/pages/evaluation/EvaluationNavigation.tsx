import { useRef, type KeyboardEvent } from 'react';
import {
  BarChart3,
  Database,
  FlaskConical,
  ListChecks,
  PlayCircle,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react';
import { EVALUATION_VIEWS, type EvaluationView } from './evaluationView';

const VIEW_META: Record<EvaluationView, { label: string; icon: LucideIcon }> = {
  overview: { label: '总览', icon: BarChart3 },
  cases: { label: '案例中心', icon: ListChecks },
  datasets: { label: '评测集', icon: Database },
  runs: { label: '评测任务', icon: PlayCircle },
  experiments: { label: '实验对比', icon: FlaskConical },
  gates: { label: '发布门禁', icon: ShieldCheck },
};

export function EvaluationNavigation({
  activeView,
  onChange,
}: {
  activeView: EvaluationView;
  onChange: (view: EvaluationView) => void;
}) {
  const tabRefs = useRef<Partial<Record<EvaluationView, HTMLButtonElement | null>>>({});

  const activateFromKeyboard = (event: KeyboardEvent<HTMLButtonElement>, current: EvaluationView) => {
    const currentIndex = EVALUATION_VIEWS.indexOf(current);
    let targetIndex: number | null = null;
    if (event.key === 'ArrowRight') targetIndex = (currentIndex + 1) % EVALUATION_VIEWS.length;
    if (event.key === 'ArrowLeft') targetIndex = (currentIndex - 1 + EVALUATION_VIEWS.length) % EVALUATION_VIEWS.length;
    if (event.key === 'Home') targetIndex = 0;
    if (event.key === 'End') targetIndex = EVALUATION_VIEWS.length - 1;
    if (targetIndex === null) return;
    event.preventDefault();
    const targetView = EVALUATION_VIEWS[targetIndex];
    onChange(targetView);
    tabRefs.current[targetView]?.focus();
  };

  return (
    <nav className="evaluation-tabs" role="tablist" aria-label="评测治理功能">
      {EVALUATION_VIEWS.map((view) => {
        const meta = VIEW_META[view];
        const Icon = meta.icon;
        const selected = view === activeView;
        return (
          <button
            key={view}
            ref={(node) => { tabRefs.current[view] = node; }}
            type="button"
            id={`evaluation-tab-${view}`}
            role="tab"
            aria-selected={selected}
            aria-controls={`evaluation-panel-${view}`}
            tabIndex={selected ? 0 : -1}
            className={selected ? 'is-active' : undefined}
            onClick={() => onChange(view)}
            onKeyDown={(event) => activateFromKeyboard(event, view)}
          >
            <Icon size={17} strokeWidth={1.8} aria-hidden="true" />
            <span>{meta.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
