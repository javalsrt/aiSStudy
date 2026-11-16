# BKT 自适应学习升级方案

> 目标：每个学生、每门科目都有可量化的掌握度，教师端可见、可对比；并用演示账号证明"每刷一次题，数值真的会变"。

---

## 1. 设计范围

BKT 的天然粒度是"知识点"，不是"科目"。所以采用两级结构：

| 层级 | 存什么 | 谁看 |
|---|---|---|
| 科目级 | 不落库，由知识点加权平均实时算出 | 教师列表、学生首页 |
| 知识点级 | `user_knowledge_mastery` 每行一个概率 | 教师详情、报告页、选题 |

这样既满足"每个学生每门科目有一个 BKT 数值"的直观需求，又保留了真正能指导出题的细粒度。

---

## 2. 数据模型

### 2.1 知识点表

```sql
CREATE TABLE knowledge_point (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  course_id    BIGINT NOT NULL,
  chapter_id   BIGINT NULL COMMENT '来源章节，可空',
  name         VARCHAR(120) NOT NULL,
  description  VARCHAR(500) NULL,
  sort_order   INT DEFAULT 0,
  status       TINYINT DEFAULT 1,
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_course_name (course_id, name),
  KEY idx_course (course_id)
) COMMENT '课程知识点';
```

知识点的两个来源：

1. **快速版**：直接用 `course_chapter` 当知识点（零新数据，先跑通）
2. **正式版**：每个章节由 LLM 抽 2~4 个知识点，教师端可增删改

建议先跑快速版，演示前再把数学课拆成 3 个知识点。

### 2.2 题目与知识点的关联

题目是动态生成的，没有稳定主键。生成时给每道题算一个内容指纹：

```java
qid = md5(courseId + "|" + subject + "|" + question + "|" + options.join(","))
```

写入题目 JSON 的 `qid` 字段，同时落关联表：

```sql
CREATE TABLE question_knowledge (
  qid          CHAR(32) NOT NULL,
  knowledge_id BIGINT NOT NULL,
  course_id    BIGINT NOT NULL,
  create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (qid, knowledge_id),
  KEY idx_knowledge (knowledge_id)
) COMMENT '题目-知识点关联';
```

### 2.3 当前掌握度

```sql
CREATE TABLE user_knowledge_mastery (
  user_id      BIGINT NOT NULL,
  course_id    BIGINT NOT NULL,
  knowledge_id BIGINT NOT NULL,
  p_mastery    DECIMAL(5,4) NOT NULL DEFAULT 0.3000,
  attempts     INT DEFAULT 0,
  correct      INT DEFAULT 0,
  last_session_id BIGINT NULL,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, knowledge_id),
  KEY idx_user_course (user_id, course_id)
) COMMENT 'BKT 当前掌握概率';
```

### 2.4 快照（演示和曲线的核心）

没有快照，教师端只能看到当前值，看不到变化。每次交卷写一批：

```sql
CREATE TABLE mastery_snapshot (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id      BIGINT NOT NULL,
  course_id    BIGINT NOT NULL,
  knowledge_id BIGINT NOT NULL,
  session_id   BIGINT NOT NULL,
  p_before     DECIMAL(5,4) NOT NULL,
  p_after      DECIMAL(5,4) NOT NULL,
  question_count INT DEFAULT 0,
  correct_count  INT DEFAULT 0,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_kp_time (user_id, knowledge_id, created_at),
  KEY idx_session (session_id)
) COMMENT '每次交卷的掌握度快照';
```

### 2.5 参数配置

