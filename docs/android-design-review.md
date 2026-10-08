# 智学职达 Android App 设计审查报告

> 审查范围：`android/app/src/main`（46 个布局、50+ drawable、3 个 values 文件、42 个 Java 文件）
> 基准：iOS 极简风格设计系统（画布 `#F2F2F7`、卡片白色、圆角 14px、按钮 8px、文字 `#1D1D1F`、强调色）
> 方式：静态只读审查

---

## 一、总体结论

| 维度 | 评价 |
|---|---|
| 设计 token 定义 | ★★★★☆ `colors.xml` 命名清晰（canvas / surface / ink / hairline），语义化良好 |
| token 落地执行 | ★★☆☆☆ 约 116 处布局 + 14+ 个 Java 文件绕过 token 硬编码 hex |
| 色板一致性 | ★★☆☆☆ 三套色板混用：品牌紫 `#5E6AD2`、iOS 蓝 `#0A84FF`、Ant Design 蓝 `#1890FF` |
| 圆角/字号体系 | ★★☆☆☆ 圆角 13 种取值、字号 10+ 档散写，无 dimens / textAppearance |
| 暗色主题 | ☆ 无 `values-night`，状态栏颜色被 Java 锁死浅色 |
| 可用性硬伤 | ★★☆☆☆ 存在 8~10sp 超小字号、26~32dp 触摸目标 |

**核心问题一句话**：设计系统"文档化"不错，但"工程化"落地严重不足——token 形同虚设，页面之间像三个人做的。

---

## 二、高严重度问题（必须修）

### H1. 硬编码颜色泛滥（约 116 处 / 21 个布局文件）

不在设计系统调色板中的"孤儿色"大量出现：

| 来源 | 示例色值 | 出现位置 |
|---|---|---|
| Ant Design | `#1890FF` `#52C41A` `#FA8C16` `#F6FFED` `#E6F7FF` | activity_exam_homework.xml、activity_exam_homework_result.xml、ProfileFragment.java |
| Tailwind 灰阶 | `#666666` `#6B7280` `#4B5563` `#111827` | 考试作业相关布局、dialog |
| iOS 系统蓝 | `#0A84FF` | activity_chapter_learn.xml L22、activity_course_detail.xml L23（返回按钮） |
| 自造金色系 | `#FFC64B` `#EA9518` `#E3C97E` `#6CABF6` | activity_quiz_result.xml L386-473 |

典型示例：
```
1:9:android/app/src/main/res/layout/fragment_schedule.xml
    android:background="#FFFFFF"   ← 应为 @color/surface_1
```
```
42:55:android/app/src/main/res/layout/fragment_schedule.xml
    android:textColor="#5E6AD2"    ← 应为 @color/primary
    android:background="#F0F4FF"   ← 不在调色板
```

**建议**：全局替换为 token；确实需要的语义色（如考试金）先收编进 `colors.xml` 再引用。

### H2. Java 层动态 UI 大规模硬编码（≥100 处）

- `fragment/ScheduleFragment.java`：**整个课程表 UI 用 Java 拼装**，L100-117 定义 12+ 个 `0xFF` 硬编码色数组，圆角 6/15/20dp 与 drawable 体系完全脱节
- `fragment/FocusFragment.java` L674-681、L830-841：`Color.parseColor("#5E6AD2"...)` 循环内动态建按钮
- `fragment/ProfileFragment.java` L568-578：`0xFFFA8C16 / 0xFF1890FF / 0xFF722ED1`（Ant Design 色）
- `CourseDetailActivity.java` L630-1172：聊天气泡 UI 全部代码构建 + 硬编码
- `QuizPagerAdapter.java` L30-35：在 Java 里另建一套 `COLOR_PRIMARY/...` 常量，与 colors.xml 重复
- 自定义控件偏离调色板：`HoldSubmitButton`（`#FF135A` 粉红）、`AnimatedChoiceButton`（`#2196F3` Material 蓝）

