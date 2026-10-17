#!/usr/bin/env bash
#
# pack_and_push.sh —— 一键「打包 + 上传 + 出 APK」
#
# 做三件事，全程不需要你手工操作 GitHub，也不需要手机/电脑上有 Android SDK：
#   1) 把本工程源码推到 GitHub（仓库不存在就自动创建，空仓库直接推）；
#   2) 触发仓库里的 GitHub Actions（.github/workflows/build-apk.yml）云端编译；
#   3) 等编译结束，把编出来的 APK 下载回本地，并作为 Release 附件传回 GitHub。
#
# 适用环境：Linux / macOS / WSL / Termux(手机)。
#
# ── 用法 ─────────────────────────────────────────────────────────────
#   export GITHUB_TOKEN=ghp_xxxxxxxx        # 必填：PAT，需 repo + workflow 权限
#   export GITHUB_OWNER=你的用户名           # 可选：不填会用 token 反查
#   export GITHUB_REPO=mcp-android-bridge    # 可选
#   bash scripts/pack_and_push.sh
#
# ── 可调参数（都是环境变量） ──────────────────────────────────────────
#   BRANCH          目标分支，默认 main
#   REPO_PRIVATE    是否私有仓库，默认 true
#   WORKFLOW_FILE   workflow 文件名，默认 build-apk.yml
#   RELEASE_TAG     Release 标签，默认 v1.0-<时间戳>
#   WAIT_TIMEOUT    等待编译的最长秒数，默认 1800
#   SKIP_WAIT       1 = 只推源码，不等编译
#   SKIP_RELEASE    1 = 不传 Release，只把 APK 放到本地 out/
#   FORCE_PUSH      1 = 允许覆盖远端历史（慎用）
#   TARGET_DIR      工程目录，默认取脚本的上一级目录
#
set -euo pipefail

# ------------------------------------------------------------------ 配置
GITHUB_TOKEN="${GITHUB_TOKEN:-}"
GITHUB_OWNER="${GITHUB_OWNER:-}"
GITHUB_REPO="${GITHUB_REPO:-mcp-android-bridge}"
BRANCH="${BRANCH:-main}"
REPO_PRIVATE="${REPO_PRIVATE:-true}"
WORKFLOW_FILE="${WORKFLOW_FILE:-build-apk.yml}"
RELEASE_TAG="${RELEASE_TAG:-v1.0-$(date +%Y%m%d-%H%M%S)}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-1800}"
SKIP_WAIT="${SKIP_WAIT:-0}"
SKIP_RELEASE="${SKIP_RELEASE:-0}"
FORCE_PUSH="${FORCE_PUSH:-0}"
TARGET_DIR="${TARGET_DIR:-}"

API="https://api.github.com"
USER_AGENT="mcp-android-bridge-pack"

