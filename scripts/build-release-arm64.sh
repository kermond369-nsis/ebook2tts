#!/usr/bin/env bash
# build-release-arm64.sh —— 构建**正式包**（arm64-v8a 单 ABI）
#
# 背景：`ndk.abiFilters` 无法剔除经本地 AAR 注入的预编译库（与 armeabi-v7a 同类问题），
# 故正式包在构建后做**后处理**：剔除 lib/x86_64/** → zipalign → 用发布密钥重新签名。
# 本脚本为**唯一入口**：本地与 CI 使用同一套流程（避免"两台机器两个产物"）。
#
# 前置：app/android/key.properties 存在（本地手工维护；CI 由 Secrets 生成）。
# 用法：scripts/build-release-arm64.sh [输出路径]
set -euo pipefail
cd "$(dirname "$0")/.."
APP=app
PROPS="$APP/android/key.properties"
[ -f "$PROPS" ] || { echo "缺少 $PROPS：正式包必须签名（此为有意设计，禁用无凭据构建）" >&2; exit 2; }

echo "::group::flutter build apk --release (arm64)"
( cd "$APP" && flutter pub get >/dev/null && flutter build apk --release --target-platform android-arm64 )
echo "::endgroup::"

SRC="$APP/build/app/outputs/flutter-apk/app-release.apk"
# 输出路径统一解析到 app/ 下：允许传「相对 app/ 的路径」或绝对路径
# （CI 传 build/app/outputs/...；本地可省略，默认同目录）
OUT="${1:-build/app/outputs/flutter-apk/app-release-arm64.apk}"
if [ "${OUT#/}" = "$OUT" ]; then
  [ "${OUT#"$APP"/}" = "$OUT" ] && OUT="$APP/$OUT"
fi
test -f "$SRC"

python3 - "$SRC" "$OUT" "$PROPS" <<'PY'
import os, re, subprocess, sys, zipfile
src, out, props = sys.argv[1], sys.argv[2], sys.argv[3]
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
bt = os.path.join(sdk, "build-tools")
vers = sorted([d for d in os.listdir(bt)], key=lambda s: [int(x) for x in re.findall(r"\d+", s)]) if os.path.isdir(bt) else []
assert vers, "未找到 build-tools（需要 zipalign / apksigner）"
btd = os.path.join(bt, vers[-1])

zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
n = 0
for it in zin.infolist():
    if it.filename.startswith("lib/x86_64/"):
        n += 1
        continue
    zout.writestr(it, zin.read(it.filename))
zout.close(); zin.close()
print(f"[1/3] 剔除 x86_64 运行库条目：{n} 个")

aligned = out + ".aligned"
r = subprocess.run([f"{btd}/zipalign", "-f", "-p", "4", out, aligned], capture_output=True, text=True)
assert r.returncode == 0, r.stderr
print("[2/3] zipalign OK")

kp = {}
for line in open(props, encoding="utf-8"):
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1); kp[k.strip()] = v.strip()
ks = kp["storeFile"]
if not os.path.isabs(ks):
    ks = os.path.join(os.path.dirname(props), ks)
r = subprocess.run([f"{btd}/apksigner", "sign", "--ks", ks, "--ks-key-alias", kp["keyAlias"],
                    "--ks-pass", f"pass:{kp['storePassword']}", "--key-pass", f"pass:{kp['keyPassword']}",
                    "--out", out, aligned], capture_output=True, text=True)
assert r.returncode == 0, (r.stderr or r.stdout)
os.remove(aligned)
print("[3/3] apksigner 重签 OK（口令不回显）")

v = subprocess.run([f"{btd}/apksigner", "verify", "--print-certs", out], capture_output=True, text=True)
assert v.returncode == 0, "验签失败"
b = subprocess.run([f"{btd}/aapt2", "dump", "badging", out], capture_output=True, text=True).stdout
nat = [l for l in b.splitlines() if l.startswith("native-code")]
pkg = [l for l in b.splitlines() if l.startswith("package:")]
print("[核验]", pkg[0][:80] if pkg else "?")
print("[核验]", nat[0].strip() if nat else "native-code 缺失")
assert nat and "x86_64" not in nat[0], "ABI 收敛失败：仍含 x86_64"
print(f"[完成] {out}  {os.path.getsize(out)} bytes（剔除前 {os.path.getsize(src)}）")
PY
