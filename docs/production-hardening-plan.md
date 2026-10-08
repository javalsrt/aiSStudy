# 生产加固方案与实施清单

> 目标：把 aiStudy 从功能可用推进到可多实例部署、并发可控、可监控、可回归的上线状态。
> 本文覆盖用户提出的 7 项加固需求，并标注当前代码中已落地的部分与后续任务。

## 一、总体现状

| 能力 | 当前状态 | 主要问题 |
|---|---|---|
| 签到并发 | 已改造为唯一索引 + 原子写入 | 迁移脚本需在存量库执行 |
| 调课并发 | 已增加 SELECT ... FOR UPDATE | 跨班级同冲突仍建议加分布式锁 |
| 幂等/限流 | 已接入 Redis，保留本地降级 | 需部署 Redis 并做多实例验证 |
| AI 出题/课表导入异步化 | 任务表已加入迁移脚本，方案设计完成 | 端点、消费者、前端轮询待改造 |
| 数据库优化 | 连接池可配置、索引迁移脚本已提供 | 慢 SQL 审计和压测需接入 CI |
| 性能回归 | 已有 6 个并发脚本和 Locust | 已增加阈值门禁脚本 |
| 安全扫描/越权测试 | 已有权限脚本，已增加扫描入口 | 需接入 CI 并修复扫描结果 |
| 监控告警/健康检查 | 已接入 Actuator + Prometheus | 需配置 Prometheus/Grafana/告警规则 |

## 二、逐项实施清单

### 1. check_in 并发重复修复

已落地：

- `backend/.../CheckInController.java`
  - 删除原来的先查后插逻辑，改为 `INSERT IGNORE` 原子写入；
  - 依赖唯一索引判断是否重复签到。
- `init-db/11-production-hardening.sql`
  - 先清理历史重复签到；
  - 为 `check_in_record(check_in_id, student_id)` 创建唯一索引。

验收：

1. 执行迁移脚本成功。
2. `test_04_checkin_concurrency.py` 多学生并发签到，`check_in_record` 无重复记录。
3. 同一学生重复调用返回您已签到过了。

### 2. 调课增加 SELECT ... FOR UPDATE

已落地：

- `ScheduleMapper.selectByIdForUpdate(Long id)`
  - 通过 `SELECT * FROM schedule WHERE id = ? FOR UPDATE` 在事务内锁定课表行。
- `TeacherScheduleAdjustController.adjustSchedule`
  - 使用 `selectByIdForUpdate` 读取目标课表；
  - 关联课表查询追加 `ORDER BY id` 和 `FOR UPDATE`，减少并发覆盖。
- 已有 `@Transactional` 保证锁在事务提交后释放。

后续建议：

- 对班级 + 课程 + 周次 + 原时段增加 Redis 分布式锁，彻底解决跨班级同冲突竞态。
- 增加死锁重试（`@Retryable` 或 TransactionTemplate 重试 2 次）。

验收：

1. `test_03_schedule_adjust_concurrency.py` 相同 requestId 只落库一次。
2. 不同 requestId 并发调同一节课时，后提交者被冲突/版本校验拒绝。
3. 压测并发调课无重复通知、无数据损坏。

### 3. 幂等、限流迁移到 Redis

已落地：

- 新增依赖：`spring-boot-starter-data-redis`。
- 新增服务：
  - `RedisIdempotencyService`：分布式幂等标记，Redis 故障自动降级本地 `ConcurrentHashMap`；
  - `RedisRateLimiterService`：基于 Lua 的固定窗口限流，全局 20 次/秒、单用户 10 次/分钟。
- `LlmService` 已改为优先走 Redis 限流，Redis 不可用时回退 Guava RateLimiter。
- `TeacherScheduleAdjustController` 和 `ScheduleImportController` 已接入分布式幂等/锁。
- `docker-compose.yml` 已增加 `redis` 服务和持久化卷。
- `application.yml` 已增加 Redis 连接配置。

