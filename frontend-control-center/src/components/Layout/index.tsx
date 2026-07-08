import { useState, useEffect } from 'react';
import { Layout as AntLayout, Menu, Button, Dropdown } from 'antd';
import {
  DashboardOutlined,
  ThunderboltOutlined,
  NodeIndexOutlined,
  AimOutlined,
  SettingOutlined,
  BellOutlined,
  SignalFilled,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  ReloadOutlined,
  WifiOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';

const { Header, Sider, Content } = AntLayout;

interface Props {
  children: React.ReactNode;
  currentModule: string;
  onModuleChange: (m: string) => void;
}

const NAV = [
  { key: 'dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: 'dispatch', icon: <NodeIndexOutlined />, label: '调度控制' },
  { key: 'linesignal', icon: <AimOutlined />, label: '线路信号' },
  { key: 'energy', icon: <ThunderboltOutlined />, label: '能源评估' },
] as const;

const TITLES: Record<string, string> = {
  dashboard: '系统仪表盘',
  dispatch: '调度控制',
  linesignal: '线路信号',
  energy: '能源评估',
};

const DEVICES = [
  { label: 'ATS 信号系统', ok: true },
  { label: '车载终端', ok: true },
  { label: '视景系统', ok: true },
  { label: '驾驶台 PLC', ok: false },
] as const;

function LayoutFrame({ children, currentModule, onModuleChange }: Props) {
  const [running, setRunning] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [clock, setClock] = useState('');

  useEffect(() => {
    const t = () => setClock(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
    t();
    const id = setInterval(t, 1000);
    return () => clearInterval(id);
  }, []);

  const menuItems: MenuProps['items'] = NAV.map((m) => ({
    key: m.key,
    icon: m.icon,
    label: <span className="text-sm font-medium">{m.label}</span>,
  }));

  const settingsMenu = {
    items: [
      { key: 'settings', label: '系统设置' },
      { key: 'help', label: '帮助文档' },
      { type: 'divider' as const },
      { key: 'logout', label: '退出登录' },
    ],
  };

  return (
    <AntLayout className="h-screen overflow-hidden" style={{ background: '#020617' }}>
      {/* ====== Sider ====== */}
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        width={240}
        collapsedWidth={72}
        trigger={null}
        style={{
          background: '#030712',
          borderRight: '1px solid rgba(148,163,184,0.06)',
        }}
      >
        <div className="flex flex-col h-full">
          {/* Brand */}
          <div className="flex items-center gap-3 h-14 px-5 shrink-0 select-none border-b border-slate-800/40">
            <div className="w-9 h-9 rounded-lg bg-blue-500 flex items-center justify-center shrink-0">
              <SignalFilled className="text-white text-base" />
            </div>
            {!collapsed && (
              <div className="overflow-hidden">
                <div className="text-sm font-bold text-white tracking-tight whitespace-nowrap" style={{ fontFamily: "'Orbitron', monospace" }}>
                  CRRC.SIM
                </div>
                <div className="text-[10px] text-slate-500 whitespace-nowrap mt-0.5">中控调度中心</div>
              </div>
            )}
          </div>

          {/* Navigation */}
          <div className="px-2.5 pt-4 pb-1">
            {!collapsed && (
              <div className="text-[11px] text-slate-500 font-semibold px-3 mb-1.5 uppercase tracking-wider select-none">功能</div>
            )}
            <Menu
              mode="inline"
              selectedKeys={[currentModule]}
              onClick={({ key }) => onModuleChange(key)}
              items={menuItems}
              theme="dark"
              className="border-0 bg-transparent"
              inlineIndent={collapsed ? 0 : 12}
            />
          </div>

          <div className="mx-3 my-2 border-t border-slate-800/40" />

          {/* Status */}
          {!collapsed && (
            <div className="flex-1 overflow-auto px-4 py-2 space-y-3">
              <div>
                <div className="text-[11px] text-slate-500 font-semibold uppercase tracking-wider mb-2 select-none">设备</div>
                <div className="space-y-2">
                  {DEVICES.map((d) => (
                    <div key={d.label} className="flex items-center justify-between text-xs">
                      <span className="text-slate-400">{d.label}</span>
                      <span className={`flex items-center gap-1.5 ${d.ok ? 'text-emerald-400' : 'text-amber-400'}`}>
                        <span className={`w-1.5 h-1.5 rounded-full ${d.ok ? 'bg-emerald-400' : 'bg-amber-400'}`} />
                        {d.ok ? 'ON' : 'STBY'}
                      </span>
                    </div>
                  ))}
                </div>
              </div>

              <div>
                <div className="text-[11px] text-slate-500 font-semibold uppercase tracking-wider mb-2 select-none">信息</div>
                <div className="space-y-2 text-xs text-slate-400">
                  <div className="flex justify-between"><span>线路</span><span className="text-slate-300">北京9号线</span></div>
                  <div className="flex justify-between"><span>在线列车</span><span className="text-blue-400 font-bold" style={{ fontFamily: "'Orbitron', monospace" }}>04</span></div>
                  <div className="flex justify-between"><span>仿真步长</span><span className="tabular-nums font-mono">100 ms</span></div>
                </div>
              </div>
            </div>
          )}

          {/* Controls */}
          <div className="px-3 py-2.5 border-t border-slate-800/40 space-y-1.5">
            <Button
              block size="small"
              className="h-8! text-xs! font-semibold! rounded-lg!"
              icon={running ? <PauseCircleOutlined /> : <PlayCircleOutlined />}
              onClick={() => setRunning(!running)}
              style={running
                ? { background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.15)', color: '#f87171' }
                : { background: '#3b82f6', border: 'none', color: '#fff' }}
            >
              {collapsed ? '' : (running ? '暂停' : '启动')}
            </Button>
            {!collapsed && (
              <Button block size="small" className="h-8! text-xs! font-medium! rounded-lg! text-slate-400!"
                style={{ background: 'rgba(100,116,139,0.06)', border: '1px solid rgba(100,116,139,0.12)' }}
                icon={<ReloadOutlined />}>
                重置场景
              </Button>
            )}
          </div>

          {/* Toggle */}
          <button
            type="button"
            onClick={() => setCollapsed(!collapsed)}
            className="w-full flex items-center justify-center h-10 text-slate-500 hover:text-slate-300 transition-colors bg-transparent border-0 cursor-pointer border-t border-slate-800/40"
            aria-label={collapsed ? '展开' : '收起'}>
            {collapsed ? <MenuUnfoldOutlined className="text-base" /> : <MenuFoldOutlined className="text-base" />}
          </button>
        </div>
      </Sider>

      {/* ====== Main ====== */}
      <AntLayout style={{ background: 'transparent' }}>
        {/* Header */}
        <Header
          className="flex items-center justify-between px-6 h-14 select-none"
          style={{
            background: '#030712',
            borderBottom: '1px solid rgba(148,163,184,0.06)',
            minHeight: 56,
          }}
        >
          <div className="flex items-center gap-3">
            <span className="text-[15px] font-semibold text-slate-100">{TITLES[currentModule]}</span>
            <span className="w-px h-4 bg-slate-800" />
            <span className="hidden sm:inline text-xs text-slate-500">CRRC.SIM · 城轨多系统仿真平台</span>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={() => setRunning(!running)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-transparent border-0 cursor-pointer transition-colors"
              style={{
                background: running ? 'rgba(59,130,246,0.08)' : 'rgba(100,116,139,0.06)',
                color: running ? '#60a5fa' : '#64748b',
              }}
            >
              <span className={`w-1.5 h-1.5 rounded-full ${running ? 'bg-blue-400' : 'bg-slate-500'}`} />
              <span className="hidden sm:inline">{running ? '运行中' : '就绪'}</span>
            </button>

            <span className="hidden md:inline text-xs text-slate-500 tabular-nums" style={{ fontFamily: "'JetBrains Mono', monospace" }}>
              <ClockCircleOutlined className="mr-1" />{clock}
            </span>

            <span className="hidden sm:flex items-center gap-1 text-xs text-emerald-400 font-medium">
              <WifiOutlined />
            </span>

            <div className="flex items-center gap-0.5 ml-1">
              <Button type="text" size="small" className="w-8! h-8! rounded-lg!"
                icon={<BellOutlined className="text-slate-400" />} aria-label="通知" />
              <Dropdown menu={settingsMenu} trigger={['click']}>
                <Button type="text" size="small" className="w-8! h-8! rounded-lg!"
                  icon={<SettingOutlined className="text-slate-400" />} aria-label="设置" />
              </Dropdown>
            </div>
          </div>
        </Header>

        {/* Content */}
        <Content className="overflow-auto" style={{ background: '#020617' }}>
          {children}
        </Content>
      </AntLayout>
    </AntLayout>
  );
}

export default LayoutFrame;
