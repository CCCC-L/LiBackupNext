#!/system/bin/sh
#
# 把恢复时被拆成多份的硬链接重新连起来。
#
# 背景：早期版本的恢复没有处理硬链接，快照里 links > 1 的文件（同一个 inode
# 挂了多个名字）会被当成独立文件各写一份。结果是应用数据体积翻倍，而且改一个
# 另一个不同步 —— 应用会认为数据损坏。
#
# 这个脚本扫描应用数据目录，找出「大小 + 权限 + 修改时间 + 内容」全部相同的
# 文件组，把它们重新连成硬链接。
#
# 不带目录参数时，自动扫描**所有第三方应用**的 /data/user/0/<包名>。
#
# 用法（需要 root）：
#
#   # 扫所有第三方应用，只看统计
#   sh dedupe-hardlinks.sh
#
#   # 只看某个应用
#   sh dedupe-hardlinks.sh com.termux
#
#   # 也可以直接给目录
#   sh dedupe-hardlinks.sh /data/user/0/com.termux
#
#   # 确认后真的合并
#   sh dedupe-hardlinks.sh --apply
#   sh dedupe-hardlinks.sh com.termux --apply
#
#   # 跳过匹配的路径（可多次）
#   sh dedupe-hardlinks.sh --exclude '/\.git/' --exclude nvim
#
# 从电脑上跑：
#   adb push dedupe-hardlinks.sh /data/local/tmp/
#   adb shell su -c "sh /data/local/tmp/dedupe-hardlinks.sh"
#
# 只用了 toybox 自带的命令（pm / find / stat / md5sum / sort / awk / xargs / ln），
# 不需要 busybox，也不需要 Python。

set -u

# 小于这个大小的文件不参与合并：空文件、几行的配置太多，误连风险高、也省不了空间
MIN_SIZE=4096
APPLY=0
TARGET=""
# 缓存目录本来就不参与备份，去重没有意义；.git 里同内容文件极多，容易误连
EXCLUDE='/cache/|/code_cache/|/\.git/'

usage() {
    echo "用法：sh $0 [包名|目录] [--apply] [--min-size <字节>] [--exclude <正则>]"
    echo ""
    echo "  不给目标时扫描所有第三方应用；默认只统计、不改任何东西。"
    echo "  加 --apply 才真的合并。需要 root（adb root 或 KernelSU 授权）。"
    echo ""
    echo "  --exclude  跳过路径匹配该正则的文件，可以给多次。"
    echo "             例如 --exclude '/\\.git/' --exclude nvim"
    echo ""
    echo "  注意：inode 信息在恢复时已经丢了，这里只能靠「大小 + 权限 + 修改时间 +"
    echo "  内容」反推，所以**内容恰好相同的独立文件也会被连起来**（典型例子："
    echo "  两个插件各自的 LICENSE、测试用的截图）。连上之后改一个，另一个会跟着变。"
    echo "  务必先看清单，确认都是本该共享的文件再加 --apply。"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apply)      APPLY=1 ;;
        --min-size)   MIN_SIZE="$2"; shift ;;
        --exclude)    EXCLUDE="${EXCLUDE:+$EXCLUDE|}$2"; shift ;;
        -h|--help)    usage; exit 0 ;;
        -*)           echo "未知参数：$1" >&2; exit 1 ;;
        *)            TARGET="$1" ;;
    esac
    shift
done

if [ "$(id -u)" != "0" ]; then
    echo "需要 root：先执行 adb root，或用 KernelSU 给 shell 授权" >&2
    exit 2
fi

TMP="/data/local/tmp/dedupe.$$"
mkdir -p "$TMP" || exit 1
trap 'rm -rf "$TMP"' EXIT INT TERM

# 字节数转成人能看的
human() {
    awk -v n="$1" 'BEGIN {
        split("B KB MB GB TB", unit, " ")
        i = 1
        while (n >= 1024 && i < 5) { n /= 1024; i++ }
        if (i == 1) printf "%d %s", n, unit[i]
        else        printf "%.1f %s", n, unit[i]
    }'
}

