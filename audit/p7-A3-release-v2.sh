#!/usr/bin/env bash
# p7-A3-release-v2.sh —— 只做「释放」取证：等预览彻底空闲 → 显式广播 → Native Heap 前后
set -uo pipefail
ADB=[TOOLS-DIR]/android-sdk/platform-tools/adb
DEV="${P7_DEV:-[LAN-TEST-DEVICE]:38483}"; PKG=com.kermond.ebook2tts
LOG=[WORKDIR]/p7-evidence/p7-A3-release-v2.log; : > "$LOG"
say(){ echo "[$(date +%T)] $*" | tee -a "$LOG"; }
pid(){ $ADB -s "$DEV" shell pidof $PKG:tts_service 2>/dev/null | tr -d '\r'; }
tot(){ timeout 40 $ADB -s "$DEV" shell "dumpsys meminfo $1 2>/dev/null" | tr -d '\r' | awk '/TOTAL PSS:/{print $3" KB"}'; }
nat(){ timeout 40 $ADB -s "$DEV" shell "dumpsys meminfo $1 2>/dev/null" | tr -d '\r' | awk '/Native Heap:/{print $2" KB"}'; }

P=$(pid); say "引擎 PID=$P"
say "---- ① 等预览彻底空闲（连续 20s 无 playing/synth 事件）----"
idle=0
for i in $(seq 1 40); do
  R=$($ADB -s "$DEV" logcat -d -t 120 2>/dev/null | grep -aE "PREVIEW\|(playing|synth|segments_done|write)" | tail -1)
  age=$(echo "$R" | grep -oE "^[0-9-]+ [0-9:.]+" | head -1)
  if [ -z "$R" ]; then sleep 5; continue; fi
  if [ -n "$age" ]; then
    t_ev=$(date -d "$(echo "$age" | cut -d' ' -f2 | tr -d '\r')" +%s 2>/dev/null || echo 0)
    now=$(date +%s); d=$(( now - t_ev ))
    if [ "$d" -ge 15 ] && ! echo "$R" | grep -q "playing=true"; then
      say "  ✓ 已空闲（最后事件 ${d}s 前: $(echo "$R" | cut -c1-60)）"; idle=1; break
    fi
  fi
  sleep 5
done
[ "$idle" = "0" ] && say "  ⚠️ 未确认空闲，仍继续（结果按实际判断）"

say "---- ② 释放前 ----"
say "  NativeHeap=$(nat "$P")  TOTAL_PSS=$(tot "$P")"
say "---- ③ 显式广播触发 ----"
timeout 20 $ADB -s "$DEV" logcat -c
timeout 20 $ADB -s "$DEV" shell "am broadcast -n $PKG/.DebugTriggers -a com.kermond.ebook2tts.DEBUG_RELEASE_IDLE" 2>&1 | tail -1
sleep 6
say "  日志: $(timeout 25 $ADB -s "$DEV" logcat -d 2>/dev/null | grep -aE 'DebugTriggers|RELEASE\|' | tail -3 | tr '\n' '|')"
sleep 4
say "---- ④ 释放后 ----"
say "  NativeHeap=$(nat "$P")  TOTAL_PSS=$(tot "$P")"
say "=== 结束 ==="
