function Dashboard() {
  return (
    <div className="page-placeholder">
      <div className="placeholder-icon" style={{ background: '#ebf4ff', color: '#1a73e8' }}>
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <rect x="3" y="3" width="7" height="7" rx="1" />
          <rect x="14" y="3" width="7" height="7" rx="1" />
          <rect x="3" y="14" width="7" height="7" rx="1" />
          <rect x="14" y="14" width="7" height="7" rx="1" />
        </svg>
      </div>
      <h2>系统仪表盘</h2>
      <p>全局运行状态总览，实时监控列车位置、能耗指标与系统健康度</p>
    </div>
  );
}

export default Dashboard;
