#!/usr/bin/env bash
# gdut-post.sh —— 带已登录会话,探测任意 jxfw 接口(POST EasyUI DataGrid 风格)。
#
# 用法:
#   scripts/gdut-post.sh <cookies.txt> <接口路径或action> [表单体] [Referer]
#
# 例:
#   # 考试安排(2024 秋)
#   scripts/gdut-post.sh temp/session/cookies.txt 'xsksap!getDataList.action' \
#     'xnxqdm=202401&page=1&rows=200&sort=zc,xq,jcdm2&order=asc'
#
#   # 课表(注意:课表 A 的 Referer 必须是 getXsgrbkList,见 docs/01 4.1 节)
#   scripts/gdut-post.sh temp/session/cookies.txt 'xsgrkbcx!xsAllKbList.action' \
#     'xnxqdm=202601' \
#     'https://jxfw.gdut.edu.cn/xsgrkbcx!getXsgrbkList.action'
#
# 接口路径兼容三种写法:完整 URL / 'xsksap!getDataList.action' / 'xsksap/getDataList'。
# 响应打到 stdout(方便接 jq);会话失效(302 回登录页)时非零退出。
set -euo pipefail

COOKIES="${1:?用法: gdut-post.sh <cookies.txt> <接口> [表单体] [Referer]}"
API="${2:?缺少接口路径}"
BODY="${3:-}"
REFERER="${4:-https://jxfw.gdut.edu.cn/}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6613.35 Safari/537.36'
BASE='https://jxfw.gdut.edu.cn'

[[ -f "$COOKIES" ]] || { echo "FAIL cookie 文件不存在: $COOKIES(先跑 scripts/gdut-login.sh)" >&2; exit 2; }

# ---------- 归一化接口路径 ----------
case "$API" in
    http*)  URL="$API" ;;
    '!'*)   URL="$BASE$API" ;;                                   # 兼容 '!getDataList...'
    *\!*)   URL="$BASE/$API" ;;                                  # 'xsksap!getDataList.action'
    */*)    URL="$BASE/${API%%/*}!${API##*/}" ;;                 # 'xsksap/getDataList'
    *)      URL="$BASE/$API" ;;
esac

# ---------- TLS ----------
CURL_TLS=(-k)   # 与 gdut-login.sh 保持一致;严格校验时同步去掉

# ---------- 请求 ----------
TMP="$(mktemp)"
BODY_OUT="$(mktemp)"
trap 'rm -f "$TMP" "$BODY_OUT"' EXIT

HTTP_CODE="$(curl -sS "${CURL_TLS[@]}" -b "$COOKIES" -c "$COOKIES" -A "$UA" \
    -H "Referer: $REFERER" \
    -H 'X-Requested-With: XMLHttpRequest' \
    -H 'Accept: application/json, text/javascript, */*; q=0.01' \
    -D "$TMP" \
    -o "$BODY_OUT" -w '%{http_code}' \
    ${BODY:+-H 'Content-Type: application/x-www-form-urlencoded' --data-binary "$BODY"} \
    "$URL")"

# ---------- 会话失效检测 ----------
# jxfw 的两种失效形态:302 回 authserver;或 200 + 直接渲染登录页 HTML(含 salt 输入框)。
if [[ "$HTTP_CODE" == "30"* ]] || grep -qi '^location: .*authserver' "$TMP" \
   || grep -q 'pwdEncryptSalt' "$BODY_OUT"; then
    echo "" >&2
    echo "FAIL 会话已失效(HTTP $HTTP_CODE)。重新登录: scripts/gdut-login.sh $(dirname "$COOKIES")" >&2
    exit 3
fi
if [[ "$HTTP_CODE" != "200" ]]; then
    echo "" >&2
    echo "FAIL HTTP $HTTP_CODE, 响应头见 $TMP" >&2
    exit 4
fi
# 响应体像 HTML(以 '<' 开头)→ 很可能是登录页/错误页。⚠ 不要信 Content-Type:
# 学校接口实测会拿 text/html 之类的头返回 JSON 体(docs/01 第 1.9 节),头不可靠。
if [[ "$(head -c 1 "$BODY_OUT" | tr -d '[:space:]')" == "<" ]]; then
    echo "" >&2
    echo "WARN 响应体疑似 HTML(登录页/错误页?) —— 检查会话或参数(响应头见 $TMP)" >&2
fi

cat "$BODY_OUT"
