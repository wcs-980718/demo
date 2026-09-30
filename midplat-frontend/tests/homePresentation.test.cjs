const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const test = require('node:test');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const ts = require('typescript');

const projectRoot = path.resolve(__dirname, '..');
const originalResolveFilename = Module._resolveFilename;
const originalLoad = Module._load;

function compileTypeScript(module, filename) {
  const source = require('node:fs').readFileSync(filename, 'utf8');
  const projectIconImport = "import { projectIcon } from '@/projectIcons';";
  const normalizedSource = source.includes(projectIconImport)
    ? `${projectIconImport}\n${source.replace(projectIconImport, '')}`
    : source;
  const output = ts.transpileModule(normalizedSource, {
    compilerOptions: {
      esModuleInterop: true,
      jsx: ts.JsxEmit.ReactJSX,
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2020,
    },
    fileName: filename,
  }).outputText;
  module._compile(output, filename);
}

require.extensions['.ts'] = compileTypeScript;
require.extensions['.tsx'] = compileTypeScript;

Module._resolveFilename = function resolveFilename(request, parent, isMain, options) {
  if (request.startsWith('@/')) {
    return originalResolveFilename.call(
      this,
      path.join(projectRoot, 'src', request.slice(2)),
      parent,
      isMain,
      options,
    );
  }
  return originalResolveFilename.call(this, request, parent, isMain, options);
};

Module._load = function load(request, parent, isMain) {
  if (request === '@umijs/max') {
    return { useNavigate: () => () => {} };
  }
  if (request === '@/api/midplatApi' || request.includes('/api/midplatApi')) {
    return { midplatApi: { listPlatforms: async () => [] } };
  }
  return originalLoad.call(this, request, parent, isMain);
};

const { QueryClient, QueryClientProvider } = require('@tanstack/react-query');
const HomePage = require('../src/pages/home').default;
const { BrandMark } = require('../src/components/BrandMark');

function renderHome(apps) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, enabled: false } },
  });
  if (apps) client.setQueryData(['master-apps'], apps);
  return renderToStaticMarkup(
    React.createElement(QueryClientProvider, { client }, React.createElement(HomePage)),
  );
}

const MASTER_APPS_FIXTURE = [
  { key: 'a1', title: '数据集成', remark: '统一接入', projectGroup: '数智大脑', path: '/di' },
  { key: 'a2', title: '知识库', projectGroup: '数智大脑', projectTwoGroup: '知识中台' },
  { key: 'a3', title: '指标平台', projectGroup: '经营分析' },
  { key: 'hidden', title: '不应展示', hide: 'True' },
];

function extractBrandGeometry(svg) {
  return {
    paths: [...svg.matchAll(/<path\b[^>]*\bd="([^"]+)"/g)].map((match) => match[1]),
    circles: [...svg.matchAll(/<circle\b[^>]*\bcx="([^"]+)"[^>]*\bcy="([^"]+)"[^>]*\br="([^"]+)"/g)]
      .map((match) => match.slice(1)),
  };
}

test('首页搜索以具备可访问名称的 search 表单提交', () => {
  const markup = renderHome();

  assert.match(markup, /<form[^>]*class="home-hero-search"[^>]*role="search"/);
  assert.match(markup, /<label[^>]*for="home-capability-search"[^>]*>搜索项目或能力<\/label>/);
  assert.match(markup, /<input[^>]*id="home-capability-search"/);
  assert.match(markup, /<button[^>]*type="submit"/);
});

test('首页英雄区用去字场景视频，并把数智大脑放在画面左侧', () => {
  const markup = renderHome();

  assert.match(markup, /class="home-hero-stage"/);
  assert.match(markup, /<h1 class="home-hero-title">数智大脑<\/h1>/);
  assert.match(markup, /media\/hero-light\.mp4/);
  assert.doesNotMatch(markup, /hub-face/);
});

test('主应用目录按一二级分组渲染入口卡片，整卡可进入且隐藏项不展示', () => {
  const markup = renderHome(MASTER_APPS_FIXTURE);
  const cards = markup.match(/<article class="app"/g) ?? [];

  assert.equal(cards.length, 3);
  assert.match(markup, /class="grp-h grp-main">[\s\S]*?数智大脑<span class="n">2<\/span>/);
  assert.match(markup, /class="grp-h sub"[^>]*>[\s\S]*?知识中台<span class="n">1<\/span>/);
  assert.match(markup, /role="link"/);
  assert.match(markup, /tabindex="0"/);
  assert.match(markup, /aria-label="进入数据集成"/);
  assert.doesNotMatch(markup, /不应展示/);
});

test('favicon 与 Header 品牌图标共享同一套神经中枢几何', () => {
  const favicon = fs.readFileSync(path.join(projectRoot, 'public/favicon.svg'), 'utf8');
  const headerMark = renderToStaticMarkup(React.createElement(BrandMark, { size: 24 }));

  assert.deepEqual(extractBrandGeometry(favicon), extractBrandGeometry(headerMark));
});

test('首页英雄标题在深浅主题下都有独立颜色', () => {
  const css = fs.readFileSync(path.join(projectRoot, 'src/styles/home.css'), 'utf8');
  assert.match(css, /\.home-hero-title[\s\S]*color:\s*#f8fbff/);
  assert.match(css, /\[data-theme='light'\] \.home-hero-title[\s\S]*color:\s*#172033/);
  assert.match(css, /\.home-hero-stage video/);
});

test('应用目录区块头为 en2 + h2 双层标题并展示应用与分组统计', () => {
  const markup = renderHome(MASTER_APPS_FIXTURE);

  assert.match(
    markup,
    /<div class="sec-head"><div><span class="en2">APP DIRECTORY<\/span><h2[^>]*>应用目录<\/h2>/,
  );
  assert.match(
    markup,
    /<div class="sec-stats">[\s\S]*?<b>3<\/b><span>个应用<\/span>[\s\S]*?<b>2<\/b><span>个分组<\/span>/,
  );
  assert.match(markup, /role="tablist" aria-label="应用分组"/);
  assert.match(markup, /role="tab" aria-selected="true">全部<span class="n">3<\/span>/);
});
