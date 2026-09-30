import { Button, Drawer, Tabs, message } from 'antd';
import { ClipboardCopy, Info } from 'lucide-react';
import type { ReactNode } from 'react';
import type { PlatformApiItem } from '@/api/midplatApi';
import { copyText } from '@/copyText';
import {
  contractForApi,
  type CapabilityError,
  type CapabilityParameter,
  type CapabilityResponseField,
} from '@/pages/capabilities/capabilityContracts';
import { openApiCurl, openApiUrl, sourceApiUrl } from '@/pages/platforms/apiCatalog';

export function ApiDetailDrawer({
  open,
  platformId,
  platformName,
  entryUrl,
  token,
  api,
  onClose,
}: {
  open: boolean;
  platformId: string;
  platformName: string;
  entryUrl?: string | null;
  token?: string | null;
  api: PlatformApiItem;
  onClose: () => void;
}) {
  const gatewayUrl = openApiUrl(platformId, api.id, api.path);
  const sourceUrl = sourceApiUrl(entryUrl, api.path);
  const contract = contractForApi(api.method, api.path);
  const credential = token?.trim() ? token.trim() : '';
  const headers = contract.headers.map((row) => (
    row.name === 'Authorization' && credential
      ? {
          ...row,
          example: `Bearer ${credential}`,
          rules: '格式固定为 Bearer <本项目接入凭证>，默认展示真实凭证。',
        }
      : row
  ));
  const pathRows = contract.requestParameters.filter((row) => row.location === 'Path');
  const queryRows = contract.requestParameters.filter((row) => row.location === 'Query');
  const bodyRows = contract.requestParameters.filter((row) => row.location === 'Body');
  const requestBody = api.method.toUpperCase() !== 'GET' && looksLikeJson(contract.requestExample)
    ? contract.requestExample
    : undefined;
  const curl = openApiCurl(api.method, gatewayUrl, requestBody, credential);

  return (
    <Drawer title="接口调用说明" open={open} size={860} onClose={onClose} destroyOnHidden>
      <div className="capability-detail-head">
        <div>
          <div className="capability-detail-name">
            <h2>{api.name}</h2>
            <span className={`method m-${api.method.toLowerCase()}`}>{api.method}</span>
          </div>
          <p>{api.note || `${platformName} 经中台转发的接口。`}</p>
        </div>
      </div>

      <section className="capability-detail-section no-border">
        <h3>请求地址</h3>
        <div className="capability-endpoint detail-endpoint">
          <span className={`method m-${api.method.toLowerCase()}`}>{api.method}</span>
          <code>{gatewayUrl}</code>
          <Button
            type="text"
            aria-label="复制中台请求地址"
            icon={<ClipboardCopy size={15} />}
            onClick={async () => {
              await copyText(gatewayUrl);
              message.success('中台请求地址已复制');
            }}
          />
        </div>
        <dl className="capability-call-summary">
          <div><dt>所属项目</dt><dd>{platformName}</dd></div>
          <div><dt>接口编码</dt><dd><code>{api.id}</code></dd></div>
          <div><dt>认证方式</dt><dd>{contract.authentication}</dd></div>
          <div><dt>Content-Type</dt><dd><code>{contract.contentType}</code></dd></div>
          <div><dt>来源接口</dt><dd><code>{sourceUrl}</code></dd></div>
          <div><dt>开放范围</dt><dd>{api.external ? '允许其他项目经中台调用' : '仅本项目可经中台调用'}</dd></div>
        </dl>
        <div className="api-credential-panel">
          <div className="api-credential-panel-head">
            <h3>调用凭证</h3>
            {credential ? (
              <Button
                size="small"
                type="text"
                icon={<ClipboardCopy size={14} />}
                onClick={async () => {
                  await copyText(credential);
                  message.success('凭证已复制');
                }}
              >
                复制凭证
              </Button>
            ) : null}
          </div>
          <pre className="codebox api-credential-value">{credential ? `Authorization: Bearer ${credential}` : '当前项目还没有接入凭证，请到工作台「接入凭证」查看。'}</pre>
          <p>默认展示本项目凭证，可直接复制到请求头。其他项目调用时请改用调用方自己的接入凭证。</p>
        </div>
        <div className="capability-contract-note">
          <Info size={15} />
          <span>
            经中台调用必须携带接入凭证。开关只决定其他项目能不能调，不代替凭证。不带或无效返回 401，开关关闭返回 403。
          </span>
        </div>
      </section>

      <Tabs
        className="capability-contract-tabs"
        defaultActiveKey="request"
        items={[
          {
            key: 'request',
            label: `请求参数（${contract.headers.length + contract.requestParameters.length}）`,
            children: (
              <div className="capability-contract-panel">
                <ContractSection title="请求头" description="经中台网关调用时必须携带的 HTTP Header。Authorization 为必填，默认展示本项目真实凭证。">
                  <ParameterTable rows={headers} />
                </ContractSection>
                {pathRows.length ? (
                  <ContractSection title="路径参数" description="替换中台地址中的 {占位符}，接在接口编码后面。">
                    <ParameterTable rows={pathRows} />
                  </ContractSection>
                ) : null}
                {queryRows.length ? (
                  <ContractSection title="查询参数" description="原样拼到中台地址的查询字符串，网关会转发给来源接口。">
                    <ParameterTable rows={queryRows} />
                  </ContractSection>
                ) : null}
                <ContractSection title="请求体参数" description="字段名、类型、必填规则来自来源项目接口。GET 通常没有请求体。">
                  <ParameterTable rows={bodyRows} />
                </ContractSection>
                <CodeExample title="curl 调用示例（含必填凭证）" value={curl} />
                {requestBody ? <CodeExample title="请求体示例" value={requestBody} /> : null}
              </div>
            ),
          },
          {
            key: 'response',
            label: `响应说明（${contract.responseFields.length}）`,
            children: (
              <div className="capability-contract-panel">
                <ContractSection title="响应字段" description="成功时可能出现的字段。未单独建契约的接口会原样返回来源系统响应。">
                  <ResponseFieldTable rows={contract.responseFields} />
                </ContractSection>
                <CodeExample title="成功响应示例" value={contract.responseExample} />
              </div>
            ),
          },
          {
            key: 'errors',
            label: `错误码（${contract.errors.length}）`,
            children: (
              <div className="capability-contract-panel">
                <ContractSection title="失败响应" description="先看中台网关的 401/403/404，再看来源系统错误码。">
                  <ErrorTable rows={contract.errors} />
                </ContractSection>
              </div>
            ),
          },
        ]}
      />
    </Drawer>
  );
}

