import { StrictMode } from 'react';
import ReactDOM from 'react-dom/client';
import { ConfigProvider, theme } from 'antd';
import './styles/tailwind.css';
import './styles/global.css';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider
      theme={{
        algorithm: theme.darkAlgorithm,
        token: {
          colorPrimary: '#3b82f6',
          colorSuccess: '#22c55e',
          colorWarning: '#f59e0b',
          colorError: '#ef4444',
          colorInfo: '#3b82f6',

          colorBgBase: '#020617',
          colorBgContainer: '#0f172a',
          colorBgElevated: '#1e293b',
          colorBgLayout: '#020617',

          colorBorder: 'rgba(148,163,184,0.08)',
          colorBorderSecondary: 'rgba(148,163,184,0.05)',

          colorText: '#f1f5f9',
          colorTextSecondary: '#94a3b8',
          colorTextTertiary: '#64748b',
          colorTextQuaternary: '#475569',

          fontFamily: "'Plus Jakarta Sans', 'Noto Sans SC', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
          fontFamilyCode: "'JetBrains Mono', monospace",
          fontSize: 14,
          fontSizeSM: 13,
          fontSizeLG: 15,
          fontSizeXL: 18,
          fontSizeHeading1: 26,
          fontSizeHeading2: 22,
          fontSizeHeading3: 18,
          fontSizeHeading4: 16,
          fontSizeHeading5: 14,
          lineHeight: 1.6,

          borderRadius: 8,
          borderRadiusSM: 6,
          borderRadiusLG: 12,
          borderRadiusXS: 4,

          controlHeight: 36,
          controlHeightSM: 30,
          controlHeightLG: 42,
          controlPaddingHorizontal: 14,
          controlPaddingHorizontalSM: 10,

          motionDurationSlow: '0.25s',
          motionDurationMid: '0.15s',
          motionDurationFast: '0.1s',
          motionEaseInOut: 'cubic-bezier(0.4, 0, 0.2, 1)',
        },
        components: {
          Layout: {
            siderBg: '#030712',
            headerBg: '#030712',
            bodyBg: '#020617',
            triggerBg: '#030712',
            triggerColor: '#64748b',
          },
          Menu: {
            darkItemBg: 'transparent',
            darkItemSelectedBg: 'rgba(59,130,246,0.1)',
            darkItemSelectedColor: '#60a5fa',
            darkItemColor: '#94a3b8',
            darkItemHoverColor: '#f1f5f9',
            darkItemHoverBg: 'rgba(255,255,255,0.03)',
            itemBorderRadius: 8,
            itemHeight: 40,
            iconSize: 18,
            collapsedIconSize: 20,
            itemMarginBlock: 2,
            itemMarginInline: 6,
          },
          Table: {
            headerBg: 'rgba(15,23,42,0.6)',
            headerColor: '#94a3b8',
            rowHoverBg: 'rgba(59,130,246,0.03)',
            borderColor: 'rgba(148,163,184,0.06)',
            cellPaddingBlock: 12,
            cellPaddingInline: 16,
            fontSize: 14,
          },
          Button: {
            primaryShadow: 'none',
            borderRadius: 8,
            controlHeight: 36,
            controlHeightSM: 32,
            contentFontSize: 14,
            fontWeight: 600,
          },
          Card: {
            colorBorderSecondary: 'rgba(148,163,184,0.08)',
            borderRadiusLG: 12,
            paddingLG: 24,
            colorBgContainer: '#0f172a',
          },
          Tabs: {
            inkBarColor: '#3b82f6',
            itemActiveColor: '#60a5fa',
            itemColor: '#94a3b8',
            itemHoverColor: '#cbd5e1',
            titleFontSize: 14,
            horizontalMargin: '0',
          },
          Tag: {
            defaultBg: 'rgba(148,163,184,0.08)',
            defaultColor: '#94a3b8',
            borderRadiusSM: 6,
          },
          Statistic: {
            contentFontSize: 28,
            titleFontSize: 13,
          },
          Input: {
            borderRadius: 8,
            controlHeight: 36,
          },
          Select: { borderRadius: 8, controlHeight: 36 },
          Segmented: { borderRadius: 8 },
        },
      }}
    >
      <App />
    </ConfigProvider>
  </StrictMode>,
);
