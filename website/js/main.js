/* ============ 智学职达官网 · 交互 ============ */

// ===== 「开始」/「立即体验」→ 管理端登录页 =====
// 本地开发（localhost）→ Vite 管理端 :5174；线上部署 → Docker Nginx :8081
(() => {
  const host = window.location.hostname;
  if (!host) return;  // file:// 直接打开时跳过，保留锚点行为
  const port = (host === 'localhost' || host === '127.0.0.1') ? '5174' : '8081';
  const adminLoginUrl = 'http://' + host + ':' + port + '/login';
  ['navCtaLink', 'startLink'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.href = adminLoginUrl;
  });
})();

// ===== 导航滚动态 =====
const nav = document.getElementById('nav');
const onScroll = () => {
  if (window.scrollY > 24) nav.classList.add('scrolled');
  else nav.classList.remove('scrolled');
};
window.addEventListener('scroll', onScroll, { passive: true });
onScroll();

// ===== 移动端菜单 =====
const navToggle = document.getElementById('navToggle');
if (navToggle) {
  navToggle.addEventListener('click', () => nav.classList.toggle('open'));
  document.querySelectorAll('#navLinks a').forEach(a =>
    a.addEventListener('click', () => nav.classList.remove('open')));
}

// ===== 滚动渐入：改由全站多光标搭建引擎负责，见文件末尾 initSiteBuild =====

// ===== DeepSeek 出题打字动画 =====
const typeDemo = document.getElementById('typeDemo');
const genQuestion = document.getElementById('genQuestion');
const genState = document.getElementById('genState');

const JSON_SNIPPET = `[
  { "type": "单选", "question": "带宽的单位是什么？",
    "options": ["A. 字节每秒", "B. 比特每秒*", "C. 帧每秒", "D. 报文每秒"] },
  { "type": "判断", "question": "吞吐量总是等于带宽。", "options": ["错误*"] },
  { "type": "解析", "question": "请解释带宽与吞吐量的区别。" },
  ... 共 15 题 (7单选 · 3判断 · 3解析 · 2填空)
]`;

const QUESTION_HTML = `
  <div class="qt">AI 生成 · 第 1 题 · 单选</div>
  <div class="qq">带宽的单位是什么？</div>
  <div class="qo">A. 字节每秒</div>
  <div class="qo ok">B. 比特每秒 ✓（正确答案）</div>
  <div class="qo">C. 帧每秒</div>
  <div class="qo">D. 报文每秒</div>`;

let demoPlayed = false;

function playDemo() {
  if (!typeDemo || demoPlayed) return;
  demoPlayed = true;

  const stages = [
    ['正在检索章节内容…', 1400],
    ['DeepSeek V4 Flash 生成中…', 1600],
    ['出题完成 · 缓存已更新（15 分钟）', 0]
  ];
  let stage = 0;
  const nextStage = () => {
    if (stage < stages.length) {
      genState.textContent = stages[stage][0];
      const wait = stages[stage][1];
      stage++;
      if (wait > 0) setTimeout(nextStage, wait);
    }
  };
  nextStage();

  // 1.2s 后开始流式输出 JSON
  setTimeout(() => {
    let i = 0;
    const cur = document.createElement('span');
    cur.className = 'cur';
    typeDemo.appendChild(cur);
    const timer = setInterval(() => {
      // 每帧输出 2~4 个字符，模拟流式生成
      i += 2 + Math.floor(Math.random() * 3);
      typeDemo.textContent = JSON_SNIPPET.slice(0, i);
      typeDemo.appendChild(cur);
      if (i >= JSON_SNIPPET.length) {
        clearInterval(timer);
        cur.remove();
        // 展示渲染后的题目卡片
        setTimeout(() => {
          genQuestion.innerHTML = QUESTION_HTML;
          genQuestion.classList.add('show');
        }, 500);
      }
    }, 34);
  }, 1200);
}

// 深色 AI 区块的解答演示由全站搭建引擎在「画出演示窗口」后触发（见 initSiteBuild）

