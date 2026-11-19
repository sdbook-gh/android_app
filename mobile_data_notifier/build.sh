#!/usr/bin/env bash
# =============================================================================
# build.sh
#
# 功能:
#   - 可指定 Gradle 版本 / 分发下载地址 (多镜像 fallback: 腾讯->阿里->华为->官方)
#   - 可指定 Gradle 缓存位置 (默认当前目录下 gradle_cache/), 完全隔离系统 ~/.gradle
#   - 可指定 Android SDK / NDK 位置, 自动适配 WSL(Linux) 与 Windows(Git Bash)
#   - 可指定下载代理 (用于分发下载 + Gradle 依赖下载)
#   - clean / cleangradle / cleanall 清理编译产物及冗余 gradle 文件, 最小空间占用
#   - 所有构建 (debug/release) 使用脚本内置统一 keystore 签名
#   - 自动同步 cmake 版本 (app/build.gradle.kts + CMakeLists.txt) 到 SDK 已安装版本
#   - 自动修改本地文件处统一 [auto-update] 高亮提示, 然后继续编译
#
# 用法: ./build.sh [命令] [选项...] [-- 额外gradle参数...]
#   命令: build(默认) | release | clean | cleangradle | cleanall | help
#   选项见 --help
#
# 停掉可能残留的 Gradle 进程
# ./build.sh stop
# 跑单元测试
# ./build.sh -- testDebugUnitTest
# 跑仪器化测试（需要已连接设备/模拟器）
# ./build.sh -- connectedDebugAndroidTest
# 一条命令连跑两项：
# ./build.sh -- testDebugUnitTest connectedDebugAndroidTest
# 看更详细日志：
# ./build.sh -- testDebugUnitTest connectedDebugAndroidTest --info --stacktrace
# =============================================================================

set -uo pipefail

# ----------------------------- 初始化变量 -------------------------------------
ORIG_PWD="$PWD"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$SCRIPT_DIR" || { echo "无法进入项目目录: $SCRIPT_DIR"; exit 1; }

COMMAND=""
GRADLE_ARGS=()
NO_DAEMON=0
SDK=""
NDK_DIR=""
TMP_DIR=""
PROXY_INIT_FILE=""
OFFLINE=0
COPY_APK=0

# 统一签名: 脚本内置 keystore, debug/release 使用同一 key。
# 注意: keystore 以 base64 内嵌在脚本中, 脚本分发即等同于私钥随之分发。
UNIFIED_KEYSTORE_B64='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'
UNIFIED_KEY_ALIAS="androiddebugkey"
UNIFIED_STORE_PASS="android"
UNIFIED_KEY_PASS="android"
UNIFIED_STORE_TYPE="JKS"
UNIFIED_EXPECTED_SHA256="DC:22:9B:11:0F:87:8F:23:E3:6F:0E:52:B2:1C:28:67:EA:CC:FE:D8:16:BD:26:3F:62:C4:14:70:FD:C6:55:DB"
UNIFIED_SIGNING_DIR=""
UNIFIED_SIGNING_INIT=""
UNIFIED_SIGNING_PROPS=""
UNIFIED_SIGNING_PROPS_JAVA=""
UNIFIED_SHA256=""

# 颜色输出
log()  { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[warn]\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31m[err ]\033[0m %s\n' "$*" >&2; }

# 醒目高亮: 本脚本自动修改了本地文件 / 生成新文件时提示 (与普通 log 区分)
hi()   { printf '\033[1;7;33m[auto-update]\033[0m %s\n' "$*"; }
hiline(){ printf '\033[1;33m       %s\033[0m\n' "$*"; }

# ----------------------------- 用法说明 ---------------------------------------
usage() {
  cat <<'EOF'
用法: ./build.sh [命令] [选项...] [-- 额外gradle参数...]

命令:
  build            构建 Debug APK (assembleDebug, 默认)
  release          构建 Release APK (assembleRelease)
  clean            清理编译产物 (保留 gradle_cache 与源码/配置)
  cleangradle      仅删除编译不依赖的 gradle wrapper 重复文件
                   (gradlew, gradlew.bat, gradle/wrapper/, gradle/ 为空时一并删除;
                   不影响 gradle_cache 与源码/配置)
  cleanall         深度清理: 编译产物 + 整个 gradle_cache/ + gradle wrapper 重复文件
                   (gradlew, gradlew.bat, gradle/wrapper/; 最小空间占用)
  help             显示本帮助

选项 (均可通过同名环境变量覆盖, 命令行参数优先):
  -v, --gradle-version <ver>   Gradle 版本, 默认 9.5.0
      --gradle-dist-url <url>  Gradle 分发下载地址 (指定则仅用该地址)
                               默认多镜像 fallback: 腾讯->阿里->华为->官方
  -c, --gradle-cache <dir>     Gradle 缓存目录, 默认 <项目>/gradle_cache
      --sdk <path>             直接指定当前环境 Android SDK (优先级最高)
      --sdk-linux <path>       WSL/Linux 下 SDK, 默认 /mnt/d/devd/android/sdk-linux
      --sdk-win <path>         Windows(Git Bash) 下 SDK, 默认 D:/devd/android/sdk-win
      --ndk-version <ver>      NDK 版本, 默认 30.0.15729638
      --ndk-dir <path>         直接指定 NDK 目录 (优先级最高)
  -p, --proxy <url>            下载代理, 如 http://172.19.16.1:4067
  -j, --java-home <path>       JDK 路径, 默认按环境探测 (JDK 21)
      --tmp <dir>              临时目录, 默认 WSL:/mnt/e/temp  Win:E:/temp
      --no-daemon              不使用 Gradle daemon
      --offline                仅使用 gradle_cache, 不下载任何依赖
  -o, --copy-apk              构建完成后将 APK 复制到当前工作目录

签名:
  所有构建 (debug/release) 使用脚本内置统一 keystore 签名
  alias: androiddebugkey
  证书 SHA-256: DC:22:9B:11:0F:87:8F:23:E3:6F:0E:52:B2:1C:28:67:EA:CC:FE:D8:16:BD:26:3F:62:C4:14:70:FD:C6:55:DB

环境变量: GRADLE_VERSION, GRADLE_DIST_URL, GRADLE_CACHE, SDK, SDK_LINUX, SDK_WIN,
          NDK_VERSION, NDK_DIR, PROXY, JAVA_HOME, TMP_DIR, OFFLINE

示例:
  ./build.sh                                   # Debug 构建
  ./build.sh release                           # Release 构建
  ./build.sh --offline build                   # 离线构建 (仅用 gradle_cache)
  ./build.sh -v 9.5.0 -c ./gradle_cache        # 指定版本与缓存
  ./build.sh --proxy http://172.19.16.1:4067   # 使用代理
  ./build.sh clean                             # 清理产物
  ./build.sh cleangradle                        # 仅清理 wrapper 重复文件
  ./build.sh cleanall                          # 清理产物 + 缓存
  ./build.sh lint -- --info                    # 透传 gradle 参数
EOF
}

# 带值选项缺参数: 报错并以退出码 2 退出 (避免 shift 2 直接触发 set -u 崩溃)
# $1: 选项名; $2: parse_args 剩余参数个数 ($#)
need_val() {
  if [[ $2 -lt 2 ]]; then
    err "选项 $1 需要参数值"
    exit 2
  fi
}

# ----------------------------- 参数解析 ---------------------------------------
parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help|help)   COMMAND="help"; shift;;
      build|assembleDebug) COMMAND="build"; shift;;
      release|assembleRelease) COMMAND="assembleRelease"; shift;;
      stop)     COMMAND="stop"; shift;;
      clean)            COMMAND="clean"; shift;;
      cleanall) COMMAND="clean-all"; shift;;
      cleangradle|clean-gradle) COMMAND="cleangradle"; shift;;
      -v|--gradle-version) need_val "$1" $#; GRADLE_VERSION="$2"; shift 2;;
      --gradle-dist-url)   need_val "$1" $#; GRADLE_DIST_URL="$2"; shift 2;;
      -c|--gradle-cache)   need_val "$1" $#; GRADLE_CACHE="$2"; shift 2;;
      --sdk)               need_val "$1" $#; SDK="$2"; shift 2;;
      --sdk-linux)         need_val "$1" $#; SDK_LINUX="$2"; shift 2;;
      --sdk-win)           need_val "$1" $#; SDK_WIN="$2"; shift 2;;
      --ndk-version)       need_val "$1" $#; NDK_VERSION="$2"; shift 2;;
      --ndk-dir)           need_val "$1" $#; NDK_DIR="$2"; shift 2;;
      -p|--proxy)          need_val "$1" $#; PROXY="$2"; shift 2;;
      -j|--java-home)      need_val "$1" $#; JAVA_HOME="$2"; shift 2;;
      --tmp)               need_val "$1" $#; TMP_DIR="$2"; shift 2;;
      --no-daemon)         NO_DAEMON=1; shift;;
      --offline)           OFFLINE=1; shift;;
      -o|--copy-apk)       COPY_APK=1; shift;;
      --) shift; GRADLE_ARGS+=("$@"); break;;
      *)
        if [[ -z "$COMMAND" && "$1" != -* ]]; then
          COMMAND="$1"; shift
        else
          GRADLE_ARGS+=("$1"); shift
        fi;;
    esac
  done
  [[ -z "$COMMAND" ]] && COMMAND="build"
}

