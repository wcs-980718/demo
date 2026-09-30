import { createBusinessGateway } from './fusion-business-gateway.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(process.env.FUSION_WEB_ROOT || path.join(path.dirname(fileURLToPath(import.meta.url)), '../dist'));
const backend = new URL(process.env.FUSION_API_ORIGIN || 'http://127.0.0.1:18090');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.woff2': 'font/woff2', '.ico': 'image/x-icon' };
const server = http.createServer((req, res) => {
  if (req.url?.startsWith('/api/')) {
    const upstream = http.request({ hostname: backend.hostname, port: backend.port, method: req.method, path: req.url, headers: { ...req.headers, host: backend.host } }, incoming => {
      res.writeHead(incoming.statusCode || 502, incoming.headers); incoming.pipe(res);
    });
    upstream.setTimeout(req.url?.includes('/stream') ? 650000 : 150000, () => upstream.destroy(new Error('timeout')));
    upstream.on('error', () => { if (!res.headersSent) { res.writeHead(502, { 'Content-Type': 'application/problem+json; charset=utf-8' }); res.end(JSON.stringify({ detail: '融合后端暂不可用' })); } else res.destroy(); });
    res.on('close', () => upstream.destroy()); req.pipe(upstream); return;
  }
  if (!['GET', 'HEAD'].includes(req.method || 'GET')) { res.writeHead(405); res.end(); return; }
  let pathname; try { pathname = decodeURIComponent(new URL(req.url || '/', 'http://local').pathname); } catch { res.writeHead(400); res.end(); return; }
  let file = path.resolve(root, `.${pathname}`);
  if (file !== root && !file.startsWith(root + path.sep)) { res.writeHead(403); res.end(); return; }
  try { if (!fs.statSync(file).isFile()) file = path.join(root, 'index.html'); } catch { file = path.join(root, 'index.html'); }
  const stream = fs.createReadStream(file); stream.on('error', () => { if (!res.headersSent) res.writeHead(404); res.end(); });
  res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
  res.setHeader('X-Content-Type-Options', 'nosniff');res.setHeader('Referrer-Policy', 'same-origin');
  res.setHeader('Cache-Control', /\.[a-f0-9]{8,}\./i.test(path.basename(file)) ? 'public, max-age=31536000, immutable' : 'no-cache');
  if (req.method === 'HEAD') { stream.destroy(); res.end(); } else stream.pipe(res);
});
server.listen(Number(process.env.FUSION_WEB_PORT || 18080), '127.0.0.1');

if (process.env.FUSION_BUSINESS_PORT) {
  createBusinessGateway(backend).listen(Number(process.env.FUSION_BUSINESS_PORT), process.env.FUSION_BUSINESS_HOST || '127.0.0.1');
}