# ------------------------------------------------------------------ 小工具
c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_info() { printf '\033[36m%s\033[0m\n' "$*"; }
c_warn() { printf '\033[33m%s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*" >&2; }
die()    { c_err "错误：$*"; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少命令 $1，请先安装"; }

# jq → python3 → sed 三级回退，尽量在各种环境都能解析 JSON
json_get() { # <json> <jq_path>   例如 json_get "$body" '.workflow_runs[0].id'
  local j="$1" p="$2"
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$j" | jq -r "$p" 2>/dev/null && return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "$j" | python3 -c "
import sys, json
d = json.load(sys.stdin)
cur = d
for tok in '''$p'''.strip('.').split('.'):
    if '[' in tok:
        name, idx = tok.split('[')
        if name:
            cur = cur[name]
        cur = cur[int(idx.rstrip(']'))]
    else:
        cur = cur[tok]
print('' if cur is None else cur)
" 2>/dev/null && return 0
  fi
  # grep 兜底：只支持取最后一段键的「第一个」匹配（避免贪婪匹配到嵌套字段）
  local key="${p##*.}"; key="${key%%[*}"
  printf '%s' "$j" \
    | grep -o "\"${key}\"[[:space:]]*:[[:space:]]*\"\{0,1\}[^\",}]*" \
    | head -n1 \
    | sed 's/^[^:]*:[[:space:]]*//' \
    | tr -d '"'
}

# 统一的 GitHub API 调用：api_call <METHOD> <PATH> [JSON_BODY]
#   结果放在 HTTP_CODE / HTTP_BODY
HTTP_CODE=""
HTTP_BODY=""
api_call() {
  local method="$1" path="$2" data="${3:-}"
  local out_body="$TMP_DIR/body"
  local args=(-sS -X "$method"
    -H "Authorization: Bearer $GITHUB_TOKEN"
    -H "Accept: application/vnd.github+json"
    -H "X-GitHub-Api-Version: 2022-11-28"
    -H "User-Agent: $USER_AGENT"
    -o "$out_body" -w '%{http_code}')
  [ -n "$data" ] && args+=(-H "Content-Type: application/json" -d "$data")
  HTTP_CODE="$(curl "${args[@]}" "$API$path" || true)"
  HTTP_BODY="$(cat "$out_body" 2>/dev/null || true)"
}

# 下载二进制（产物 zip 会 302 跳转，所以必须 -L）
api_download() { # <PATH> <OUT_FILE>
  curl -sS -L -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" -H "User-Agent: $USER_AGENT" \
    -o "$2" "$API$1"
}

extract_zip() { # <ZIP> <OUT_DIR>
  if command -v unzip >/dev/null 2>&1; then
    unzip -o -q "$1" -d "$2"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$1" "$2"
  else
    die "需要 unzip 或 python3 来解压产物（Termux 可执行: pkg install unzip）"
  fi
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# ------------------------------------------------------------------ 0. 环境检查
need git
need curl

if [ -z "$GITHUB_TOKEN" ]; then
  if [ -t 0 ]; then
    read -rsp "请输入 GitHub PAT（需 repo + workflow 权限）: " GITHUB_TOKEN
    echo
  else
    die "未设置 GITHUB_TOKEN（PAT），无法上传。示例：export GITHUB_TOKEN=ghp_xxx"
  fi
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "$TARGET_DIR" ]; then
  ROOT="$(cd "$TARGET_DIR" && pwd)"
else
  ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
[ -f "$ROOT/settings.gradle.kts" ] || die "在 $ROOT 找不到 settings.gradle.kts，请用 TARGET_DIR 指定工程目录"

OUT_DIR="$ROOT/out"
mkdir -p "$OUT_DIR"

c_info "==> 工程目录：$ROOT"

# ------------------------------------------------------------------ 1. 确认账号 & 建仓
api_call GET "/user"
[ "$HTTP_CODE" = "200" ] || die "PAT 校验失败（HTTP $HTTP_CODE）：$(json_get "$HTTP_BODY" '.message')"
LOGIN="$(json_get "$HTTP_BODY" '.login')"
if [ -z "$GITHUB_OWNER" ]; then
  GITHUB_OWNER="$LOGIN"
fi
c_info "==> 账号：$LOGIN    目标仓库：$GITHUB_OWNER/$GITHUB_REPO"

api_call GET "/repos/$GITHUB_OWNER/$GITHUB_REPO"
if [ "$HTTP_CODE" = "200" ]; then
  c_ok "    仓库已存在，直接复用"
elif [ "$HTTP_CODE" = "404" ]; then
  payload=$(printf '{"name":"%s","private":%s,"description":"MCP Android Bridge (auto pack & push)","auto_init":false}' \
    "$GITHUB_REPO" "$REPO_PRIVATE")
  api_call POST "/user/repos" "$payload"
  [ "$HTTP_CODE" = "201" ] || api_call POST "/orgs/$GITHUB_OWNER/repos" "$payload"
  case "$HTTP_CODE" in
    201) c_ok "    仓库已创建（空仓库）" ;;
    *)   die "建仓失败（HTTP $HTTP_CODE）：$(json_get "$HTTP_BODY" '.message')" ;;
  esac
