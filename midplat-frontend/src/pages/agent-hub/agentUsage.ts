// Resolve against the same gateway used by the app, including embedded deployments.
export function agentRuntimeURL(apiBase: string, locationHref: string) {
  const url = new URL(`${apiBase.replace(/\/+$/, '')}/runtime/agent`, locationHref);
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('调用地址必须是 HTTP 或 HTTPS 地址');
  return url.toString().replace(/\/+$/, '');
}

function javaStringLiteral(value: string) {
  let out = '"';
  for (const char of value) {
    const code = char.codePointAt(0)!;
    if (char === '\\') out += '\\\\';
    else if (char === '"') out += '\\"';
    else if (char === '\n') out += '\\n';
    else if (char === '\r') out += '\\r';
    else if (char === '\t') out += '\\t';
    else if (code < 0x20) out += '\\u' + code.toString(16).padStart(4, '0');
    else out += char;
  }
  return out + '"';
}

export function agentJavaExample(url: string, taskKey: string, input: string, requestId: string) {
  return `import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AgentRunSubmit {
    public static void main(String[] args) throws Exception {
        String token = System.getenv("MIDPLAT_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("缺少环境变量 MIDPLAT_TOKEN");
        }
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("taskKey", ${javaStringLiteral(taskKey)});
        body.put("input", ${javaStringLiteral(input)});
        body.put("idempotencyKey", ${javaStringLiteral(requestId)});
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(${javaStringLiteral(`${url}/runs`)}))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 202) {
            throw new IllegalStateException("期望 HTTP 202 已受理，实际 HTTP " + response.statusCode() + " " + response.body());
        }
        System.out.println(response.body());
    }
}
`;
}

export function agentReactExample(taskKey: string, input: string, requestId: string) {
  return `import { useState } from 'react';

type AgentRunSubmitProps = { csrfToken?: string };

// 此路径只是调用你们已有 Java 后端转发接口的示意，需替换为实际业务接口；本平台没有新增该路由。
const BUSINESS_RUNS_URL = '/api/ai-agent/runs';

export function AgentRunSubmit({ csrfToken }: AgentRunSubmitProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const payload = {
    taskKey: ${JSON.stringify(taskKey)},
    input: ${JSON.stringify(input)},
    idempotencyKey: ${JSON.stringify(requestId)},
  };

  const submit = async () => {
    setLoading(true);
    setError('');
    setResult('');
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };
      if (csrfToken) headers['X-CSRF-TOKEN'] = csrfToken;
      const response = await fetch(BUSINESS_RUNS_URL, {
        method: 'POST', credentials: 'same-origin', headers, body: JSON.stringify(payload),
      });
      const text = await response.text();
      if (response.status !== 202) throw new Error(text || ('HTTP ' + response.status));
      setResult(text);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '提交失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <button type="button" disabled={loading} onClick={() => void submit()}>{loading ? '提交中…' : '提交请求'}</button>
      {error ? <pre>{error}</pre> : null}
      {result ? <pre>{result}</pre> : null}
    </div>
  );
}
`;
}