# ----------------------------- 环境探测 ---------------------------------------
detect_platform() {
  local sys; sys="$(uname -s)"
  case "$sys" in
    MINGW*|MSYS*|CYGWIN*) PLATFORM="windows";;
    *) PLATFORM="linux";;   # 含 WSL 与原生 Linux
  esac
}

# ----------------------------- 路径与默认值解析 -------------------------------
resolve_defaults() {
  : "${GRADLE_VERSION:=9.5.0}"
  : "${SDK_LINUX:=/mnt/d/devd/android/sdk-linux}"
  : "${SDK_WIN:=D:/devd/android/sdk-win}"
  : "${NDK_VERSION:=30.0.15729638}"

  # JAVA_HOME 默认按平台
  if [[ -z "${JAVA_HOME:-}" ]]; then
    case "$PLATFORM" in
      linux)   JAVA_HOME="/mnt/d/devd/jdk/openlogic-openjdk-21.0.11+10-linux-x64";;
      windows) JAVA_HOME="D:/devd/jdk/openlogic-openjdk-21.0.11+10-windows-x64";;
    esac
  fi

  # SDK 默认按平台 (除非 --sdk 强制指定)
  if [[ -z "${SDK:-}" ]]; then
    case "$PLATFORM" in
      linux)   SDK="$SDK_LINUX";;
      windows) SDK="$SDK_WIN";;
    esac
  fi

  # NDK 默认由 SDK 推导
  if [[ -z "${NDK_DIR:-}" ]]; then
    NDK_DIR="$SDK/ndk/$NDK_VERSION"
  fi

  # 临时目录
  if [[ -z "${TMP_DIR:-}" ]]; then
    case "$PLATFORM" in
      linux)   TMP_DIR="/mnt/e/temp";;
      windows) TMP_DIR="E:/temp";;
    esac
  fi

  # Gradle 缓存默认当前目录下 gradle_cache
  if [[ -z "${GRADLE_CACHE:-}" ]]; then
    GRADLE_CACHE="$SCRIPT_DIR/gradle_cache"
  fi

  # GRADLE_DIST_URL 默认留空 -> ensure_gradle 内部使用多镜像 fallback
}

# ----------------------------- JDK 校验 ---------------------------------------
validate_java() {
  if [[ ! -d "$JAVA_HOME" ]]; then
    err "JAVA_HOME 不存在: $JAVA_HOME"
    exit 1
  fi
  local javacmd="$JAVA_HOME/bin/java"
  [[ "$PLATFORM" == "windows" ]] && javacmd="$JAVA_HOME/bin/java.exe"
  if ! "$javacmd" -version >/dev/null 2>&1; then
    err "JDK 不可用: $javacmd"
    exit 1
  fi
  log "JAVA_HOME = $JAVA_HOME"
}

# ----------------------------- 文件下载 ---------------------------------------
download_file() {
  local url="$1" dest="$2"

  if command -v curl >/dev/null 2>&1; then
    curl -fL --connect-timeout 30 --retry 3 --continue-at - -# -o "$dest" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget --timeout=30 --tries=3 -c -O "$dest" "$url"
  else
    err "未找到 curl 或 wget, 无法下载"
    return 1
  fi
}