后续建议：

- 将限流阈值、TTL 抽到配置项。
- 生产 Redis 开启密码、持久化和监控。
- 多实例部署时确保 `SPRING_DATA_REDIS_HOST` 指向同一 Redis。

验收：

1. 启动 Redis 后，两个后端实例同时调 AI 接口，限流计数共享。
2. 多实例并发调课时，相同 requestId 只处理一次。
3. 停掉 Redis 后系统仍可单机运行，但多实例不再共享幂等/限流。

### 4. AI 出题、课表导入异步化

现状：

- `QuizController.generate`、`ScheduleImportController.preview` 仍为同步长请求，AI 调用可能超过 60 秒。
- 现有 `ExamHomeworkController` 已有 `LLM_POOL` 线程池，可作为局部异步基础。

推荐方案：任务表 + 轻量消息队列

1. 新增表 `async_task`：
   - `id`, `biz_type` (`QUIZ_GENERATE` / `SCHEDULE_IMPORT_PREVIEW`), `status` (`PENDING`/`RUNNING`/`SUCCESS`/`FAILED`), `request_json`, `result_json`, `error_msg`, `created_by`, `created_at`, `updated_at`。
2. 接口改造：
   - `POST /api/quiz/generate-async` 返回 `taskId`；
   - `POST /api/schedule/import/preview-async` 返回 `taskId`；
   - `GET /api/async-task/{taskId}` 查询状态和结果。
3. 异步执行：
   - 使用 Spring `@Async` + `ThreadPoolTaskExecutor` 或 Redis Stream 消费者；
   - 任务状态写入 `async_task`，结果 JSON 入库；
   - 增加超时、重试、失败原因记录。
4. 前端：
   - 提交后进入任务处理中状态，轮询 `GET /api/async-task/{taskId}`；
   - 完成后渲染预览结果或题目列表。

验收：

1. 提交 AI 出题后 1 秒内返回 taskId，不再阻塞 HTTP 线程。
2. 任务完成后可从结果接口拿到题目。
3. 任务失败时能看到失败原因并可重试。
4. 重启后端后未完成任务可重新调度或标记失败。

### 5. 数据库连接池、慢 SQL、索引专项优化

已落地：

- `application.yml`
  - Hikari 最大连接数可通过 `DB_POOL_MAX` 配置，默认 30；
  - 最小空闲可通过 `DB_POOL_MIN` 配置，默认 10；
  - MyBatis 日志实现可通过 `MYBATIS_LOG_IMPL` 切换为 stdout，便于本地排查慢 SQL。
- `init-db/11-production-hardening.sql`
  - 已添加 schedule、chat_message、focus_session、quiz_answer、user 等热点索引；
  - quiz_session、exam_submission 已有等价索引，迁移脚本不再重复创建。

后续建议：

1. MySQL 开启慢查询日志：
   - `slow_query_log=1`
   - `long_query_time=1`
2. 使用 `EXPLAIN` 检查课表查询、未读消息、学习统计等高频 SQL。
3. 对 `schedule.weeks` 这类 JSON 过滤，评估增加冗余周次列或关联表。
4. 增加读写分离或缓存热点课程列表。
5. Actuator + Micrometer 已暴露 HikariCP、JVM、HTTP 指标，接入 Prometheus/Grafana。

验收：

1. 压测中数据库连接池不长时间占满。
2. 高频读接口 P99 小于 300ms。
3. 慢查询日志中无超过 1 秒的新增 SQL。
4. 索引迁移后 `EXPLAIN` 显示命中索引。

### 6. 自动化性能回归测试

已落地：

- 已有 `test/test_01` 至 `test_06`、`test/locustfile.py`、`test/run_all_loadtests.py`。
- 新增 `scripts/perf_regression_gate.py`：
  - 执行全套并发场景；
  - 读取本次 JSONL 报告；
  - 对非 AI 限流场景断言成功率 >= 99%、平均响应 < 1000ms、P99 < 3000ms。

