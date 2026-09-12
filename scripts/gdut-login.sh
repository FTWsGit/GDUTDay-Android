#!/usr/bin/env bash
# gdut-login.sh —— 模拟浏览器登录广工统一认证,产出 jxfw 会话 cookie。
#
# 用法:
#   scripts/gdut-login.sh <会话目录>       # 例: scripts/gdut-login.sh temp/session
#
# 凭据来源(二选一,环境变量优先):
#   1. 环境变量 GDUT_STUDENT_ID / GDUT_PASSWORD
#   2. 项目根目录 secrets.properties(GDUT_STUDENT_ID= / GDUT_PASSWORD=)
#
# 产物(均在 <会话目录> 下,该目录应位于 temp/,已被 .gitignore 忽略):
#   cookies.txt        curl cookie jar,-b 直接可用
#   login_page.html    登录页快照,便于人工复查表单结构
#
# 安全约定(与 docs/07-verify-login.md 一致):
#   密码不打印、不落盘;cookie 值不打印(只列名);用完 rm -rf 会话目录。
#
# 原理见 docs/08-api-probing.md 第 3 节;字段级细节见 docs/01-gdut-protocol.md 第 1 节。
set -euo pipefail

SESSION_DIR="${1:?用法: gdut-login.sh <会话目录>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
UA='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6613.35 Safari/537.36'

# ---------- 凭据 ----------
if [[ -z "${GDUT_STUDENT_ID:-}" || -z "${GDUT_PASSWORD:-}" ]]; then
    if [[ -f "$ROOT/secrets.properties" ]]; then
        # shellcheck disable=SC1090
        GDUT_STUDENT_ID="$(grep '^GDUT_STUDENT_ID=' "$ROOT/secrets.properties" | head -1 | cut -d= -f2- | tr -d '\r')"
        GDUT_PASSWORD="$(grep '^GDUT_PASSWORD=' "$ROOT/secrets.properties" | head -1 | cut -d= -f2- | tr -d '\r')"
    fi
fi
[[ -n "${GDUT_STUDENT_ID:-}" && -n "${GDUT_PASSWORD:-}" ]] || {
    echo "缺少凭据: 设 GDUT_STUDENT_ID/GDUT_PASSWORD 或根目录 secrets.properties" >&2
    exit 2
}

# ---------- TLS ----------
CURL_TLS=(-k)   # 本机调试;要严格校验就去掉,并把学校 CA 导入系统信任链

# ---------- 工具 ----------
die() { echo "FAIL $*" >&2; exit 1; }

# 百分号编码(safe 集与 FormFields.encodeComponent 一致: 保留 A-Za-z0-9-._*)
enc() { python - "$1" <<'PY'
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe="-._*"))
PY
}

# 48 字符随机串(与 encrypt.js 的 $aes_chars 一致)
randstr() { python - "$1" <<'PY'
import sys, secrets
chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
print("".join(secrets.choice(chars) for _ in range(int(sys.argv[1]))))
PY
}

# ---------- ① 登录页 ----------
mkdir -p "$SESSION_DIR"
ENTRY='https://jxfw.gdut.edu.cn/new/ssoLogin'
curl -sS "${CURL_TLS[@]}" -c "$SESSION_DIR/cookies.txt" -A "$UA" -L \
    -o "$SESSION_DIR/login_page.html" "$ENTRY" \
    || die "访问 $ENTRY 失败"

PAGE="$SESSION_DIR/login_page.html"
SALT="$(grep -o 'pwdEncryptSalt" value="[^"]*' "$PAGE" | head -1 | sed 's/.*value="//')"
EXEC="$(grep -o 'id="execution" name="execution" value="[^"]*' "$PAGE" | head -1 | sed 's/.*value="//')"
[[ -n "$SALT" && -n "$EXEC" ]] || die "登录页解析失败(结构可能已变更), 见 $PAGE"

# ---------- ② 加密(AES-128-CBC, 64 字符随机前缀 + 16 字符随机 IV) ----------
PLAIN="$GDUT_PASSWORD"
PREFIX="$(randstr 64)"
IV="$(randstr 16)"
CIPHER="$(printf '%s' "$PREFIX$PLAIN" \
    | openssl enc -aes-128-cbc -K "$(printf '%s' "$SALT" | xxd -p | tr -d '\n')" \
        -iv "$(printf '%s' "$IV" | xxd -p | tr -d '\n')" -nosalt \
    | base64 -w0)"
[[ -n "$CIPHER" ]] || die "加密失败"

# ---------- ③ 提交 ----------
SERVICE_ENC='https%3A%2F%2Fjxfw.gdut.edu.cn%2Fnew%2FssoLogin'
BODY="username=$(enc "$GDUT_STUDENT_ID")&password=$(enc "$CIPHER")&=$SALT&rememberMe=true&_eventId=submit&execution=$(enc "$EXEC")"

REDIRECT="$(curl -sS "${CURL_TLS[@]}" -b "$SESSION_DIR/cookies.txt" -c "$SESSION_DIR/cookies.txt" -A "$UA" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -H "Referer: https://authserver.gdut.edu.cn/authserver/login?service=$SERVICE_ENC" \
    -o "$SESSION_DIR/login_resp.html" -w '%{redirect_url}' \
    -X POST "https://authserver.gdut.edu.cn/authserver/login?service=$SERVICE_ENC" \
    --data-binary "$BODY")"

[[ "$REDIRECT" == *"ticket=ST-"* ]] || die "登录未通过(无 CAS 票据), 见 $SESSION_DIR/login_resp.html"

# ---------- ④ 换 jxfw 会话 ----------
FINAL="$(curl -sS "${CURL_TLS[@]}" -b "$SESSION_DIR/cookies.txt" -c "$SESSION_DIR/cookies.txt" -A "$UA" \
    -L -o "$SESSION_DIR/jxfw_home.html" -w '%{url_effective}' "$REDIRECT")"

case "$FINAL" in
    # 成功落点是 jxfw 首页(实测为 /login!welcome.action —— 含 "login" 是正常的!)
    *authserver*|*ssoLogin*) die "票据兑换失败(落点: $FINAL)" ;;
esac
grep -q 'jxfw.gdut.edu.cn' "$SESSION_DIR/cookies.txt" \
    || die "cookies.txt 里没有 jxfw 会话"

JSESSIONID="$(awk '$6=="JSESSIONID" && /jxfw/ {print substr($7,1,6)"…"}' "$SESSION_DIR/cookies.txt" | head -1)"
echo "OK 已登录, jxfw JSESSIONID=${JSESSIONID:-?}"
echo "   会话目录: $SESSION_DIR (cookies.txt / login_page.html)"
echo "   用法示例: scripts/gdut-post.sh $SESSION_DIR/cookies.txt 'xsksap!getDataList.action' 'xnxqdm=202601&page=1&rows=1'"