// ===== 技术架构 3×3×3 立方体（严格按 uiverse csemzepp 结构生成）=====
// 结构：container > .cube×3（深度副本）> .cl×3（--x 列偏移）> span×3（行堆叠，--i 层级）
const techMosaic = document.getElementById('techMosaic');
if (techMosaic) {
  // 技术详情（按视觉从上到下：前端 → 后端 → 底层模型）
  const TECHS = [
    { n: 'Android App', d: '基于 Java 与 Android SDK 原生开发的学生端应用，采用 MVVM 分层设计，集成 JWT 鉴权、OkHttp 网络层与 WebSocket 专注度上报，覆盖刷题、错题、测评全学习场景。' },
    { n: 'React 管理端', d: '基于 React 18 + TypeScript + Vite 构建的教师管理后台，使用 Zustand 状态管理、TanStack Table 数据表格与 React Router 权限路由，支持组件级懒加载。' },
    { n: '微信小程序', d: '基于 uni-app（Vue 3 语法）跨端编译，复用统一 REST API 与会话体系，提供轻量化的课程浏览、刷题与学习入口，无需安装即可触达学生。' },
    { n: 'Tailwind CSS', d: '原子化 CSS 引擎，通过设计令牌（Design Tokens）统一多端色彩、间距与圆角规范，配合 PostCSS 按需Tree-Shaking，生产样式体积减少约 70%。' },
    { n: 'ECharts 可视化', d: '基于 Apache ECharts 渲染六维能力雷达图、学情趋势折线图与班级对比柱状图，支持大数据量 Canvas 降采样与容器响应式自适应。' },
    { n: 'Spring Boot', d: '后端采用 Controller / Service / Mapper 三层架构，统一 REST API 规范与全局异常处理，集成 MyBatis-Plus ORM、HikariCP 连接池与声明式事务管理。' },
    { n: 'RAG 检索增强', d: '检索增强生成管线：BGE-M3 将章节内容向量化入库，出题时按已学章节 Top-K 召回最相关教材片段注入 Prompt，从源头约束大模型不超纲。' },
    { n: 'JWT 鉴权 + 限流', d: 'HS256 签名的无状态令牌认证实现接口鉴权，配合令牌桶算法对登录、AI 出题等敏感接口做并发限流，防止恶意请求与资源耗尽。' },
    { n: 'AI 语义判分', d: '主观题由大模型按「要点覆盖度 + 语义一致性」双维度评分，辅以文本相似度算法兜底；AI 服务不可用时自动降级，保障判分链路可用性。' },
    { n: 'BGE-M3', d: 'BGE-M3（BAAI General Embedding M3），是北京智源人工智能研究院（BAAI）开源的多语言全能文本嵌入（Embedding）模型。支持多语言、多粒度（稠密 + 稀疏 + ColBERT）混合检索，单模型即可覆盖语义检索全场景，本项目中负责章节教材与用户查询的向量化。' },
    { n: 'DeepSeek V4 Flash', d: 'DeepSeek 旗舰级高速推理模型，承担智能出题、错题归因解析与答疑对话三大核心任务，输出 JSON Schema 约束的结构化试题并支持流式响应，大幅降低首字延迟。' },
    { n: 'MySQL 8.0', d: '核心业务数据主库，存储用户、课程、题库与学习行为记录；utf8mb4 字符集 + 组合索引 + 出题指纹缓存表设计，将重复大模型调用减少约 90%。' },
    { n: 'Docker Compose', d: '四容器编排（Nginx / Spring Boot / MySQL / Embedding Service），一键构建部署与健康检查，数据卷持久化，支持 --profile 按需启用 AI 向量化能力。' }
  ];

  // 生成 DOM：3 个 .cube × 3 个 .cl(--x: -1/0/1) × 3 个 span(--i: 3/2/1)
  const X_POS = [-1, 0, 1];
  const I_POS = [3, 2, 1];
  X_POS.forEach(() => {
    const cube = document.createElement('div');
    cube.className = 'cube';
    X_POS.forEach(x => {
      const cl = document.createElement('div');
      cl.className = 'cl';
      cl.style.setProperty('--x', x);
      I_POS.forEach(i => {
        const s = document.createElement('span');
        s.style.setProperty('--i', i);
        cl.appendChild(s);
      });
      cube.appendChild(cl);
    });
    techMosaic.appendChild(cube);
  });

  // 悬停详情：视觉顺序（左上 → 右下）循环分配技术条目
  // —— 右侧粒子文字面板（原生移植 reactbits ParticleText）——
  const canvas = document.getElementById('particleCanvas');
  const panel = document.getElementById('particlePanel');
  const ctx = canvas ? canvas.getContext('2d') : null;
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  // density 为整数采样步长（非整数会让像素索引取到 undefined，导致文字采样为空）
  const PCFG = { size: 1.65, density: 2, color: '#ffffff', highlight: '#8b5cf6', scatter: 150, gather: 1400, stagger: 380, repel: 38, radius: 110, drift: .7, font: 120, weight: 800 };
  // 性能·设备自适应：低端核/移动端加大采样步长并压低粒子上限（粒子数 ≈ 文字面积/步长²）
  const LOW_END = (navigator.hardwareConcurrency || 4) <= 4 || /Mobi|Android|iPhone/i.test(navigator.userAgent);
  if (LOW_END) PCFG.density = 3;
  const MAX_PARTICLES = LOW_END ? 10000 : 32000;

  const PT = {
    particles: [], raf: null, build: 0,
    gathering: false, gatherStart: 0, frame: 0,
    w: 0, h: 0, dpr: 1, visible: true,
    pointer: { active: false, x: 0, y: 0, sx: 0, sy: 0 }
  };

  const clamp = (v, a, b) => Math.min(Math.max(v, a), b);
  const hexToRgb = hex => {
    const c = hex.replace('#', '');
    return { r: parseInt(c.slice(0, 2), 16), g: parseInt(c.slice(2, 4), 16), b: parseInt(c.slice(4, 6), 16) };
  };
  const mixRgb = (a, b, t) => ({
    r: Math.round(a.r + (b.r - a.r) * t),
    g: Math.round(a.g + (b.g - a.g) * t),
    b: Math.round(a.b + (b.b - a.b) * t)
  });
  const rgbCss = rgb => `rgb(${rgb.r}, ${rgb.g}, ${rgb.b})`;

  // 调色板：白→紫量化 17 级。渲染时按色索引批量切换 fillStyle，
  // 状态切换次数从「每粒子一次（2.4 万次/帧）」降为「每帧最多 17 次」
  const PALETTE_N = 17;
  const PALETTE = (() => {
    const base = hexToRgb(PCFG.color), hl = hexToRgb(PCFG.highlight), arr = [];
    for (let i = 0; i < PALETTE_N; i++) arr.push(rgbCss(mixRgb(base, hl, i / (PALETTE_N - 1))));
    return arr;
  })();

  const startGather = fromScatter => {
    if (!PT.particles.length) return;
    PT.gatherStart = performance.now();
    PT.particles.forEach(p => {
      if (fromScatter && !reducedMotion) {
        const ang = p.seed * Math.PI * 2;
        const dist = PCFG.scatter * (0.35 + p.depth * 0.75);
        p.x = p.tx + Math.cos(ang) * dist + (p.depth - 0.5) * PCFG.scatter * 0.55;
        p.y = p.ty + Math.sin(ang) * dist + (p.seed - 0.5) * PCFG.scatter * 0.55;
      }
      p.sx = p.x; p.sy = p.y;
      p.delay = reducedMotion ? 0 : p.seed * PCFG.stagger;
    });
    PT.gathering = true;
    if (PT.raf === null) PT.raf = requestAnimationFrame(render);
  };

  const render = now => {
    // 性能·静止期降帧：无聚合动画且鼠标未交互时按 ~30fps 渲染。
    // 必须放在 clearRect 之前（跳过的帧不清屏，否则会闪黑），且直接读 PT.gathering
    // （render 内的 const gathering 声明在后，提前访问会进入暂时性死区抛错）。
    const staticMode = !PT.gathering && !(PT.pointer.active && !reducedMotion) && !reducedMotion;
    if (staticMode && now - (PT.lastDraw || 0) < 31) { PT.raf = requestAnimationFrame(render); return; }
    PT.lastDraw = now;

    const ps = PT.particles, n = ps.length;
    ctx.clearRect(0, 0, PT.w, PT.h);

    PT.pointer.sx += (PT.pointer.x - PT.pointer.sx) * 0.18;
    PT.pointer.sy += (PT.pointer.y - PT.pointer.sy) * 0.18;
    const px = PT.pointer.sx, py = PT.pointer.sy;
    const pointerOn = PT.pointer.active && !reducedMotion;
    const R = PCFG.radius, R2 = R * R, repel = PCFG.repel;
    const gathering = PT.gathering;
    const follow = reducedMotion ? 1 : 0.22;
    const t = now * 0.001;
    // 漂移的三角函数隔帧计算并缓存：follow=0.22 的插值下视觉完全无差
    const driftOn = !reducedMotion && ((PT.frame++ & 1) === 0);
    const drift = PCFG.drift;
    // 聚合期边缘羽化距离：靠近画布四边的粒子渐隐，消除散开时被画布裁出的矩形硬边
    const edgeFadeDist = Math.min(PT.w, PT.h) * 0.18;
    let complete = true;
    let curColor = -1;

    ctx.globalAlpha = 1;
    for (let k = 0; k < n; k++) {
      const p = ps[k];
      let bx = p.tx, by = p.ty, progress = 1, eased = 1;
      if (gathering) {
        const local = (now - PT.gatherStart - p.delay) / PCFG.gather;
        if (local < 1) { complete = false; progress = local > 0 ? local : 0; }
        eased = 1 - (1 - progress) * (1 - progress) * (1 - progress); // easeOutCubic 内联
        bx = p.sx + (p.tx - p.sx) * eased;
        by = p.sy + (p.ty - p.sy) * eased;
      } else if (!reducedMotion) {
        if (driftOn) {
          p.ddx = Math.sin(t * 0.9 + p.ph1) * drift * p.depth;
          p.ddy = Math.cos(t * 0.75 + p.ph2) * drift * p.depth;
        }
        bx += p.ddx; by += p.ddy;
      }
      if (pointerOn) {
        // 平方距离判据代替 hypot；(1-d/R)² 代数展开代替 pow
        const dx = bx - px, dy = by - py;
        const d2 = dx * dx + dy * dy;
        if (d2 > 0.01 && d2 < R2) {
          const dist = Math.sqrt(d2);
          const f = 1 - dist / R;
          const force = f * f * repel;
          bx += (dx / dist) * force;
          by += (dy / dist) * force;
        }
      }
      p.x += (bx - p.x) * follow;
      p.y += (by - p.y) * follow;

      // alpha 仅在聚合期逐粒子变化；静止期为 1，不再设置。
      // 越靠近画布边缘越透明，且粒子越接近目标点越恢复不透明，文字成形后完全不受影响。
      if (gathering) {
        const edge = Math.min(p.x, PT.w - p.x, p.y, PT.h - p.y);
        const ef = clamp(edge / edgeFadeDist, 0, 1);
        const edgeFade = ef * ef * (3 - 2 * ef);       // smoothstep：越靠边越透明
        const reveal = eased * eased;                  // 越接近目标点越恢复不透明
        const baseAlpha = progress >= 1 ? 1 : 0.35 + progress * 0.65;
        ctx.globalAlpha = baseAlpha * (edgeFade + (1 - edgeFade) * reveal);
      }
      const ci = p.ci;
      if (ci !== curColor) { ctx.fillStyle = PALETTE[ci]; curColor = ci; }
      ctx.fillRect(p.x - p.hs, p.y - p.hs, p.size, p.size);
    }

    ctx.globalAlpha = 1;
    if (gathering && complete) PT.gathering = false;

    // 帧调度：滚出视口 / 标签页隐藏 / 降级动效且已静止时停止渲染，事件驱动恢复
    if (!PT.visible || document.hidden || (reducedMotion && !PT.gathering)) {
      PT.raf = null;
      return;
    }
    PT.raf = requestAnimationFrame(render);
  };

  // 将「技术名 + 详细说明」共同采样为粒子目标点并触发聚合动画
  const setParticleText = (name, desc) => {
    if (!ctx) return;
    PT.current = name;
    PT.currentDesc = desc || '';
    const build = ++PT.build;
    const rect = panel.getBoundingClientRect();
    PT.w = Math.max(1, Math.floor(rect.width));
    PT.h = Math.max(280, Math.floor(canvas.clientHeight || 400));
    PT.dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = PT.w * PT.dpr;
    canvas.height = PT.h * PT.dpr;
    ctx.setTransform(PT.dpr, 0, 0, PT.dpr, 0, 0);

    const family = '"Songti SC", "SimSun", "宋体", "STSong", serif';
    const off = document.createElement('canvas');
    const offCtx = off.getContext('2d', { willReadFrequently: true });

    // 技术名：大字，超宽自动缩小
    let nameSize = PCFG.font;
    const nameFont = s => `${PCFG.weight} ${s}px ${family}`;
    offCtx.font = nameFont(nameSize);
    const maxNameW = PT.w * 0.94;
    const nameW = offCtx.measureText(name).width;
    if (nameW > maxNameW) {
      nameSize = Math.max(36, nameSize * (maxNameW / nameW));
      offCtx.font = nameFont(nameSize);
    }

    // 描述：宽画布 32px / 窄画布(手机) 26px 起排，行数超高清晰度优先逐级缩小字号
    // 按「词」换行：英文单词与中文词组整体保留，避免行尾把单词劈成两半
    let descSize = PT.w < 480 ? 26 : 32;
    const descText = desc || '';
    const maxTextW = PT.w * 0.94;
    const wordSegmenter = (typeof Intl !== 'undefined' && Intl.Segmenter)
      ? new Intl.Segmenter('zh-Hans', { granularity: 'word' })
      : null;
    const tokenize = s => {
      if (!wordSegmenter) return s.match(/[A-Za-z0-9]+(?:[-_.'’][A-Za-z0-9]+)*|\s+|[\s\S]/g) || [];
      const segs = Array.from(wordSegmenter.segment(s), seg => seg.segment);
      // 合并被分词器拆开的连字符单词：MyBatis + - + Plus → MyBatis-Plus
      const tokens = [];
      for (let i = 0; i < segs.length; i++) {
        const cur = segs[i];
        const next = segs[i + 1] || '';
        if (cur === '-' && tokens.length && /[A-Za-z0-9]$/.test(tokens[tokens.length - 1])
            && /^[A-Za-z0-9]/.test(next)) {
          tokens[tokens.length - 1] += '-' + next;
          i++;
        } else {
          tokens.push(cur);
        }
      }
      return tokens;
    };
    const NO_LINE_START = /^[，。、；：！？）》」』】,.;:!?)]/;
    const NO_LINE_END = /[（《「『【(]$/;
    const wrapDesc = size => {
      offCtx.font = `600 ${size}px ${family}`;
      const lines = [];
      let line = '';
      let space = '';
      for (const tok of tokenize(descText)) {
        if (!tok) continue;
        if (/^\s+$/.test(tok)) { space = ' '; continue; }
        // 行首禁则：闭合标点不留行首，直接并入上一行
        if (!line && NO_LINE_START.test(tok) && lines.length) {
          lines[lines.length - 1] += tok;
          space = '';
          continue;
        }
        const candidate = line + space + tok;
        const width = offCtx.measureText(candidate).width;
        if (line && width > maxTextW) {
          if (NO_LINE_START.test(tok)) {
            lines.push(line + tok);   // 标点不留行首，跟随上一行
            line = '';
          } else {
            lines.push(line);
            line = tok;
          }
        } else {
          line = candidate;
        }
        space = '';
      }
      if (line) lines.push(line);
      // 行尾禁则：开括号不留在上一行行尾，移到下一行开头
      for (let i = 0; i < lines.length - 1; i++) {
        if (NO_LINE_END.test(lines[i])) {
          lines[i + 1] = lines[i].slice(-1) + lines[i + 1];
          lines[i] = lines[i].slice(0, -1);
        }
      }
      return lines;
    };
    // 合并标点后可能略超 94% 排版宽度，超限则继续缩小字号，避免贴边裁切
    const lineFits = ls => {
      const limit = PT.w * 0.985;
      return ls.every(l => offCtx.measureText(l).width <= limit);
    };
    const nameY = Math.round(nameSize * 0.72);
    const descTop = nameY + Math.round(nameSize * 0.62);
    const availH = PT.h - descTop - 24;
    let lines = wrapDesc(descSize);
    let lineHeight = Math.round(descSize * 1.5);
    while ((lines.length * lineHeight > availH || !lineFits(lines)) && descSize > 18) {
      descSize -= 2;
      lines = wrapDesc(descSize);
      lineHeight = Math.round(descSize * 1.5);
    }

    // 离屏合成：名称居上、描述居下
    off.width = PT.w;
    off.height = PT.h;
    offCtx.fillStyle = '#fff';
    offCtx.textAlign = 'center';
    offCtx.textBaseline = 'middle';
    offCtx.font = nameFont(nameSize);
    offCtx.fillText(name, PT.w / 2, nameY);
    offCtx.font = `600 ${descSize}px ${family}`;
    lines.forEach((line, li) => {
      offCtx.fillText(line, PT.w / 2, descTop + lineHeight * li + lineHeight / 2);
    });

    // 采样像素点
    const img = offCtx.getImageData(0, 0, off.width, off.height);
    const targets = [];
    const step = PCFG.density;
    for (let y = 0; y < off.height; y += step) {
      for (let x = 0; x < off.width; x += step) {
        const a = img.data[(y * off.width + x) * 4 + 3];
        if (a > 40) targets.push({ x, y, alpha: a / 255 });
      }
    }

    const maxParticles = MAX_PARTICLES;
    const stride = Math.max(1, Math.ceil(targets.length / maxParticles));

    PT.particles = targets.filter((_, i) => i % stride === 0).map((t, i) => {
      const seed = ((i * 9301 + 49297) % 233280) / 233280;
      const depth = 0.45 + (((i * 233 + 97) % 1000) / 1000) * 0.9;
      const blend = clamp(t.x / Math.max(1, PT.w) + (seed - 0.5) * 0.35, 0, 1);
      const size = Math.max(0.8, PCFG.size * (0.75 + t.alpha * 0.45));
      return {
        // 散开态：全画布均匀随机分布（无矩形边界）
        x: reducedMotion ? t.x : Math.random() * PT.w,
        y: reducedMotion ? t.y : Math.random() * PT.h,
        sx: 0, sy: 0, tx: t.x, ty: t.y,
        size, hs: size * 0.5,
        ci: Math.round(blend * (PALETTE_N - 1)),  // 调色板色索引（替代每粒子颜色字符串）
        ddx: 0, ddy: 0,                            // 漂移偏移缓存（隔帧更新）
        ph1: seed * 10, ph2: depth * 10,           // 预计算三角函数相位
        seed, depth, delay: 0
      };
    });
    // 按色索引排序：渲染循环中 fillStyle 切换次数 = 调色板级数（17），而非粒子数
    PT.particles.sort((a, b) => a.ci - b.ci);

    PT.pointer.x = PT.w / 2; PT.pointer.y = PT.h / 2;
    PT.pointer.sx = PT.pointer.x; PT.pointer.sy = PT.pointer.y;
    if (build !== PT.build) return;
    startGather(true);
  };

  // 立方体悬停 → 名称与描述共同融入粒子特效
  const allSpans = techMosaic.querySelectorAll('.cl span');
  allSpans.forEach((s, idx) => {
    const tech = TECHS[idx % TECHS.length];
    s.addEventListener('mouseenter', () => {
      setParticleText(tech.n, tech.d);
    });
  });

  // 画布鼠标斥力交互
  if (canvas) {
    canvas.addEventListener('pointermove', e => {
      const r = canvas.getBoundingClientRect();
      PT.pointer.x = e.clientX - r.left;
      PT.pointer.y = e.clientY - r.top;
      PT.pointer.active = true;
    });
    canvas.addEventListener('pointerleave', () => { PT.pointer.active = false; });
  }

  // 尺寸变化重采样（保持当前文字）
  if (panel && 'ResizeObserver' in window) {
    let roTimer = null;
    new ResizeObserver(() => {
      clearTimeout(roTimer);
      roTimer = setTimeout(() => setParticleText(PT.current || '智学职达'), 200);
    }).observe(panel);
  }

  // 帧率优化：区块滚出视口时暂停渲染循环，回到视口自动恢复（事件驱动，不再空转 rAF）
  if (panel && 'IntersectionObserver' in window) {
    new IntersectionObserver(entries => {
      entries.forEach(e => {
        PT.visible = e.isIntersecting;
        if (PT.visible && PT.raf === null) PT.raf = requestAnimationFrame(render);
      });
    }, { threshold: 0 }).observe(panel);
  }
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && PT.visible && PT.raf === null) PT.raf = requestAnimationFrame(render);
  });

  // 初始文字（等待字体就绪）
  if (ctx) {
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(() => setParticleText(PT.current || '智学职达'));
    } else {
      setParticleText(PT.current || '智学职达');
    }
  }
}

