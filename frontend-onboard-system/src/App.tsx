import Vehicle from './pages/Vehicle';

function App() {
  return (
    <div className="app-container">
      <header className="app-header">
        <h1>车载仿真系统</h1>
        <p className="app-subtitle">Onboard System - Vehicle Simulation & Monitoring</p>
      </header>
      <main className="app-main">
        <Vehicle />
      </main>
      <footer className="app-footer">
        <p>北京交通大学 &copy; 2026</p>
      </footer>
    </div>
  );
}

export default App;