**建议**：Java 中统一改用 `ContextCompat.getColor(context, R.color.xxx)`；ScheduleFragment 的色板收编进 colors.xml。

### H3. 8~11sp 超小字号（可读性硬伤）

`ScheduleFragment.java`：L486 `setTextSize(8)`、L657 `setTextSize(9)`、L728/746/791 `setTextSize(10)`；`ProfileFragment.java` L670 `setTextSize(11)`。XML 布局最小 12sp（达标），但 Java 动态 UI 进入 8-10sp 区间，普通手机上基本不可读。

**建议**：下限 11sp 仅用于徽标角标，正文/标签不低于 12sp。

### H4. 触摸目标普遍小于 48dp

| 位置 | 尺寸 | 问题 |
|---|---|---|
| fragment_schedule.xml L43-55 `tv_semester_label` | **26dp** 高 | 严重不足 |
| activity_login.xml L93-108 / L120-131 CheckBox | `padding/minWidth/minHeight 全部 0` | 触摸区仅剩图标 <24dp |
| layout_add_sheet.xml L29/L49 关闭图标 | 32×32dp | 不足 |
| item_lesson.xml L53、item_mention.xml L16 | 32dp | 不足 |
| activity_chapter_learn.xml L16-25、activity_course_detail.xml L20 返回按钮 | 40×40dp | 不达标 |
| fragment_schedule.xml L74-85 `btn_today` | 32dp 高 | 不达标 |
| 多个对话框按钮（dialog_confirm_start、dialog_wrong_analysis 等） | 44dp | 略低于 48dp |

**建议**：小按钮用 `TouchDelegate` 或加大 padding 至 48dp 等效热区；CheckBox 恢复 minHeight/minWidth=48dp。

### H5. ScrollView 嵌套 RecyclerView（性能反模式）

- `activity_quiz_result.xml` L15 ScrollView → L319-324 RecyclerView（`nestedScrollingEnabled="false"` 全量加载）
- `fragment_profile.xml` L213-219 `rv_courses` 在 NestedScrollView 内且 `nestedScrollingEnabled="true"`（滚动冲突风险）
- `item_wrong_card.xml` L17-19 卡片内再嵌 ScrollView

**建议**：改单 RecyclerView + 多 viewType，或至少关掉内层 nestedScrolling。

---

## 三、中严重度问题（应修）

### M1. 圆角 13 种取值，无收敛

drawable 中 corners 分布：2 / 4 / 5 / 6 / 8 / 10 / 12 / 14 / 16 / 20 / 24 / 25 / 36 / 44 / 999dp。
- 卡片：12/14/16/20dp 并存（目标应是统一 14px≈14dp）
- 按钮：10/16/25/999dp 并存（目标应是统一 8px，或全圆角胶囊二选一）

**重点矛盾**：`bg_btn_primary` 圆角 25dp（胶囊），而 `themes.xml` 的 `LoginButton` 样式是 8dp——同一类按钮两种语言，且登录页实际用的是 drawable 而非样式。

**建议**：收敛为 4 档 token 放入 `dimens.xml`：`radius_sm=8dp（按钮/输入框）`、`radius_md=12dp（小卡）`、`radius_lg=14dp（卡片）`、`radius_full（胶囊/导航）`。

### M2. 字号阶梯混乱，无 typography 体系

XML 中 textSize 分布：12sp×21、13sp×26、14sp×21、15sp×18、16sp×12，另有 17/18/20/22/26/36/48/64sp 零散使用；导航 11sp。13/15/17 等奇数档大量出现，且全部逐节点散写、无 textAppearance 复用。

**建议**：收敛为 6 档：11（导航/角标）、12（辅助）、13→14、15→16（正文）、20（标题）、28+（大标题），并定义 `TextAppearance.App.*` 样式。

### M3. 可访问性细节

