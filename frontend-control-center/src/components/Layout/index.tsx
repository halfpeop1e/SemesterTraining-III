import { useState, useEffect } from 'react';
import { Layout as AntLayout, Button, Dropdown } from 'antd';
import {
  DashboardOutlined,
  ThunderboltOutlined,
  NodeIndexOutlined,
  AimOutlined,
  SettingOutlined,
  BellOutlined,
  SignalFilled,
  ReloadOutlined,
  WifiOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons';

const { Header, Content } = AntLayout;

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

/** 科技金色 */
const GOLD = {
  solid: 'linear-gradient(135deg, #E8C547 0%, #C9A227 55%, #A8841A 100%)',
  solidHover: 'linear-gradient(135deg, #F0D060 0%, #D4AF37 55%, #B8921F 100%)',
  text: '#1A1408',
  soft: 'rgba(212, 175, 55, 0.14)',
  border: 'rgba(212, 175, 55, 0.45)',
  glow: '0 0 14px rgba(212, 175, 55, 0.35)',
};

function LayoutFrame({ children, currentModule, onModuleChange }: Props) {
  const [clock, setClock] = useState('');

  useEffect(() => {
    const t = () => setClock(new Date().toLocaleTimeString('zh-CN', { hour12: false }));
    t();
    const id = setInterval(t, 1000);
    return () => clearInterval(id);
  }, []);

  const settingsMenu = {
    items: [
      { key: 'settings', label: '系统设置' },
      { key: 'help', label: '帮助文档' },
      { type: 'divider' as const },
      { key: 'logout', label: '退出登录' },
    ],
  };

  return (
    <AntLayout className="h-screen overflow-hidden" style={{ background: '#0B1220' }}>
      <Header
        className="app-topbar flex items-center gap-4 px-4 h-14 select-none shrink-0"
        style={{
          background: 'linear-gradient(180deg, #121A2B 0%, #0D1524 100%)',
          borderBottom: '1px solid rgba(212, 175, 55, 0.18)',
          minHeight: 56,
          lineHeight: 'normal',
          paddingInline: 16,
        }}
      >
        {/* Brand */}
        <div className="flex items-center gap-2.5 shrink-0">
          <div
            className="w-9 h-9 rounded-lg flex items-center justify-center shrink-0"
            style={{ background: GOLD.solid, boxShadow: GOLD.glow }}
          >
            <SignalFilled style={{ color: GOLD.text, fontSize: 16 }} />
          </div>
          <div className="hidden sm:block leading-tight">
            <div
              className="text-sm font-bold tracking-tight whitespace-nowrap"
              style={{ fontFamily: "'Orbitron', monospace", color: '#F5E6B8' }}
            >
              CRRC.SIM
            </div>
            <div className="text-[10px] whitespace-nowrap" style={{ color: 'rgba(212,175,55,0.65)' }}>
              中控调度中心
            </div>
          </div>
        </div>

        <span className="w-px h-7 shrink-0" style={{ background: 'rgba(212,175,55,0.2)' }} />

        {/* Top Tabbar */}
        <nav className="app-tabbar flex items-center gap-1 min-w-0 flex-1 overflow-x-auto">
          {NAV.map((m) => {
            const active = currentModule === m.key;
            return (
              <button
                key={m.key}
                type="button"
                onClick={() => onModuleChange(m.key)}
                className="app-tab"
                data-active={active ? 'true' : 'false'}
              >
                <span className="app-tab-icon">{m.icon}</span>
                <span>{m.label}</span>
              </button>
            );
          })}
        </nav>

        {/* Actions — 科技金 */}
        <div className="flex items-center gap-2 shrink-0">
          <span className="hidden lg:inline text-xs text-slate-500 truncate max-w-[140px]">
            {TITLES[currentModule]}
          </span>

          <span
            className="hidden md:inline text-xs tabular-nums"
            style={{ fontFamily: "'JetBrains Mono', monospace", color: 'rgba(245,230,184,0.7)' }}
          >
            <ClockCircleOutlined className="mr-1" />
            {clock}
          </span>

          <span className="hidden sm:flex items-center gap-1 text-xs font-medium" style={{ color: '#00E676' }}>
            <WifiOutlined />
          </span>

          <Button
            size="small"
            icon={<ReloadOutlined />}
            className="app-btn-gold-ghost"
          >
            重置
          </Button>

          <Button
            type="text"
            size="small"
            className="w-8! h-8! rounded-lg!"
            icon={<BellOutlined style={{ color: 'rgba(245,230,184,0.7)' }} />}
            aria-label="通知"
          />
          <Dropdown menu={settingsMenu} trigger={['click']}>
            <Button
              type="text"
              size="small"
              className="w-8! h-8! rounded-lg!"
              icon={<SettingOutlined style={{ color: 'rgba(245,230,184,0.7)' }} />}
              aria-label="设置"
            />
          </Dropdown>
        </div>
      </Header>

      <Content className="overflow-auto min-h-0" style={{ background: '#0B1220' }}>
        {children}
      </Content>
    </AntLayout>
  );
}

export default LayoutFrame;
