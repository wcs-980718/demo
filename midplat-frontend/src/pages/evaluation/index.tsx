import { useEffect } from 'react';
import { history, useLocation } from '@umijs/max';
import { PageHeader } from '@/components/PageHeader';
import { CasesPanel } from './CasesPanel';
import { DatasetsPanel } from './DatasetsPanel';
import { ExperimentsPanel } from './ExperimentsPanel';
import { GatesPanel } from './GatesPanel';
import { RunsPanel } from './RunsPanel';
import { ExceptionHistoryPanel } from './exception/ExceptionHistoryPanel';
import { normalizeEvaluationView, type EvaluationView } from './evaluationView';
import './index.css';

const LEGACY_VIEWS = new Set<EvaluationView>(['cases', 'datasets', 'runs', 'experiments', 'gates']);

export default function EvaluationPage() {
  const location = useLocation();
  const activeView = normalizeEvaluationView(new URLSearchParams(location.search).get('view'));
  const legacy = LEGACY_VIEWS.has(activeView);

  useEffect(() => {
    if (!legacy && location.search !== '') {
      history.replace('/evaluation');
    }
  }, [legacy, location.pathname, location.search]);

  return (
    <main className="page-shell evaluation-page">
      {legacy ? (
        <>
          <PageHeader
            eyebrow="AI EVALUATION GOVERNANCE"
            title="评测治理（开发中）"
            description="把案例、评测集、运行结果和发布门禁沉淀为可追溯的数据飞轮。"
          />
          {activeView === 'cases' && <CasesPanel />}
          {activeView === 'datasets' && <DatasetsPanel />}
          {activeView === 'runs' && <RunsPanel />}
          {activeView === 'experiments' && <ExperimentsPanel />}
          {activeView === 'gates' && <GatesPanel />}
        </>
      ) : (
        <ExceptionHistoryPanel />
      )}
    </main>
  );
}