function looksLikeJson(value: string) {
  const trimmed = value.trim();
  return trimmed.startsWith('{') || trimmed.startsWith('[');
}

function ContractSection({ title, description, children }: { title: string; description: string; children: ReactNode }) {
  return (
    <section className="capability-contract-section">
      <div className="capability-contract-section-head">
        <h3>{title}</h3>
        <p>{description}</p>
      </div>
      {children}
    </section>
  );
}

function ParameterTable({ rows }: { rows: CapabilityParameter[] }) {
  if (!rows.length) return <div className="capability-detail-empty">这个位置没有额外参数。</div>;
  return (
    <div className="capability-contract-table-wrap">
      <table className="capability-contract-table parameter-table">
        <thead>
          <tr>
            <th>参数</th>
            <th>位置</th>
            <th>类型</th>
            <th>必填</th>
            <th>说明与规则</th>
            <th>示例</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.location}-${row.name}`}>
              <td><code>{row.name}</code></td>
              <td>{row.location}</td>
              <td><span className="capability-type">{row.type}</span></td>
              <td><span className={`capability-required ${row.required ? 'yes' : 'no'}`}>{row.required ? '必填' : '可选'}</span></td>
              <td><span>{row.description}</span>{row.rules ? <small>{row.rules}</small> : null}</td>
              <td><code>{row.example ?? '—'}</code></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ResponseFieldTable({ rows }: { rows: CapabilityResponseField[] }) {
  if (!rows.length) return <div className="capability-detail-empty">尚未定义响应字段，将原样返回来源系统响应。</div>;
  return (
    <div className="capability-contract-table-wrap">
      <table className="capability-contract-table response-table">
        <thead>
          <tr>
            <th>字段</th>
            <th>类型</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.name}>
              <td><code>{row.name}</code></td>
              <td><span className="capability-type">{row.type}</span></td>
              <td>{row.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ErrorTable({ rows }: { rows: CapabilityError[] }) {
  if (!rows.length) return <div className="capability-detail-empty">尚未定义错误码。</div>;
  return (
    <div className="capability-contract-table-wrap">
      <table className="capability-contract-table error-table">
        <thead>
          <tr>
            <th>HTTP</th>
            <th>错误码</th>
            <th>处理说明</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.status}-${row.code}`}>
              <td><span className="capability-http-status">{row.status}</span></td>
              <td><code>{row.code}</code></td>
              <td>{row.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function CodeExample({ title, value }: { title: string; value: string }) {
  return (
    <section className="capability-code-example">
      <div className="capability-code-example-head">
        <h3>{title}</h3>
        <Button
          size="small"
          type="text"
          icon={<ClipboardCopy size={14} />}
          onClick={async () => {
            await copyText(value);
            message.success('示例已复制');
          }}
        >
          复制
        </Button>
      </div>
      <pre><code>{value}</code></pre>
    </section>
  );
}
