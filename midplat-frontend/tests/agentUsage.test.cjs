const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('typescript');
const vm = require('node:vm');
const code = ts.transpileModule(fs.readFileSync('src/pages/agent-hub/agentUsage.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const exportsObject = {}; vm.runInNewContext(code, { exports: exportsObject, URL });
const { agentRuntimeURL, agentJavaExample, agentReactExample } = exportsObject;
const unescapeJava = quoted => {
  const inner = quoted.slice(1, -1);
  let out = '';
  for (let i = 0; i < inner.length; i++) {
    if (inner[i] !== '\\') { out += inner[i]; continue; }
    const next = inner[++i];
    if (next === 'n') out += '\n';
    else if (next === 'r') out += '\r';
    else if (next === 't') out += '\t';
    else if (next === '"') out += '"';
    else if (next === '\\') out += '\\';
    else if (next === 'u') { out += String.fromCharCode(parseInt(inner.slice(i + 1, i + 5), 16)); i += 4; }
    else out += next;
  }
  return out;
};
const javaLiteral = (source, pattern) => {
  const match = source.match(pattern);
  assert.ok(match, String(pattern));
  return unescapeJava(match[1]);
};
test('runtime URL follows direct, embedded, and prefixed API gateways', () => {
  assert.equal(agentRuntimeURL('/api', 'http://demo:30200/agent-hub/use'), 'http://demo:30200/api/runtime/agent');
  assert.equal(agentRuntimeURL('https://gateway.example/api/', 'http://portal/app'), 'https://gateway.example/api/runtime/agent');
  assert.equal(agentRuntimeURL('/midplat/api', 'https://portal.example/app'), 'https://portal.example/midplat/api/runtime/agent');
  assert.throws(() => agentRuntimeURL('javascript:bad', 'http://portal/app'));
});
test('Java example posts the current payload as safe literals over HttpClient', () => {
  const input = 'quote"\\\nJSON\n中文 😄 \\u0041\t';
  const example = agentJavaExample('https://gateway.example/api/runtime/agent', 'quality_check', input, 'request-1');
  assert.match(example, /import java\.net\.http\.HttpClient/);
  assert.match(example, /HttpClient\.newBuilder/);
  assert.match(example, /System\.getenv\("MIDPLAT_TOKEN"\)/);
  assert.match(example, /statusCode\(\) != 202/);
  assert.doesNotMatch(example, /queued|while |\/close|LOCAL-FIXTURE-SECRET|randomUUID/);
  assert.equal(javaLiteral(example, /body\.put\("taskKey",\s*("(?:\\.|[^"\\])*")\)/), 'quality_check');
  assert.equal(javaLiteral(example, /body\.put\("input",\s*("(?:\\.|[^"\\])*")\)/), input);
  assert.equal(javaLiteral(example, /body\.put\("idempotencyKey",\s*("(?:\\.|[^"\\])*")\)/), 'request-1');
  assert.equal(javaLiteral(example, /URI\.create\(("(?:\\.|[^"\\])*")\)/), 'https://gateway.example/api/runtime/agent/runs');
  assert.match(example, /connectTimeout/);
  assert.match(example, /\.timeout\(/);
});
test('React example is submit-only TSX with a fixed business forward path', () => {
  const input = "换行\n三引号'''\"\"\"\\ Unicode 😄";
  const example = agentReactExample('main', input, 'request-1');
  const compiled = ts.transpileModule(example, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 }, fileName: 'AgentRunSubmit.tsx', reportDiagnostics: true });
  const errors = (compiled.diagnostics || []).filter(item => item.category === ts.DiagnosticCategory.Error);
  assert.equal(errors.length, 0, errors.map(item => item.messageText).join('\n'));
  const payload = example.match(/const payload = (\{[\s\S]*?\});/);
  assert.ok(payload);
  assert.deepEqual(Function(`"use strict"; return (${payload[1]});`)(), { taskKey: 'main', input, idempotencyKey: 'request-1' });
  assert.match(example, /from 'react'/);
  assert.match(example, /credentials:\s*'same-origin'/);
  assert.match(example, /X-CSRF-TOKEN/);
  assert.match(example, /status !== 202/);
  assert.equal([...example.matchAll(/\/api\/ai-agent\/runs/g)].length, 1);
  assert.doesNotMatch(example, /MIDPLAT_TOKEN|Bearer|runtime\/agent|dangerouslySetInnerHTML|randomUUID|uuid/);
});
