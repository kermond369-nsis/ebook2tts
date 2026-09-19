#!/usr/bin/env bash
# p7-A3-release-evidence.sh —— A3 内存释放取证（修正版）
#  ① 先切到本地模式（让 native 后端真正加载）② 合成一次 ③ 显式广播触发 releaseIfIdle
#  ④ 前后 Native Heap 对比 ⑤ 释放后再合成一次（验证重新预热）
# 产出：[WORKDIR]/p7-evidence/p7-A3-release.log
set -uo pipefail
ADB=[TOOLS-DIR]/android-sdk/platform-tools/adb
PKG=com.kermond.ebook2tts
LOG=[WORKDIR]/p7-evidence/p7-A3-release.log
: > "$LOG"
say(){ echo "[$(date +%T)] $*" | tee -a "$LOG"; }

DEV="${P7_DEV:-}"
for d in $($ADB devices | awk '$2=="device"{print $1}'); do
  case "$d" in *:7555) continue;; esac
  [ "$($ADB -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r')" = "[DEVICE-SERIAL]" ] && { DEV="$d"; break; }
done
[ -z "$DEV" ] && { say "MIX 2S 不在线 ⇒ 终止"; exit 1; }
say "设备: $DEV"

# ── 安全前置（2026-09-19 事故后加）：任何点击前先确认前台是我们的 App ──
ensure_fg(){ # $1=期望包名
  for i in 1 2 3; do
    FG=$($ADB -s "$DEV" shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r')
    case "$FG" in *"$1"*) return 0;; esac
    say "  ⚠️ 前台不是 $1 ⇒ 拉起 App 后再试（当前: $FG）"
    $ADB -s "$DEV" shell am start -n "$1/.MainActivity" >/dev/null 2>&1; sleep 6
  done
  say "  ✗ 前台校验失败 ⇒ 中止（拒绝盲点，防止误触系统页面）"; exit 4
}


heap(){ local p="$1"; timeout 30 $ADB -s "$DEV" shell "dumpsys meminfo $p 2>/dev/null" | tr -d '\r' \
  | awk '/Native Heap:/{nh=$2} /TOTAL PSS:/{tp=$3} END{printf "NativeHeap=%sKB TOTAL_PSS=%sKB", nh, tp}'; }

say "---- ① 切到本地模式（设置 → 更改 → 本地模式 → 谁优先 → 完成）----"
ensure_fg "$PKG"   # 点击前校验前台
$ADB -s "$DEV" shell input tap 810 2130; sleep 3      # 设置 tab
$ADB -s "$DEV" shell input tap 900 565;  sleep 3      # 更改
$ADB -s "$DEV" shell input tap 540 830;  sleep 2      # 本地模式（该机低配 ⇒ 会弹警告）
$ADB -s "$DEV" shell input tap 702 1458; sleep 2      # 仍选本地
$ADB -s "$DEV" shell input tap 900 2030; sleep 2      # 下一步
$ADB -s "$DEV" shell input tap 540 820;  sleep 2      # 谁优先
$ADB -s "$DEV" shell input tap 930 2035; sleep 3      # 完成
say "  当前模式: $(timeout 20 $ADB -s "$DEV" shell "run-as $PKG sh -c 'cat files/mmkv/ebook2tts_cfg 2>/dev/null | strings | grep -oE \"only_(online|local)|prefer_(online|local)\" | tail -1'" 2>/dev/null | tr -d '\r')"

say "---- ② 触发本地合成（加载 native 后端）----"
timeout 15 $ADB -s "$DEV" logcat -c
$ADB -s "$DEV" shell input tap 630 2130; sleep 3      # 朗读 tab
$ADB -s "$DEV" shell input tap 540 1715               # 开始朗读
for i in $(seq 1 40); do sleep 5; timeout 15 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aq "segments_done" && break; done
sleep 5
P=$(timeout 15 $ADB -s "$DEV" shell pidof $PKG:tts_service | tr -d '\r'); say "  引擎 PID=$P"
say "  加载证据: $(timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'SherpaBackend: loaded' | tail -1 | sed 's/.*SherpaBackend: //')"
say "  释放前 PSS: $(heap "$P")"

say "---- ③ 显式广播触发 releaseIfIdle（隐式广播在 Android 8+ 收不到，这是上轮没打点的原因）----"
timeout 15 $ADB -s "$DEV" logcat -c
timeout 20 $ADB -s "$DEV" shell "am broadcast -n $PKG/.DebugTriggers -a com.kermond.ebook2tts.DEBUG_RELEASE_IDLE" 2>&1 | tail -1
sleep 5
say "  日志: $(timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aE 'DebugTriggers|RELEASE\|' | tail -3 | tr '\n' '|')"
sleep 3
say "  释放后 PSS: $(heap "$P")"

say "---- ④ 释放后再合成一次（验证重新预热可用）----"
timeout 15 $ADB -s "$DEV" logcat -c
$ADB -s "$DEV" shell input tap 540 1715
for i in $(seq 1 40); do sleep 5; timeout 15 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aq "segments_done" && break; done
say "  重新加载: $(timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'SherpaBackend: loaded' | tail -1 | sed 's/.*SherpaBackend: //')"
say "  合成耗时: $(timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'PREVIEW|synth' | tail -1 | sed 's/.*PREVIEW|synth//')"
say "  再合成后 PSS: $(heap "$P")"
say "=== A3 取证结束 ==="
