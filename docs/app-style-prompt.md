# 「智学职达」App 设计风格提示词（Design Style Prompt）

> 用途：把本 App 的视觉语言复刻成提示词，用于 AI 生成同风格 UI（网页 / 移动端 / 原型图均可）。
> 使用方式：将「核心提示词」整段复制进 prompt，再附上你要生成的具体页面描述即可。

---

## 一、核心提示词（中文版，直接复制使用）

```
请以「iOS 极简扁平 + 鸿蒙字体」的设计语言生成界面，遵循以下规范：

【整体气质】
扁平无渐变、无边框堆叠、大留白、低饱和。参考 iOS 系统设置页与 Linear 的克制感，
卡片浮在浅灰画布上，靠层次和留白而非线条组织信息。

【色板】
- 画布底色：#F2F2F7（浅灰，页面背景）
- 卡片/表面：#FFFFFF（一级）、#F9F9FB（二级）、#F0F0F4（三级/骨架屏灰 #E8E8ED）
- 主色：#0A84FF（iOS 系统蓝，唯一强调色，用于按钮/选中态/链接/图标）
- 主色辅助：#E5F1FF（浅蓝底，用于标签/选中项底色）、#A9D2FF（浅蓝填充态）
- 文字：#1D1D1F（主文字）、#6E6E73（次要）、#8E8E93（辅助/未选中）、#C7C7CC（占位/箭头）
- 语义色：成功 #34C759、警告 #FF9500、危险 #FF3B30（各配 5% 淡底：#E8F8EE / #FFF3E2 / #FFECEB）
- 分割线：#E5E5EA，统一 1px；禁止使用彩色分割线

【字体】
HarmonyOS Sans SC（中文无衬线，免费商用）。字阶只有 7 档：
28（大标题，medium，字距 -0.02）/ 22（页面标题，medium）/ 17（区块标题，medium）/
16（正文）/ 14（次要文字）/ 12（说明/标签）/ 11（底部导航）。
强调用 Medium（500），不用 Bold 加粗正文；数字与中文同族。

【圆角】按钮/输入框 8px；小卡片/聊天气泡 10px；标准卡片 14px；大弹窗 20px；
胶囊（导航/开关/标签）全圆角 999px。同类组件圆角必须一致。

【阴影与描边】
阴影极轻：卡片 elevation 2-4px 的柔和投影，或大范围低透明度（rgba(0,0,0,.05)，偏移 20px 模糊 30px）；
元素之间靠画布灰与卡片的对比区分层级，不靠描边。唯一允许的描边是 1px #E5E5EA 发丝线。

【组件】
- 底部导航：悬浮白色胶囊（高 72px，距边 16px，全圆角，微透明白 #FAFFFFFF + 1px 发丝线 + 柔影），
  无选中背景块，选中仅图标和文字变主蓝，未选中 #8E8E93
- 卡片：白底、14px 圆角、16px 内边距，浮在 #F2F2F7 上
- 底部弹出面板（iOS Action Sheet 双卡结构）：主内容白卡（顶部两角 20px 圆角）
  + 独立的「取消」白卡（顶部两角直角、底部两角 14px），45% 黑遮罩，行间 1px 发丝线，按下浅灰反馈
- 状态标签：胶囊 pill，淡色底 + 同色系文字（蓝/绿/橙/紫），12px
- 开关：iOS 样式，46×24，关=灰 #838383 开=绿 #34C759，白色圆钮带投影，回弹动效
- 列表行：左标签 + 右值/箭头（16px 细描边 chevron，#C7C7CC），行高 ≥44px
- 输入框：白底 + 1px 发丝线 + 8px 圆角，高 50px
- 空态/骨架屏：浅灰 #E8E8ED 圆形+条形占位块，750ms 呼吸动画（透明度 1.0↔0.35）

【动效】
克制而干净，全部 ease-out / decelerate：
- 页面切换淡入淡出 180ms；元素入场 300-550ms 减速
- 按钮点击：虹膜填充——浅蓝色从按钮四周边缘向中心合拢 500ms（不是从中心扩散），
  文字颜色在进度前 60% 内完成渐变；无按压缩放
- 弹出面板自底部滑入 180-250ms + 遮罩淡入；开关 220ms 带轻微回弹（Overshoot）

【交互规则】
- 所有可点击目标 ≥44px；间距遵循 4px 网格
- 全屏沉浸场景（如答题）隐藏悬浮导航，页面背景延伸到屏幕底
- 未读提示：导航图标右上角红色数字角标，进入对应页即清除
- 图标一律 2px 线性描边（Lucide 风格，24 视口），禁止 emoji 做图标
- 禁止：渐变、粗边框、彩色分割线、Material 默认选中胶囊、系统默认 AlertDialog、
  密集小字（<12px 正文）、深色模式（当前为纯浅色定位）
```

---