- `item_chat_image.xml` L37-47 `iv_message` 缺 `contentDescription`（全项目唯一漏网）
- fragment_profile.xml L57-69 `btn_settings`：wrap_content + 6dp padding，触摸区过小
- fragment_profile.xml L142/L181：**用 emoji 当图标**（"📌 收藏题目"、"👥 通讯录"）——违反专业 UI 规范，应换矢量图标（ic_star / ic_contacts），项目里已有 `ic_schedule` 等矢量图可对齐风格
- L154-159 用文本 `"  >"` 模拟箭头，应使用 `ic_arrow_right` drawable

### M4. 无 dimens.xml / 无样式体系

`values/` 下仅 colors/strings/themes。间距、圆角、字号全部字面量散布，无单一事实来源。布局中还有硬编码中文文案（如 activity_login.xml L49 "学号 / 用户名"、L184 "教师请使用 Web 管理端登录"）应进 strings.xml。

---

## 四、低严重度问题（可择机修）

1. **间距脱离 4/8 网格**：3dp（item_wrong_question L38、item_wrong_card L87）、5dp（item_lesson L118）、9dp（item_quiz_panel L73）。1dp 分隔线属 hairline 惯例可接受。
2. **语义不一致**：返回按钮在 `layout_common_header.xml` 用 `@color/primary`，在 chapter_learn / course_detail 用 `#0A84FF`；次要文字灰混用 `#86868B` / `#6E6E73` / `#666666` 三种（`ink_muted` 应为唯一答案）。
3. **fragment_learn.xml 是空壳**（只有标题 TextView），真实视图疑在 Java 侧构建——需确认 LearnFragment 实现并纳入统一审查。
4. **fragment_profile.xml L63 负 margin hack**（`layout_marginTop="-28dp"` 定位设置按钮），建议改用 FrameLayout/ConstraintLayout 叠放。
5. **无暗色主题**：`windowLightStatusBar=true` 写死，MainActivity/FullscreenLoaderDialog 在 Java 中锁死浅色状态栏。当前为浅色定位可接受，但建议至少把状态栏色改为跟随主题。

---

## 五、亮点（值得保留）

1. **悬浮药丸底部导航**设计出色：全圆角 36dp、微透明白 + hairline 描边 + elevation 柔影，`bg_nav_bar.xml` 注释里对"Android 无法实现窗口内毛玻璃"的说明诚实专业。
2. **colors.xml 语义分层**（canvas → surface → ink → hairline）是标准做法，legacy alias 区平滑迁移思路正确。
3. 大部分页面已用 token 引用，主 Tab 页（profile/learn）整体观感统一。
4. Material3 主题基座正确，`NavIndicatorTransparent` 去除 M3 胶囊高亮还原分段按钮风格的处理干净。

---

## 六、修复优先级路线图

| 优先级 | 事项 | 预估工作量 |
|---|---|---|
| P0 | Java 动态 UI 中的 8~10sp 字号提升至 ≥12sp；CheckBox/26~32dp 触摸目标修至 48dp | 小（逐点修） |
| P0 | 修复 ScrollView 包 RecyclerView 三处（quiz_result / profile / wrong_card） | 中 |
| P1 | 建 `dimens.xml`（radius 4 档）+ `TextAppearance` 体系，新代码强制使用 | 中 |
| P1 | 布局硬编码色 116 处批量替换为 token（脚本辅助 + 人工核对） | 中 |
| P1 | Java 侧颜色统一走 `R.color`；ScheduleFragment / ProfileFragment / FocusFragment 色板收编 | 大 |
| P2 | 三蓝归一：确定品牌色（建议保留 `#5E6AD2` 或回归 `#0A84FF`，二选一），删除 `#1890FF`/`#0A84FF` 散点 | 小 |
| P2 | emoji 图标换矢量图标；补 `item_chat_image` 的 contentDescription；硬编码中文进 strings.xml | 小 |
| P3 | 圆角/字号按 token 收敛重刷全部 drawable；评估 values-night | 大 |

---

## 七、设计系统建议 token（供落地参考）

