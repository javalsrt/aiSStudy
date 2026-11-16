# ============================================
# 管理端后台共用配置
# 供 admin.conf.template（HTTP）与 admin-ssl.conf.template（HTTPS）两个外壳引用，
# 由容器启动脚本 40-https.sh 决定实际启用哪个外壳，避免两份配置漂移
# ============================================

# 前端静态文件（React SPA，由 Dockerfile.frontend 构建产出）
root /usr/share/nginx/html;
index index.html;

# 入口 HTML 不缓存：避免升级后浏览器仍加载旧 index.html（引用旧 hash 的 JS/CSS）
location = /index.html {
    add_header Cache-Control "no-store, no-cache, must-revalidate";
    expires -1;
}

# 前端页面（SPA 路由回退）
location / {
    try_files $uri $uri/ /index.html;
}

# 带 hash 的静态资源长缓存
location /assets/ {
    expires 30d;
    add_header Cache-Control "public, immutable";
}

# Docker 内部 DNS：允许 backend 容器不存在时 nginx 仍可启动（请求时才解析）
resolver 127.0.0.11 valid=30s ipv6=off;

# 后端 API 反向代理（变量写法延迟解析，避免启动时因 backend 不存在而崩溃）
set $backend_upstream http://backend:8080;

location /api/ {
    proxy_pass $backend_upstream;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 20m;
}

# 上传文件静态资源
location /uploads/ {
    proxy_pass $backend_upstream;
    proxy_set_header Host $host;
}

# WebSocket 代理 (聊天功能)
location /ws/ {
    proxy_pass $backend_upstream;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
}

gzip on;
gzip_types text/plain text/css application/json application/javascript image/svg+xml;
gzip_min_length 1k;
