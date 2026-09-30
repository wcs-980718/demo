const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const source = fs.readFileSync(path.resolve(__dirname, '../src/pages/agent-hub/fusionApi.ts'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText;
const sessionValue = n => ({ identity: { principal: 'test', projects: [], writable: true, admin: true }, localAuthentication: false, csrf: `csrf-${n}` });
const failure = status => ({ isAxiosError: true, response: status ? { status, data: { detail: `http-${status}` } } : undefined });

function load(handle) {
  const calls = [];
  const exports = {};
  const axios = { create: () => ({ request: async config => { calls.push(config); return { data: await handle(config, calls) }; } }), isAxiosError: error => error.isAxiosError };
  vm.runInNewContext(compiled, { exports, window: {}, require: name => name === 'axios' ? axios : { getMidplatApiBaseURL: () => '/api' } });
  return { api: exports.fusionApi, calls };
}

test('a read renews an expired session and retries once', async () => {
  let sessions = 0, reads = 0;
  const { api, calls } = load(config => {
    if (config.url === '/session') return sessionValue(++sessions);
    if (++reads === 1) throw failure(401);
    return [];
  });
  await api.projects();
  assert.equal(sessions, 2);
  assert.equal(reads, 2);
  assert.equal(calls.at(-1).headers['X-Fusion-CSRF'], 'csrf-2');
});

test('late parallel 401 responses reuse the session already renewed by another read', async () => {
  let sessions = 0;
  let releaseLateFailure;
  const pending = new Promise((_, reject) => { releaseLateFailure = () => reject(failure(401)); });
  const { api } = load(config => {
    if (config.url === '/session') return sessionValue(++sessions);
    if (config.headers['X-Fusion-CSRF'] === 'csrf-1') {
      if (config.url === '/projects') return pending;
      throw failure(401);
    }
    return [];
  });
  await api.session();
  const first = api.projects();
  await api.bindings('development');
  releaseLateFailure();
  await first;
  assert.equal(sessions, 2);
});

test('explicit reconnect fetches a fresh session after an opaque network failure', async () => {
  let sessions = 0, fail = true;
  const { api } = load(config => {
    if (config.url === '/session') return sessionValue(++sessions);
    if (fail) throw failure(0);
    return [];
  });
  await assert.rejects(api.projects(), error => error.status === 0);
  fail = false;
  await api.reconnect();
  await api.projects();
  assert.equal(sessions, 2);
});

test('failed writes are not replayed and the next user action establishes a new session', async () => {
  let sessions = 0, writes = 0;
  const { api } = load(config => {
    if (config.url === '/session') return sessionValue(++sessions);
    if (++writes === 1) throw failure(401);
    return {};
  });
  await assert.rejects(api.save('project', 'development', 1, {}), error => error.status === 401);
  assert.equal(writes, 1);
  await api.save('project', 'development', 1, {});
  assert.equal(sessions, 2);
  assert.equal(writes, 2);
});

test('read authentication retries are bounded even if the renewed session is rejected', async () => {
  let sessions = 0, reads = 0;
  const { api } = load(config => {
    if (config.url === '/session') return sessionValue(++sessions);
    ++reads; throw failure(401);
  });
  await assert.rejects(api.projects(), error => error.status === 401);
  assert.equal(sessions, 2);
  assert.equal(reads, 2);
  await api.session();
  assert.equal(sessions, 3);
});

test('permission and server failures remain visible without automatic replay', async () => {
  for (const status of [403, 500]) {
    let sessions = 0, reads = 0;
    const { api } = load(config => {
      if (config.url === '/session') return sessionValue(++sessions);
      ++reads; throw failure(status);
    });
    await assert.rejects(api.projects(), error => error.status === status);
    assert.equal(sessions, 1);
    assert.equal(reads, 1);
  }
});