# ----------------------------- Gradle 分发管理 --------------------------------
ensure_gradle() {
  local dist_dir="$GRADLE_CACHE/gradle-$GRADLE_VERSION"
  local gradle_bin="$dist_dir/bin/gradle"

  if [[ -x "$gradle_bin" ]]; then
    log "复用已存在的 Gradle 分发: $dist_dir"
    return 0
  fi

  if [[ "$OFFLINE" == 1 ]]; then
    err "离线模式下未找到 Gradle 分发: $dist_dir；请先在线运行一次构建以缓存 Gradle。"
    exit 1
  fi

  # 分发下载地址列表: --gradle-dist-url 指定则仅用该地址, 否则多镜像 fallback
  local -a urls=()
  if [[ -n "${GRADLE_DIST_URL:-}" ]]; then
    urls+=("$GRADLE_DIST_URL")
  else
    local v="$GRADLE_VERSION"
    urls+=("https://mirrors.cloud.tencent.com/gradle/gradle-${v}-bin.zip")
    urls+=("https://mirrors.aliyun.com/gradle/gradle-${v}-bin.zip")
    urls+=("https://mirrors.huaweicloud.com/gradle/gradle-${v}-bin.zip")
    urls+=("https://services.gradle.org/distributions/gradle-${v}-bin.zip")
  fi

  log "未找到 Gradle $GRADLE_VERSION, 开始下载..."
  log "  缓存: $GRADLE_CACHE"

  mkdir -p "$GRADLE_CACHE" "$TMP_DIR"
  local tmp_zip="$TMP_DIR/gradle-$GRADLE_VERSION-bin.zip"
  local tmp_extract="$TMP_DIR/gradle-extract-$$"
  local ok=0 url
  for url in "${urls[@]}"; do
    log "  尝试镜像: $url"
    rm -f "$tmp_zip"
    if download_file "$url" "$tmp_zip"; then
      ok=1
      log "  下载成功"
      break
    fi
    warn "  该镜像下载失败, 尝试下一个..."
  done
  if [[ "$ok" != 1 ]]; then
    err "所有镜像下载失败, 请检查网络或使用 --proxy / --gradle-dist-url"
    rm -rf "$tmp_extract" "$tmp_zip"
    exit 1
  fi

  if ! command -v unzip >/dev/null 2>&1; then
    err "未找到 unzip, 请安装后重试"
    rm -rf "$tmp_extract" "$tmp_zip"
    exit 1
  fi

  if ! unzip -tq "$tmp_zip" >/dev/null 2>&1; then
    err "Gradle 分发 ZIP 校验失败: $tmp_zip"
    rm -rf "$tmp_extract" "$tmp_zip"
    exit 1
  fi

  log "解压中..."
  rm -rf "$tmp_extract"; mkdir -p "$tmp_extract"
  if ! unzip -q "$tmp_zip" -d "$tmp_extract"; then
    err "Gradle 分发解压失败: $tmp_zip"
    rm -rf "$tmp_extract" "$tmp_zip"
    exit 1
  fi

  # zip 内顶层目录通常为 gradle-<ver>, 取第一个目录并重命名为标准名
  local extracted
  extracted="$(find "$tmp_extract" -maxdepth 1 -mindepth 1 -type d | head -1)"
  if [[ -z "$extracted" ]]; then
    err "解压后未找到目录, 请检查分发 zip"
    rm -rf "$tmp_extract" "$tmp_zip"
    exit 1
  fi

  rm -rf "$dist_dir"
  mkdir -p "$GRADLE_CACHE"
  mv "$extracted" "$dist_dir"
  hi "已写入 Gradle 分发到缓存: $dist_dir"
  chmod +x "$dist_dir/bin/gradle" 2>/dev/null || true
  rm -rf "$tmp_extract" "$tmp_zip"

  if [[ ! -x "$gradle_bin" ]]; then
    err "Gradle 安装异常: $gradle_bin 不可执行"
    exit 1
  fi
  log "Gradle $GRADLE_VERSION 就绪: $dist_dir"
}

# ----------------------------- SDK / NDK 配置 ---------------------------------
write_local_properties() {
  local sdk="$1"
  local line="sdk.dir=$sdk"
  local tmp="local.properties.tmp.$$"

  # 只更新 sdk.dir；保留用户自己的 ndk.dir、cmake.dir 等其他配置。
  if [[ -f local.properties ]]; then
    if ! awk -v line="$line" '
      BEGIN { seen = 0 }
      /^[[:space:]]*sdk\.dir[[:space:]]*=/ {
        if (!seen) { print line; seen = 1 }
        next
      }
      { print }
      END { if (!seen) print line }
    ' local.properties > "$tmp"; then
      rm -f "$tmp"
      err "更新 local.properties 失败"
      return 1
    fi
  else
    printf '%s\n' "$line" > "$tmp" || {
      rm -f "$tmp"
      err "写入 local.properties 失败"
      return 1
    }
  fi

  if [[ -f local.properties ]] && cmp -s local.properties "$tmp"; then
    rm -f "$tmp"
    hiline "local.properties 已为最新, 跳过写入"
    return 0
  fi

  if ! mv -f "$tmp" local.properties; then
    rm -f "$tmp"
    err "无法替换 local.properties"
    return 1
  fi
  hi "已更新 local.properties: $line（保留其他配置）"
}

# ----------------------------- cmake 版本自动同步 -----------------------------
# SDK 只装了单一 cmake 版本时, 把 gradle DSL 的 version 与 CMakeLists 的
# cmake_minimum_required 自动对齐到 SDK 实际安装版本, 避免 CXX1300。
# 版本来源: sdk/cmake/<ver>/source.properties 的 Pkg.Revision (AGP 按包版本匹配)。
# 匹配语义 (下限判定): 配置值 >= SDK 版本就不动, 否则对齐到 SDK 实际安装版本。
# 实现说明: 只用 awk + bash, 不用 sed 的 \1 反引用与 \(...) ERE 分组——
#           GNU sed / BSD sed (Windows Git Bash) / 非 GNU 移植版 三者行为不一致。
#           awk 里的引号字符用 sprintf("%c", 34/39) 生成, 避开 shell 单双引号转义。

