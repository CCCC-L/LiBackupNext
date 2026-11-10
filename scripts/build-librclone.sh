#!/usr/bin/env bash
# 手动编译精简版 librclone（c-shared）。Gradle 的 buildLibrclone_* 任务已经会按
# rustAbis 自动编各 ABI，这个脚本只是排错 / 单独重编时用。
#
# 用法： ./scripts/build-librclone.sh [arm64-v8a|x86_64]
# 默认 arm64-v8a；debug 模拟器要 x86_64 就传 `x86_64`。
#
# c-shared 产物必须 GOOS=android + CGO_ENABLED=1：产物是真正的 .so，
# 只依赖 bionic，由 :root 进程按 nativeLibraryDir 绝对路径 dlopen 后调 RC。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/librclone"
API=31

ABI="${1:-arm64-v8a}"
case "$ABI" in
    arm64-v8a) GOARCH_VAL=arm64; CLANG_PREFIX=aarch64-linux-android ;;
    x86_64)    GOARCH_VAL=amd64; CLANG_PREFIX=x86_64-linux-android ;;
    *) echo "不支持的 ABI：$ABI（只支持 arm64-v8a / x86_64）" >&2; exit 1 ;;
esac
OUT="$ROOT/app/src/main/jniLibs/$ABI"

NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" && -f "$ROOT/local.properties" ]]; then
    SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)"
    if [[ -n "$SDK_DIR" ]]; then
        NDK="$(ls -d "$SDK_DIR"/ndk/* 2>/dev/null | sort -V | tail -1)"
    fi
fi
[[ -n "$NDK" ]] || { echo "找不到 NDK：请设置 ANDROID_NDK_HOME" >&2; exit 1; }
# Git Bash 下环境变量常是 D:\... 反斜杠形式，统一转成 POSIX 再拼路径
if command -v cygpath >/dev/null 2>&1; then
    NDK="$(cygpath -u "$NDK")"
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) HOST="windows-x86_64"; EXT=".cmd" ;;
    Darwin*)              HOST="darwin-x86_64";  EXT="" ;;
    *)                    HOST="linux-x86_64";   EXT="" ;;
esac
CC="$NDK/toolchains/llvm/prebuilt/$HOST/bin/${CLANG_PREFIX}${API}-clang$EXT"
[[ -f "$CC" ]] || { echo "找不到 NDK clang：$CC" >&2; exit 1; }
# go 在 Windows 上调 .cmd，需要 Windows 风格路径（正斜杠即可）
if command -v cygpath >/dev/null 2>&1; then
    CC="$(cygpath -m "$CC")"
fi

export GOTOOLCHAIN=auto
export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"
export GOOS=android
export GOARCH="$GOARCH_VAL"
export CGO_ENABLED=1
export GOFLAGS=-mod=mod
export CC

mkdir -p "$OUT"
cd "$SRC"
go build -buildmode=c-shared -trimpath -ldflags="-s -w" \
    -o "$OUT/librclone.so" .
# go build 会顺带生成头文件，不要留在 jniLibs 里
rm -f "$OUT/librclone.h"

echo "librclone ($ABI) built:"
ls -lh "$OUT/librclone.so"
