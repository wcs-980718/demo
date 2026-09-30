export const ROUTE_CATALOG = [
  { name: 'home', path: '/home', title: '首页', filePath: 'src/pages/home/index.tsx', component: '@/pages/home/index' },
  { name: 'evaluation', path: '/evaluation', title: '异常总览', filePath: 'src/pages/evaluation/index.tsx', component: '@/pages/evaluation/index' },
  { name: 'entry-scene', path: '/entry/scene/:sceneId', title: '场景入口', filePath: 'src/pages/entry/scene.tsx', component: '@/pages/entry/scene' },
  { name: 'capabilities', path: '/capabilities', title: '对外接口总览', filePath: 'src/pages/capabilities/index.tsx', component: '@/pages/capabilities/index' },
  { name: 'capability-assets', path: '/capabilities/assets', title: '能力资产', filePath: 'src/pages/capability-assets/index.tsx', component: '@/pages/capability-assets/index' },
  { name: 'access', path: '/access', title: '客户与凭证', filePath: 'src/pages/access/index.tsx', component: '@/pages/access/index' },
  { name: 'agent-platform', path: '/agent-hub/overview', title: '智能体开发', filePath: 'src/pages/agent-hub/index.tsx', component: '@/pages/agent-hub/index' },
  { name: 'models', path: '/models', title: '模型管理', filePath: 'src/pages/models/index.tsx', component: '@/pages/models/index' },
  { name: 'prompts', path: '/prompts', title: '提示词管理', filePath: 'src/pages/prompts/index.tsx', component: '@/pages/prompts/index' },
  { name: 'menus', path: '/settings/menus', title: '菜单设置', filePath: 'src/pages/settings/menus/index.tsx', component: '@/pages/settings/menus/index' },
  { name: 'entry-category', path: '/entry/category/:menuId', title: '分类平台', filePath: 'src/pages/entry/category.tsx', component: '@/pages/entry/category' },
] as const;

export type RouteName = (typeof ROUTE_CATALOG)[number]['name'];

export type RouteMeta = {
  name: RouteName;
  path: string;
  title: string;
  filePath: string;
  component: string;
};

export type MenuCreateDefaults = {
  icon: string;
  visible: boolean;
  routeName: string;
  path: string;
  filePath: string;
};

export function getRouteMeta(name: RouteName): RouteMeta {
  const entry = ROUTE_CATALOG.find((r) => r.name === name);
  if (!entry) {
    throw new Error(`Unknown route name: ${name}`);
  }
  return {
    name: entry.name,
    path: entry.path,
    title: entry.title,
    filePath: entry.filePath,
    component: entry.component,
  };
}

export function isStandardRoute(name: string): name is RouteName {
  return ROUTE_CATALOG.some((r) => r.name === name);
}

export function getMenuCreateDefaults(parentId?: string): MenuCreateDefaults {
  if (parentId === 'menu-entry') {
    const route = getRouteMeta('entry-category');
    return {
      icon: 'LayoutDashboard',
      visible: true,
      routeName: route.name,
      path: route.path,
      filePath: route.filePath,
    };
  }
  return {
    icon: 'LayoutDashboard',
    visible: true,
    routeName: '',
    path: '',
    filePath: '',
  };
}

export { ICON_OPTIONS as ICON_CATALOG } from '@/components/LucideIcon';
