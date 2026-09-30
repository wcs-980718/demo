#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/../.." && pwd)"
temp_root="${TMPDIR:-/tmp}"
temp_root="${temp_root%/}"
temp_dir="$(mktemp -d "${temp_root}/midplat-evaluation-e2e.XXXXXX")"
schema="midplat_eval_e2e_$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
pg_host="${MIDPLAT_E2E_DB_HOST:-127.0.0.1}"
pg_port="${MIDPLAT_E2E_DB_PORT:-5432}"
pg_database="${MIDPLAT_E2E_DB_NAME:-postgres}"
pg_user="${MIDPLAT_E2E_DB_USER:-$(id -un)}"
pg_password="${MIDPLAT_E2E_DB_PASSWORD:-}"
backend_pid=""
fake_pid=""
test_token="local-e2e-token-$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"

if [[ ! "${schema}" =~ ^midplat_eval_e2e_[a-f0-9]{32}$ ]]; then
  echo "拒绝使用不安全的端到端 schema 名称" >&2
  exit 1
fi

export PGPASSWORD="${pg_password}"

psql_e2e() {
  psql -X -q -v ON_ERROR_STOP=1 -h "${pg_host}" -p "${pg_port}" -U "${pg_user}" -d "${pg_database}" "$@"
}

cleanup() {
  local exit_code=$?
  set +e
  [[ -n "${backend_pid}" ]] && kill "${backend_pid}" 2>/dev/null
  [[ -n "${fake_pid}" ]] && kill "${fake_pid}" 2>/dev/null
  [[ -n "${backend_pid}" ]] && wait "${backend_pid}" 2>/dev/null
  [[ -n "${fake_pid}" ]] && wait "${fake_pid}" 2>/dev/null
  psql_e2e -c "drop schema if exists \"${schema}\" cascade" >/dev/null 2>&1
  local residue
  residue="$(psql_e2e -Atc "select count(*) from information_schema.schemata where schema_name='${schema}'" 2>/dev/null)"
  if [[ "${residue}" != "0" ]]; then
    echo "端到端 schema 清理失败：${schema}" >&2
    exit_code=1
  fi
  if [[ "$(dirname "${temp_dir}")" == "${temp_root}" && "$(basename "${temp_dir}")" == midplat-evaluation-e2e.* ]]; then
    rm -rf -- "${temp_dir}"
  else
    echo "拒绝删除未通过校验的临时目录：${temp_dir}" >&2
    exit_code=1
  fi
  if [[ ${exit_code} -eq 0 ]]; then
    echo "端到端验收完成，随机 schema 已清理"
  fi
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

free_port() {
  node -e 'const n=require("node:net").createServer();n.listen(0,"127.0.0.1",()=>{console.log(n.address().port);n.close()})'
}

cd "${repo_dir}"
psql_e2e -c "create schema \"${schema}\"" >/dev/null

node "${script_dir}/fake-openai-server.mjs" --port 0 --port-file "${temp_dir}/fake-port" >"${temp_dir}/fake.log" 2>&1 &
fake_pid=$!
for _ in {1..100}; do
  [[ -s "${temp_dir}/fake-port" ]] && break
  sleep 0.05
done
[[ -s "${temp_dir}/fake-port" ]] || { echo "假模型服务启动失败" >&2; exit 1; }
fake_port="$(<"${temp_dir}/fake-port")"
backend_port="$(free_port)"

jar_file="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | sort | tail -1)"
if [[ -z "${jar_file}" ]]; then
  mvn -q -DskipTests package
  jar_file="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | sort | tail -1)"
fi
[[ -n "${jar_file}" ]] || { echo "未找到后端 JAR" >&2; exit 1; }

SPRING_DATASOURCE_URL="jdbc:postgresql://${pg_host}:${pg_port}/${pg_database}?currentSchema=${schema}" \
SPRING_DATASOURCE_USERNAME="${pg_user}" \
SPRING_DATASOURCE_PASSWORD="${pg_password}" \
SPRING_FLYWAY_SCHEMAS="${schema}" \
SPRING_FLYWAY_DEFAULT_SCHEMA="${schema}" \
SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA="${schema}" \
MIDPLAT_PORT="${backend_port}" \
MIDPLAT_EVALUATION_RECOVERY_ENABLED="false" \
java -jar "${jar_file}" >"${temp_dir}/backend.log" 2>&1 &
backend_pid=$!

for _ in {1..160}; do
  if curl -fsS "http://127.0.0.1:${backend_port}/api/health" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "${backend_pid}" 2>/dev/null; then
    echo "后端启动失败" >&2
    tail -80 "${temp_dir}/backend.log" >&2
    exit 1
  fi
  sleep 0.25
done
curl -fsS "http://127.0.0.1:${backend_port}/api/health" >/dev/null

MIDPLAT_E2E_BASE_URL="http://127.0.0.1:${backend_port}" \
MIDPLAT_E2E_FAKE_BASE_URL="http://127.0.0.1:${fake_port}" \
MIDPLAT_E2E_TEST_TOKEN="${test_token}" \
node "${script_dir}/verify-e2e.mjs"

if rg -F "${test_token}" "${temp_dir}/backend.log" "${temp_dir}/fake.log" >/dev/null; then
  echo "测试令牌出现在运行日志中" >&2
  exit 1
fi

if [[ "${MIDPLAT_E2E_HOLD_OPEN:-false}" == "true" ]]; then
  echo "浏览器验收环境已就绪：http://127.0.0.1:${backend_port}"
  while kill -0 "${backend_pid}" 2>/dev/null && kill -0 "${fake_pid}" 2>/dev/null; do
    sleep 1
  done
  echo "浏览器验收依赖进程意外退出" >&2
  exit 1
fi
