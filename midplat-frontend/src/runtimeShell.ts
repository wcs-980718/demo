import { isQiankunSlave } from '@/themeMode';

/**
 * 运行形态（shell）——所有「挂载主应用 / 单独使用」的差异都从这里判定：
 *
 * - embedded：挂载在主应用（malacca 统一门户，qiankun）里。主应用负责顶栏、导航与
 *   html/body；本应用不渲染自有顶栏，样式只能作用在 .midplat-frontend-root 内部。
 * - standalone：直接访问本应用（本地开发 / NodePort 直连）。本应用拥有整页，
 *   渲染自有顶栏，并负责 html/body 的底色与 color-scheme。
 *
 * 形态写在子应用根节点的 data-shell 属性上，样式按 [data-shell='embedded'] /
 * [data-shell='standalone'] 分流，见 src/styles/shell-*.css。
 */
export type ShellMode = 'embedded' | 'standalone';

export const SHELL_ATTRIBUTE = 'data-shell';

const MASTER_ROOT_ID = 'root-master';

/** 主应用根容器是否存在（qiankun jsSandbox:false 时 __POWERED_BY_QIANKUN__ 可能已被其他子应用卸载时删除） */
export function inMasterPortal(): boolean {
  return typeof document !== 'undefined' && Boolean(document.getElementById(MASTER_ROOT_ID));
}

/** 挂载时判定一次即可：运行期不随宿主全局标记漂移。 */
export function detectShellMode(): ShellMode {
  return isQiankunSlave() || inMasterPortal() ? 'embedded' : 'standalone';
}