// ===== 管理端页面预览 · Tab 切换 + 自动轮播 =====
// 每 8 秒自动切到下一页，手动点击 Tab 后重新计时；离屏或减少动效时停止自动轮播。
(function initAdminPreview() {
  const browserEl = document.querySelector('.browser');
  const urlEl = document.getElementById('adminShotUrl');
  const tabs = Array.from(document.querySelectorAll('.shot-tab'));
  if (!browserEl || !tabs.length) return;

  const pages = tabs.map(tab => document.getElementById(tab.dataset.page));
  const INTERVAL = 8000;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let current = Math.max(0, tabs.findIndex(t => t.classList.contains('on')));
  let timer = 0;

  const show = (index) => {
    current = index;
    tabs.forEach((t, i) => t.classList.toggle('on', i === index));
    pages.forEach((page, i) => page.classList.toggle('on', i === index));
    if (urlEl) urlEl.textContent = tabs[index].dataset.url;
  };

  const stop = () => {
    if (timer) clearInterval(timer);
    timer = 0;
  };

  const start = () => {
    stop();
    if (reduceMotion) return;
    timer = setInterval(() => show((current + 1) % tabs.length), INTERVAL);
  };

  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => {
      show(index);
      start(); // 手动点击后重新计时
    });
  });

  if (!reduceMotion) {
    if ('IntersectionObserver' in window) {
      // 仅当浏览器样机在视口内时轮播，离屏自动暂停
      new IntersectionObserver((entries) => {
        entries.forEach((entry) => (entry.isIntersecting ? start() : stop()));
      }, { threshold: 0.2 }).observe(browserEl);
    } else {
      start();
    }
  }
})();

