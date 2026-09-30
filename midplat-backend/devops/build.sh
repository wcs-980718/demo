#!/bin/bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$script_dir/.." && pwd)"
registry="${REGISTRY:-reg.malacca.tech:8000}"
app_name="midplat-backend"
image_tag="${tag:-${TAG:-$(date '+%Y%m%d%H%M')}}"
image="$registry/$app_name"

cd "$project_dir"
if [ "${SKIP_MAVEN_BUILD:-0}" != "1" ]; then
  mvn clean package -s settings.xml -DskipTests
fi

cp "$project_dir/target/midplat-backend.jar" "$script_dir/app.jar"
rm -f "$script_dir"/*.tar

cd "$script_dir"
docker build -t "$image:$image_tag" -t "$image:latest" .
docker push "$image:$image_tag"
docker push "$image:latest"
docker save -o "$app_name-$image_tag.tar" "$image:$image_tag"
docker rmi -f "$image:$image_tag" "$image:latest"

echo "已发布镜像：$image:$image_tag"
