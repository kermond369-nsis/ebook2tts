#!/usr/bin/env bash
# P7-A 压测：2 次/秒 持续合成（近似 NFR §5.2 / TC-07；本地强制路径=音色库试听，不消耗在线额度）
# 与 NFR 原定义的差异（如实登记）：NFR 要求"2 次/秒热更配置 + 持续合成 10 分钟"；
# 本脚本只做"2 次/秒 连续起停合成"，**未做配置热更**（配置热更需可编程驱动，待工具落地）。
set -u
ADB=[TOOLS-DIR]/android-sdk/platform-tools/adb
DEV=[LAN-WIN-HOST]:7555
PKG=com.kermond.ebook2tts
DUR=${1:-300}     # 持续秒数（默认 300 = 5 分钟）
OUT="[WORKDIR]/p7-evidence/perf/stress-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
echo "start $(date -Is) dur=${DUR}s" | tee "$OUT/meta.txt"

$ADB -s $DEV logcat -c
$ADB -s $DEV shell dumpsys meminfo $PKG > "$OUT/meminfo-before.txt" 2>/dev/null

END=$(( $(date +%s) + DUR ))
i=0
while [ "$(date +%s)" -lt "$END" ]; do
  # 2 次/秒：每 500ms 点一次「试听」（音色库第 1 张卡片）
  $ADB -s $DEV shell input tap 300 1070 >/dev/null 2>&1
  i=$((i+1))
  sleep 0.5
  if [ $((i % 120)) -eq 0 ]; then      # 每 60 秒采一次内存
    echo "--- t=+$((i/2))s ---" >> "$OUT/meminfo-series.txt"
    $ADB -s $DEV shell dumpsys meminfo $PKG 2>/dev/null | grep -E "TOTAL PSS|TOTAL RSS" >> "$OUT/meminfo-series.txt"
    $ADB -s $DEV shell dumpsys meminfo "$PKG:tts_service" 2>/dev/null | grep -E "TOTAL PSS" >> "$OUT/meminfo-series.txt"
  fi
done

echo "taps=$i end $(date -Is)" | tee -a "$OUT/meta.txt"
$ADB -s $DEV logcat -d -v time > "$OUT/logcat-stress.txt" 2>/dev/null
$ADB -s $DEV shell dumpsys meminfo $PKG > "$OUT/meminfo-after.txt" 2>/dev/null
$ADB -s $DEV shell ps -A | grep -i $PKG > "$OUT/ps-after.txt"
echo "FATAL=$(grep -c 'FATAL EXCEPTION' "$OUT/logcat-stress.txt")" | tee -a "$OUT/meta.txt"
echo "ANR=$(grep -ciE 'ANR in|Application Not Responding' "$OUT/logcat-stress.txt")" | tee -a "$OUT/meta.txt"
echo "OUT=$OUT"
