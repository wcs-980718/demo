#!/bin/bash
set -e

npm_registry="${NPM_REGISTRY:-https://registry.npmmirror.com}"
deploy_base="${DEPLOY_BASE:-/midplat-frontend/}"

cd "$(dirname "$0")/.."

rm -rf node_modules
npm ci --registry "$npm_registry"

DEPLOY_BASE="$deploy_base" npm run build

rm -rf ./devops/dist
cp -r ./dist ./devops/dist

cd ./devops
rm -rf ./*.tar
rm -rf core.*

appname="midplat-frontend"
tag=${tag:-$(date "+%Y%m%d%H%M")}
registry="${REGISTRY:-reg.malacca.tech:8000}"
image="${registry}/${appname}"

docker build -t "${image}:${tag}" -t "${image}:latest" .
docker push "${image}:${tag}"
docker push "${image}:latest"
docker save -o ./${appname}-${tag}.tar "${image}:${tag}"
docker rmi -f "${image}:${tag}" "${image}:latest"
