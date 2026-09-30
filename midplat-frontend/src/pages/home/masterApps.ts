/**
 * 主应用（malacca 统一门户）应用目录 —— GET /api/base/setting/menus?key=apps
 *
 * 字段口径随主应用接口走（主应用调整属性时此处同步）：
 *  - title/remark/icon 卡片三要素（icon 为 data:image/*;base64，可直接 <img>）
 *  - entry + path 拼打开链接（entry 可为协议相对 //host:port；path 可为完整 URL）
 *  - hide === 'True' 不展示（与主应用展开菜单一致）
 *  - projectGroup / projectTwoGroup 一二级分组，projectGroupIcon 分组图标
 *
 * 取数顺序：同源 /api（qiankun 或经门户访问时直达主应用，浏览器自带会话 cookie）
 * → /portal-api（本地 dev 代理，见 .umirc.ts）→ public/mock/master-apps.json 快照兜底。
 */
import { publicAsset } from '@/publicAsset';
import { detectShellMode } from '@/runtimeShell';

export interface MasterAppItem {
  app?: string;
  name?: string;
  key?: string;
  title?: string;
  remark?: string;
  icon?: string;
  entry?: string;
  path?: string;
  hide?: string | boolean;
  default?: string | boolean;
  isdev?: string | boolean;
  children?: MasterAppItem[];
  projectGroup?: string;
  projectGroupIcon?: string;
  projectGroupDes?: string;
  projectTwoGroup?: string;
  projectTwoGroupIcon?: string;
  projectTwoGroupDes?: string;
}

export interface MasterAppSubGroup {
  name: string;
  desc?: string;
  apps: MasterAppItem[];
}

export interface MasterAppGroup {
  name: string;
  icon?: string;
  desc?: string;
  /** 组内全部可见应用（含二级分组里的），保持接口顺序 */
  apps: MasterAppItem[];
  /** 二级分组，仅收录非空 projectTwoGroup */
  subs: MasterAppSubGroup[];
}

const SNAPSHOT = 'mock/master-apps.json';

export async function fetchMasterApps(): Promise<MasterAppItem[]> {
  // qiankun/门户同源时 /api 直达主应用；本地 dev 走 /portal-api 代理。
  // 按环境只探测当前形态可达的源，避免必然失败的请求刷 console 错误。
  const prefixes = detectShellMode() === 'embedded' ? ['/api', '/portal-api'] : ['/portal-api', '/api'];
  for (const prefix of prefixes) {
    try {
      const res = await fetch(`${prefix}/base/setting/menus?key=apps`, {
        credentials: 'include',
      });
      if (!res.ok) continue;
      const json = await res.json();
      if (json?.success === true && Array.isArray(json.data)) {
        return json.data as MasterAppItem[];
      }
    } catch {
      /* 尝试下一个候选源 */
    }
  }
  try {
    const res = await fetch(publicAsset(SNAPSHOT));
    if (res.ok) {
      const json = await res.json();
      const list = Array.isArray(json) ? json : json?.data;
      if (Array.isArray(list)) return list as MasterAppItem[];
    }
  } catch {
    /* 忽略，返回空 */
  }
  return [];
}

/** 卡片打开地址：path 是绝对地址直接用，否则 entry + path；entry 支持协议相对 //host */
export function masterAppUrl(app: MasterAppItem): string {
  const path = (app.path ?? '').trim();
  if (/^https?:\/\//i.test(path)) return path;
  const entry = (app.entry ?? '').trim().replace(/\/+$/, '');
  const suffix = path ? (path.startsWith('/') ? path : `/${path}`) : '';
  if (/^https?:\/\//i.test(entry)) return entry + suffix;
  if (entry.startsWith('//')) return `${location.protocol}${entry}${suffix}`;
  return entry ? entry + suffix : path;
}

/** 门户内导航地址：path 是根相对路由时直接给主应用 SPA 用（与主应用菜单跳转一致），其余情况返回 null */
export function masterPortalRoute(app: MasterAppItem): string | null {
  const path = (app.path ?? '').trim();
  return path.startsWith('/') ? path : null;
}

const truthy = (v: string | boolean | undefined) =>
  v === true || String(v).toLowerCase() === 'true';
const clean = (v?: string) => {
  const s = (v ?? '').trim();
  return s && s !== '/' ? s : '';
};

export function groupMasterApps(items: MasterAppItem[]): MasterAppGroup[] {
  const groups = new Map<string, MasterAppGroup>();
  for (const it of items) {
    if (truthy(it.hide) || !(it.title || it.name)) continue;
    const gname = clean(it.projectGroup) || '其他';
    let g = groups.get(gname);
    if (!g) {
      g = { name: gname, icon: clean(it.projectGroupIcon) || undefined, desc: clean(it.projectGroupDes) || undefined, apps: [], subs: [] };
      groups.set(gname, g);
    }
    g.apps.push(it);
    const sname = clean(it.projectTwoGroup);
    if (sname) {
      let s = g.subs.find((x) => x.name === sname);
      if (!s) {
        s = { name: sname, desc: clean(it.projectTwoGroupDes) || undefined, apps: [] };
        g.subs.push(s);
      }
      s.apps.push(it);
    }
  }
  return [...groups.values()];
}

/**
 * 分组展示行：无二级分组的应用排前面，二级分组随后；cap 为全组总卡片数上限
 * （「全部」视图里每组先展示前 6 个，超出的由「展开全部」进入该分组查看）。
 */
export function groupRows(
  g: MasterAppGroup,
  cap?: number,
): { sub?: string; apps: MasterAppItem[] }[] {
  const ungrouped = g.apps.filter((a) => !clean(a.projectTwoGroup));
  const rows = [
    ...(ungrouped.length ? [{ apps: ungrouped }] : []),
    ...g.subs.map((s) => ({ sub: s.name, apps: s.apps })),
  ];
  if (cap == null) return rows;
  let left = cap;
  return rows
    .map((r) => {
      const take = r.apps.slice(0, Math.max(0, left));
      left -= take.length;
      return { ...r, apps: take };
    })
    .filter((r) => r.apps.length > 0);
}