detect_sdk_cmake_version() {
  local sp
  sp="$(find "$SDK/cmake" -maxdepth 2 -name source.properties 2>/dev/null | sort -V | tail -1)"
  if [[ -n "$sp" ]]; then
    grep -m1 '^Pkg.Revision' "$sp" 2>/dev/null | awk -F' = ' '{print $2}' | tr -d '\r'
  else
    # find 不可用或无匹配时, 回退到扫描目录名
    ls -d "$SDK"/cmake/*/ 2>/dev/null | awk -F'cmake/' '{print $2}' | awk -F'/' '{print $1}' | sort -V | tail -1
  fi
}

# 首次改动前备份 (之后不再覆盖), 复用 optimize_* 的 .bak 约定
backup_once() {
  local f="$1"
  [[ -f "$f.bak" ]] && return 0
  cp "$f" "$f.bak"
  hi "已备份: $f -> $f.bak"
  return 0
}

# gradle DSL: cmake { version = "..." } 的值 (仅在 cmake { } 块内匹配, 避免误取项目版本)
gks_ver_of() {
  awk 'BEGIN { dq = sprintf("%c", 34); sq = sprintf("%c", 39); incmk = 0; depth = 0; done = 0 } {
    line = $0
    if (!incmk && line ~ /^[ \t]*cmake[ \t]*\{/) {
      incmk = 1
      depth = gsub(/\{/, "{", line) - gsub(/\}/, "}", line)
      next
    }
    if (incmk) {
      if (!done && line ~ /^[ \t]*version[ \t]*=/) {
        p = index(line, "=")
        rest = substr(line, p + 1)
        for (i = 1; i <= length(rest); i++) if (substr(rest, i, 1) !~ /[ \t]/) { rest = substr(rest, i); break }
        q = substr(rest, 1, 1)
        if (q == dq || q == sq) {
          j = index(substr(rest, 2), q)
          if (j) { print substr(rest, 2, j - 1); done = 1; next }
        }
      }
      depth += gsub(/\{/, "{", line) - gsub(/\}/, "}", line)
      if (depth <= 0) incmk = 0
    }
  } END { if (!done) exit 1 }' "$1"
}

# gradle DSL: path = file(...) 的目标路径 (直接匹配 path = file("...") 行, 支持绝对/相对路径)
cml_path_of() {
  awk 'BEGIN { dq = sprintf("%c", 34); sq = sprintf("%c", 39) } {
    if (match($0, /(^|[^A-Za-z0-9_])path[ \t]*=/)) {
      s = substr($0, RSTART + RLENGTH)
      sub(/^[ \t]*/, "", s)
      sub(/^file[ \t]*\([ \t]*/, "", s)
      q = substr(s, 1, 1)
      if (q != dq && q != sq) next
      j = index(substr(s, 2), q)
      if (j) { print substr(s, 2, j - 1); exit }
    }
  }' "$1"
}

# CMakeLists.txt: cmake_minimum_required(VERSION <arg>) 的完整 arg
cml_arg_of() {
  awk 'match($0, /cmake_minimum_required[ \t]*\([ \t]*VERSION[ \t]+/) {
    s = substr($0, RSTART + RLENGTH)
    j = index(s, ")")
    if (j) s = substr(s, 1, j - 1)
    sub(/[ \t]+$/, "", s)
    print s
    exit
  }' "$1"
}

# 替换 gradle DSL 的 cmake version 值 (仅在 cmake { } 块内, 保留行尾注释); 未替换到返回 1
set_gks_cmake_version() {
  local file="$1" new="$2" tmp="$TMP_DIR/setver-$$"
  awk -v nv="$new" 'BEGIN { dq = sprintf("%c", 34); sq = sprintf("%c", 39); done = 0; incmk = 0; depth = 0 } {
    line = $0
    if (!done && !incmk && line ~ /^[ \t]*cmake[ \t]*\{/) {
      incmk = 1
      depth = gsub(/\{/, "{", line) - gsub(/\}/, "}", line)
      print line
      next
    }
    if (!done && incmk) {
      if (line ~ /^[ \t]*version[ \t]*=/) {
        p = index(line, "=")
        pre = substr(line, 1, p - 1)
        sub(/[ \t]+$/, "", pre)
        rest = substr(line, p + 1)
        for (i = 1; i <= length(rest); i++) if (substr(rest, i, 1) !~ /[ \t]/) { rest = substr(rest, i); break }
        q = substr(rest, 1, 1)
        if (q == dq || q == sq) {
          j = index(substr(rest, 2), q)
          if (j) {
            rest = substr(rest, j + 2)
            sub(/^[ \t]+/, "", rest)
            if (rest != "") rest = " " rest
            print pre " = " dq nv dq rest
            done = 1
            next
          }
        }
      }
      depth += gsub(/\{/, "{", line) - gsub(/\}/, "}", line)
      if (depth <= 0) incmk = 0
    }
    print line
  } END { if (!done) exit 1 }' "$file" > "$tmp" || { rm -f "$tmp"; return 1; }
  mv "$tmp" "$file"
}

# 无 version 行时插入到 path 行之前 (AGP 找不到 version 会退回默认 3.22.1 -> CXX1300)
add_gks_cmake_version() {
  local file="$1" new="$2" tmp="$TMP_DIR/setver-$$"
  awk -v nv="$new" 'BEGIN { dq = sprintf("%c", 34); done = 0 } {
    if (!done && $0 ~ /^[ \t]*path[ \t]*=/) {
      ind = $0
      sub(/path.*/, "", ind)
      print ind "version = " dq nv dq
      done = 1
    }
    print
  } END { if (!done) exit 1 }' "$file" > "$tmp" || { rm -f "$tmp"; return 1; }
  mv "$tmp" "$file"
}

# 替换 CMakeLists.txt 的 cmake_minimum_required 版本 (保留 ) 之后的注释)
set_cml_min_version() {
  local file="$1" new="$2" tmp="$TMP_DIR/setmin-$$"
  awk -v nv="$new" 'BEGIN { done = 0 } {
    if (!done && match($0, /cmake_minimum_required[ \t]*\([ \t]*VERSION[ \t]+/)) {
      s = substr($0, RSTART + RLENGTH)
      j = index(s, ")")
      if (j) {
        pre = substr($0, 1, RSTART - 1)
        rest = substr(s, j + 1)
        sub(/^[ \t]+/, "", rest)
        if (rest != "") rest = " " rest
        print pre "cmake_minimum_required(VERSION " nv ")" rest
        done = 1
        next
      }
    }
    print
  } END { if (!done) exit 1 }' "$file" > "$tmp" || { rm -f "$tmp"; return 1; }
  mv "$tmp" "$file"
}

