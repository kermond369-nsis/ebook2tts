#!/usr/bin/env bash
# adb-guard.sh —— 真机（Mi MIX 2S / [DEVICE-SERIAL]）连接自愈
#
# 背景（用户 2026-09-16 告知）：本服务器主板 USB 端口**长时间插着会抖动**，
# 表现为设备从 `adb devices` 消失、dmesg 出现 `error -71` / `attempt power cycle` /
# `unable to enumerate USB device`。物理拔插可解，但白天 12 小时用户不在机器旁。
# 已知有效的软件替代：① `adb kill-server && adb start-server`（清陈旧状态）；
# ② 通过 sysfs unbind/bind 复位端口（**等效于拔插**，需要 root）。
#
# 用法：
#   adb-guard.sh                 # 确保默认真机可用（必要时自愈）
#   adb-guard.sh <serial|host:port> [usbdev]   # 指定目标；usbdev 形如 1-1
# 退出码：0 = 可用；1 = 自愈失败（需人工拔插）
set -uo pipefail

TARGET="${1:-[DEVICE-SERIAL]}"
USBDEV="${2:-1-1}"
ADB="${ADB:-[TOOLS-DIR]/android-sdk/platform-tools/adb}"
RETRIES=3

log() { echo "[adb-guard $(date +%T)] $*"; }

dev_ok() {
  $ADB devices 2>/dev/null | awk -v t="$TARGET" '$1==t && $2=="device" {found=1} END{exit !found}'
}

usb_reset() {
  local drv=/sys/bus/usb/drivers/usb
  if [ ! -w "$drv/unbind" ]; then log "sysfs 不可写（非 root？）⇒ 跳过端口复位"; return 1; fi
  log "sysfs 端口复位 $USBDEV（等效拔插）"
  if [ -e "/sys/bus/usb/devices/$USBDEV" ]; then
    echo -n "$USBDEV" > "$drv/unbind" 2>/dev/null
    sleep 3
  fi
  echo -n "$USBDEV" > "$drv/bind" 2>/dev/null || echo -n "$USBDEV" > "$drv/bind" 2>/dev/null
  sleep 4
}

if dev_ok; then log "OK：$TARGET 已连接"; $ADB devices -l | grep -F "$TARGET"; exit 0; fi

log "未发现 $TARGET ⇒ 开始自愈"
for i in $(seq 1 $RETRIES); do
  log "第 $i/$RETRIES 轮：重启 adb 服务"
  $ADB kill-server  >/dev/null 2>&1; sleep 2
  $ADB start-server >/dev/null 2>&1; sleep 3
  if dev_ok; then log "OK（adb 服务重启后恢复）"; $ADB devices -l | grep -F "$TARGET"; exit 0; fi

  usb_reset || true
  $ADB kill-server >/dev/null 2>&1; sleep 2
  $ADB start-server >/dev/null 2>&1; sleep 4
  if dev_ok; then log "OK（端口复位后恢复）"; $ADB devices -l | grep -F "$TARGET"; exit 0; fi
done

log "失败：$RETRIES 轮自愈未恢复 —— 需要物理拔插（请告知用户）"
dmesg 2>/dev/null | grep -iE "usb 1-1|usb1-port1" | tail -5
exit 1
