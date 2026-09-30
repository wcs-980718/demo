import { useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { Layout, Menu, Spin, Tooltip } from 'antd';
import { Outlet, useLocation, useNavigate } from '@umijs/max';
import { Home, Moon, Sun } from 'lucide-react';
import { configureMidplatAntd, MidplatConfigProvider, type ThemeMode } from '@/antdConfig';
import { applyThemeMode, persistThemeMode, readStoredThemeMode } from '@/themeMode';
import { detectShellMode, type ShellMode } from '@/runtimeShell';
import { midplatRootClassName } from '@/antdIsolation';
import { midplatApi, type MenuItem } from '@/api/midplatApi';
import { LucideIcon } from '@/components/LucideIcon';
import { BrandMark } from '@/components/BrandMark';
import { CubeField } from '@/components/CubeField';
import { particleChapterForPath } from '@/components/particleScene';
import { menuHref } from '@/menuHref';
import { headerMenusFromSettings, headerSelectedKeys } from '@/headerMenu';

const { Content } = Layout;
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
});

export default function AppLayout() {
  const [themeMode, setThemeMode] = useState<ThemeMode>(readStoredThemeMode);
  // 运行形态只在挂载时判定一次：embedded（挂在主应用里）/ standalone（单独访问）
  const [shell] = useState<ShellMode>(detectShellMode);

  useEffect(() => {
    applyThemeMode(themeMode, shell === 'embedded');
    persistThemeMode(themeMode);
    configureMidplatAntd(themeMode);
  }, [themeMode, shell]);

  return (
    <MidplatConfigProvider mode={themeMode}>
      <QueryClientProvider client={queryClient}>
        <Shell
          shell={shell}
          themeMode={themeMode}
          onToggleTheme={() => setThemeMode((mode) => mode === 'dark' ? 'light' : 'dark')}
        />
      </QueryClientProvider>
    </MidplatConfigProvider>
  );
}

type ThemeToggleProps = { themeMode: ThemeMode; onToggleTheme: () => void };

function Shell({ shell, themeMode, onToggleTheme }: ThemeToggleProps & { shell: ShellMode }) {
  const location = useLocation();
  const isProjectConfig = location.pathname.startsWith('/entry/category/');
  return (
    <Layout className={`${midplatRootClassName} app-layout`} data-shell={shell}>
      {!isProjectConfig && <CubeField chapter={particleChapterForPath(location.pathname)} />}
      {/* 单独使用：自有品牌顶栏 + 主导航；挂载主应用：主应用已有全局导航，只保留悬浮主题开关，避免双重 header。 */}
      {shell === 'standalone'
        ? <StandaloneTopbar themeMode={themeMode} onToggleTheme={onToggleTheme} />
        : <ThemeToggle className="theme-toggle theme-toggle-float" themeMode={themeMode} onToggleTheme={onToggleTheme} />}
      <Content className="app-content">
        <Outlet />
      </Content>
    </Layout>
  );
}

function ThemeToggle({ className, themeMode, onToggleTheme }: ThemeToggleProps & { className: string }) {
  const label = themeMode === 'dark' ? '切换到浅色主题' : '切换到深色主题';
  return (
    <Tooltip title={label}>
      <button type="button" className={className} aria-label={label} onClick={onToggleTheme}>
        {themeMode === 'dark' ? <Sun size={18} /> : <Moon size={18} />}
      </button>
    </Tooltip>
  );
}

/** 仅 standalone 渲染：品牌 + 菜单设置驱动的水平主导航 + 主题开关。 */
function StandaloneTopbar({ themeMode, onToggleTheme }: ThemeToggleProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const menusQuery = useQuery({
    queryKey: ['menus'],
    queryFn: midplatApi.listMenus,
  });
  const sourceMenus = withRenderableWorkspaceChildren(menusQuery.data ?? []);
  const homeMenu = sourceMenus.find((item) => item.id === 'menu-home');
  const menus = headerMenusFromSettings(sourceMenus).filter((item) => item.visible);
  const homeNavItem = homeMenu
    ? (homeMenu.visible ? {
        key: menuHref(homeMenu),
        icon: <LucideIcon name={homeMenu.icon} size={18} strokeWidth={1.75} />,
        label: homeMenu.name,
        onClick: () => navigate(menuHref(homeMenu)),
      } : null)
    : {
        key: '/home',
        icon: <Home size={18} strokeWidth={1.75} />,
        label: '首页',
        onClick: () => navigate('/home'),
      };
  return (
    <header className="topbar">
      <div className="topbar-brand-slot">
        <button
          type="button"
          className="brand"
          aria-label="返回数智大脑首页"
          onClick={() => navigate('/home')}
        >
          <span className="brand-mark" aria-hidden="true">
            <BrandMark size={24} />
          </span>
          <span className="brand-copy">
            <span className="brand-title">数智大脑</span>
            <span className="brand-subtitle">AI 能力中枢</span>
          </span>
        </button>
      </div>
      <nav className="topbar-nav" aria-label="主导航">
        {menusQuery.isLoading ? (
          <div className="nav-loading"><Spin /></div>
        ) : (
          <Menu
            mode="horizontal"
            selectedKeys={headerSelectedKeys(location.pathname, sourceMenus)}
            className="app-menu top-menu"
            onClick={({ key }) => {
              if (key.startsWith('/')) navigate(key);
            }}
            items={[
              ...(homeNavItem ? [homeNavItem] : []),
              ...menus.map((item) => toMenuItem(item, navigate)),
            ]}
          />
        )}
      </nav>
      <div className="topbar-right">
        <ThemeToggle className="theme-toggle" themeMode={themeMode} onToggleTheme={onToggleTheme} />
      </div>
    </header>
  );
}

function withRenderableWorkspaceChildren(items: MenuItem[]): MenuItem[] {
  return items.flatMap((item) => {
    if (item.id !== 'menu-entry') return [item];
    const sourceChildren = item.children ?? [];
    const children = sourceChildren.filter((child) => {
      const platformIds = child.platformIds ?? (child.platformId ? [child.platformId] : []);
      return child.visible && platformIds.length > 0;
    });
    // AI 工作台自身已无落地页，没有可用子菜单时整组不展示，避免点进 /entry 404。
    return children.length ? [{ ...item, children }] : [];
  });
}

function toMenuItem(item: MenuItem, navigate: (path: string) => void) {
  const children = (item.children ?? []).filter((child) => child.visible);
  return {
    key: children.length ? item.id : menuHref(item),
    icon: <LucideIcon name={item.icon} size={18} strokeWidth={1.75} />,
    label: item.name,
    children: children.length
      ? children.map((child) => ({
          key: menuHref(child),
          icon: <LucideIcon name={child.icon} size={18} strokeWidth={1.75} />,
          label: child.name,
          onClick: () => navigate(menuHref(child)),
        }))
      : undefined,
    onClick: children.length ? undefined : () => navigate(menuHref(item)),
  };
}
