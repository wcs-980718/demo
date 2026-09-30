#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
midplat_dir="$(cd "${script_dir}/../.." && pwd)"
annotation_dir="$(cd "${midplat_dir}/../../数据标注平台/data-annotatio-backend" && pwd)"
temp_root="${TMPDIR:-/tmp}"
temp_root="${temp_root%/}"
temp_dir="$(mktemp -d "${temp_root}/midplat-openapi-e2e.XXXXXX")"
fake_pid=""
midplat_pid=""
annotation_pid=""

cleanup() {
  local exit_code=$?
  set +e
  [[ -n "${annotation_pid}" ]] && kill "${annotation_pid}" 2>/dev/null
  [[ -n "${midplat_pid}" ]] && kill "${midplat_pid}" 2>/dev/null
  [[ -n "${fake_pid}" ]] && kill "${fake_pid}" 2>/dev/null
  [[ -n "${annotation_pid}" ]] && wait "${annotation_pid}" 2>/dev/null
  [[ -n "${midplat_pid}" ]] && wait "${midplat_pid}" 2>/dev/null
  [[ -n "${fake_pid}" ]] && wait "${fake_pid}" 2>/dev/null
  rm -rf -- "${temp_dir}"
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

free_port() {
  node -e 'const n=require("node:net").createServer();n.listen(0,"127.0.0.1",()=>{console.log(n.address().port);n.close()})'
}

wait_http() {
  local url="$1"
  local tries="${2:-80}"
  for _ in $(seq 1 "${tries}"); do
    if curl -sS -m 2 -o /dev/null "${url}" 2>/dev/null; then
      return 0
    fi
    sleep 0.25
  done
  echo "等待服务就绪失败：${url}" >&2
  return 1
}

json_get() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)'"$1"')'
}

toggle() {
  local api_id="$1"
  local external="$2"
  curl -sS -m 10 -X PATCH "http://127.0.0.1:${midplat_port}/api/platforms/plat-kb/apis/${api_id}" \
    -H 'Content-Type: application/json' \
    -d "{\"external\":${external}}" >/dev/null
}

expect_status() {
  local label="$1"
  local expected="$2"
  local actual="$3"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "失败：${label} 期望 HTTP ${expected}，实际 ${actual}" >&2
    echo "响应：${body:-}" >&2
    exit 1
  fi
  echo "通过：${label} → HTTP ${actual}"
}

cd "${midplat_dir}"

node "${script_dir}/fake-kb-server.mjs" --port 0 --port-file "${temp_dir}/fake-port" >"${temp_dir}/fake.log" 2>&1 &
fake_pid=$!
for _ in {1..80}; do
  [[ -s "${temp_dir}/fake-port" ]] && break
  sleep 0.05
done
[[ -s "${temp_dir}/fake-port" ]] || { echo "假知识库启动失败"; cat "${temp_dir}/fake.log"; exit 1; }
fake_port="$(<"${temp_dir}/fake-port")"

jar_file="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | sort | tail -1)"
if [[ -z "${jar_file}" ]]; then
  mvn -q -DskipTests package
  jar_file="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*.original' | sort | tail -1)"
fi

midplat_port="$(free_port)"
java -jar "${jar_file}" \
  --spring.profiles.active=test \
  --server.port="${midplat_port}" \
  --spring.datasource.url="jdbc:h2:mem:midplat_openapi_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE" \
  >"${temp_dir}/midplat.log" 2>&1 &
midplat_pid=$!
wait_http "http://127.0.0.1:${midplat_port}/api/health" || { cat "${temp_dir}/midplat.log"; exit 1; }

platforms="$(curl -sS -m 10 "http://127.0.0.1:${midplat_port}/api/platforms")"
an_token="$(printf '%s' "${platforms}" | python3 -c 'import json,sys
items=json.load(sys.stdin)["data"]
print(next(i["token"] for i in items if i["id"]=="plat-an"))')"
kb="$(curl -sS -m 10 "http://127.0.0.1:${midplat_port}/api/platforms/plat-kb")"
kb_name="$(printf '%s' "${kb}" | json_get '["data"]["name"]')"
curl -sS -m 10 -X PATCH "http://127.0.0.1:${midplat_port}/api/platforms/plat-kb" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"${kb_name}\",\"entryUrl\":\"http://127.0.0.1:${fake_port}\",\"icon\":\"Database\"}" >/dev/null

