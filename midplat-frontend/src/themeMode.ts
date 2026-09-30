export type ThemeMode = 'dark' | 'light';

export const DEFAULT_THEME: ThemeMode = 'light';
export const THEME_STORAGE_KEY = 'midplat-theme';

export function resolveThemeMode(stored: string | null | undefined): ThemeMode {
  return stored === 'dark' || stored === 'light' ? stored : DEFAULT_THEME;
}

export function readStoredThemeMode(): ThemeMode {
  if (typeof window === 'undefined') return DEFAULT_THEME;
  try {
    return resolveThemeMode(window.localStorage.getItem(THEME_STORAGE_KEY));
  } catch {
    return DEFAULT_THEME;
  }
}

export function persistThemeMode(mode: ThemeMode): void {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    /* ignore quota / private-mode */
  }
}

export const QIANKUN_HTML_CLASS = 'midplat-in-qiankun';

type QiankunWindow = Window & {
  __POWERED_BY_QIANKUN__?: boolean;
};

export function isQiankunSlave() {
  return typeof window !== 'undefined' && Boolean((window as QiankunWindow).__POWERED_BY_QIANKUN__);
}

/**
 * 两种形态都在 <html> 上写 data-theme（主题开关依赖它）；
 * 只有 standalone 才改 html 的 color-scheme，embedded 只打 midplat-in-qiankun 标记。
 */
export function applyThemeMode(mode: ThemeMode, embedded = isQiankunSlave()): void {
  const root = document.documentElement;
  root.dataset.theme = mode;
  if (embedded) {
    root.classList.add(QIANKUN_HTML_CLASS);
    root.style.removeProperty('color-scheme');
    return;
  }
  root.classList.remove(QIANKUN_HTML_CLASS);
  root.style.colorScheme = mode;
}
