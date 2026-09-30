import http from 'node:http';

// The remote business entry exposes only project-authenticated model calls.
// Management pages and local development sessions remain on the SSH-only listener.
export function createBusinessGateway(backend) {
  return http.createServer((req, res) => {
    const allowed = req.method === 'GET' && req.url === '/api/runtime/agent/configuration'
      || req.method === 'POST' && /^\/api\/runtime\/agent\/tasks\/[a-zA-Z0-9_-]{1,64}\/v1\/chat\/completions$/.test(req.url || '');
    if (!allowed) { res.writeHead(404); res.end(); return; }
    if (!/^Bearer \S+$/.test(req.headers.authorization || '')) { res.writeHead(401); res.end(); return; }
    const headers = {};
    for (const name of ['authorization', 'content-type', 'accept', 'x-request-id', 'x-request-deadline-ms']) {
      if (req.headers[name]) headers[name] = req.headers[name];
    }
    const upstream = http.request({ hostname: backend.hostname, port: backend.port,
      method: req.method, path: req.url, headers }, incoming => {
      res.writeHead(incoming.statusCode || 502, incoming.headers); incoming.pipe(res);
    });
    upstream.setTimeout(650000, () => upstream.destroy(new Error('timeout')));
    upstream.on('error', () => {
      if (res.headersSent) { res.destroy(); return; }
      res.writeHead(502, { 'Content-Type': 'application/problem+json; charset=utf-8' });
      res.end(JSON.stringify({ detail: '智能体服务暂不可用' }));
    });
    res.on('close', () => upstream.destroy()); req.pipe(upstream);
  });
}
