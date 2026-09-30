const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const projectRoot = path.resolve(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(projectRoot, relative), 'utf8');

test('qiankun 子应用隐藏整个自有顶栏并悬浮保留主题开关', () => {
  const layout = read('src/layouts/index.tsx');
  assert.match(layout, /useState<ShellMode>\(detectShellMode\)/, '布局应在挂载时判定运行形态');
  assert.match(layout, /data-shell=\{shell\}/, '运行形态应写在子应用根节点上');
  assert.match(
    layout,
    /shell === 'standalone'\s*\?\s*<StandaloneTopbar[\s\S]*?:\s*<ThemeToggle className="theme-toggle theme-toggle-float"/,
    '自有顶栏只应在 standalone 渲染，embedded 仅保留悬浮主题开关',
  );
  const css = read('src/styles/shell-embedded.css');
  assert.match(css, /\[data-shell='embedded'\] \.theme-toggle-float \{[^}]*position: absolute;/);
});

test('运行形态判定与主题模块共用同一 qiankun 检测实现', () => {
  const shell = read('src/runtimeShell.ts');
  assert.match(shell, /import \{ isQiankunSlave \} from '@\/themeMode'/);
  assert.match(shell, /export function detectShellMode\(\)/);
  const themeMode = read('src/themeMode.ts');
  assert.match(themeMode, /__POWERED_BY_QIANKUN__/);
  assert.match(themeMode, /export function isQiankunSlave\(\)/);
});