## 二、核心提示词（英文版，适合 Midjourney / v0 / Galileo 等工具）

```
Design a mobile UI in a flat, minimal iOS-inspired style with these exact specs:

STYLE: Ultra-clean flat design, no gradients, no borders, generous whitespace,
low saturation. Inspired by iOS Settings and Linear. Cards float on a light gray
canvas; hierarchy comes from elevation and spacing, not lines.

COLORS: canvas #F2F2F7; surfaces #FFFFFF / #F9F9FB; single accent #0A84FF (iOS blue);
soft accent backgrounds #E5F1FF, #A9D2FF; text #1D1D1F / #6E6E73 / #8E8E93 / #C7C7CC;
semantic green #34C759, orange #FF9500, red #FF3B30 (each with 5% tint background);
hairline dividers #E5E5EA at 1px only — no colored dividers.

TYPOGRAPHY: HarmonyOS Sans SC. Scale: 28/22/17/16/14/12/11px. Medium (500) weight
for titles, never bold body text. Tight letter-spacing (-0.02) on large titles.

RADII: buttons & inputs 8px; small cards 10px; standard cards 14px; large dialogs
20px; pills (navbar/switches/tags) fully rounded.

SHADOWS: extremely subtle — 2-4px elevation soft shadows, or wide low-opacity
(20px 20px 30px rgba(0,0,0,.05)). Never borders.

COMPONENTS: floating pill bottom nav (72px tall, 16px margins, translucent white
#FAFFFFFF + hairline stroke, selected item tinted blue with no background pill);
white cards (14px radius, 16px padding) on gray canvas; iOS action-sheet style
bottom sheets (rounded-top main card + separate square-top cancel card, 45% dim);
pill status tags in tinted backgrounds; iOS-style toggle (46x24, gray→green
#34C759); skeleton loading placeholders (#E8E8ED circle + bars, 750ms breathing).

MOTION: restrained, all ease-out. Button press: "iris fill" — light blue (#A9D2FF)
closes in from all edges toward the center over 500ms (edge-to-center, NOT
center-outward); text color transitions within the first 60% of the fill; no press
scale. Sheets slide up 180-250ms; toggles 220ms with slight overshoot.

RULES: touch targets ≥44px; 4px spacing grid; 2px-stroke line icons (Lucide style);
no emoji icons; no gradients; no system-default dialogs; light mode only.
```

---

## 三、Token 速查表（给开发/设计直接抄）

| 类别 | Token | 值 |
|---|---|---|
| 画布 | canvas | #F2F2F7 |
| 表面 | surface_1 / surface_2 / surface_3 | #FFFFFF / #F9F9FB / #F0F0F4 |
| 主色 | primary / hover / disabled / soft | #0A84FF / #0071E3 / #A8CBF0 / #E5F1FF |
| 文字 | ink / ink_muted / ink_subtle / ink_tertiary | #1D1D1F / #6E6E73 / #8E8E93 / #C7C7CC |
| 发丝线 | hairline / hairline_strong | #E5E5EA / #D1D1D6 |
| 语义 | success / warning / danger | #34C759 / #FF9500 / #FF3B30 |
| 语义淡底 | success_bg / warning_bg / danger_bg | #E8F8EE / #FFF3E2 / #FFECEB |
| 交互填充 | fill_light_blue | #A9D2FF |
| 字体 | 全局 | HarmonyOS Sans SC（Regular/Medium/Bold） |
| 字阶 | LargeTitle→Nav | 28 / 22 / 17 / 16 / 14 / 12 / 11 sp |
| 圆角 | radius_sm / md / lg / xl / full | 8 / 12 / 14 / 20 / 999 |
| 动效 | 微交互 / 面板 / 填充 / 骨架 | 180ms / 250ms / 500ms ease-out / 750ms 呼吸 |
| 触摸 | 最小目标 | 44dp（导航胶囊 72dp） |

---

## 四、风格要点说明（为什么这样定）

1. **单一强调色**：全 App 只有 iOS 蓝 `#0A84FF` 一个强调色，状态语义用绿/橙/红三色辅助——信息层级靠灰度深浅，不靠颜色数量。
2. **"边缘向中心"的虹膜填充**是本 App 的签名动效（复刻自 uiverse red-stingray-4 的 inset box-shadow 机制），用于所有选项类按钮，方向与常见"中心扩散"相反，识别度高。
3. **双卡 Action Sheet**（主卡 + 直角顶边的独立取消卡）是所有弹窗的统一形态：学期选择、周选择、设置都用它。
4. **悬浮胶囊导航**是 App 最强的视觉记忆点：无选中背景块，仅颜色变化，配合答题时整体隐藏实现全屏沉浸。
5. **字体即气质**：HarmonyOS Sans SC 的中性笔画搭配 Medium 字重标题，是"鸿蒙版 iOS"观感的核心。
