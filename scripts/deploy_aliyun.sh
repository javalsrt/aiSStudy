#!/usr/bin/env bash
# =============================================================
# 智学职达 · 阿里云 ECS 一键部署脚本
# 用法：在服务器上执行  bash deploy_aliyun.sh
# 可选环境变量：
#   REPO_URL   仓库地址（默认 https://github.com/javalsrt/aiSStudy.git）
#   DEPLOY_DIR 部署目录（默认 ~/aiStudy）
#   WITH_AI    1 = 同时启动 Embedding 服务（RAG/AI 问答），默认 0
# =============================================================
set -e

REPO_URL="${REPO_URL:-https://github.com/javalsrt/aiSStudy.git}"
DEPLOY_DIR="${DEPLOY_DIR:-$HOME/aiStudy}"
WITH_AI="${WITH_AI:-0}"

echo "==> [1/4] 检查/安装 Docker"
if ! command -v docker &>/dev/null; then
  # 国内服务器使用阿里云镜像源安装，速度更快
  curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
  systemctl enable --now docker
fi
docker compose version >/dev/null 2>&1 || {
  echo "缺少 docker compose 插件，请安装 docker-compose-plugin 后重试"; exit 1;
}

echo "==> [2/4] 拉取代码 -> ${DEPLOY_DIR}"
if [ -d "$DEPLOY_DIR/.git" ]; then
  cd "$DEPLOY_DIR" && git pull --ff-only
else
  # GitHub 直连慢时自动走代理加速
  if ! git clone --depth 1 "$REPO_URL" "$DEPLOY_DIR" 2>/dev/null; then
    git clone --depth 1 "https://ghproxy.net/${REPO_URL}" "$DEPLOY_DIR"
  fi
  cd "$DEPLOY_DIR"
fi

echo "==> [3/4] 配置环境变量"
if [ ! -f .env ]; then
  echo "DEEPSEEK_API_KEY=" > .env
  echo "已生成 .env，请填入 DEEPSEEK_API_KEY 以启用 AI 问答功能（不填也可正常使用其他功能）"
fi

echo "==> [4/4] 构建并启动（首次构建约 5-10 分钟）"
if [ "$WITH_AI" = "1" ]; then
  docker compose --profile ai up -d --build
else
  docker compose up -d --build
fi

docker compose ps
PUBLIC_IP=$(curl -s --connect-timeout 3 ifconfig.me || echo "<服务器IP>")
echo ""
echo "============================================="
echo " 部署完成！浏览器访问: http://${PUBLIC_IP}"
echo " 默认账号: admin / 123456"
echo " 查看日志: docker compose logs -f"
echo "============================================="