建议接入 CI：

```bash
# 在项目根目录执行
python scripts/perf_regression_gate.py
```

或 Locust：

```bash
python -m locust -f locustfile.py --host http://localhost:8080 \
  --headless -u 50 -r 10 -t 60s --csv loadtest_locust
```

验收：

1. 每次发布前自动执行性能回归。
2. 阈值不通过时阻断发布。
3. 保留每轮 JSONL 报告，支持趋势对比。

### 7. 安全扫描与权限越权测试

已落地：

- 已有 `scripts/test-perm.ps1`、`scripts/test-frontend-perm.ps1`。
- 新增 `scripts/security-scan.ps1`：
  - 后端 OWASP dependency-check；
  - 前端 npm audit；
  - 后端/前端权限越权测试。

建议：

1. CI 中增加依赖扫描，禁止高危漏洞进入主分支。
2. 增加 SQL 注入、XSS、CSRF、越权、重放攻击测试。
3. 对上传文件做类型、大小、病毒扫描。
4. 定期执行渗透测试。
5. 敏感配置（数据库密码、JWT Secret、DeepSeek Key）全部走环境变量或密钥管理。

验收：

1. dependency-check 无 CVSS >= 7 漏洞，或已记录豁免。
2. npm audit 无 high/critical 漏洞。
3. 无权限 token 访问受保护接口返回 403。
4. 权限测试脚本在 CI 中稳定通过。

### 8. 生产监控、告警、健康检查

已落地：

- `pom.xml` 已加入：
  - `spring-boot-starter-actuator`
  - `micrometer-registry-prometheus`
- `application.yml` 已配置：
  - `/actuator/health`
  - `/actuator/info`
  - `/actuator/metrics`
  - `/actuator/prometheus`
  - graceful shutdown
- HikariCP、JVM、HTTP 指标可被 Prometheus 抓取。

后续建议：

1. 部署 Prometheus + Grafana。
2. 增加告警规则：
   - 5xx 比例
   - P99 延迟
   - 数据库连接池使用率
   - Redis 连接失败
   - JVM 堆内存
   - AI 限流触发次数
3. 增加 `/actuator/health` 到 Docker/K8s 健康检查。
4. 统一日志格式，接入 Loki 或 ELK。

验收：

1. `/actuator/health` 返回 `UP`。
2. Prometheus 能采集到 JVM、HTTP、HikariCP、Redis 指标。
3. 关键异常能触发告警。
4. 服务停止时优雅关闭，无请求中断。

## 三、实施顺序建议

1. 第一优先级：数据库迁移脚本 + 签到原子写 + 调课行锁。
2. 第二优先级：Redis 幂等/限流 + 多实例验证。
3. 第三优先级：Actuator/Prometheus 监控 + 性能回归门禁。
4. 第四优先级：AI 出题/课表导入异步化。
5. 第五优先级：慢 SQL 专项优化 + 安全扫描接入 CI。

## 四、上线验收清单

- [ ] 数据库迁移脚本在测试库和生产库演练通过
- [ ] 签到无重复、调课无覆盖
- [ ] Redis 多实例幂等/限流验证通过
- [ ] AI 出题、课表导入异步任务闭环
- [ ] 性能回归阈值全部通过
- [ ] 安全扫描无高危漏洞
- [ ] Actuator 健康检查正常
- [ ] Prometheus/Grafana 指标和告警就绪
- [ ] 回滚方案和备份恢复演练通过

## 五、风险与注意事项

1. Redis 引入后必须保证高可用，否则多实例幂等/限流退化为单机。
2. `SELECT ... FOR UPDATE` 会持有行锁，事务必须尽量短，避免长事务拖垮数据库。
3. `INSERT IGNORE` 依赖唯一索引，迁移脚本必须先在存量库清理重复数据。
4. 异步化会改变前端交互流程，需要同步修改管理端和移动端。
5. 性能测试应在独立环境执行，避免影响生产数据。