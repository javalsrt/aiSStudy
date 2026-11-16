#!/bin/sh
# ============================================================
# HTTPS 自动启用脚本（容器每次启动时由 nginx 官方入口执行）
#
# 有证书（ssl/fullchain.pem + ssl/key.pem 均存在）：
#   - 官网开启 443，80 端口 301 跳到 https://zhzdai.xyz
#   - 管理端 8081 自动切换为 HTTPS（证书共用），访问 https://zhzdai.xyz:8081
# 无证书：
#   - 官网保持 HTTP，管理端 8081 保持 HTTP，不影响容器启动
#   - 若证书损坏导致配置校验失败，同样回退纯 HTTP
#
# 启用步骤（服务器上）：
#   1. 把证书放到部署目录下的 ssl/ 目录：fullchain.pem 与 key.pem
#   2. docker compose up -d nginx
# 证书签发（DNS 验证，不需要 80 端口）：
#   acme.sh --issue --dns dns_ali -d zhzdai.xyz -d www.zhzdai.xyz
# ============================================================
set -e

SSL_DIR=/etc/nginx/ssl
CONF_DIR=/etc/nginx/conf.d
HTTPS_TEMPLATE=/etc/nginx/https.conf.template
ADMIN_HTTP_TEMPLATE=/etc/nginx/admin.conf.template
ADMIN_SSL_TEMPLATE=/etc/nginx/admin-ssl.conf.template
HTTPS_CONF="$CONF_DIR/https.conf"
ADMIN_CONF="$CONF_DIR/admin.conf"
REDIRECT_INC="$CONF_DIR/ssl-redirect.inc"

# 纯 HTTP 模式：官网走 80，管理端走 8081 明文
# 注意：ssl-redirect.inc 必须保留为空文件，nginx.conf 里对它做了 include，
# 文件缺失会导致 nginx 启动失败
use_http_only() {
  rm -f "$HTTPS_CONF"
  : > "$REDIRECT_INC"
  cp "$ADMIN_HTTP_TEMPLATE" "$ADMIN_CONF"
}

if [ -f "$SSL_DIR/fullchain.pem" ] && [ -f "$SSL_DIR/key.pem" ]; then
  cp "$HTTPS_TEMPLATE" "$HTTPS_CONF"
  cp "$ADMIN_SSL_TEMPLATE" "$ADMIN_CONF"
  # 服务器级 return 在 location 匹配前生效：80 端口整站跳转 HTTPS
  printf 'return 301 https://zhzdai.xyz$request_uri;\n' > "$REDIRECT_INC"

  # 证书文件损坏导致 nginx -t 失败时，回退纯 HTTP 保证服务可访问
  if ! nginx -t >/dev/null 2>&1; then
    use_http_only
    echo "[https] 证书或配置校验失败，已回退为纯 HTTP，请检查 ssl/fullchain.pem 与 ssl/key.pem"
    exit 0
  fi
  echo "[https] 检测到证书：官网 443 已启用，管理端 8081 已切换为 HTTPS"
else
  use_http_only
  echo "[https] 未检测到 ssl/fullchain.pem + ssl/key.pem，官网与管理端均保持 HTTP"
fi
