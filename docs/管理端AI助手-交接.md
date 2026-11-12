# 管理端 AI 助手 · 交接文档

## 一、当前目标

管理端 AI 助手的三项体验优化（代码已完成，待浏览器验收）：

1. 回答「页面/功能在哪里」时，页面名必须是蓝色 Markdown 链接，点击跳到对应管理端页面。
2. 思考过程对齐手机端：灰色小字（正在理解问题 / 检索资料 / 生成回答，带百分比与耗时）+ 五颗星星闪烁动画。
3. AI 助手浮窗可按住头部拖动，限制在可视区域内。

## 二、当前运行状态

- 后端：8080（JDK 17 运行 `target/znxsgl-backend-1.0.0.jar`，最新包已包含页面链接逻辑）
- 管理端：Vite 5174 运行中（修改已热更新）
- 数据库：`znxsglTest`，审计表 `ai_agent_log`
- 测试账号：admin / 123456（管理端登录需要图形验证码）；lishiyi / 123456（教师）

## 三、已完成内容

### 后端

- `AgentPromptBuilder`：新增管理端页面路由清单；规则要求回答页面位置时用 `[页面名](路由)`，同一页面只链接第一次出现。
- 新增 `AgentPageLinker`：按角色（管理员/教师）自动把回答中的页面名转成 Markdown 链接；保护已有链接和代码块，并去掉同一页面的重复链接。
- `AgentService`：注入 `AgentPageLinker`，回答/澄清/确认类内容统一过一遍链接补全。

### 前端

- `markdown-view.tsx`：Markdown 链接统一蓝色下划线；站内 `/` 开头的链接用 `navigate` 跳转（不整页刷新），外链新窗口打开。
- `assistant-panel.tsx`：接入站内跳转回调；思考状态改为手机端同款（消息列表不再显示气泡 dots）；移动端跳转时关闭全屏助手。
- 新增 `agent-thinking-status.tsx`：五颗四点星星 + 两行灰色小字，阶段与百分比对齐 Android `AgentStarsView` / `AiAssistantFragment`。
- `globals.css`：新增 `agent-star-twinkle` 关键帧（1.4s，错峰 196ms）。
- `floating-assistant.tsx`：头部拖动、边界约束、窗口缩放回位、位置在关闭/重开后保留。

## 四、验证结果

- 后端 `mvn -q -DskipTests package` 通过并已重启；前端 `npm run build` 通过。
- 已实测接口回答（admin）：
  - 「操作记录在哪里？」→ `在左侧菜单 [AI 操作记录](/ai-records)。…`（只有一处链接）。
  - 「怎么批量导入学生？」→ 链接到 `/admin/staff?tab=students`。
  - 「发布考试在哪里操作？」→ 链接到 `/exam-homework`。
  - 「请假审批在哪个页面？」→ 链接到 `/admin/leave`。
- 5174 已能加载新组件源文件。

## 五、下一步（浏览器验收）

1. 打开 http://localhost:5174 ，登录 admin。
2. 右下角打开 AI 助手，问「操作记录在哪里？」：确认「AI 操作记录」为蓝色可点，点击后路由跳到 `/ai-records`（桌面端浮窗保留，手机宽度下自动关闭）。
3. 问任意问题：发送后观察输入框上方是否出现星星动画 + 灰色小字（理解问题 → 检索资料 → 生成回答，随耗时变化），回答返回后自动消失。
4. 按住助手头部拖动，确认位置变化且不会拖出屏幕；点「新建对话」「关闭」不受拖动影响。
5. 用教师 lishiyi 登录，确认「请假审批」链接指向 `/teacher/leave`。

## 六、重启命令

```powershell
# 前端构建
cd web-admin-react
npm run build

# 后端打包（先停掉占用 jar 的进程，否则 repackage 会因文件占用失败）
cd backend
mvn -q -DskipTests package

# 启动后端（JDK 17）
$line = Get-Content 'c:\Users\jay\Desktop\myBishe\aiStudy\.env' | Where-Object { $_ -match '^DEEPSEEK_API_KEY=' } | Select-Object -First 1
$env:DEEPSEEK_API_KEY = ($line -replace '^DEEPSEEK_API_KEY=', '').Trim()
& 'C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot\bin\java.exe' -jar target\znxsgl-backend-1.0.0.jar --frontend.local.auto-start=false --ocr.auto-start=false --embedding.local.auto-start=false
```

## 七、关键文件

后端：

- `backend/src/main/java/com/znxsgl/agent/AgentPromptBuilder.java`
- `backend/src/main/java/com/znxsgl/agent/AgentPageLinker.java`
- `backend/src/main/java/com/znxsgl/agent/AgentService.java`

前端：

- `web-admin-react/src/components/markdown-view.tsx`
- `web-admin-react/src/components/ai-assistant/assistant-panel.tsx`
- `web-admin-react/src/components/ai-assistant/agent-thinking-status.tsx`
- `web-admin-react/src/components/ai-assistant/floating-assistant.tsx`
- `web-admin-react/src/styles/globals.css`

## 八、注意事项

- 若 8080 是 IDEA 里启动的旧进程，修改后端后必须重启，否则仍是旧提示词/旧接口。
- LLM 有频率限制，连续快速提问会返回「你操作得有点快，稍等几秒再试」。
- 删除操作仍需在卡片上长按 3 秒解锁后再点击确认；记录页「恢复」是普通确认。
- `mvn package` 前先停掉 `java -jar` 进程，否则 jar 被占用导致 repackage 失败。