# 扫一个目录，把「大小|primary|follower」写进 $TMP/groups.txt，回显可省字节数
scan_dir() {
    scan_dir_path="$1"

    # 1. 大小|权限|mtime|inode|路径
    #    `-exec ... +` 一次塞多个文件；老版本 toybox 不支持时退回逐条执行
    find "$scan_dir_path" -type f -size +"${MIN_SIZE}c" \
        -exec stat -c '%s|%a|%Y|%i|%n' {} + > "$TMP/all.txt" 2>/dev/null
    if [ ! -s "$TMP/all.txt" ]; then
        find "$scan_dir_path" -type f -size +"${MIN_SIZE}c" \
            -exec stat -c '%s|%a|%Y|%i|%n' {} \; > "$TMP/all.txt" 2>/dev/null
    fi
    [ -s "$TMP/all.txt" ] || return 0

    if [ -n "$EXCLUDE" ]; then
        grep -vE "$EXCLUDE" "$TMP/all.txt" > "$TMP/all2.txt" 2>/dev/null || true
        mv "$TMP/all2.txt" "$TMP/all.txt"
    fi

    # 2. 粗筛：按 大小|权限|mtime 排序，组内 inode 数 > 1 的才留下
    #    （同一 inode 的多个名字本来就共享存储，折叠成一个代表再去比）
    sort -t'|' -k1,3 -k4,4 "$TMP/all.txt" | awk -F'|' -v out="$TMP/cand.txt" '
    {
        key = $1 FS $2 FS $3
        if (key != cur) {
            if (cur != "" && cnt > 1) printf "%s", buf > out
            cur = key
            cnt = 0
            buf = ""
            split("", seen)
        }
        if (!($4 in seen)) {
            seen[$4] = 1
            cnt++
        }
        buf = buf $0 "\n"
    }
    END {
        if (cur != "" && cnt > 1) printf "%s", buf > out
    }'

    [ -s "$TMP/cand.txt" ] || return 0

    # 3. 精筛：算 md5，内容一致的才算真重复
    awk -F'|' '{print $5}' "$TMP/cand.txt" | tr '\n' '\0' \
        | xargs -0 -r -n 40 md5sum > "$TMP/md5.txt" 2>/dev/null
    [ -s "$TMP/md5.txt" ] || return 0

    # 4. 按 md5 分组：同一 hash 的第一个当 primary，其余是 follower
    sort "$TMP/md5.txt" > "$TMP/md5sorted.txt"
    awk -v sizes="$TMP/cand.txt" '
    BEGIN { FS = "|" }
    NR == FNR {
        n = split($0, f, "|")
        size[f[n]] = f[1]
        next
    }
    {
        i = index($0, "  ")
        if (i == 0) next
        hash = substr($0, 1, i - 1)
        path = substr($0, i + 2)
        if (path in size) {
            if (hash != cur) {
                cur = hash
                primary = path
                next
            }
            printf "%s|%s|%s\n", size[primary], primary, path
        }
    }' "$TMP/cand.txt" "$TMP/md5sorted.txt" >> "$TMP/groups.txt"

    return 0
}

# 组装扫描目标
: > "$TMP/targets.txt"
if [ -z "$TARGET" ]; then
    echo "扫描所有第三方应用（>$(human "$MIN_SIZE") 的文件）…"
    pm list packages -3 2>/dev/null | sed 's/^package://' | sort > "$TMP/pkgs.txt"
    while IFS= read -r pkg; do
        [ "$pkg" = "com.cc.libackupnext" ] && continue   # 跳过自己
        [ -d "/data/user/0/$pkg" ] && echo "/data/user/0/$pkg" >> "$TMP/targets.txt"
    done < "$TMP/pkgs.txt"
    echo "  $([ -f "$TMP/targets.txt" ] && wc -l < "$TMP/targets.txt" | tr -d ' ') 个应用有数据目录"
elif [ -d "$TARGET" ]; then
    echo "$TARGET" > "$TMP/targets.txt"
elif [ -d "/data/user/0/$TARGET" ]; then
    echo "/data/user/0/$TARGET" > "$TMP/targets.txt"
else
    echo "找不到应用或目录：$TARGET" >&2
    exit 1
fi

# 逐个目录扫
: > "$TMP/groups.txt"
COUNT=0
while IFS= read -r dir; do
    COUNT=$((COUNT + 1))
    scan_dir "$dir"
done < "$TMP/targets.txt"

if [ ! -s "$TMP/groups.txt" ]; then
    echo "没有发现可以合并的重复文件。"
    exit 0
fi

# 按应用汇总：从 primary 路径里取 /data/user/0/<包名> 的包名
echo ""
echo "可合并的文件（按应用）："
awk -F'|' '
function h(n,   u, i) {
    split("B KB MB GB TB", u, " ")
    i = 1
    while (n >= 1024 && i < 5) { n /= 1024; i++ }
    if (i == 1) return sprintf("%d %s", n, u[i])
    return sprintf("%.1f %s", n, u[i])
}
{
    split($2, p, "/")
    pkg = p[5]        # /data/user/0/<包名>/... → 第 5 段
    cnt[pkg]++
    bytes[pkg] += $1
}
END {
    for (pkg in cnt) printf "  %-40s %4d 个文件  %s\n", pkg, cnt[pkg], h(bytes[pkg])
}' "$TMP/groups.txt" | sort -k2 -rn

GROUPS=$(wc -l < "$TMP/groups.txt" | tr -d ' ')
SAVED=$(awk -F'|' '{ s += $1 } END { printf "%d", s + 0 }' "$TMP/groups.txt")
echo ""
echo "合计：$GROUPS 个文件可合并，可省 $(human "$SAVED")"

if [ "$APPLY" -eq 0 ]; then
    echo ""
    echo "以上只是统计。确认无误后加 --apply 真正合并。"
    echo "注意：内容恰好相同的独立文件也会被列出来，逐个看一遍再决定。"
    echo ""
    head -15 "$TMP/groups.txt" | awk -F'|' '{
        printf "  %s\n    ← %s\n", $2, $3
    }'
    if [ "$GROUPS" -gt 15 ]; then
        echo "  …（还有 $((GROUPS - 15)) 个，加 --apply 会全部处理）"
    fi
    exit 0
fi

# 5. 真合并
echo ""
echo "开始合并…"
OK=0
FAIL=0
while IFS='|' read -r size primary follower; do
    if ln -f "$primary" "$follower" 2>/dev/null; then
        OK=$((OK + 1))
    else
        FAIL=$((FAIL + 1))
        [ "$FAIL" -le 3 ] && echo "  跳过 $follower（文件系统不支持硬链接？）"
    fi
done < "$TMP/groups.txt"

echo "完成：成功 $OK 个，失败 $FAIL 个，回收约 $(human "$SAVED")"
if [ "$FAIL" -gt 0 ]; then
    echo "失败的多半是文件系统不支持硬链接（比如 /sdcard 是 FUSE）。"
    exit 1
fi
exit 0
