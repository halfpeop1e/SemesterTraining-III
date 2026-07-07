function App() {
  return (
    <div className="app-container">
      <header className="app-header">
        <h1>中控调度系统</h1>
        <p className="app-subtitle">Control Center - Dispatch, Signal & Energy Management</p>
      </header>
      <main className="app-main">
        <div className="module-grid">
          <a href="/dashboard" className="module-card">
            <h3>系统仪表盘</h3>
            <p>Dashboard</p>
          </a>
          <a href="/dispatch" className="module-card">
            <h3>总控调度</h3>
            <p>Dispatch</p>
          </a>
          <a href="/linesignal" className="module-card">
            <h3>线路与信号</h3>
            <p>Line & Signal</p>
          </a>
          <a href="/energy" className="module-card">
            <h3>能源评估</h3>
            <p>Energy Evaluation</p>
          </a>
        </div>
      </main>
      <footer className="app-footer">
        <p>北京交通大学 &copy; 2026</p>
      </footer>
    </div>
  );
}

export default App;
