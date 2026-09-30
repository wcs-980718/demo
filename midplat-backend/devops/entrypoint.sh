#!/bin/sh
set -eu

case "${MIDPLAT_COMPONENT:-midplat}" in
  midplat)
    exec java ${JVM_OPTS:-} ${JAVA_OPTS:-} -jar /app/app.jar "$@"
    ;;
  agent-runtime)
    export FUSION_RUNTIME_JAR="${FUSION_RUNTIME_JAR:-/app/app.jar}"
    # Restore retained binaries when a new volume is used during an environment migration.
    artifact_dir="${FUSION_RUNTIME_DIRECTORY:-/data/runtime}/_artifacts"
    seed_dir="${FUSION_RUNTIME_SEED_DIRECTORY:-/app/fusion-runtime-history}"
    mkdir -p "$artifact_dir"
    chmod 700 "$artifact_dir"
    for source in "$seed_dir"/*.jar; do
      [ -f "$source" ] || continue
      name="$(basename "$source")"
      digest="${name%.jar}"
      [ "${#digest}" -eq 64 ] || { echo "Invalid retained runtime filename" >&2; exit 1; }
      case "$digest" in *[!a-f0-9]*) echo "Invalid retained runtime digest" >&2; exit 1;; esac
      [ "$(sha256sum "$source" | cut -d ' ' -f 1)" = "$digest" ] || { echo "Retained runtime checksum mismatch" >&2; exit 1; }
      target="$artifact_dir/$name"
      if [ ! -e "$target" ]; then
        staging="$(mktemp "$artifact_dir/.seed-XXXXXX")"
        cp "$source" "$staging"
        chmod 400 "$staging"
        mv "$staging" "$target"
      fi
      [ "$(sha256sum "$target" | cut -d ' ' -f 1)" = "$digest" ] || { echo "Existing runtime checksum mismatch" >&2; exit 1; }
    done
    exec java ${JVM_OPTS:-} ${FUSION_JAVA_OPTS:-} \
      -Dloader.main=com.agenthubfusion.FusionApplication \
      -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$@"
    ;;
  *)
    echo "Unknown MIDPLAT_COMPONENT" >&2
    exit 1
    ;;
esac