```sql
CREATE TABLE bkt_config (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  course_id    BIGINT NULL COMMENT 'NULL 表示全局默认',
  p_init       DECIMAL(4,3) DEFAULT 0.300,
  p_learn      DECIMAL(4,3) DEFAULT 0.100 COMMENT '每题的学习增量；0.10 时答错下降更明显，连续答对仍能收敛到 0.9+',
  p_slip       DECIMAL(4,3) DEFAULT 0.100,
  p_guess_choice  DECIMAL(4,3) DEFAULT 0.250,
  p_guess_judge   DECIMAL(4,3) DEFAULT 0.500,
  p_guess_fill    DECIMAL(4,3) DEFAULT 0.050,
  update_time  DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

猜测率按题型分开是关键：判断题 50% 猜中率，如果和选择题共用 0.25，学生会显得"什么都会"。

---

## 3. 更新算法

### 3.1 单知识点更新

```java
// p: 当前掌握概率；correct: 本题是否正确
double pObs;
if (correct) {
    double num = p * (1 - pSlip);
    double den = num + (1 - p) * pGuess;
    pObs = num / den;
} else {
    double num = p * pSlip;
    double den = num + (1 - p) * (1 - pGuess);
    pObs = num / den;
}
// 学习更新：做这道题本身也会带来掌握
double pNew = pObs + (1 - pObs) * pLearn;
```

### 3.2 一次交卷的批量更新

```java
// 1. 取本次会话所有作答，按作答顺序排序
// 2. 按知识点分组，组内保持顺序（同一知识点连做多题，概率逐题收敛）
// 3. 每题按 3.1 更新
// 4. 汇总写 user_knowledge_mastery
// 5. 每个知识点写一条 mastery_snapshot（p_before 为本次第一题前的值，p_after 为最后一题后的值）
```

解析题（主观作答）的处理：

- 默认**不参与** BKT，避免主观判分噪声
- 如需参与：得分率 ≥ 0.6 视为答对，否则答错，并在快照里标记

### 3.3 科目级汇总

```sql
SELECT course_id,
       SUM(p_mastery * GREATEST(attempts, 1)) / SUM(GREATEST(attempts, 1)) AS mastery
