#!/bin/sh
set -eu

ENV_FILE="/etc/dem/pi.env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi

: "${HEALTHCHECK_ENABLE:=1}"
: "${HEALTHCHECK_EXPECT_MAVLINK_ROUTER:=1}"
: "${HEALTHCHECK_EXPECT_CAMERA_STREAM:=1}"
: "${HEALTHCHECK_EXPECT_VIDEO_PORT:=5600}"
: "${PHONE_LINK_ENABLE:=0}"
: "${PHONE_LINK_REQUIRED:=0}"
: "${PHONE_LINK_INTERFACE:=wlan0}"
: "${PHONE_LINK_GATEWAY:=192.168.137.1}"
: "${PHONE_LINK_PING_GATEWAY:=1}"

if [ "$HEALTHCHECK_ENABLE" != "1" ]; then
  exit 0
fi

status=0

if [ "$HEALTHCHECK_EXPECT_MAVLINK_ROUTER" = "1" ]; then
  if ! systemctl --quiet is-active mavlink-router.service; then
    echo "[dem-healthcheck] mavlink-router.service is not active"
    status=1
  fi
fi

if [ "$HEALTHCHECK_EXPECT_CAMERA_STREAM" = "1" ]; then
  if ! systemctl --quiet is-active camera-stream.service; then
    echo "[dem-healthcheck] camera-stream.service is not active"
    status=1
  fi

  if ! ss -u -l -n | grep -q ":${HEALTHCHECK_EXPECT_VIDEO_PORT}[[:space:]]"; then
    echo "[dem-healthcheck] UDP video port ${HEALTHCHECK_EXPECT_VIDEO_PORT} is not listening"
    status=1
  fi
fi

if [ "$PHONE_LINK_ENABLE" = "1" ]; then
  if ! ip route show default | grep -q "dev ${PHONE_LINK_INTERFACE}"; then
    echo "[dem-healthcheck] default route is not on ${PHONE_LINK_INTERFACE}"
    if [ "$PHONE_LINK_REQUIRED" = "1" ]; then
      status=1
    fi
  fi

  if [ "$PHONE_LINK_PING_GATEWAY" = "1" ]; then
    if ! ping -c 1 -W 1 "$PHONE_LINK_GATEWAY" >/dev/null 2>&1; then
      echo "[dem-healthcheck] cannot reach phone gateway ${PHONE_LINK_GATEWAY}"
      if [ "$PHONE_LINK_REQUIRED" = "1" ]; then
        status=1
      fi
    fi
  fi
fi

if [ "$status" -eq 0 ]; then
  echo "[dem-healthcheck] ok"
fi

exit "$status"
