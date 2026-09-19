#!/usr/bin/env bash
# p7-A3-emulator-evidence.sh —— A3 释放链路的**机制级**取证（MuMu 模拟器）
# 说明：产品只跑真机，故本轮结论**只作机制验证**，不作产品结论；真机复验等 MIX 2S 回来。
# 链路：加载本地模型 → 抓 Native Heap → 显式广播触发 releaseIfIdle → 再抓 → 再合成（验可恢复）
set -uo pipefail
ADB=[TOOLS-DIR]/android-sdk/platform-tools/adb
PKG=com.kermond.ebook2tts
DEV=[LAN-WIN-HOST]:7555
REPO=[PROJECT-DIR]
LOG=[WORKDIR]/p7-evidence/p7-A3-emulator.log
: > "$LOG"
say(){ echo "[$(date +%T)] $*" | tee -a "$LOG"; }

$ADB connect "$DEV" >/dev/null 2>&1
[ "$($ADB -s "$DEV" get-state 2>/dev/null | tr -d '\r')" = "device" ] || { say "模拟器不在线 ⇒ 终止"; exit 1; }
say "设备: $DEV（MuMu 模拟器；**机制级取证**）"
say "分辨率: $($ADB -s "$DEV" shell wm size | tr -d '\r')"

say "---- ① 装最新 debug 包（含 DebugTriggers 钩子）----"
APK=$REPO/app/build/app/outputs/flutter-apk/app-debug.apk
timeout 900 $ADB -s "$DEV" install -r -t "$APK" >>"$LOG" 2>&1 && say "装机 OK" || { say "装机失败 ⇒ 终止"; exit 1; }
say "模型在位: $($ADB -s "$DEV" shell "ls /data/data/$PKG/files/models/*/.completed 2>/dev/null | head -1" | tr -d '\r' | sed 's|.*/||')"

heap(){ timeout 40 $ADB -s "$DEV" shell "dumpsys meminfo $1 2>/dev/null" | tr -d '\r' \
  | awk '/Native Heap:/{nh=$2} /TOTAL PSS:/{tp=$3} END{printf "NativeHeap=%sKB TOTAL_PSS=%sKB", nh, tp}'; }

say "---- ② 触发一次本地合成（加载模型）----"
$ADB -s "$DEV" shell am force-stop $PKG; sleep 2; timeout 20 $ADB -s "$DEV" logcat -c
W=$($ADB -s "$DEV" shell wm size | tr -d '\r' | grep -oE '[0-9]+x[0-9]+'); Wd=${W%x*}; H=${W#*x}
say "  屏幕 $W ⇒ 底部导航 y≈$(( H - 30 ))，朗读 tab x≈$(( Wd * 583 / 1080 ))"
$ADB -s "$DEV" shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 12
$ADB -s "$DEV" shell input tap $(( Wd * 583 / 1080 )) $(( H - 30 )); sleep 3    # 朗读 tab（按 1080 布局等比）
$ADB -s "$DEV" shell input tap $(( Wd / 2 )) $(( H * 1715 / 2160 )); sleep 2    # 开始朗读（等比）
for i in $(seq 1 40); do sleep 4; timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aq "segments_done" && break; done
sleep 4
P=$($ADB -s "$DEV" shell pidof $PKG:tts_service | tr -d '\r'); say "  引擎 PID=$P"
say "  加载证据: $(timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'SherpaBackend: loaded' | tail -1 | sed 's/.*SherpaBackend: //')"
BEFORE=$(heap "$P"); say "  释放前: $BEFORE"

say "---- ③ 显式广播触发 releaseIfIdle（隐式广播 Android 8+ 收不到）----"
timeout 20 $ADB -s "$DEV" logcat -c
timeout 30 $ADB -s "$DEV" shell "am broadcast -n $PKG/.DebugTriggers -a com.kermond.ebook2tts.DEBUG_RELEASE_IDLE" 2>&1 | tail -1
sleep 6
say "  日志: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aE 'DebugTriggers|RELEASE\|' | tail -3 | tr '\n' '|')"
sleep 4
AFTER=$(heap "$P"); say "  释放后: $AFTER"

say "---- ④ 释放后再合成一次（验证可恢复）----"
timeout 20 $ADB -s "$DEV" logcat -c
$ADB -s "$DEV" shell input tap $(( Wd / 2 )) $(( H * 1715 / 2160 ))
for i in $(seq 1 40); do sleep 4; timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aq "segments_done" && break; done
say "  重新加载: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'SherpaBackend: loaded' | tail -1 | sed 's/.*SherpaBackend: //')"
say "  合成: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'PREVIEW|synth' | tail -1 | sed 's/.*PREVIEW|synth//')"
say "  再合成后: $(heap "$P")"
say "=== A3 机制取证结束（真机结论仍待 MIX 2S 回归）==="
