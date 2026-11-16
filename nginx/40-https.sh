#!/bin/sh
# ============================================================
# HTTPS 自动启用脚本（容器每次启动时由 nginx 官方入口执行）
# 有证书：自动启用 443 站点，并让 80 端口 301 跳到 https://zhzdai.xyz
# 无证书：自动移除 HTTPS 配置，服务保持纯 HTTP，不影响容器启动
#
# 启用步骤（服务器上）：
#   1. 把证书放到部署目录下的 ssl/ 目录：fullchain.pem 与 key.pem
#   2. docker compose up -d nginx
# 证书签发（DNS 验证，不需要 80 端口）：
#   acme.sh --issue --dns dns_ali -d zhzdai.xyz -d www.zhzdai.xyz
# ============================================================
set -e

SSL_DIR=/etc/nginx/ssl
TEMPLATE=/etc/nginx/https.conf.template
HTTPS_CONF=/etc/nginx/conf.d/https.conf
REDIRECT_INC=/etc/nginx/conf.d/ssl-redirect.inc

if [ -f "$SSL_DIR/fullchain.pem" ] && [ -f "$SSL_DIR/key.pem" ]; then
  cp "$TEMPLATE" "$HTTPS_CONF"
  # 服务器级 return 在 location 匹配前生效：80 端口整站跳转 HTTPS
  printf 'return 301 https://zhzdai.xyz$request_uri;\n' > "$REDIRECT_INC"

  # 证书文件损坏或与域名不匹配时，nginx -t 会失败，此时回退纯 HTTP 保证可访问
  if ! nginx -t >/dev/null 2>&1; then
    rm -f "$HTTPS_CONF" "$REDIRECT_INC"
    echo "[https] 证书或配置校验失败，已回退为纯 HTTP，请检查 ssl/fullchain.pem 与 ssl/key.pem"
    exit 0
  fi
  echo "[https] 检测到证书，已启用 HTTPS（80 -> 443 跳转）"
else
  rm -f "$HTTPS_CONF" "$REDIRECT_INC"
  echo "[https] 未检测到 ssl/fullchain.pem + ssl/key.pem，官网保持 HTTP"
fi
