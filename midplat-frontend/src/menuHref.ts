import type { MenuItem } from '@/api/midplatApi';
import { ROUTE_CATALOG } from '@/routeCatalog';

function substituteParams(path: string, item: Pick<MenuItem, 'id' | 'platformId'>): string {
  let result = path;
  if (result.includes(':platformId')) {
    result = item.platformId ? result.replace(':platformId', item.platformId) : result.replace('/:platformId', '');
  }
  if (result.includes(':menuId')) {
    result = result.replace(':menuId', item.id);
  }
  return result;
}

export function menuHref(item: Pick<MenuItem, 'id' | 'routeName' | 'path' | 'filePath' | 'platformId'>): string {
  // Prefer stored path if non-empty
  if (item.path) {
    return substituteParams(item.path, item);
  }
  // Fall back to ROUTE_CATALOG path for standard routes
  const catalogEntry = ROUTE_CATALOG.find((r) => r.name === item.routeName);
  if (catalogEntry) {
    return substituteParams(catalogEntry.path, item);
  }
  return '/';
}

export function flattenMenus(items: MenuItem[]): MenuItem[] {
  return items.flatMap((item) => [item, ...flattenMenus(item.children ?? [])]);
}

export function workspaceBoundPlatformIds(menus: MenuItem[]): string[] {
  const entry = menus.find((item) => item.id === 'menu-entry');
  const ids: string[] = [];
  const seen = new Set<string>();
  for (const child of entry?.children ?? []) {
    const bound = child.platformIds?.length
      ? child.platformIds
      : child.platformId ? [child.platformId] : [];
    for (const id of bound) {
      if (id && !seen.has(id)) {
        seen.add(id);
        ids.push(id);
      }
    }
  }
  return ids;
}

export function findMenuTitle(items: MenuItem[], pathname: string): string {
  const all = flattenMenus(items);
  const exact = all.find((item) => menuHref(item) === pathname);
  if (exact) {
    return exact.name;
  }
  if (pathname.startsWith('/entry/scene/')) {
    const scene = all.find((item) => item.path === pathname);
    return scene?.name ?? '场景入口';
  }
  if (pathname.startsWith('/entry/')) {
    return 'AI 工作台';
  }
  return '数智医院智能应用中心';
}
