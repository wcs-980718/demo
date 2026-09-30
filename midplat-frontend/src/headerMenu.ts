import { AGENT_PLATFORM_URL } from '@/agentPlatformConfig';
import type { MenuItem } from '@/api/midplatApi';
import { flattenMenus, menuHref } from '@/menuHref';

const HOME_MENU_ID = 'menu-home';

/**
 * Header 与菜单设置共用后端返回的同一棵菜单树。
 * 首页因为在 Header 中单独渲染，只在这里从普通顶级菜单中排除。
 */
export function headerMenusFromSettings(items: MenuItem[]): MenuItem[] {
  return items.filter((item) => item.id !== HOME_MENU_ID);
}

function isAgentHubPath(path: string) {
  return path === '/agent-platform' || path.startsWith('/agent-platform/') || path.startsWith('/agent-hub');
}

export function headerSelectedKeys(pathname: string, items: MenuItem[]): string[] {
  const hrefs = [...new Set(flattenMenus(items).map((item) => menuHref(item)).filter((href) => href && href !== '/'))];
  const exact = hrefs.find((href) => href === pathname);
  if (exact) return [exact];
  if (isAgentHubPath(pathname)) {
    // 工作台视图已挂为 /agent-hub/* 子菜单，优先按视图路径匹配；未挂菜单的视图（如 workflows）回退到平台入口
    const viewHref = hrefs.find((href) => href.startsWith('/agent-hub/') && (pathname === href || pathname.startsWith(`${href}/`) || href.startsWith(`${pathname}/`)));
    if (viewHref) return [viewHref];
    return [hrefs.find((href) => isAgentHubPath(href)) || AGENT_PLATFORM_URL];
  }
  const prefix = hrefs.filter((href) => pathname.startsWith(`${href}/`)).sort((a, b) => b.length - a.length)[0];
  return [prefix || pathname];
}
