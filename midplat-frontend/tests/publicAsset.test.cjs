const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const path = require('node:path');
const test = require('node:test');
const ts = require('typescript');

const projectRoot = path.resolve(__dirname, '..');

function loadResolvePublicAsset() {
  const filename = path.join(projectRoot, 'src/publicAsset.ts');
  const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    fileName: filename,
  }).outputText;
  const loaded = new Module(filename, module);
  loaded.filename = filename;
  loaded.paths = module.paths;
  loaded._compile(output, filename);
  return loaded.exports.resolvePublicAsset;
}

test('生产环境和 qiankun 子应用都给静态资源加上前端前缀', () => {
  const resolvePublicAsset = loadResolvePublicAsset();
  assert.equal(
    resolvePublicAsset('media/hero-light.mp4', { nodeEnv: 'production' }),
    '/midplat-frontend/media/hero-light.mp4',
  );
  assert.equal(
    resolvePublicAsset('media/hero-light.mp4', { injectedPublicPath: 'http://portal.example.com:31010/midplat-frontend/' }),
    'http://portal.example.com:31010/midplat-frontend/media/hero-light.mp4',
  );
  assert.equal(
    resolvePublicAsset('media/hero-light.mp4', { nodeEnv: 'development' }),
    '/media/hero-light.mp4',
  );
});