for api_id in kb-list kb-create kb-upload kb-doc-get; do
  toggle "${api_id}" true
done

annotation_port="$(free_port)"
cd "${annotation_dir}"
mvn -q -DskipTests test-compile spring-boot:run \
  -Dspring-boot.run.useTestClasspath=true \
  -Dspring-boot.run.profiles=test \
  -Dspring-boot.run.arguments="--server.port=${annotation_port} --spring.config.import= --annotation.prelabel.llm.enabled=false --annotation.publish.midplat.base-url=http://127.0.0.1:${midplat_port} --annotation.publish.midplat.token=${an_token} --annotation.publish.midplat.knowledge-platform-id=plat-kb" \
  >"${temp_dir}/annotation.log" 2>&1 &
annotation_pid=$!
wait_http "http://127.0.0.1:${annotation_port}/actuator/health" 120 || { cat "${temp_dir}/annotation.log"; exit 1; }

echo "本地端口 假知识库=${fake_port} 中台=${midplat_port} 标注=${annotation_port}"

echo "---- 1. 四项打开：标注发布中心拉知识库列表 ----"
body="$(curl -sS -m 15 -w '\n%{http_code}' "http://127.0.0.1:${annotation_port}/api/knowledge-bases")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "列表-打开" "200" "${status}"
printf '%s' "${body}" | python3 -c 'import json,sys; names=[i["name"] for i in json.load(sys.stdin)["data"]]; assert "E2E测试知识库" in names, names'

echo "---- 2. 关掉知识库列表：标注拉列表失败 ----"
toggle kb-list false
body="$(curl -sS -m 15 -w '\n%{http_code}' "http://127.0.0.1:${annotation_port}/api/knowledge-bases")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "列表-关闭" "403" "${status}"
printf '%s' "${body}" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert "未开放" in (d.get("detail") or ""), d'

echo "---- 3. 再打开知识库列表：标注拉列表恢复 ----"
toggle kb-list true
body="$(curl -sS -m 15 -w '\n%{http_code}' "http://127.0.0.1:${annotation_port}/api/knowledge-bases")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "列表-恢复" "200" "${status}"

echo "---- 4. 关掉创建：标注新建知识库失败，再打开恢复 ----"
toggle kb-create false
body="$(curl -sS -m 15 -w '\n%{http_code}' -X POST "http://127.0.0.1:${annotation_port}/api/knowledge-bases" -H 'Content-Type: application/json' -d '{"name":"E2E测试新建知识库","description":"local"}')"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "创建-关闭" "403" "${status}"
toggle kb-create true
body="$(curl -sS -m 15 -w '\n%{http_code}' -X POST "http://127.0.0.1:${annotation_port}/api/knowledge-bases" -H 'Content-Type: application/json' -d '{"name":"E2E测试新建知识库","description":"local"}')"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "创建-恢复" "200" "${status}"

echo "---- 5. 关掉上传/文档状态：标注凭证经中台失败，再打开恢复 ----"
toggle kb-upload false
body="$(curl -sS -m 15 -w '\n%{http_code}' -X POST "http://127.0.0.1:${midplat_port}/api/open/plat-kb/kb-upload/9" -H "Authorization: Bearer ${an_token}" -F 'file=@-;filename=e2e.md' <<<"# e2e")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "上传-关闭" "403" "${status}"
toggle kb-upload true
body="$(curl -sS -m 15 -w '\n%{http_code}' -X POST "http://127.0.0.1:${midplat_port}/api/open/plat-kb/kb-upload/9" -H "Authorization: Bearer ${an_token}" -F 'file=@-;filename=e2e.md' <<<"# e2e")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "上传-恢复" "200" "${status}"

toggle kb-doc-get false
body="$(curl -sS -m 15 -w '\n%{http_code}' "http://127.0.0.1:${midplat_port}/api/open/plat-kb/kb-doc-get/101" -H "Authorization: Bearer ${an_token}")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "文档状态-关闭" "403" "${status}"
toggle kb-doc-get true
body="$(curl -sS -m 15 -w '\n%{http_code}' "http://127.0.0.1:${midplat_port}/api/open/plat-kb/kb-doc-get/101" -H "Authorization: Bearer ${an_token}")"
status="${body##*$'\n'}"
body="${body%$'\n'*}"
expect_status "文档状态-恢复" "200" "${status}"

echo "本地流程已跑通：标注发布中心列表/创建，以及上传/文档状态，均随知识库开关关闭失败、打开恢复。"
