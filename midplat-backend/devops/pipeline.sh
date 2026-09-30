#!/bin/bash
set -eo pipefail

if [ -f /etc/profile ]; then
  # Jenkins 非交互 Shell 需要显式加载构建机环境。
  source /etc/profile
fi
set -u

script_dir="$(cd "$(dirname "$0")" && pwd)"
registry="${REGISTRY:-reg.malacca.tech:8000}"

if [ -n "${REGISTRY_USERNAME:-}" ] || [ -n "${REGISTRY_PASSWORD:-}" ]; then
  if [ -z "${REGISTRY_USERNAME:-}" ] || [ -z "${REGISTRY_PASSWORD:-}" ]; then
    echo "REGISTRY_USERNAME 与 REGISTRY_PASSWORD 必须同时配置" >&2
    exit 1
  fi
  printf '%s' "$REGISTRY_PASSWORD" | docker login "$registry" \
    --username "$REGISTRY_USERNAME" --password-stdin
fi

exec "$script_dir/build.sh"
