const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

const source = fs.readFileSync(path.resolve(__dirname, '../src/createUuid.ts'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
function load(provider) {
  const exports = {};
  vm.runInNewContext(compiled, { exports, crypto: provider, Uint8Array });
  return exports.createUuid;
}

test('HTTP intranet pages create valid distinct UUIDs without crypto.randomUUID', () => {
  const createUuid = load({ getRandomValues: crypto.webcrypto.getRandomValues.bind(crypto.webcrypto) });
  const ids = Array.from({ length: 100 }, createUuid);
  for (const id of ids) assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(new Set(ids).size, ids.length);
});

test('secure pages preserve the native UUID implementation', () => {
  const expected = '01234567-89ab-4cde-8f01-23456789abcd';
  const provider = { randomUUID() { assert.equal(this, provider); return expected; } };
  assert.equal(load(provider)(), expected);
});

test('missing browser randomness fails with an actionable error', () => {
  assert.throws(load(undefined), /当前浏览器不支持生成请求标识/);
});