// ===== 学生端功能卡片椭圆公转 =====
// 卡片沿倾斜椭圆轨道缓慢公转；离开视口时暂停，系统偏好减少动效时保持静止。
const orbitStage = document.getElementById('orbitStage');
if (orbitStage) {
  const orbitWrap = document.getElementById('orbitWrap') || orbitStage;
  const orbitCards = orbitStage.querySelectorAll('.orbit-card');
  const CARD_HALF_W = 105;
  const TILT = -6 * Math.PI / 180;     // 椭圆倾斜角（JS 坐标旋转，卡片保持直立）
  const angle0 = -Math.PI / 2;         // 第一张卡从最前方开始
  const SPEED = Math.PI * 2 / 30000;   // 约 30 秒公转一圈
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let stageW = 0, stageH = 0;
  let angle = 0, lastTick = 0, rafId = 0, running = false;

  const measureStage = () => {
    const rect = orbitStage.getBoundingClientRect();
    stageW = rect.width; stageH = rect.height;
  };

  const layout = () => {
    const W = stageW, H = stageH;
    if (!W) return;
    const narrow = W <= 900;
    const s = narrow ? 0.65 : 1;
    const halfW = CARD_HALF_W * s;
    const rx = narrow
      ? Math.max(40, W / 2 - halfW - 10)
      : W / 2 - CARD_HALF_W - 16;
    const ry = narrow
      ? Math.max(150, H / 2 - halfW - 24)
      : 165;
    orbitCards.forEach((card, i) => {
      const t = angle0 + angle + (i * Math.PI * 2) / orbitCards.length;
      const x = Math.cos(t) * rx;
      const y = Math.sin(t) * ry;
      const xr = x * Math.cos(TILT) - y * Math.sin(TILT);
      const yr = x * Math.sin(TILT) + y * Math.cos(TILT);
      const depth = (Math.sin(t) + 1) / 2;
      const scale = (0.82 + depth * 0.24) * s;
      card.style.transform =
        `translate(calc(-50% + ${xr.toFixed(1)}px), calc(-50% + ${yr.toFixed(1)}px)) scale(${scale.toFixed(3)})`;
      card.style.opacity = (0.68 + depth * 0.32).toFixed(2);
      card.style.zIndex = Math.round(depth * 20);
    });
  };

  const tick = (now) => {
    if (lastTick) angle += (now - lastTick) * SPEED;
    lastTick = now;
    layout();
    rafId = requestAnimationFrame(tick);
  };

  const start = () => {
    if (running || reduceMotion) return;
    running = true;
    lastTick = 0;
    rafId = requestAnimationFrame(tick);
  };

  const stop = () => {
    if (rafId) cancelAnimationFrame(rafId);
    rafId = 0;
    running = false;
    lastTick = 0;
  };

  measureStage();
  layout();
  window.addEventListener('resize', () => {
    measureStage();
    layout();
  });

  if (!reduceMotion) {
    if ('IntersectionObserver' in window) {
      // 仅当轨道进入视口时启动，离屏自动暂停，避免无意义重绘
      new IntersectionObserver((entries) => {
        entries.forEach((entry) => (entry.isIntersecting ? start() : stop()));
      }, { threshold: 0.15 }).observe(orbitWrap);
    } else {
      start();
    }
  }
}

