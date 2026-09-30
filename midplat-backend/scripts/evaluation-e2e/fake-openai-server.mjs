import http from 'node:http';
import { writeFile } from 'node:fs/promises';

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1]);
}

const port = Number(args.get('--port') ?? '0');
const portFile = args.get('--port-file');
const failedInputs = new Set();

function json(response, status, body) {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(payload),
  });
  response.end(payload);
}

const server = http.createServer((request, response) => {
  if (request.method !== 'POST' || request.url !== '/chat/completions') {
    json(response, 404, { error: { message: 'not found' } });
    return;
  }

  let size = 0;
  const chunks = [];
  request.on('data', (chunk) => {
    size += chunk.length;
    if (size > 1024 * 1024) {
      request.destroy();
      return;
    }
    chunks.push(chunk);
  });
  request.on('end', () => {
    try {
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      const userMessage = body.messages?.findLast?.((item) => item?.role === 'user')?.content
        ?? [...(body.messages ?? [])].reverse().find((item) => item?.role === 'user')?.content
        ?? '';
      if (String(userMessage).includes('E2E_FAIL_ONCE') && !failedInputs.has(userMessage)) {
        failedInputs.add(userMessage);
        json(response, 500, { error: { message: 'controlled transient failure' } });
        return;
      }
      json(response, 200, {
        id: 'fake-evaluation-response',
        object: 'chat.completion',
        choices: [{ index: 0, message: { role: 'assistant', content: '评测通过' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 11, completion_tokens: 3, total_tokens: 14 },
      });
    } catch {
      json(response, 400, { error: { message: 'invalid request' } });
    }
  });
});

server.listen(port, '127.0.0.1', async () => {
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('无法取得假模型监听端口');
  if (portFile) await writeFile(portFile, String(address.port), { encoding: 'utf8', mode: 0o600 });
  process.stdout.write(`假模型服务已启动：127.0.0.1:${address.port}\n`);
});

function shutdown() {
  server.close(() => process.exit(0));
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
