#!/bin/sh
set -eu

ENV_FILE="/etc/dem/pi.env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi

: "${CAM_WIDTH:=1280}"
: "${CAM_HEIGHT:=720}"
: "${CAM_FPS:=30}"
: "${VIDEO_HOST:=192.168.1.50}"
: "${VIDEO_PORT:=5600}"
: "${DEM_LOG_DIR:=/var/log/dem}"

mkdir -p "$DEM_LOG_DIR"

echo "[dem-camera-stream] starting stream=${VIDEO_HOST}:${VIDEO_PORT} ${CAM_WIDTH}x${CAM_HEIGHT}@${CAM_FPS}fps"

exec /bin/sh -c '
/usr/bin/libcamera-vid -t 0 --inline \
  --width "${CAM_WIDTH}" --height "${CAM_HEIGHT}" \
  --framerate "${CAM_FPS}" --codec h264 -o - | \
/usr/bin/gst-launch-1.0 -q fdsrc ! h264parse ! rtph264pay config-interval=1 pt=96 ! \
udpsink host="${VIDEO_HOST}" port="${VIDEO_PORT}" sync=false
'
