#!/usr/bin/env bash
# P7-A 性能采集协议（可复现；模拟器相对值）
# 设备：MuMu x86_64 / Android 15（[LAN-WIN-HOST]:7555）
# 说明：所有产出落 [WORKDIR]/p7-evidence/perf/<时间戳>/，命令原文留档以便复算。
set -u
ADB=[TOOLS-DIR]/android-sdk/platform-tools/adb
DEV=[LAN-WIN-HOST]:7555
PKG=com.kermond.ebook2tts
OUT="[WORKDIR]/p7-evidence/perf/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

echo "## P7-A 采集 $(date -Is) device=$DEV" | tee "$OUT/protocol.txt"

# 0) 连接与设备信息
$ADB connect $DEV >/dev/null 2>&1
$ADB -s $DEV shell getprop ro.product.cpu.abi            | tee -a "$OUT/protocol.txt"
$ADB -s $DEV shell getprop ro.build.version.release      | tee -a "$OUT/protocol.txt"
$ADB -s $DEV shell dumpsys thermalservice 2>/dev/null | head -5 > "$OUT/thermal.txt"

# 1) 冷启动 ×5（Flutter UI 冷启；非引擎就绪）
echo "== cold start (am start -W) x5 ==" | tee -a "$OUT/protocol.txt"
for i in 1 2 3 4 5; do
  $ADB -s $DEV shell am force-stop $PKG; sleep 2
  $ADB -s $DEV shell am start -W -n $PKG/.MainActivity 2>/dev/null | grep -E "TotalTime|WaitTime" \
    | tr '\n' ' ' >> "$OUT/coldstart.txt"; echo "" >> "$OUT/coldstart.txt"
done

# 2) 内存基线（主进程 + 引擎进程）
$ADB -s $DEV shell dumpsys meminfo $PKG > "$OUT/meminfo-main.txt" 2>/dev/null
$ADB -s $DEV shell ps -A | grep -i $PKG > "$OUT/ps.txt" 2>/dev/null

# 3) 合成基线（本地强制路径：音色库试听，IM-518）
#    进入「音色」页（底部第 3 个 tab）→ 试听首项
$ADB -s $DEV logcat -c
$ADB -s $DEV shell input tap 450 1850; sleep 2      # 音色 tab
$ADB -s $DEV exec-out screencap -p > "$OUT/page-voices.png"
$ADB -s $DEV shell input tap 900 700; sleep 1        # 试听按钮（视页面布局，可调）
sleep 12
$ADB -s $DEV logcat -d -v time > "$OUT/logcat-voices.txt" 2>/dev/null
grep -E "PREVIEW|PERF|ONLINE|SynthCoord|Sherpa" "$OUT/logcat-voices.txt" > "$OUT/synth-markers.txt" || true

echo "OUT=$OUT"
