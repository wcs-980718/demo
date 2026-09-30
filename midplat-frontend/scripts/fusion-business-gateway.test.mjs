import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createBusinessGateway } from './fusion-business-gateway.mjs';

test('remote entry forwards only authenticated business routes and preserves stream/trace data', async () => {
  const calls = [];
  const backend = http.createServer((req, res) => {
    calls.push({ url: req.url, headers: req.headers });
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'X-Agent-Run-Id': 'run-id' });
    res.end('data: {"choices":[]}\n\ndata: [DONE]\n\n');
  });
  backend.listen(0, '127.0.0.1'); await once(backend, 'listening');
  const gateway = createBusinessGateway(new URL(`http://127.0.0.1:${backend.address().port}`));
  gateway.listen(0, '127.0.0.1'); await once(gateway, 'listening');
  const origin = `http://127.0.0.1:${gateway.address().port}`;
  try {
    for (const path of ['/api/fusion/session', '/api/platforms', '/agent-hub', '/api/runtime/agent/tasks/%2F..%2Fconfiguration']) {
      assert.equal((await fetch(origin + path, { headers: { Authorization: 'Bearer test-credential' } })).status, 404);
    }
    assert.equal((await fetch(origin + '/api/runtime/agent/configuration')).status, 401);
    assert.equal(calls.length, 0);
    const response = await fetch(origin + '/api/runtime/agent/tasks/answer/v1/chat/completions', {
      method: 'POST', body: '{}', headers: { Authorization: 'Bearer test-credential', 'X-Request-Id': 'request-id', 'X-Request-Deadline-Ms': '1788853387285', Cookie: 'local-session=private' },
    });
    assert.equal(response.headers.get('x-agent-run-id'), 'run-id');
    assert.match(await response.text(), /data: \[DONE\]/);
    assert.equal(calls[0].headers.authorization, 'Bearer test-credential');
    assert.equal(calls[0].headers['x-request-id'], 'request-id');
    assert.equal(calls[0].headers['x-request-deadline-ms'], '1788853387285');
    assert.equal(calls[0].headers.cookie, undefined);
  } finally {
    gateway.closeAllConnections(); gateway.close(); backend.closeAllConnections(); backend.close();
  }
});