// ===== 全局帧率监测 · 低性能自动降级（自适应质量算法） =====
// 每 2s 统计平均帧率：连续 2 次 <40fps → <html> 加 .low-perf（停重绘型动画保帧率）；
// 需连续 3 次 >55fps 才解除（迟滞设计，避免动画启停来回震荡）。
(() => {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  let frames = 0, last = performance.now(), lowStreak = 0, highStreak = 0;
  const check = (now) => {
    frames++;
    if (now - last >= 2000) {
      const fps = (frames * 1000) / (now - last);
      frames = 0; last = now;
      if (document.hidden) { requestAnimationFrame(check); return; }
      if (fps < 40) {
        lowStreak++; highStreak = 0;
        if (lowStreak >= 2) document.documentElement.classList.add('low-perf');
      } else if (fps > 55) {
        highStreak++;
        if (highStreak >= 3) {
          document.documentElement.classList.remove('low-perf');
          lowStreak = 0; highStreak = 0;
        }
      } else {
        lowStreak = 0; highStreak = 0;
      }
    }
    requestAnimationFrame(check);
  };
  requestAnimationFrame(check);
})();

// ===== 全站多光标页面搭建 =====
// 每个区块进入视口后，由彩色光标把标题、卡片与界面样机逐个「画」出来。
// 光标只在执行任务时出现与移动：打字严格吸附在 caret，画框严格贴住选框角，不再随机漂移。
(function initSiteBuild() {
  const layer = document.getElementById('sbLayer');
  const host = document.getElementById('sbCursors');
  if (!layer || !host) return;

  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const revealAll = () => {
    document.querySelectorAll('.reveal').forEach(el => el.classList.add('in'));
    document.querySelectorAll('.sb-hold').forEach(el => el.classList.remove('sb-hold'));
    layer.style.display = 'none';
  };
  if (reduceMotion || !('IntersectionObserver' in window)) { revealAll(); return; }

  const AGENTS = [
    { name: 'DeepSeek', color: '#5b58ff' },
    { name: 'RAG', color: '#00b5ce' },
    { name: '题库', color: '#ffa629' },
    { name: '学情', color: '#ff24bd' },
    { name: '排课', color: '#14ae5c' }
  ];

  const agents = AGENTS.map((cfg, i) => {
    const el = document.createElement('div');
    el.className = 'sb-cursor';
    el.style.setProperty('--c', cfg.color);
    el.innerHTML =
      '<svg viewBox="0 0 20 22" aria-hidden="true"><path d="M2 1 L2 17.4 L6.3 13.2 L9 19.4 L11.9 18 L9.3 12.1 L15.4 11.7 Z"/></svg>' +
      '<span class="sb-name">' + cfg.name + '</span>';
    host.appendChild(el);

    const box = document.createElement('div');
    box.className = 'sb-sel';
    box.innerHTML =
      '<i class="sb-h tl"></i><i class="sb-h tr"></i><i class="sb-h bl"></i><i class="sb-h br"></i>' +
      '<b class="sb-sel-label"></b><span class="sb-sel-size"></span>';
    host.appendChild(box);

    return {
      i, name: cfg.name, color: cfg.color, el, box,
      nameEl: el.querySelector('.sb-name'),
      labelEl: box.querySelector('.sb-sel-label'),
      sizeEl: box.querySelector('.sb-sel-size'),
      x: -60, y: -60, opacity: 0,
      job: null, section: null
    };
  });

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const ease = v => v < .5 ? 4 * v * v * v : 1 - Math.pow(-2 * v + 2, 3) / 2;

  const setCursor = a => {
    a.el.style.transform = 'translate3d(' + a.x.toFixed(1) + 'px,' + a.y.toFixed(1) + 'px,0)';
    a.el.style.opacity = a.opacity.toFixed(3);
    const flip = a.x > window.innerWidth - 130;
    a.nameEl.style.left = flip ? 'auto' : '13px';
    a.nameEl.style.right = flip ? '13px' : 'auto';
  };

  const setBox = (a, x, y, w, h, label, size) => {
    const on = w > 1 && h > 1;
    a.box.style.display = on ? 'block' : 'none';
    if (!on) return;
    a.box.style.opacity = 1;
    a.box.style.left = x + 'px';
    a.box.style.top = y + 'px';
    a.box.style.width = w + 'px';
    a.box.style.height = h + 'px';
    a.labelEl.textContent = label || '';
    a.labelEl.style.top = y < 26 ? '4px' : '-19px';
    a.sizeEl.textContent = size || '';
    a.sizeEl.style.display = size ? '' : 'none';
    const nearRight = x + w > window.innerWidth - 60;
    a.sizeEl.style.right = nearRight ? 'auto' : '-1px';
    a.sizeEl.style.left = nearRight ? '-1px' : 'auto';
    const nearBottom = y + h > window.innerHeight - 30;
    a.sizeEl.style.bottom = nearBottom ? 'auto' : '-24px';
    a.sizeEl.style.top = nearBottom ? '-22px' : 'auto';
  };

  const hideBox = a => { a.box.style.display = 'none'; };

  // ---------- 打字 ----------
  function prepareType(el) {
    if (el.dataset.sbTyped === '1') return;
    const raw = el.textContent.replace(/\s+/g, ' ').trim();
    const fullH = el.getBoundingClientRect().height;
    if (fullH) el.style.minHeight = fullH + 'px'; // 锁定最终高度，打字过程中不推动下方内容
    el.dataset.sbTyped = '1';
    el.dataset.sbRaw = raw;
    el.textContent = '';
    const typedEl = document.createElement('span');
    typedEl.className = 'sb-typed';
    const caret = document.createElement('span');
    caret.className = 'sb-caret';
    const rest = document.createElement('span');
    rest.className = 'sb-rest';
    rest.style.visibility = 'hidden';
    rest.textContent = raw;
    el.append(typedEl, caret, rest);
    // 标题本身带 .reveal 时，需要先解除它的隐藏态（否则移除 .sb-hold 后仍是 opacity:0）
    if (el.classList.contains('reveal')) el.classList.add('in');
    el._sb = { typed: typedEl, caret, rest, raw };
  }

  function typeText(el, p) {
    const d = el._sb;
    if (!d) return;
    const n = Math.round(d.raw.length * clamp(p, 0, 1));
    d.typed.textContent = d.raw.slice(0, n);
    d.rest.textContent = d.raw.slice(n);
  }

  function caretPoint(el) {
    const d = el._sb;
    if (d && d.caret && d.caret.style.display !== 'none') {
      const c = d.caret.getBoundingClientRect();
      return { x: c.left + 1, y: c.top + c.height * .5 };
    }
    const r = el.getBoundingClientRect();
    return { x: r.right, y: r.top + r.height * .5 };
  }

  function restoreType(el) {
    if (el.dataset.sbTyped !== '1') { el.classList.remove('sb-hold'); return; }
    el.textContent = el.dataset.sbRaw || '';
    el.style.minHeight = '';
    delete el.dataset.sbTyped;
    delete el.dataset.sbRaw;
    delete el._sb;
    el.classList.remove('sb-hold');
  }

  // ---------- 揭晓 ----------
  const revealed = new WeakSet();
  function revealEl(el) {
    if (!el) return;
    el.classList.remove('sb-hold');
    if (revealed.has(el)) return;
    revealed.add(el);
    if (el.classList.contains('orbit-card')) {
      const target = parseFloat(el.style.opacity || '1') || 1;
      el.style.opacity = '0';
      void el.offsetWidth; // 先落回 0，再过渡到目标透明度
      el.style.opacity = String(target);
      return;
    }
    if (el.classList.contains('reveal')) el.classList.add('in');
    else el.classList.add('sb-in');
    if (el.classList.contains('demo')) {
      window.setTimeout(() => { try { playDemo(); } catch (e) { /* noop */ } }, 420);
    }
  }

  // ---------- 区块构建计划 ----------
  const PLANS = [
    {
      root: '#top',
      pre: ['.hero-copy'],
      types: [],
      blocks: [
        { sel: '.hero-badge', label: 'Badge' },
        { sel: '.hero-copy h1', label: 'Heading' },
        { sel: '.hero-sub', label: 'Text' },
        { sel: '.hero-copy .hero-actions', label: 'Buttons' },
        { sel: '.hero-copy .hero-stats', label: 'Stats' },
        { sel: '.hero-phone', label: 'Device' }
      ]
    },
    {
      root: '#student',
      pre: ['.sec-head', '.orbit-wrap'],
      types: [
        { sel: '.sec-head h2', label: 'Heading' },
        { sel: '.sec-head p', label: 'Text' }
      ],
      blocks: [
        { sel: '.orbit-card', label: 'Card' },
        { sel: '.shot-phone', label: 'Device' }
      ]
    },
    {
      root: '#ai',
      pre: ['.sec-head'],
      types: [
        { sel: '.sec-head h2', label: 'Heading' },
        { sel: '.sec-head p', label: 'Text' }
      ],
      blocks: [
        { sel: '.demo', label: 'Frame' },
        { sel: '.demo-item', label: 'Card' }
      ]
    },
    {
      root: '#admin',
      pre: ['.sec-head'],
      types: [
        { sel: '.sec-head h2', label: 'Heading' },
        { sel: '.sec-head p', label: 'Text' }
      ],
      blocks: [
        { sel: '.browser', label: 'Frame' },
        { sel: '.mini-card', label: 'Card' }
      ]
    },
    {
      root: '#tech',
      pre: ['.sec-head'],
      types: [{ sel: '.sec-head h2', label: 'Heading' }],
      blocks: [{ sel: '.iso-stage', label: 'Frame' }]
    },
    {
      root: '#faq',
      pre: ['.faq-aside'],
      types: [
        { sel: '.faq-aside h2', label: 'Heading' },
        { sel: '.faq-aside-sub', label: 'Text' }
      ],
      blocks: [
        { sel: '.faq-nav', label: 'Nav' },
        { sel: '.faq-contact', label: 'Card' },
        { sel: '.faq-group', label: 'Group' }
      ]
    },
    {
      root: '#hero-cta',
      pre: [],
      types: [{ sel: 'h2', label: 'Heading' }],
      blocks: []
    }
  ];

  const sections = [];
  PLANS.forEach(plan => {
    const root = document.querySelector(plan.root);
    if (!root) return;
    const preEls = plan.pre.map(sel => root.querySelector(sel)).filter(Boolean);
    const seen = new Set();
    const jobs = [];
    plan.types.forEach(t => {
      Array.from(root.querySelectorAll(t.sel)).forEach(el => {
        if (seen.has(el)) return;
        seen.add(el);
        jobs.push({ kind: 'type', el, label: t.label, done: false });
      });
    });
    plan.blocks.forEach(b => {
      Array.from(root.querySelectorAll(b.sel)).forEach(el => {
        if (seen.has(el)) return;
        seen.add(el);
        jobs.push({ kind: 'draw', el, label: b.label, done: false });
      });
    });
    jobs.forEach(job => job.el.classList.add('sb-hold'));
    sections.push({
      sid: plan.root, root, preEls, jobs,
      started: false, done: false, queue: [], pending: 0
    });
  });

  // 未被任何区块计划管理的 .reveal 直接显示，避免遗漏内容
  const planned = new Set();
  const preplanned = new Set();
  sections.forEach(st => {
    st.jobs.forEach(job => planned.add(job.el));
    st.preEls.forEach(el => preplanned.add(el));
  });
  document.querySelectorAll('.reveal').forEach(el => {
    if (!planned.has(el) && !preplanned.has(el)) el.classList.add('in');
  });

  // ---------- 任务调度 ----------
  let active = null;
  let rafId = 0;
  let lastTs = 0;

  function startLoop() {
    if (rafId) return;
    lastTs = performance.now();
    rafId = requestAnimationFrame(loop);
  }

  function loop(now) {
    rafId = 0;
    const dt = Math.min(.05, (now - lastTs) / 1000 || .016);
    lastTs = now;
    agents.forEach(a => updateAgent(a, dt));
    const busy = agents.some(a => a.job) || agents.some(a => a.opacity > .02);
    if (busy) rafId = requestAnimationFrame(loop);
  }

  function beginJob(a, job, st) {
    a.job = job;
    job.phase = 'move';
    job.t = 0;
    job.from = { x: a.x, y: a.y };
    if (job.kind === 'type') prepareType(job.el);
    const rect = job.el.getBoundingClientRect();
    const to = job.kind === 'type'
      ? caretPoint(job.el)
      : { x: rect.left + 6, y: rect.top + 6 };
    if (a.section !== st.sid) {
      a.section = st.sid;
      a.x = clamp(to.x - 96 - a.i * 18, 6, window.innerWidth - 24);
      a.y = clamp(to.y + 72 + a.i * 12, 56, window.innerHeight - 18);
      a.opacity = 0;
      setCursor(a);
      job.from = { x: a.x, y: a.y };
    }
    job.to = to;
    job.dur = clamp(Math.hypot(to.x - job.from.x, to.y - job.from.y) / 850, .24, .62);

    if (job.kind === 'type') {
      const r = job.el.getBoundingClientRect();
      setBox(a, r.left, r.top, r.width, r.height, job.label,
        Math.round(r.width) + ' × ' + Math.round(r.height));
    } else {
      setBox(a, rect.left, rect.top, 0, 0, job.label, '');
    }
  }

  function finishJob(a, job) {
    if (job.kind === 'type') restoreType(job.el);
    else revealEl(job.el);
    hideBox(a);
    a.job = null;
    job.done = true;
    const st = job.state;
    if (st) {
      st.pending--;
      if (st === active) pump();
    }
  }

  function updateAgent(a, dt) {
    if (!a.job) {
      if (a.opacity > 0) {
        a.opacity = Math.max(0, a.opacity - dt * 4);
        setCursor(a);
      }
      return;
    }
    const job = a.job;
    job.t += dt;

    if (job.phase === 'move') {
      const k = ease(clamp(job.t / job.dur, 0, 1));
      a.x = job.from.x + (job.to.x - job.from.x) * k;
      a.y = job.from.y + (job.to.y - job.from.y) * k;
      a.opacity = Math.min(1, a.opacity + dt * 6);
      setCursor(a);
      if (k >= 1) {
        job.phase = 'work';
        job.t = 0;
        job.dur = job.kind === 'type'
          ? clamp(.8 + (job.el._sb ? job.el._sb.raw.length * .042 : .6), .9, 2.1)
          : .72;
        if (job.kind === 'type') job.el.classList.remove('sb-hold');
      }
      return;
    }

    const p = clamp(job.t / job.dur, 0, 1);
    if (job.kind === 'type') {
      typeText(job.el, p);
      const c = caretPoint(job.el);
      a.x = c.x;
      a.y = c.y;
      setCursor(a);
      const r = job.el.getBoundingClientRect();
      setBox(a, r.left, r.top, r.width, r.height, job.label,
        Math.round(r.width) + ' × ' + Math.round(r.height));
      if (p >= 1) finishJob(a, job);
      return;
    }

    const r = job.el.getBoundingClientRect();
    const w = r.width * p;
    const h = r.height * p;
    setBox(a, r.left, r.top, w, h, job.label, Math.round(w) + ' × ' + Math.round(h));
    a.x = r.left + w;
    a.y = r.top + h;
    setCursor(a);
    if (p >= 1) finishJob(a, job);
  }

  function jobReady(job) {
    if (job.kind === 'type') return true;
    const r = job.el.getBoundingClientRect();
    return r.top < window.innerHeight * 1.05 && r.bottom > -60;
  }

  function pump() {
    if (!active) return;
    let assigned = true;
    while (assigned) {
      assigned = false;
      const free = agents.find(a => !a.job);
      if (!free) break;
      const idx = active.queue.findIndex(jobReady);
      if (idx < 0) break;
      const job = active.queue.splice(idx, 1)[0];
      job.state = active;
      beginJob(free, job, active);
      assigned = true;
    }
    if (active.pending <= 0) {
      active.done = true;
      active = null;
    }
  }

  function finalizeState(st) {
    if (!st || st.done) return;
    st.jobs.forEach(job => {
      if (job.done) return;
      if (job.kind === 'type') restoreType(job.el);
      else revealEl(job.el);
      job.done = true;
    });
    st.queue = [];
    st.pending = 0;
    st.done = true;
    st.root.querySelectorAll('.reveal').forEach(el => el.classList.add('in'));
    if (active === st) active = null;
    agents.forEach(a => {
      if (a.job && a.job.state === st) { a.job = null; hideBox(a); }
      a.section = null;
      a.opacity = 0;
      setCursor(a);
    });
    startLoop();
  }

  function startState(st) {
    if (!st || st.started || st.done) return;
    if (active) finalizeState(active);
    active = st;
    st.started = true;
    st.queue = st.jobs.slice();
    st.pending = st.jobs.length;
    st.preEls.forEach(el => el.classList.add('sb-pre'));
    agents.forEach(a => { a.job = null; a.section = null; a.opacity = 0; hideBox(a); setCursor(a); });
    startLoop();
    pump();
  }

  const observer = new IntersectionObserver(entries => {
    entries.forEach(entry => {
      const st = sections.find(s => s.root === entry.target);
      if (!st) return;
      const r = entry.boundingClientRect;
      if (entry.isIntersecting) {
        if (!st.started && r.top < window.innerHeight * .84) startState(st);
      } else if (r.bottom < 0) {
        finalizeState(st); // 已滚出上方：直接完成，避免内容停留在隐藏态
      } else if (st === active && r.top > window.innerHeight) {
        finalizeState(st);
      }
    });
  }, { threshold: [0, .08], rootMargin: '-4% 0px -12% 0px' });

  sections.forEach(st => observer.observe(st.root));

  // 长区块分屏建造：滚动时继续领取已进入视口的元素任务
  window.addEventListener('scroll', () => {
    if (active) pump();
  }, { passive: true });
})();