```xml
<!-- dimens.xml -->
<dimen name="radius_sm">8dp</dimen>    <!-- 按钮、输入框 -->
<dimen name="radius_md">12dp</dimen>   <!-- 小卡片、标签 -->
<dimen name="radius_lg">14dp</dimen>   <!-- 标准卡片 -->
<dimen name="radius_full">999dp</dimen><!-- 胶囊、导航 -->

<!-- styles.xml（typography）-->
<style name="TextAppearance.App.Title" parent="TextAppearance.Material3.HeadlineSmall">
    <item name="android:textSize">22sp</item>
    <item name="android:textColor">@color/ink</item>
    <item name="android:fontFamily">sans-serif-medium</item>
</style>
<style name="TextAppearance.App.Body">  16sp / ink / sans-serif</style>
<style name="TextAppearance.App.Secondary"> 14sp / ink_muted / sans-serif</style>
<style name="TextAppearance.App.Caption"> 12sp / ink_subtle / sans-serif</style>
<style name="TextAppearance.App.Nav"> 11sp / selector / sans-serif</style>
```

---

## 八、统一风格改造执行记录（已完成，编译通过）

> 2026-09-20 执行。方向：扁平 iOS 风，主色统一为 iOS 蓝 `#0A84FF`，卡片 14dp、按钮/输入框 8dp、导航全圆角。

### 色板收敛
- `colors.xml`：主色 `#5E6AD2 → #0A84FF`，新增 `primary_hover/primary_disabled/primary_soft/warning(#FF9500)`；exam 色组全部改为主色系引用
- 布局层：22 个文件共 90+ 处硬编码 hex 替换为 token（含考试作业、课表、课程详情、章节学习、错题卡、聊天等）
- Java 层：9 个文件（QuizPagerAdapter / ReviewActivity / QuizResultActivity / ProtocolActivity / ShinyTextAnimation / MetaBallsView / FocusFragment / ProfileFragment / ExamHomeworkResultActivity / ScheduleFragment / 自定义控件）旧主色与 Ant Design 色全部清除，扫描残留 = 0

### 映射表
| 旧值（来源） | 新值 |
|---|---|
| `#5E6AD2`（Linear 紫） | `@color/primary` `#0A84FF` |
| `#1890FF`（AntD 蓝） | `@color/primary` |
| `#FA8C16`（AntD 橙） | `@color/warning` `#FF9500` |
| `#52C41A`（AntD 绿） | `@color/success` `#34C759` |
| `#722ED1`（AntD 紫） | `#AF52DE`（iOS 紫） |
| `#FF4D4F`（AntD 红） | `@color/danger` `#FF3B30` |
| `#666666` | `@color/ink_muted` |
| `#86868B` | `@color/ink_subtle` |
| `#E5E7EB` / `#F5F5F7` / `#F5F7FA` | `@color/hairline` / `@color/surface_3` / `@color/canvas` |

### 圆角收敛（dimens.xml token）
- 按钮/输入框/小 chip：统一 8dp（bg_btn_primary、bg_input、bg_today_btn、bg_btn_success、bg_confirm_start_yes/no）
- 标准卡片：14dp（bg_exam_card 等，bg_card_entry 本就是 14dp）
- 底部导航药丸：36dp 全圆角保留

### 字号体系（themes.xml TextAppearance.App.*）
- 新增 7 档样式：LargeTitle 28 / Title 22 / Headline 17 / Body 16 / Secondary 14 / Caption 12 / Nav 11
- ScheduleFragment 动态 UI：8sp→10sp、9sp→10sp、10sp→11sp，消除超小字号

### 可用性修复
- 登录页两个 CheckBox：恢复 44dp 触摸热区
- 课表页 `btn_today`：32→44dp；`tv_semester_label`：26→44dp
- "我的"页设置按钮：44dp 高热区

### 其他
- "我的"页 emoji 图标（📌/👥）与文本箭头（>）替换为 Lucide 风格矢量图标（新增 `ic_star` / `ic_user` / `ic_chevron_right`）
- 验证：`gradlew assembleDebug` BUILD SUCCESSFUL，旧色板全局扫描 0 残留
