#!/bin/sh
set -eu

ENV_FILE="/etc/dem/pi.env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi

: "${GCS_HOST:=192.168.1.50}"
: "${GCS_UDP_PORT:=14550}"
: "${LOCAL_UDP_PORT:=14551}"
: "${PX4_UART_DEVICE:=/dev/ttyAMA0}"
: "${PX4_BAUD:=115200}"
: "${DEM_LOG_DIR:=/var/log/dem}"

mkdir -p "$DEM_LOG_DIR"

echo "[dem-mavlink-router] starting uplink=${GCS_HOST}:${GCS_UDP_PORT} local=127.0.0.1:${LOCAL_UDP_PORT} uart=${PX4_UART_DEVICE}:${PX4_BAUD}"

exec /usr/bin/mavlink-routerd \
  -e "${GCS_HOST}:${GCS_UDP_PORT}" \
  -e "127.0.0.1:${LOCAL_UDP_PORT}" \
  "${PX4_UART_DEVICE}:${PX4_BAUD}"