FROM user_knowledge_mastery
WHERE user_id = ?
GROUP BY course_id;
```

按作答次数加权，避免"只做过一道题的知识点"拉低整体。`attempts = 0` 的知识点不计入。

### 3.4 执行时机

交卷接口 `evaluateQuiz` 返回前，用线程池异步执行，失败写日志并重试一次。不阻塞学生看报告。

---

## 4. 冷启动

| 场景 | 处理 |
|---|---|
| 新学生 | 所有知识点初始 0.300 |
| 已读章节 | 对应知识点初始 0.350（章节已学，先验略高） |
| 作答少于 3 次 | 标记"证据不足"，教师端显示灰色，不参与排名 |
| 历史数据 | 提供回放脚本：按 `quiz_answer` 时间顺序重跑 BKT，生成初始掌握度 |

回放脚本很重要：上线时已经有历史答题记录，不回放的话所有学生都从 0.3 开始，教师会觉得"数据是假的"。

---

## 5. 接口设计

### 5.1 教师端

**学生列表（班级 + 课程）**

```
GET /api/teacher/mastery/students?classId=1&courseId=359
```

```json
{
  "courseId": 359,
  "courseName": "数学",
  "students": [
    {
      "userId": 1468,
      "name": "李博盛",
      "mastery": 0.72,
      "trend": "up",
      "weakCount": 2,
      "lastPracticeAt": "2026-11-16 10:20",
      "evidence": "enough"
    }
  ]
}
```

**学生详情**

```
GET /api/teacher/mastery/student/1468?courseId=359
```

```json
{
  "summary": { "mastery": 0.72, "delta7d": 0.18, "attempts": 46 },
  "knowledge": [
    { "id": 11, "name": "函数定义域", "mastery": 0.88, "attempts": 12, "delta": 0.05 },
    { "id": 12, "name": "极限的 ε-N 定义", "mastery": 0.36, "attempts": 15, "delta": -0.09 },
    { "id": 13, "name": "导数公式", "mastery": 0.91, "attempts": 19, "delta": 0.12 }
  ]
}
```

**掌握度曲线**

```
GET /api/teacher/mastery/history?studentId=1468&knowledgeId=12
```

返回 `[{ "time": "...", "p": 0.30, "sessionId": 88 }, ...]`，直接来自 `mastery_snapshot`。

**对比**

```
GET /api/teacher/mastery/compare?studentIds=1468,1470&courseId=359
```

返回每个学生每个知识点的当前值与曲线，前端并排展示。

**班级分布**

```
GET /api/teacher/mastery/class-distribution?classId=1&courseId=359&knowledgeId=12
```

返回 P25 / P50 / P75 / 最低 / 最高，前端画箱线图或分位条。

### 5.2 学生端

```
GET /api/quiz/mastery?courseId=359
```

报告页用，返回知识点列表和本次变化：

```json
{
  "items": [
    { "name": "极限的 ε-N 定义", "before": 0.45, "after": 0.36, "attempts": 15 }
  ]
}
```

### 5.3 演示管理

```
POST /api/admin/demo/reset?userId=1468&courseId=359
```

清空该学生的 `user_knowledge_mastery` 和 `mastery_snapshot`，重置难度为 1，保留账号。只能对标记为 demo 的账号执行。

---

## 6. 教师端界面

新增页面：**学情追踪**

### 6.1 主视图

```
┌────────────────────────────────────────────────────┐
│ 班级 [计算机 1 班 ▾]   课程 [数学 ▾]     [对比模式]  │
├────────────────────────────────────────────────────┤
│ 姓名     科目掌握度   趋势   薄弱知识点   最近刷题     │
│ 李博盛   72%  ██████  ↑     2 个          10:20     │
│ 王小雨   54%  ████    →     4 个          昨天       │
│ 陈明     38%  ███     ↓     5 个          2 天前     │
└────────────────────────────────────────────────────┘
```

- 掌握度用进度条 + 百分比
- 趋势对比 7 天前
- 薄弱知识点 = 掌握度 < 0.4 且作答 ≥ 3 次的数量
- 证据不足的学生整行置灰

### 6.2 学生详情

```
┌─────────────────────────────────────────────┐
│ 李博盛 · 数学          综合掌握度 72%        │
│ 近 7 天 +18%    累计作答 46 题               │
├─────────────────────────────────────────────┤
│ 知识点掌握度（按弱到强）                      │
│ 极限的 ε-N 定义  ████░░░░  36%  ↓9%         │
│ 函数定义域      ███████░  88%  ↑5%         │
│ 导数公式        ████████  91%  ↑12%        │
├─────────────────────────────────────────────┤
│ 掌握度变化（按刷题次数）                      │
│  1.0 ┤                                      │
│  0.5 ┤      ╭─╮   ╭──                       │
│  0.3 ┤──╮──╯ ╰───╯                          │
│      └──────────────────                    │
└─────────────────────────────────────────────┘
```

曲线数据来自 `mastery_snapshot`，横轴是刷题时间，不是日期。

### 6.3 对比模式

勾选 2~3 个学生后：

- 每个知识点一组并排条形（同色系深浅区分学生）
- 或雷达图，维度是知识点，每人一条线
- 下方表格列出差异最大的 3 个知识点，标"领先/落后"

### 6.4 预警

班级视图顶部一条提示：

> 3 名学生在「极限的 ε-N 定义」上掌握度低于 40%，建议安排一次集中讲解。

点击跳到该知识点的班级分布。

---

## 7. 学生端展示

报告页新增区块：

```
知识点掌握
极限的 ε-N 定义   45% → 36%   ↓9%
导数公式          79% → 91%   ↑12%
```

只显示本次涉及的知识点，不铺全量列表。首页可选显示科目级掌握度环形图。

---

## 8. 演示方案（重点）

### 8.1 演示目标

让导师在 3 分钟内看到三件事：

1. 答对，数值上升
2. 答错，数值下降但**不会归零**
3. 连续刷题，教师端曲线持续更新

### 8.2 演示准备

| 项目 | 内容 |
|---|---|
| 演示账号 | `demo_math` / `123456`，与真实学生同班，已开通"数学" |
| 固定题库 | 12 道单选，3 个知识点 × 4 题；演示每次固定练第一个知识点（4 题），便于同一知识点反复演示升降 |
| 知识点 | 函数与极限、一致连续、导数与微分 |
| 教师端 | 打开"学情追踪"，选中该学生 |
| 重置入口 | 学情追踪 → 学生详情右上角"重置数据" |

固定题库放在 `backend/src/main/resources/demo/demo_math_quiz.json`，演示模式直接返回，不调用 LLM。这是防翻车的关键——现场生成题目有延迟和不确定性。

题目示例（每个知识点 4 题，难度一致）：

```json
{
  "knowledge": "极限的 ε-N 定义",
  "question": "数列 $\{a_n\}$ 收敛于 $L$ 的 $\varepsilon$-$N$ 定义是？",
  "options": ["...", "...", "...", "..."],
  "answer": "A",
  "qid": "demo-kp2-01"
}
```

### 8.3 演示脚本（四幕）

**第一幕：初始状态**

- 学生未刷题，教师端三个知识点都是 30%，标注"证据不足"
- 讲解词：这是 BKT 的先验，不是随便写的 0，是"刚接触、大概率没掌握"

**第二幕：全对一次**

- 学生刷 4 道"函数与极限"，全部答对
- 教师端刷新：30% → 99%，曲线新增一个点
- 讲解词：连续答对时贝叶斯更新快速收敛，4 题就把概率推到 0.9 以上

**第三幕：故意连错四题**

- 再刷同样 4 道，全部选错
- 掌握度 99% → 约 15%，不归零
- 讲解词：BKT 把错题解释为"失误 + 未掌握"的混合，所以会大幅下降但不会判成 0；这正是它比正确率合理的地方

**第四幕：换知识点 + 对比**

- 重新答对 4 题，掌握度回到约 99%，曲线形成"上去—下来—再上去"
- 教师端对比 `demo_math` 与另一个真实学生：同一知识点两条曲线
- 讲解词：同一套公式，既能看个人变化，也能做横向对比

### 8.4 为什么这个演示站得住

- 数值是公式算出来的，不是脚本写死的
- 同一个知识点连续作答，曲线单调性和收敛速度符合 BKT 的理论预期
- 答错不清零，展示的是模型假设，不是"演示特例"
- 教师端数据来自 `mastery_snapshot`，与真实学生同一条链路

### 8.5 防翻车清单

- 演示前在学情追踪里点"重置数据"，清空该学生的掌握度和快照
- 固定题库走本地 JSON，不依赖 LLM 和大模型额度
- 教师端刷新学情追踪即可看到新快照点（也可现场重开详情弹窗）
- 提前截图保存四幕效果，现场网络异常时直接放图
- 演示数据与真实班级分开：固定账号 `demo_math`，可随时重置

---

## 9. 实施计划

| 阶段 | 内容 | 产出 |
|---|---|---|
| P0 | 4 张表 + 交卷更新 + 快照 + 报告页数值 | 数值能跑通 |
| P1 | 教师端学生列表 + 详情 + 曲线 | 能看、能讲 |
| P2 | 对比模式 + 班级分布 + 预警 | 答辩亮点 |
| P3 | 知识点自动抽取 + 按弱项选题 | 真正影响出题 |
| P4 | 演示题库 + 重置接口 + 四幕脚本 | 演示可用 |

P0 是分水岭：做完就能在报告页看到"本次上升/下降"，其余都是展示层。

---

## 10. 验收标准

- 刷一次题，教师端 10 秒内看到数值变化
- 连续刷 3 次，曲线有 3 个点，且答对时单调上升
- 答错后掌握度下降，但不会低于 0.05
- 两个学生在同一知识点上可以并排对比
- 报告页显示本次每个知识点的 before / after
- 重置演示数据后可以重复演示，数值回到 0.30
- 证据不足（<3 次）的学生不参与排名

---

## 11. 风险与边界

| 风险 | 处理 |
|---|---|
| 知识点标注错误 | 教师端可修正关联；演示题库人工标注 |
| 题目覆盖不足 | 掌握度标注"证据不足"，不参与排名 |
| 参数不个性化 | 先全局默认，后续按班级拟合 |
| 解析题判分噪声 | 默认不参与 BKT |
| 演示数据混入真实统计 | `is_demo` 标记，班级统计默认排除 |
| 冷启动无历史 | 提供回放脚本，按历史作答重建 |
