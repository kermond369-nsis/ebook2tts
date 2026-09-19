#!/usr/bin/env bash
# p7-A3-rewarm.sh —— 释放后重新预热验证（换新文本 ⇒ 必缓存未命中 ⇒ 必须重新加载模型）
set -uo pipefail
ADB=[TOOLS-DIR]/android-sdk/platform-tools/adb
DEV="${P7_DEV:-[LAN-TEST-DEVICE]:38483}"; PKG=com.kermond.ebook2tts
LOG=[WORKDIR]/p7-evidence/p7-A3-rewarm.log; : > "$LOG"
say(){ echo "[$(date +%T)] $*" | tee -a "$LOG"; }
tot(){ timeout 40 $ADB -s "$DEV" shell "dumpsys meminfo $1 2>/dev/null" | tr -d '\r' | awk '/TOTAL PSS:/{print $3" KB"}'; }
fg(){ $ADB -s "$DEV" shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" | tr -d '\r'; }

say "=== 重新预热验证 ==="
case "$(fg)" in *"$PKG"*) say "  前台已是目标应用";; *) say "  拉起应用…"; $ADB -s "$DEV" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 10;; esac
P=$(timeout 15 $ADB -s "$DEV" shell pidof $PKG:tts_service | tr -d '\r'); say "引擎 PID=$P  当前 PSS=$(tot "$P")"

say "---- ① 到朗读页并把文本改成新内容（追加 ASCII 后缀 ⇒ 缓存必未命中）----"
$ADB -s "$DEV" shell input tap 630 2130; sleep 3        # 朗读 tab
$ADB -s "$DEV" shell input tap 540 640;  sleep 2        # 文本框
$ADB -s "$DEV" shell input keyevent KEYCODE_MOVE_END; sleep 1
$ADB -s "$DEV" shell input text "-rewarm"; sleep 2
say "  文本已追加后缀"

say "---- ② 触发合成（须重新加载模型）----"
timeout 20 $ADB -s "$DEV" logcat -c
$ADB -s "$DEV" shell input tap 540 1715
for i in $(seq 1 60); do sleep 4; timeout 20 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aq "segments_done" && break; done
say "  加载: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'SherpaBackend: loaded' | tail -1 | sed 's/.*SherpaBackend: //')"
say "  缓存: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'CACHE|' | tail -1 | sed 's/.*SherpaBackend: //')"
say "  合成: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -a 'PREVIEW|synth' | tail -1 | sed 's/.*PREVIEW|synth//')"
say "  再合成后 PSS=$(tot "$P")"
say "=== 结束 ==="
