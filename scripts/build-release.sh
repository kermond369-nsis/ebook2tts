#!/usr/bin/env bash
# build-release.sh —— 正式发布包统一构建与验签脚本（仅产出 Arm64 或 Universal，严禁产出 debug）
#
# 用法：
#   scripts/build-release.sh arm64     [输出路径]
#   scripts/build-release.sh universal [输出路径]
#
# 前置：app/android/key.properties 存在（本地手工维护；CI 仅在打 tag / 注入 Secrets 时生成）。
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-}"
case "$MODE" in
  arm64|universal) ;;
  *)
    echo "用法: $0 <arm64|universal> [输出路径]" >&2
    exit 2
    ;;
esac

APP=app
PROPS="$APP/android/key.properties"
[ -f "$PROPS" ] || { echo "缺少 $PROPS：正式包必须签名（此为有意设计，禁用无凭据构建）" >&2; exit 2; }

if [ "$MODE" = "arm64" ]; then
  TARGET_PLATFORMS="android-arm64"
  DEFAULT_OUT="build/app/outputs/flutter-apk/app-release-arm64.apk"
else
  TARGET_PLATFORMS="android-arm64,android-arm,android-x64"
  DEFAULT_OUT="build/app/outputs/flutter-apk/app-release-universal.apk"
fi

OUT="${2:-$DEFAULT_OUT}"
if [ "${OUT#/}" = "$OUT" ]; then
  [ "${OUT#"$APP"/}" = "$OUT" ] && OUT="$APP/$OUT"
fi

echo "::group::flutter build apk --release ($MODE: $TARGET_PLATFORMS)"
(
  cd "$APP"
  flutter pub get >/dev/null
  EBOOK2TTS_ABI_MODE="$MODE" flutter build apk --release \
    --target-platform "$TARGET_PLATFORMS"
)
echo "::endgroup::"

SRC="$APP/build/app/outputs/flutter-apk/app-release.apk"
test -f "$SRC"

python3 - "$MODE" "$SRC" "$OUT" "$PROPS" <<'PY'
import os, re, shutil, subprocess, sys, zipfile

mode, src, out, props = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
bt = os.path.join(sdk, "build-tools")
vers = sorted([d for d in os.listdir(bt)], key=lambda s: [int(x) for x in re.findall(r"\d+", s)]) if os.path.isdir(bt) else []
assert vers, "未找到 build-tools（需要 zipalign / apksigner / aapt2）"
btd = os.path.join(bt, vers[-1])

os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)

if mode == "arm64":
    unwanted_prefixes = ("lib/armeabi-v7a/", "lib/x86/", "lib/x86_64/")
else:
    unwanted_prefixes = ("lib/x86/",)

with zipfile.ZipFile(src) as zin:
    stray = [it.filename for it in zin.infolist() if it.filename.startswith(unwanted_prefixes)]

if stray:
    print(f"[1/3] 检测到残留非目标 ABI 条目 {len(stray)} 个，执行二次裁剪 + zipalign + 重签…")
    raw_out = out + ".trim"
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(raw_out, "w", zipfile.ZIP_DEFLATED) as zout:
        for it in zin.infolist():
            if it.filename.startswith(unwanted_prefixes):
                continue
            zout.writestr(it, zin.read(it.filename))
    aligned = out + ".aligned"
    r = subprocess.run([f"{btd}/zipalign", "-f", "-p", "4", raw_out, aligned], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    os.remove(raw_out)

    kp = {}
    with open(props, encoding="utf-8") as f:
        for line in f:
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                kp[k.strip()] = v.strip()
    ks = kp["storeFile"]
    if not os.path.isabs(ks):
        ks = os.path.join(os.path.dirname(props), ks)
    r = subprocess.run([
        f"{btd}/apksigner", "sign",
        "--ks", ks,
        "--ks-key-alias", kp["keyAlias"],
        "--ks-pass", f"pass:{kp['storePassword']}",
        "--key-pass", f"pass:{kp['keyPassword']}",
        "--out", out, aligned,
    ], capture_output=True, text=True)
    assert r.returncode == 0, (r.stderr or r.stdout)
    os.remove(aligned)
    print("[2/3] 重签完成（口令不回显）")
else:
    shutil.copy2(src, out)
    print("[1/3] APK ABI 已在 Gradle 阶段严格收敛，无需二次重打包")

v = subprocess.run([f"{btd}/apksigner", "verify", "--print-certs", out], capture_output=True, text=True)
assert v.returncode == 0, f"验签失败: {v.stderr or v.stdout}"
b = subprocess.run([f"{btd}/aapt2", "dump", "badging", out], capture_output=True, text=True).stdout
nat = [l for l in b.splitlines() if l.startswith("native-code")]
pkg = [l for l in b.splitlines() if l.startswith("package:")]
print("[核验]", pkg[0][:90] if pkg else "?")
print("[核验]", nat[0].strip() if nat else "native-code 缺失")
assert nat, "native-code 缺失"
if mode == "arm64":
    assert "arm64-v8a" in nat[0] and "x86_64" not in nat[0] and "armeabi-v7a" not in nat[0], f"Arm64 ABI 校验失败: {nat[0]}"
else:
    assert "arm64-v8a" in nat[0] and "armeabi-v7a" in nat[0] and "x86_64" in nat[0], f"Universal ABI 校验失败: {nat[0]}"
    assert re.search(r"'x86'", nat[0]) is None, f"Universal 不应包含 32 位 x86: {nat[0]}"

dex_bytes = b""
with zipfile.ZipFile(out) as zout:
    for it in zout.infolist():
        if it.filename.startswith("classes") and it.filename.endswith(".dex"):
            dex_bytes += zout.read(it.filename)
assert b"Lcom/kermond/ebook2tts/engine/SherpaAudioCallback;" in dex_bytes, "R8 丢失 SherpaAudioCallback 类"
assert b"invoke\x00" in dex_bytes, "R8 丢失 JNI invoke 回调签名"
print("[核验] R8 DEX 回调签名守卫通过: SherpaAudioCallback + invoke")
print(f"[完成] mode={mode} out={out} bytes={os.path.getsize(out)}")
PY
