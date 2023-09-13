#!/usr/bin/env bash
set -Eeuo pipefail

HOST_PORT=$1

CONFIG=$(cat $2)
CONFIG_DIR=$(mktemp -d)
CONFIG_FILE="$CONFIG_DIR/envoy.yaml"

echo "${CONFIG}" | sed -e "s/HOST_PORT/${HOST_PORT}/g" > "${CONFIG_FILE}"
cat "${CONFIG_FILE}"

shift 2
/usr/local/bin/envoy --drain-time-s 1 -c "${CONFIG_FILE}" "$@"

rm -rf "${CONFIG_DIR}"