else
  die "查询仓库失败（HTTP $HTTP_CODE）：$(json_get "$HTTP_BODY" '.message')"
fi

# ------------------------------------------------------------------ 2. 推源码
cd "$ROOT"

if [ ! -d .git ]; then
  git init -q
  git symbolic-ref HEAD "refs/heads/$BRANCH" 2>/dev/null || true
fi
git symbolic-ref -q HEAD >/dev/null 2>&1 || true
git rev-parse --verify HEAD >/dev/null 2>&1 || git checkout -q -b "$BRANCH" 2>/dev/null || true

git config user.email >/dev/null 2>&1 || git config user.email "mcp-bridge@users.noreply.github.com"
git config user.name  >/dev/null 2>&1 || git config user.name  "MCP Android Bridge"

git add -A
if git diff --cached --quiet; then
  c_warn "    没有新增改动，跳过提交"
else
  git commit -q -m "chore: sync MCP Android Bridge source" || true
  c_ok "    已生成提交"
fi

git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$GITHUB_OWNER/$GITHUB_REPO.git"

# 用 token 临时推送，推完把远端 URL 还原，避免 token 留在 .git/config
PUSH_URL="https://x-access-token:${GITHUB_TOKEN}@github.com/$GITHUB_OWNER/$GITHUB_REPO.git"
c_info "==> 推送源码到 $BRANCH 分支"
if ! git push -q "$PUSH_URL" "HEAD:refs/heads/$BRANCH" 2>"$TMP_DIR/push.err"; then
  if [ "$FORCE_PUSH" = "1" ]; then
    c_warn "    普通推送失败，按要求强制覆盖远端历史"
    git push -q --force "$PUSH_URL" "HEAD:refs/heads/$BRANCH"
  else
    c_err "$(cat "$TMP_DIR/push.err")"
    die "推送失败。若远端已有旧历史，可设 FORCE_PUSH=1 后重试（会覆盖远端）"
  fi
fi
SHA="$(git rev-parse HEAD)"
c_ok "    推送完成：${SHA:0:8}"

if [ "$SKIP_WAIT" = "1" ]; then
  c_ok "==> 已跳过等待。源码已上传，去仓库 Actions 页面即可看到编译："
  echo "    https://github.com/$GITHUB_OWNER/$GITHUB_REPO/actions"
  exit 0
fi

# ------------------------------------------------------------------ 3. 等编译结束
c_info "==> 等待 GitHub Actions 编译（同一提交 SHA 的 run）…"
run_id=""
deadline=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  api_call GET "/repos/$GITHUB_OWNER/$GITHUB_REPO/actions/runs?head_sha=$SHA&per_page=1"
  run_id="$(json_get "$HTTP_BODY" '.workflow_runs[0].id')"
  [ -n "$run_id" ] && break
  sleep 5
done
[ -n "$run_id" ] || die "没等到本次提交对应的 run，稍后可到 Actions 页面手动查看"

