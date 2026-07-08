import { useState, useMemo } from 'react';
import LayoutFrame from './components/Layout';
import Dashboard from './pages/Dashboard/index';
import Dispatch from './pages/Dispatch/index';
import LineSignal from './pages/LineSignal/index';
import EnergyEvaluation from './pages/EnergyEvaluation/index';

const pageComponents: Record<string, React.FC> = {
  dashboard: Dashboard,
  dispatch: Dispatch,
  linesignal: LineSignal,
  energy: EnergyEvaluation,
};

function App() {
  const [module, setModule] = useState('dashboard');

  const PageComponent = useMemo(() => pageComponents[module] || Dashboard, [module]);

  return (
    <LayoutFrame currentModule={module} onModuleChange={setModule}>
      <PageComponent />
    </LayoutFrame>
  );
}

export default App;
