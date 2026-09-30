import http from 'node:http';
import { writeFile } from 'node:fs/promises';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1]);
}

const port = Number(args.get('--port') ?? '0');
const portFile = args.get('--port-file');

function json(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  });
  response.end(payload);
}

function drain(request) {
  return new Promise((resolve, reject) => {
    request.on('data', () => {});
    request.on('end', resolve);
    request.on('error', reject);
  });
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', 'http://127.0.0.1');
  const pathname = url.pathname;
  try {
    await drain(request);
  } catch {
    json(response, 400, { code: 400, message: 'bad request', data: null });
    return;
  }

  if (pathname === '/api/settings' && (request.method === 'GET' || request.method === 'PUT')) {
    json(response, 200, { code: 0, message: 'ok', data: { saved: true } });
    return;
  }
  if (pathname === '/api/knowledge-bases' && request.method === 'GET') {
    json(response, 200, {
      code: 0,
      message: 'ok',
      data: { content: [{ id: 9, name: 'E2E测试知识库', status: 'active', docCount: 0, chunkCount: 0 }] },
    });
    return;
  }
  if (pathname === '/api/knowledge-bases' && request.method === 'POST') {
    json(response, 200, {
      code: 0,
      message: 'ok',
      data: { id: 10, name: 'E2E测试新建知识库', status: 'active', docCount: 0, chunkCount: 0 },
    });
    return;
  }
  if (request.method === 'POST' && /\/api\/knowledge-bases\/\d+\/documents\/upload$/.test(pathname)) {
    json(response, 200, { code: 0, message: 'ok', data: { id: 101, status: 'pending' } });
    return;
  }
  if (request.method === 'GET' && /\/api\/documents\/\d+$/.test(pathname)) {
    json(response, 200, { code: 0, message: 'ok', data: { id: 101, status: 'completed', chunkCount: 1 } });
    return;
  }
  json(response, 404, { code: 404, message: 'not found', data: null });
});

server.listen(port, '127.0.0.1', async () => {
  const address = server.address();
  const bound = typeof address === 'object' && address ? address.port : port;
  if (portFile) {
    await writeFile(portFile, String(bound));
  }
  process.stdout.write(`fake-kb listening on 127.0.0.1:${bound}\n`);
});
