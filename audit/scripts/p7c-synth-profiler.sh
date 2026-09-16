#!/usr/bin/env bash
# p7c-synth-profiler.sh —— 合成期间采样：CPU 频率 / 温度 / 预览线程占用
# 用法：p7c-synth-profiler.sh <serial> [采样轮数] [间隔秒]
set -uo pipefail
ADB="${ADB:-[TOOLS-DIR]/android-sdk/platform-tools/adb}"
S="${1:-[DEVICE-SERIAL]}"; ROUNDS="${2:-12}"; IV="${3:-3}"
D="-s $S"
OUT=[WORKDIR]/p7-evidence/realdevice/synth-profile.txt
: > "$OUT"

PID=$($ADB $D shell pidof com.kermond.ebook2tts:tts_service | tr -d '\r')
[ -z "$PID" ] && { echo "引擎进程不在"; exit 1; }
echo "pid=$PID  采样 ${ROUNDS} 轮 x ${IV}s" | tee -a "$OUT"

# 记录预览线程 tid 与初始 CPU 时间
TID=$($ADB $D shell "for t in /proc/$PID/task/*; do [ \"\$(cat \$t/comm)\" = preview-player ] && basename \$t; done" | tr -d '\r' | head -1)
echo "preview-player tid=$TID" | tee -a "$OUT"
prev=""; cpu="?"
for i in $(seq 1 "$ROUNDS"); do
  line="$($ADB $D shell "
    freqs=\$(for c in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do cat \$c 2>/dev/null; done | tr '\n' ',')
    temp=\$(for t in /sys/class/thermal/thermal_zone*/temp; do cat \$t 2>/dev/null; done | sort -rn | head -1)
    st=\$(cat /proc/$TID/stat 2>/dev/null | awk '{print \$14+\$15}')
    load=\$(cat /proc/loadavg | cut -d' ' -f1-3)
    echo \"\$freqs|\$temp|\$st|\$load\"
  " 2>/dev/null | tr -d '\r')"
  f=$(echo "$line" | cut -d'|' -f1); t=$(echo "$line" | cut -d'|' -f2); st=$(echo "$line" | cut -d'|' -f3); ld=$(echo "$line" | cut -d'|' -f4)
  if [ -n "$prev" ] && [ -n "$st" ]; then
    cpu=$(python3 -c "import sys;a,b,iv='$st','$prev','$IV'
try:
    a=int(a); b=int(b)
    print(f'{(a-b)/100/iv*100:.0f}%' if a>=b else '?')
except Exception: print('?')")
  fi
  prev="$st"
  printf "t+%02ds  预览线程CPU=%s  最高温=%s°C  load=%s\n     频率(kHz)=%s\n" "$((i*IV))" "$cpu" "$t" "$ld" "$f" | tee -a "$OUT"
  [ "$i" = "1" ] && sleep 1
  sleep "$IV"
done
echo "---- 采样结束 ----" | tee -a "$OUT"