c_info "    run_id=$run_id"
status=""; conclusion=""
deadline=$(( $(date +%s) + WAIT_TIMEOUT ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  api_call GET "/repos/$GITHUB_OWNER/$GITHUB_REPO/actions/runs/$run_id"
  status="$(json_get "$HTTP_BODY" '.status')"
  conclusion="$(json_get "$HTTP_BODY" '.conclusion')"
  printf '\r    状态: %-10s 结论: %-10s' "$status" "${conclusion:-—}"
  [ "$status" = "completed" ] && break
  sleep 10
done
echo
[ "$status" = "completed" ] || die "等待超时（${WAIT_TIMEOUT}s），构建仍在进行，可稍后重跑本脚本并设 SKIP_WAIT=1"

if [ "$conclusion" != "success" ]; then
  c_err "构建未成功（conclusion=$conclusion）。日志下载到 $OUT_DIR/build-logs.zip，可用 diagnose 工具或人工查看。"
  api_download "/repos/$GITHUB_OWNER/$GITHUB_REPO/actions/runs/$run_id/logs" "$OUT_DIR/build-logs.zip" || true
  echo "    运行详情：https://github.com/$GITHUB_OWNER/$GITHUB_REPO/actions/runs/$run_id"
  exit 1
fi
c_ok "==> 编译成功"

# ------------------------------------------------------------------ 4. 取 APK
api_call GET "/repos/$GITHUB_OWNER/$GITHUB_REPO/actions/runs/$run_id/artifacts"
artifact_id="$(json_get "$HTTP_BODY" '.artifacts[0].id')"
[ -n "$artifact_id" ] || die "该 run 没有产物，请确认 workflow 里 upload-artifact 的 path 是否匹配 APK"
c_info "==> 下载产物 artifact=$artifact_id"
rm -rf "$TMP_DIR/art"; mkdir -p "$TMP_DIR/art"
api_download "/repos/$GITHUB_OWNER/$GITHUB_REPO/actions/artifacts/$artifact_id/zip" "$TMP_DIR/art.zip"
extract_zip "$TMP_DIR/art.zip" "$TMP_DIR/art"

apk_path="$(find "$TMP_DIR/art" -type f -name '*.apk' | head -n1)"
[ -n "$apk_path" ] || die "产物里没有找到 .apk 文件"
APK_NAME="$(basename "$apk_path")"
cp -f "$apk_path" "$OUT_DIR/$APK_NAME"
c_ok "==> APK 已保存：$OUT_DIR/$APK_NAME  （$(wc -c <"$OUT_DIR/$APK_NAME" | tr -d ' ') 字节）"

# ------------------------------------------------------------------ 5. 把 APK 传回 GitHub（Release 附件）
if [ "$SKIP_RELEASE" = "1" ]; then
  c_info "==> 已跳过 Release 上传（SKIP_RELEASE=1）"
else
  c_info "==> 创建 Release 并上传 APK 到 GitHub"
  rel_payload=$(printf '{"tag_name":"%s","name":"%s","body":"由 pack_and_push.sh 自动打包：源码经 GitHub Actions 编译生成 APK。","draft":false,"prerelease":false}' \
    "$RELEASE_TAG" "$RELEASE_TAG")
  api_call POST "/repos/$GITHUB_OWNER/$GITHUB_REPO/releases" "$rel_payload"
  if [ "$HTTP_CODE" != "201" ]; then
    c_warn "    Release 创建失败（HTTP $HTTP_CODE）：$(json_get "$HTTP_BODY" '.message')，APK 仍已保存在本地 $OUT_DIR/"
  else
    upload_url="$(json_get "$HTTP_BODY" '.upload_url')"
    upload_url="${upload_url%%\{*}"   # 去掉 {?name,label} 模板
    asset_code=$(curl -sS -o "$TMP_DIR/asset.json" -w '%{http_code}' -X POST \
      -H "Authorization: Bearer $GITHUB_TOKEN" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/vnd.android.package-archive" \
      -H "User-Agent: $USER_AGENT" \
      --data-binary "@$OUT_DIR/$APK_NAME" \
      "$upload_url?name=$APK_NAME")
    if [ "$asset_code" = "201" ]; then
      c_ok "    已上传 APK 到 Release：$RELEASE_TAG"
    else
      c_warn "    附件上传失败（HTTP $asset_code），APK 仍在本地 $OUT_DIR/"
    fi
  fi
fi

# ------------------------------------------------------------------ 汇总
echo
c_ok "══════════════ 完成 ══════════════"
echo "  仓库      : https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
echo "  本次构建  : https://github.com/$GITHUB_OWNER/$GITHUB_REPO/actions/runs/$run_id"
echo "  Release   : https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
echo "  本地 APK  : $OUT_DIR/$APK_NAME"
echo
c_info "下一步：把 $APK_NAME 装到手机上 → 启动 App 的 MCP 服务 → 把界面上的地址+令牌交给 AI 客户端。"
