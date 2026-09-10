# 智学职达 - Docker 部署指南（阿里云 ECS）

服务器：阿里云 ECS，公网 IP `8.138.171.50`
仓库：`https://github.com/javalsrt/aiSStudy.git`

## 架构

| 容器 | 服务 | 端口 | 说明 |
|------|------|------|------|
| znxsgl-nginx | **项目官网（静态）** | **80** | http://8.138.171.50 |
| znxsgl-nginx | React 管理端 + 反向代理 | **8081** | http://8.138.171.50:8081 |
| znxsgl-backend | Spring Boot 后端 | 8080 | 由 Nginx 转发 `/api` `/uploads` `/ws` |
| znxsgl-mysql | MySQL 8.0 | 3307 | 数据卷持久化，外部不可直接访问 |
| znxsgl-embedding | BGE-M3 向量服务（可选） | 8000 | RAG/AI 问答，`--profile ai` 启动 |

> 建议配置：2核 4GB 起步；若启用 AI 问答（Embedding 服务加载 BGE-M3 约占 2GB 内存），建议 4核 8GB。

---

## 一、阿里云控制台配置（重要）

1. **安全组放行端口**：ECS 控制台 → 实例 → 安全组 → 配置规则 → 添加入方向规则：
   - 端口 `80`，源 `0.0.0.0/0`（项目官网，必开）
   - 端口 `8081`，源 `0.0.0.0/0`（管理端后台，必开）
   - 端口 `22`，源 `你的IP/32`（SSH，默认已开）
   - `8080`/`3307`/`8000` **不建议**对公网开放
2. **域名（可选）**：如有域名，在 DNS 解析中添加 A 记录指向 `8.138.171.50`；无域名直接用 IP 访问。

## 二、SSH 登录服务器并一键部署

```bash
ssh root@8.138.171.50

# 下载部署脚本并执行
curl -fsSL https://raw.githubusercontent.com/javalsrt/aiSStudy/main/scripts/deploy_aliyun.sh -o deploy_aliyun.sh
bash deploy_aliyun.sh          # 基础部署（不含 AI 问答）
# bash deploy_aliyun.sh        # WITH_AI=1 bash deploy_aliyun.sh  ← 含 AI 问答
```

脚本自动完成：安装 Docker（阿里源）→ 克隆仓库 → 生成 `.env` → 构建启动。

## 三、手动部署（等价步骤）

```bash
# 1. 安装 Docker（国内镜像源）
curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
systemctl enable --now docker

# 2. 拉取代码
git clone --depth 1 https://github.com/javalsrt/aiSStudy.git ~/aiStudy
cd ~/aiStudy

# 3. 配置 AI 对话 Key（可选，不填则 AI 问答不可用，其余功能正常）
echo "DEEPSEEK_API_KEY=你的DeepSeekKey" > .env

# 4. 构建并启动
docker compose up -d --build              # 基础版
# docker compose --profile ai up -d --build   # 含 Embedding（AI 问答）

# 5. 查看状态与日志
docker compose ps
docker compose logs -f backend
```

完成后浏览器访问：
- 项目官网：**http://8.138.171.50**
- 管理端后台：**http://8.138.171.50:8081**

## 四、默认账号

| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | 123456 |
| 教师 | zhangmy 等 | 123456 |
| 学生 | student1~8 | 123456 |

> 上线后请立即修改默认密码！

## 五、常用命令

```bash
docker compose up -d              # 启动
docker compose down               # 停止
docker compose logs -f backend    # 后端日志
docker compose restart backend    # 重启单个服务
docker compose down -v            # 停止并清除全部数据（慎用）
docker exec -it znxsgl-backend sh # 进入容器调试
```

## 六、更新版本

```bash
cd ~/aiStudy && git pull
docker compose up -d --build
```

## 七、常见问题

1. **页面打不开** → 先检查阿里云安全组是否放行 80 端口，再 `docker compose ps` 看容器是否全部 Up。
2. **GitHub 克隆慢** → 脚本已自动尝试 `ghproxy.net` 加速；手动可改用 `https://ghproxy.net/https://github.com/javalsrt/aiSStudy.git`。
3. **AI 问答报错** → 检查 `.env` 中 `DEEPSEEK_API_KEY` 是否已填；使用本地 Embedding 需 `--profile ai` 启动 embedding 容器。
4. **Embedding 模型下载慢** → 容器内已默认走 `hf-mirror.com` 镜像，约 2.2GB；也可本地下载后拷贝到数据卷 `embedding_models`。
5. **内存不足（构建/启动卡死）** → 添加 2G swap：`fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`。
6. **数据备份** → `docker run --rm -v znxsgl_mysql_data:/data -v $(pwd):/backup alpine tar czf /backup/mysql_backup.tar.gz /data`。
