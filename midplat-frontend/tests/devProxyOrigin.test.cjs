const assert = require('node:assert/strict');
const { once } = require('node:events');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const ts = require('typescript');
const { createProxy } = require('@umijs/bundler-utils/dist/proxy');

function developmentProxy(target) {
  const root = path.resolve(__dirname, '..');
  const compiled = ts.transpileModule(fs.readFileSync(path.join(root, '.umirc.ts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const loaded = { exports: {} };
  vm.runInNewContext(compiled, {
    module: loaded,
    exports: loaded.exports,
    __dirname: root,
    process: { env: { API_TARGET: target } },
    require: (name) => name === '@umijs/max' ? { defineConfig: (value) => value } : require(name),
  });
  // vm 上下文里创建的对象原型属于 vm realm，宿主侧的 isPlainObject 判定会失败，
  // 先 JSON 一轮把配置物化到宿主 realm（配置本身不含函数）。
  return JSON.parse(JSON.stringify(loaded.exports.default.proxy));
}

async function listen(server) {
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  return server.address().port;
}

function request(port, origin) {
  return new Promise((resolve, reject) => {
    const headers = {
      'Content-Type': 'application/json',
      Cookie: 'JSESSIONID=preview-session',
      'X-Fusion-CSRF': 'preview-csrf',
    };
    if (origin) headers.Origin = origin;
    const req = http.request({ hostname: '127.0.0.1', port, path: '/api/fusion/probe', method: 'POST', headers }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (part) => { body += part; });
      res.on('end', () => resolve(JSON.parse(body)));
    });
    req.setTimeout(5000, () => req.destroy(new Error('Proxy request timed out')));
    req.on('error', reject);
    req.end('{}');
  });
}

test('开发代理保留浏览器来源及会话凭据供后端校验', async (t) => {
  const upstream = http.createServer((req, res) => {
    req.resume();
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ origin: req.headers.origin ?? null, cookie: req.headers.cookie,
      csrf: req.headers['x-fusion-csrf'], method: req.method }));
  });
  t.after(() => new Promise((resolve) => upstream.close(resolve)));
  const upstreamPort = await listen(upstream);
  const handlers = [];
  createProxy(developmentProxy(`http://127.0.0.1:${upstreamPort}`), { use: (handler) => handlers.push(handler) });
  const proxy = http.createServer((req, res) => {
    let index = 0;
    const next = () => index < handlers.length ? handlers[index++](req, res, next) : res.end('null');
    next();
  });
  t.after(() => new Promise((resolve) => proxy.close(resolve)));
  const proxyPort = await listen(proxy);
  for (const origin of ['http://127.0.0.1:8000', 'https://untrusted.invalid', null]) {
    const actual = await request(proxyPort, origin);
    assert.equal(actual.origin, origin, '代理不能把浏览器来源改成后端地址或替换不可信来源');
    assert.equal(actual.cookie, 'JSESSIONID=preview-session');
    assert.equal(actual.csrf, 'preview-csrf');
    assert.equal(actual.method, 'POST');
  }
});
