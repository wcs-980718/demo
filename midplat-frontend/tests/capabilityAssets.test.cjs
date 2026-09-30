const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const projectRoot = path.resolve(__dirname, '..');
const read = (relative) => fs.readFileSync(path.join(projectRoot, relative), 'utf8');

test('能力资产页面与表单文件存在', () => {
  for (const file of [
    'src/pages/capability-assets/index.tsx',
    'src/pages/capability-assets/assetForm.tsx',
    'src/pages/capability-assets/asset.css',
  ]) {
    assert.ok(fs.existsSync(path.join(projectRoot, file)), `${file} 应存在`);
  }
});

test('.umirc.ts 注册 /capabilities/assets 且保留旧 /capabilities 路由', () => {
  const umiConfig = read('.umirc.ts');
  assert.match(umiConfig, /path:\s*'\/capabilities',\s*component:\s*'@\/pages\/capabilities\/index'/);
  assert.match(umiConfig, /path:\s*'\/capabilities\/assets',\s*component:\s*'@\/pages\/capability-assets\/index'/);
});

test('routeCatalog 新增 capability-assets，开发中心接口页标题为对外接口总览', () => {
  const catalog = read('src/routeCatalog.ts');
  assert.match(catalog, /name:\s*'capabilities',\s*path:\s*'\/capabilities',\s*title:\s*'对外接口总览'/);
  assert.match(catalog, /name:\s*'capability-assets',\s*path:\s*'\/capabilities\/assets',\s*title:\s*'能力资产'/);
  assert.match(catalog, /filePath:\s*'src\/pages\/capability-assets\/index\.tsx'/);
});

test('midplatApi 能力资产方法与后端路径一一对应', () => {
  const api = read('src/api/midplatApi.ts');
  assert.match(api, /client\.get<ApiResponse<CapabilityAssetPage>>\('\/capabilities\/assets',\s*\{ params \}\)/);
  assert.match(api, /client\.post<ApiResponse<CapabilityAssetDetail>>\('\/capabilities\/assets', payload\)/);
  assert.match(api, /client\.get<ApiResponse<CapabilityAssetDetail>>\(`\/capabilities\/assets\/\$\{id\}`\)/);
  assert.match(api, /client\.put<ApiResponse<CapabilityAssetDetail & \{ sync: CapabilityAssetSync\[\] \}>>\(`\/capabilities\/assets\/\$\{id\}`, payload\)/);
  assert.match(api, /client\.patch<ApiResponse<CapabilityAssetDetail & \{ sync: CapabilityAssetSync\[\] \}>>\(`\/capabilities\/assets\/\$\{id\}\/status`, \{ status \}\)/);
  assert.match(api, /client\.get<ApiResponse<CapabilityAssetRevision\[\]>>\(`\/capabilities\/assets\/\$\{id\}\/revisions`\)/);
  assert.match(api, /client\.post<ApiResponse<CapabilityAssetDetail & \{ sync: CapabilityAssetSync\[\] \}>>\(`\/capabilities\/assets\/\$\{id\}\/sync`\)/);
  assert.match(api, /client\.get<ApiResponse<CapabilityAssetGrantPage>>\(`\/capabilities\/projects\/\$\{projectId\}\/asset-grants`,\s*\{ params: \{ environment \} \}\)/);
  assert.match(api, /client\.put<ApiResponse<CapabilityAssetGrantPage>>\(`\/capabilities\/projects\/\$\{projectId\}\/asset-grants`, payload, \{ params: \{ environment \} \}\)/);
  assert.match(api, /expectedRevision/);
});

test('智能体侧资产面板跳转目标改为能力资产页且保持只读', () => {
  const workspace = read('src/pages/agent-hub/FusionWorkspace.tsx');
  const views = read('src/pages/agent-hub/FusionWorkspaceViews.tsx');
  assert.match(workspace, /onCenter=\{\(\) => navigate\('\/capabilities\/assets'\)\}/);
  assert.doesNotMatch(workspace, /navigate\('\/capabilities'\)/);
  assert.doesNotMatch(views, /saveAsset|assetEnabled/, '资产面板不得恢复写操作');
});

test('能力资产页面声明兼容内嵌文档语义且不用浏览器存储保存秘密', () => {
  const page = read('src/pages/capability-assets/index.tsx');
  const form = read('src/pages/capability-assets/assetForm.tsx');
  assert.match(page, /兼容内嵌文档\/数据资产/);
  assert.match(page, /PinnedRevisionSelect assetId=\{row\.assetId\}/);
  assert.match(page, /grants\.data\.grants/);
  assert.match(page, /retryCapabilityAssetSync/);
  assert.match(page, /includeCatalog:\s*false/);
  assert.match(form, /严禁在此或正文中填写密钥本身/);
  for (const file of ['src/pages/capability-assets/index.tsx', 'src/pages/capability-assets/assetForm.tsx']) {
    const source = read(file);
    assert.doesNotMatch(source, /localStorage|sessionStorage/, `${file} 不得使用浏览器存储`);
    assert.doesNotMatch(source, /headers\s*[:=].*Authorization|apiKey\s*[:=]/, `${file} 不得提供 headers/apiKey 字段`);
  }
});