# a >= b ? 两者形如 X.Y.Z; 缺段补 0; 非纯数字 (如 ${CMAKE_VERSION}) 视为 < b
cmake_ver_ge() {
  local a="${1:-}" b="${2:-0.0.0}"
  [[ "$a" == *'+'* ]] && a="${a%%+*}"
  [[ "$a" =~ ^[0-9]+([.][0-9]+)*$ ]] || return 1
  local -a A=(${a//./ }) B=(${b//./ })
  local k x y
  for k in 0 1 2; do
    x="${A[$k]:-0}"; y="${B[$k]:-0}"
    (( 10#$x > 10#$y )) && return 0
    (( 10#$x == 10#$y )) || return 1
  done
  return 0
}

sync_cmake_version() {
  local gks="app/build.gradle.kts"
  local sdk_ver cml ver cml_arg cml_num gks_ver_out cml_arg_out
  [[ -f "$gks" ]] || return 0

  # 前置判定: 项目无 cmake { } 块则视为非 CMake 项目, 完全静默跳过
  if ! grep -qE '^[[:space:]]*cmake[[:space:]]*\{' "$gks"; then
    return 0
  fi

  sdk_ver="$(detect_sdk_cmake_version)"
  if [[ -z "$sdk_ver" ]]; then
    warn "未找到 SDK cmake 版本 ($SDK/cmake 为空), 跳过 cmake 版本同步"
    return 0
  fi
  log "SDK cmake 版本: $sdk_ver"
  mkdir -p "$TMP_DIR"

  # CMakeLists.txt 路径从 gradle DSL 的 path = file(...) 读取, 不硬编码;
  # 解析不到则留空, 不猜默认路径 (避免 awk: fatal: cannot open file)
  cml="$(cml_path_of "$gks")"
  if [[ -n "$cml" ]]; then
    [[ "$cml" == /* ]] || cml="$(dirname "$gks")/$cml"
    [[ -f "$cml" ]] || cml=""
  fi

  local changed=0

  # 1) build.gradle.kts 的 cmake { version = "..." }
  # 下限判定: 配置值 >= SDK 版本就不动, 只在低于 SDK 时对齐 (不降级)
  if ver="$(gks_ver_of "$gks")"; then
    if ! cmake_ver_ge "$ver" "$sdk_ver"; then
      backup_once "$gks"
      if set_gks_cmake_version "$gks" "$sdk_ver"; then
        hi "cmake 版本低于 SDK: $gks version \"$ver\" -> \"$sdk_ver\""
        changed=1
      else
        warn "未能替换 $gks 的 cmake version (格式无法识别), 请手动检查"
      fi
    fi
  else
    backup_once "$gks"
    if add_gks_cmake_version "$gks" "$sdk_ver"; then
      hi "cmake 版本不一致: $gks 缺少 cmake { version }, 已补充 \"$sdk_ver\""
      changed=1
    else
      # 未找到 path 行锚点: 视为无有效 cmake 配置, 静默忽略
      return 0
    fi
  fi

  # 2) CMakeLists.txt 的 cmake_minimum_required(VERSION ...)
  if [[ -n "$cml" && -f "$cml" ]] && grep -qE 'cmake_minimum_required[[:space:]]*\(' "$cml" 2>/dev/null; then
    cml_arg="$(cml_arg_of "$cml")"
    cml_num="${cml_arg%%+*}"
    if ! cmake_ver_ge "$cml_num" "$sdk_ver"; then
      backup_once "$cml"
      if set_cml_min_version "$cml" "$sdk_ver"; then
        hi "cmake 版本不一致: $cml cmake_minimum_required VERSION ${cml_arg:-<无>} -> ${sdk_ver}"
        changed=1
      else
        warn "未能替换 $cml 的 cmake_minimum_required (格式无法识别), 请手动检查"
      fi
    fi
  fi

  [[ $changed -eq 1 ]] && { hi "已自动对齐 cmake 版本为 SDK 的 ${sdk_ver}, 继续编译"; return 0; }
  gks_ver_out="$(gks_ver_of "$gks" 2>/dev/null || true)"
  if [[ -n "$cml" && -f "$cml" ]]; then
    cml_arg_out="$(cml_arg_of "$cml" 2>/dev/null || true)"
  fi
  hiline "cmake 版本已对齐 (gradle=${gks_ver_out:-<无>}, CMakeLists=${cml_arg_out:-<无>}, SDK=${sdk_ver}), 无需修改"
  return 0
}

setup_sdk_ndk() {
  export ANDROID_HOME="$SDK"
  export ANDROID_SDK_ROOT="$SDK"
  export ANDROID_NDK_HOME="$NDK_DIR"
  export ANDROID_NDK_ROOT="$NDK_DIR"

  if [[ ! -d "$SDK" ]]; then
    err "Android SDK 不存在: $SDK"
    exit 1
  fi
  if [[ ! -d "$NDK_DIR" ]]; then
    warn "NDK 目录不存在: $NDK_DIR (本项目若不编译原生代码可忽略)"
  fi
  log "Android SDK = $SDK"
  log "Android NDK = $NDK_DIR"
  write_local_properties "$SDK" || exit 1
}

# ----------------------------- 代理配置 ---------------------------------------
setup_proxy() {
  if [[ -z "${PROXY:-}" ]]; then
    return 0
  fi
  log "使用代理: $PROXY"
  export http_proxy="$PROXY" https_proxy="$PROXY"
  export HTTP_PROXY="$PROXY" HTTPS_PROXY="$PROXY"
  export no_proxy="localhost,127.0.0.1" NO_PROXY="localhost,127.0.0.1"

  # 解析 host / port
  local stripped="${PROXY#http://}"
  stripped="${stripped#https://}"
  stripped="${stripped%/}"
  local host="${stripped%%:*}"
  local port=""
  if [[ "$stripped" == *:* ]]; then port="${stripped##*:}"; fi

  mkdir -p "$GRADLE_CACHE"
  PROXY_INIT_FILE="$GRADLE_CACHE/init-proxy.gradle"
  {
    echo "// 自动生成 by build.sh - Gradle JVM 代理设置"
    echo "System.setProperty(\"http.proxyHost\", \"$host\")"
    if [[ -n "$port" ]]; then
      echo "System.setProperty(\"http.proxyPort\", \"$port\")"
    fi
    echo "System.setProperty(\"https.proxyHost\", \"$host\")"
    if [[ -n "$port" ]]; then
      echo "System.setProperty(\"https.proxyPort\", \"$port\")"
    fi
    echo "System.setProperty(\"http.nonProxyHosts\", \"localhost|127.0.0.1\")"
  } > "$PROXY_INIT_FILE"
  hi "已生成 Gradle 代理 init 脚本: $PROXY_INIT_FILE"
}

# ----------------------------- 项目优化 (中国大陆加速) -------------------------
optimize_repos() {
  local file="settings.gradle.kts"
  [[ -f "$file" ]] || { file="settings.gradle"; [[ -f "$file" ]] || return 0; }

  grep -q "maven.aliyun.com" "$file" 2>/dev/null && return 0

  local marker='maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }'

  # marker 预检: 整行字面匹配不到就跳过, 不做无意义的文件重写
  if ! grep -qF "$marker" "$file" 2>/dev/null; then
    log "settings 中未找到腾讯云 marker, 跳过镜像注入: $file"
    return 0
  fi

  [[ -f "$file.bak" ]] || { cp "$file" "$file.bak"; hi "新建备份: $file -> $file.bak"; }

  mkdir -p "$TMP_DIR"
  local tmp="$TMP_DIR/opt-settings-$$"
  rm -f "$tmp"
  local found_plugin=0 found_dep=0

  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line" >> "$tmp"
    if [[ "$line" == *"$marker"* ]]; then
      if [[ $found_plugin -eq 0 ]]; then
        printf '%s\n' '        maven { url = uri("https://maven.aliyun.com/repository/google") }' >> "$tmp"
        printf '%s\n' '        maven { url = uri("https://maven.aliyun.com/repository/central") }' >> "$tmp"
        printf '%s\n' '        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }' >> "$tmp"
        printf '%s\n' '        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }' >> "$tmp"
        found_plugin=1
      elif [[ $found_dep -eq 0 ]]; then
        printf '%s\n' '        maven { url = uri("https://maven.aliyun.com/repository/google") }' >> "$tmp"
        printf '%s\n' '        maven { url = uri("https://maven.aliyun.com/repository/central") }' >> "$tmp"
        printf '%s\n' '        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }' >> "$tmp"
        found_dep=1
      fi
    fi
  done < "$file"

  mv "$tmp" "$file"
  if [[ $found_dep -eq 1 ]]; then
    hi "重写 settings 仓库配置: $file (pluginManagement 与 dependencies 均已注入阿里云/华为国内镜像)"
  else
    hi "重写 settings 仓库配置: $file (仅 pluginManagement 注入国内镜像, 未匹配到 dependencies 的 marker)"
  fi
}

optimize_gradle_properties() {
  local file="gradle.properties"
  [[ -f "$file" ]] || return 0

  [[ -f "$file.bak" ]] || { cp "$file" "$file.bak"; hi "新建备份: $file -> $file.bak"; }

  local changed=0

  if ! grep -Eq '^org\.gradle\.jvmargs=.*UseG1GC' "$file" 2>/dev/null; then
    # 同 set_gks_cmake_version 模式: awk + 临时文件 + mv, 不用 sed -i (BSD/macOS 语法不兼容)
    mkdir -p "$TMP_DIR"
    local tmp="$TMP_DIR/opt-props-$$"
    if awk '{ if (!done && sub(/^org\.gradle\.jvmargs=-Xmx[^ ]*/, "& -XX:+UseG1GC -XX:MaxGCPauseMillis=200")) done = 1; print } END { exit(done ? 0 : 1) }' "$file" > "$tmp"; then
      mv "$tmp" "$file"
      changed=1
    else
      rm -f "$tmp"
      warn "gradle.properties 中无 org.gradle.jvmargs=-Xmx... 行, 跳过 G1GC 注入"
    fi
  fi

  if ! grep -Eq '^kotlin\.incremental[[:space:]]*=' "$file" 2>/dev/null; then
    # 确保文件以换行结尾，避免追加内容粘连到上一行（原文件末行无换行时）
    [[ -s "$file" && -n "$(tail -c1 "$file")" ]] && echo >> "$file"
    echo "kotlin.incremental=true" >> "$file"
    changed=1
  fi

  if ! grep -Eq '^kotlin\.daemon\.jvmargs[[:space:]]*=' "$file" 2>/dev/null; then
    echo "kotlin.daemon.jvmargs=-Xmx2048m" >> "$file"
    changed=1
  fi

  [[ $changed -eq 1 ]] && hi "原地修改 gradle.properties (G1GC + Kotlin daemon)"
}

optimize_project() {
  log "分析项目配置..."
  optimize_repos
  optimize_gradle_properties
}

# ----------------------------- 缓存隔离与构建优化 -----------------------------
write_gradle_properties() {
  # 读取项目 gradle.properties 中的关键属性, 同步到缓存目录
  mkdir -p "$GRADLE_CACHE"
  local proj_jvmargs="org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8"
  local proj_parallel="org.gradle.parallel=true"
  local proj_caching="org.gradle.caching=true"
  if [[ -f "gradle.properties" ]]; then
    local v; v=$(grep "^org.gradle.jvmargs=" gradle.properties 2>/dev/null | head -1); [[ -n "$v" ]] && proj_jvmargs="$v"
    v=$(grep "^org.gradle.parallel=" gradle.properties 2>/dev/null | head -1); [[ -n "$v" ]] && proj_parallel="$v"
    v=$(grep "^org.gradle.caching=" gradle.properties 2>/dev/null | head -1); [[ -n "$v" ]] && proj_caching="$v"
  fi
  cat > "$GRADLE_CACHE/gradle.properties" <<EOF
# 自动生成 by build.sh - 项目缓存隔离与构建优化
org.gradle.daemon=true
android.builder.sdkDownload=false
$proj_jvmargs
$proj_parallel
$proj_caching
EOF
}

# ----------------------------- 统一签名 ---------------------------------------
# 内置 keystore 在运行时解码到缓存目录, 再由 init script 注入所有
# com.android.application 模块的 variant (debug/release/flavor 等)。
base64_decode() {
  if base64 -d </dev/null >/dev/null 2>&1; then
    base64 -d
  elif base64 -D </dev/null >/dev/null 2>&1; then
    base64 -D
  elif command -v openssl >/dev/null 2>&1; then
    openssl base64 -d -A
  else
    err "未找到可用的 base64 解码命令"
    return 1
  fi
}

# Windows(Git Bash) 下把 POSIX 路径转换为 Java 可识别的 D:/... 形式
to_java_path() {
  local p="$1"
  if [[ "$PLATFORM" == "windows" ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$p"
  else
    printf '%s' "$p"
  fi
}

prepare_unified_signing() {
  UNIFIED_SIGNING_DIR="$GRADLE_CACHE/buildsh-signing"
  mkdir -p "$UNIFIED_SIGNING_DIR" || {
    err "无法创建统一签名目录: $UNIFIED_SIGNING_DIR"
    exit 1
  }
  chmod 700 "$UNIFIED_SIGNING_DIR" 2>/dev/null || true

  local ks="$UNIFIED_SIGNING_DIR/unified.keystore"
  local tmp="$ks.tmp.$$"
  if ! printf '%s' "$UNIFIED_KEYSTORE_B64" | tr -d '[:space:]' | base64_decode > "$tmp"; then
    rm -f "$tmp"
    err "解码内置统一 keystore 失败"
    exit 1
  fi
  if [[ ! -s "$tmp" ]]; then
    rm -f "$tmp"
    err "内置统一 keystore 为空"
    exit 1
  fi
  chmod 600 "$tmp" 2>/dev/null || true
  if [[ -f "$ks" ]] && cmp -s "$ks" "$tmp"; then
    rm -f "$tmp"
  else
    mv -f "$tmp" "$ks" || {
      rm -f "$tmp"
      err "写入统一 keystore 失败: $ks"
      exit 1
    }
  fi
  chmod 600 "$ks" 2>/dev/null || true

  local keytool="$JAVA_HOME/bin/keytool"
  [[ "$PLATFORM" == "windows" ]] && keytool="$keytool.exe"
  if [[ ! -x "$keytool" ]]; then
    err "未找到 keytool: $keytool"
    exit 1
  fi
  if ! "$keytool" -list -keystore "$ks" -storetype "$UNIFIED_STORE_TYPE" \
        -alias "$UNIFIED_KEY_ALIAS" -storepass "$UNIFIED_STORE_PASS" >/dev/null 2>&1; then
    err "统一 keystore 校验失败 (alias=$UNIFIED_KEY_ALIAS)"
    exit 1
  fi

  UNIFIED_SHA256="$("$keytool" -list -v -keystore "$ks" -storetype "$UNIFIED_STORE_TYPE" \
      -alias "$UNIFIED_KEY_ALIAS" -storepass "$UNIFIED_STORE_PASS" 2>/dev/null \
      | awk -F': ' '/SHA256:/{print $2; exit}' | tr -d ' ')"
  if [[ -z "$UNIFIED_SHA256" ]]; then
    err "无法读取统一 keystore 的 SHA-256 指纹"
    exit 1
  fi
  if [[ "${UNIFIED_SHA256//:/}" != "${UNIFIED_EXPECTED_SHA256//:/}" ]]; then
    err "内置统一 keystore 指纹不匹配: $UNIFIED_SHA256 (期望 $UNIFIED_EXPECTED_SHA256)"
    exit 1
  fi

  UNIFIED_SIGNING_PROPS="$UNIFIED_SIGNING_DIR/signing.properties"
  {
    printf 'storeFile=%s\n' "$(to_java_path "$ks")"
    printf 'storeType=%s\n' "$UNIFIED_STORE_TYPE"
    printf 'storePassword=%s\n' "$UNIFIED_STORE_PASS"
    printf 'keyAlias=%s\n' "$UNIFIED_KEY_ALIAS"
    printf 'keyPassword=%s\n' "$UNIFIED_KEY_PASS"
  } > "$UNIFIED_SIGNING_PROPS" || {
    err "写入统一签名配置失败: $UNIFIED_SIGNING_PROPS"
    exit 1
  }
  chmod 600 "$UNIFIED_SIGNING_PROPS" 2>/dev/null || true
  UNIFIED_SIGNING_PROPS_JAVA="$(to_java_path "$UNIFIED_SIGNING_PROPS")"

  UNIFIED_SIGNING_INIT="$UNIFIED_SIGNING_DIR/init-unified-signing.gradle"
  cat > "$UNIFIED_SIGNING_INIT" <<'EOF'
import java.util.Properties

def signProps = new Properties()
new File(System.getProperty("buildsh.signing.properties")).withInputStream {
    signProps.load(it)
}

allprojects { p ->
    p.plugins.withId("com.android.application") {
        def android = p.extensions.getByName("android")
        def signingConfig = android.signingConfigs.findByName("buildshUnified")
        if (signingConfig == null) {
            signingConfig = android.signingConfigs.create("buildshUnified")
        }
        signingConfig.storeFile = new File(signProps.getProperty("storeFile"))
        signingConfig.storeType = signProps.getProperty("storeType")
        signingConfig.storePassword = signProps.getProperty("storePassword")
        signingConfig.keyAlias = signProps.getProperty("keyAlias")
        signingConfig.keyPassword = signProps.getProperty("keyPassword")

        def androidComponents = p.extensions.getByName("androidComponents")
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            variant.signingConfig.setConfig(signingConfig)
        }
    }
}
EOF
  chmod 600 "$UNIFIED_SIGNING_INIT" 2>/dev/null || true

  hi "已启用统一签名: alias=$UNIFIED_KEY_ALIAS, SHA-256=$UNIFIED_SHA256"
}

# 构建后校验 APK 实际签名, 避免 init script 未生效时静默产出非统一 key 的 APK
verify_apk_signing() {
  local task="${1:-}"
  local apk_dir="app/build/outputs/apk"
  case "$task" in
    assembleDebug)   apk_dir="$apk_dir/debug";;
    assembleRelease) apk_dir="$apk_dir/release";;
  esac
  [[ -d "$apk_dir" ]] || return 0

  local apks
  apks="$(find "$apk_dir" -name '*.apk' -type f 2>/dev/null | sort)"
  [[ -n "$apks" ]] || return 0

  local bt apksigner
  bt="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
  if [[ -z "$bt" ]]; then
    warn "未找到 Android build-tools, 跳过 APK 签名校验"
    return 0
  fi
  apksigner="${bt}apksigner"
  [[ "$PLATFORM" == "windows" && -f "${apksigner}.bat" ]] && apksigner="${apksigner}.bat"
  if [[ ! -f "$apksigner" && ! -x "$apksigner" ]]; then
    warn "未找到 apksigner, 跳过 APK 签名校验: $apksigner"
    return 0
  fi

  export PATH="$JAVA_HOME/bin:$PATH"
  local apk actual expected
  expected="${UNIFIED_SHA256//:/}"
  expected="${expected,,}"
  while IFS= read -r apk; do
    [[ -n "$apk" ]] || continue
    actual="$("$apksigner" verify --print-certs "$apk" 2>/dev/null \
      | awk -F': ' '/SHA-256 digest/{print $2; exit}' | tr -d ' ')"
    if [[ -z "$actual" ]]; then
      err "无法读取 APK 签名: $apk"
      return 1
    fi
    actual="${actual//:/}"
    actual="${actual,,}"
    if [[ "$actual" != "$expected" ]]; then
      err "APK 签名与统一 keystore 不一致: $apk"
      err "  实际: $actual"
      err "  期望: $expected"
      return 1
    fi
    log "签名校验通过: $apk"
  done <<< "$apks"
  return 0
}

# ----------------------------- Gradle 调用 ------------------------------------
run_gradle() {
  local dist_dir="$GRADLE_CACHE/gradle-$GRADLE_VERSION"
  local gradle_bin="$dist_dir/bin/gradle"
  [[ -x "$gradle_bin" ]] || { err "未找到 Gradle: $gradle_bin"; exit 1; }

  export JAVA_HOME GRADLE_USER_HOME ANDROID_HOME ANDROID_SDK_ROOT ANDROID_NDK_HOME ANDROID_NDK_ROOT
  export GRADLE_USER_HOME="$GRADLE_CACHE"

  local args=()
  args+=("--project-cache-dir" "$GRADLE_CACHE/project-cache")
  [[ -n "$PROXY_INIT_FILE" ]] && args+=("--init-script" "$PROXY_INIT_FILE")
  [[ -n "$UNIFIED_SIGNING_INIT" ]] && args+=("--init-script" "$UNIFIED_SIGNING_INIT")
  [[ -n "$UNIFIED_SIGNING_PROPS_JAVA" ]] && args+=("-Dbuildsh.signing.properties=$UNIFIED_SIGNING_PROPS_JAVA")
  [[ "$NO_DAEMON" == 1 ]] && args+=("--no-daemon")
  [[ "$OFFLINE" == 1 ]] && args+=("--offline")
  args+=("$@")

  log "运行 Gradle: $gradle_bin ${args[*]}"
  log "GRADLE_USER_HOME = $GRADLE_USER_HOME"
  "$gradle_bin" "${args[@]}"
}

gradle_stop() {
  local dist_dir="$GRADLE_CACHE/gradle-$GRADLE_VERSION"
  local gradle_bin="$dist_dir/bin/gradle"
  if [[ -x "$gradle_bin" ]]; then
    export GRADLE_USER_HOME="$GRADLE_CACHE" JAVA_HOME
    "$gradle_bin" --stop >/dev/null 2>&1 || true
  fi
}

# ----------------------------- 清理 -------------------------------------------
do_clean() {
  log "停止 Gradle daemon..."
  gradle_stop
  log "清理编译产物..."
  rm -rf build app/build .gradle .kotlin
  rm -f *.apk *.idsig 2>/dev/null || true
  log "清理完成 (已保留 gradle_cache/ 与源码/配置)"
}

# 清理编译不依赖的 gradle wrapper 重复文件
# (build.sh 使用 gradle_cache 内自下载的分发, 不依赖 wrapper;
#  wrapper 若被使用会在 ~/.gradle/wrapper/dists 再下载一份相同分发)
do_clean_gradle() {
  local f
  for f in gradlew gradlew.bat; do
    if [[ -e "$f" ]]; then
      rm -f "$f"
      log "已删除 wrapper 脚本: $f"
    fi
  done
  if [[ -d "gradle/wrapper" ]]; then
    rm -rf "gradle/wrapper"
    log "已删除 gradle/wrapper/"
  fi
  # gradle/ 仅在为空时删除 (将来可能存放 libs.versions.toml 等版本目录文件)
  if [[ -d "gradle" && -z "$(ls -A gradle 2>/dev/null)" ]]; then
    rmdir "gradle"
    log "已删除空目录 gradle/"
  fi
  log "wrapper 重复文件清理完成 (已保留 gradle_cache/ 与源码/配置)"
}

do_clean_all() {
  log "深度清理: 编译产物 + gradle_cache + wrapper 重复文件"
  gradle_stop
  rm -rf build app/build .gradle .kotlin
  rm -f *.apk *.idsig 2>/dev/null || true
  rm -rf "$GRADLE_CACHE"
  log "已删除 gradle_cache/ (下次构建需重新下载分发与依赖, 空间占用最小)"
  do_clean_gradle
}

# ----------------------------- 复制 APK 到当前工作目录 -------------------------
copy_apk_to_cwd() {
  local apk_dir="app/build/outputs/apk"
  local variant_dir
  case "$COMMAND" in
    build|assembleDebug) variant_dir="$apk_dir/debug";;
    assembleRelease)     variant_dir="$apk_dir/release";;
    *)                   variant_dir="";;
  esac

  # 如果指定了具体 variant 目录则优先查找, 否则搜索整个 apk 目录
  local search_dir="$apk_dir"
  if [[ -n "$variant_dir" && -d "$variant_dir" ]]; then
    search_dir="$variant_dir"
  fi

  # 查找最新的 .apk 文件
  local apk
  apk="$(find "$search_dir" -name '*.apk' -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | awk '{print $2}')"

  if [[ -z "$apk" ]]; then
    warn "未找到 APK 文件, 跳过复制"
    return 1
  fi

  local dest="$ORIG_PWD/$(basename "$apk")"
  cp "$apk" "$dest" || return 1
  log "APK 已复制到: $dest"
}

# ----------------------------- 主流程 -----------------------------------------
main() {
  parse_args "$@"

  if [[ "$COMMAND" == "help" ]]; then
    usage
    exit 0
  fi

  detect_platform
  resolve_defaults

  log "平台: $PLATFORM"
  log "Gradle 版本: $GRADLE_VERSION"
  log "Gradle 缓存: $GRADLE_CACHE"

  # 代理设置 (构建需要下载依赖, 尽早生效)
  setup_proxy

  # 停止/清理命令不需要 JDK, 提前分派, 避免默认 JDK 路径缺失时连 clean 都跑不了
  case "$COMMAND" in
    stop)     gradle_stop; exit 0;;
    clean)     do_clean; exit 0;;
    clean-all) do_clean_all; exit 0;;
    cleangradle) do_clean_gradle; exit 0;;
  esac

  validate_java

  # 自动优化项目配置 (中国大陆加速)
  optimize_project

  # 构建类命令 (build / release / 自定义任务)
  ensure_gradle
  setup_sdk_ndk
  sync_cmake_version
  write_gradle_properties
  prepare_unified_signing

  local task
  case "$COMMAND" in
    build)            task="assembleDebug";;
    assembleRelease)  task="assembleRelease";;
    *)                task="$COMMAND";;   # 透传作为 gradle 任务名
  esac

  if ((${#GRADLE_ARGS[@]})); then
    run_gradle "$task" "${GRADLE_ARGS[@]}" || { rc=$?; err "Gradle 构建失败 (退出码 $rc)"; exit $rc; }
  else
    run_gradle "$task" || { rc=$?; err "Gradle 构建失败 (退出码 $rc)"; exit $rc; }
  fi

  # 构建类任务完成后校验 APK 签名, 不一致则中断
  if [[ "$task" == assemble* ]]; then
    verify_apk_signing "$task" || exit 1
  fi

  # 构建完成后复制 APK 到当前工作目录
  if [[ "$COPY_APK" == 1 ]]; then
    copy_apk_to_cwd || exit 1
  fi
}

main "$@"
