import type { ReactNode } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import type { ConfigProviderProps } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {
  antIconPrefixCls,
  antPrefixCls,
  midplatRootSelector,
  resolveMidplatContainer,
} from '@/antdIsolation';
import { DEFAULT_THEME, type ThemeMode } from '@/themeMode';

export type { ThemeMode } from '@/themeMode';

type MidplatConfigProviderProps = {
  children: ReactNode;
  mode?: ThemeMode;
};

function getChildAppContainer() {
  return document.querySelector<HTMLElement>(midplatRootSelector) ?? document.body;
}

function getPopupContainer(triggerNode?: HTMLElement) {
  return resolveMidplatContainer(triggerNode, getChildAppContainer());
}

const fontFamily = 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", "PingFang SC", "Microsoft YaHei", sans-serif';

function createTheme(mode: ThemeMode): ConfigProviderProps['theme'] {
  const dark = mode === 'dark';
  return {
    algorithm: dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
    colorPrimary: dark ? '#3b82f6' : '#2563eb',
    colorLink: dark ? '#3b82f6' : '#2563eb',
    colorLinkHover: dark ? '#60a5fa' : '#1d4ed8',
    colorInfo: dark ? '#3b82f6' : '#2563eb',
    colorSuccess: '#16a34a',
    colorWarning: '#d97706',
    colorError: '#dc2626',
    colorText: dark ? '#e6edf7' : '#111c2e',
    colorTextSecondary: dark ? '#a4b1c5' : '#4b5970',
    colorBgContainer: dark ? '#0e1627' : '#ffffff',
    colorBgElevated: dark ? '#121c30' : '#ffffff',
    colorBgLayout: dark ? '#070c17' : '#f3f5f9',
    colorBorder: dark ? 'rgba(148,163,184,.22)' : 'rgba(15,23,42,.16)',
    colorBorderSecondary: dark ? 'rgba(148,163,184,.12)' : 'rgba(15,23,42,.08)',
    borderRadius: 6,
    borderRadiusLG: 10,
    fontFamily,
  },
    components: {
    Button: {
      colorPrimary: dark ? '#3b82f6' : '#2563eb',
      controlHeight: 32,
      controlHeightSM: 28,
      controlHeightLG: 40,
      paddingInline: 14,
      paddingInlineSM: 10,
      paddingInlineLG: 18,
      borderRadius: 6,
      borderRadiusSM: 6,
      borderRadiusLG: 8,
      fontWeight: 500,
      primaryShadow: '0 1px 2px rgba(0, 0, 0, 0.25)',
      defaultShadow: '0 1px 2px rgba(0, 0, 0, 0.3)',
      dangerShadow: '0 1px 2px rgba(239, 68, 68, 0.3)',
    },
    Card: {
      borderRadiusLG: 10,
      colorBgContainer: dark ? 'rgba(12,19,33,.88)' : 'rgba(255,255,255,.95)',
    },
    Menu: {
      itemBg: 'transparent',
      itemSelectedBg: 'rgba(59,130,246,.45)',
      itemSelectedColor: dark ? '#ffffff' : '#1d4ed8',
      itemHoverBg: dark ? 'rgba(255,255,255,.10)' : 'rgba(59,130,246,.08)',
      itemColor: dark ? 'rgba(255,255,255,.92)' : '#334155',
    },
    Table: {
      headerBg: 'rgba(148,163,184,.07)',
      headerColor: dark ? '#8b96ad' : '#526078',
      headerBorderRadius: 0,
      borderColor: 'rgba(148,163,184,.12)',
      colorBgContainer: 'transparent',
      rowHoverBg: dark ? 'rgba(59,130,246,.10)' : 'rgba(59,130,246,.05)',
      cellPaddingBlock: 10,
      cellPaddingBlockSM: 6,
    },
    Tag: {
      borderRadiusSM: 999,
    },
    Modal: {
      borderRadiusLG: 14,
      contentBg: dark ? '#101a2d' : '#ffffff',
      headerBg: dark ? '#101a2d' : '#ffffff',
    },
    Drawer: {
      borderRadiusLG: 14,
    },
    Input: {
      colorBgContainer: dark ? 'rgba(13,20,38,.78)' : '#ffffff',
      activeShadow: '0 0 0 3px rgba(59, 130, 246, 0.15)',
    },
    Select: {
      colorBgContainer: dark ? 'rgba(13,20,38,.78)' : '#ffffff',
    },
    },
  };
}

export function MidplatConfigProvider({ children, mode = DEFAULT_THEME }: MidplatConfigProviderProps) {
  return (
    <ConfigProvider
      prefixCls={antPrefixCls}
      iconPrefixCls={antIconPrefixCls}
      locale={zhCN}
      button={{ autoInsertSpace: false }}
      getPopupContainer={getPopupContainer}
      getTargetContainer={getChildAppContainer}
      theme={createTheme(mode)}
    >
      {children}
    </ConfigProvider>
  );
}

export function configureMidplatAntd(mode: ThemeMode = DEFAULT_THEME) {
  const theme = createTheme(mode);
  ConfigProvider.config({
    prefixCls: antPrefixCls,
    iconPrefixCls: antIconPrefixCls,
    theme,
    holderRender: (children) => <MidplatConfigProvider mode={mode}>{children}</MidplatConfigProvider>,
  });
}
