import { detectShellMode } from './runtimeShell';

/**
 * 主应用（malacca qiankun 主应用）顶栏的「应用中心」菜单按钮。
 *
 * 主应用布局里它是局部 CSS Module 类（.menuBtn，生产构建会被哈希成
 * menuBtn___xxxx），不能直接按类名选取；按钮内嵌的是 antd 的 BarsOutlined
 * 图标，渲染为全局类 .anticon-bars，是文档里唯一稳定可选的锚点。
 */
function findMasterMenuButton(): HTMLElement | null {
  const anchors = ['.anticon-bars', '[aria-label="bars"]', '[class*="menuBtn"]'];
  for (const selector of anchors) {
    const hit = document.querySelector<HTMLElement>(selector);
    if (hit) {
      // 图标命中时向上取到真正的可点击容器，否则直接用命中节点。
      return hit.closest<HTMLElement>('[class*="menuBtn"]') ?? hit;
    }
  }
  return null;
}

/**
 * 走 DOM 打开主应用的应用中心抽屉。
 *
 * qiankun 的 jsSandbox 为 false，子应用与主应用共享同一 window/document，
 * 主应用顶栏菜单按钮和本页面同处一份 DOM。点击该按钮会触发主应用自身的
 * setAppSelectorVisible(true)，抽屉原样打开，与用户手动点击效果一致。
 *
 * @returns 是否找到并点击了主应用菜单按钮（非 qiankun 子应用形态或找不到时返回 false）
 */
export function openMasterAppCenter(): boolean {
  if (detectShellMode() !== 'embedded') return false;
  const button = findMasterMenuButton();
  if (!button) return false;
  button.click();
  return true;
}
