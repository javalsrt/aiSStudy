/* ============ 智学职达官网 · 交互 ============ */

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

// ===== 滚动渐入（IntersectionObserver） =====
const io = new IntersectionObserver(entries => {
  entries.forEach(e => {
    if (e.isIntersecting) {
      e.target.classList.add('in');
      // 浏览器样机中的柱状图进入视口后再播放生长动画
      e.target.querySelectorAll('.bar-v i').forEach(bar => {
        bar.style.animation = 'none';
        void bar.offsetWidth;
        bar.style.animation = '';
      });
      io.unobserve(e.target);
    }
  });
}, { threshold: 0.15 });
document.querySelectorAll('.reveal').forEach(el => io.observe(el));

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

// 深色 AI 区块进入视口时触发演示
const demoObserver = new IntersectionObserver(entries => {
  entries.forEach(e => {
    if (e.isIntersecting) {
      playDemo();
      demoObserver.unobserve(e.target);
    }
  });
}, { threshold: 0.3 });
const demoEl = document.querySelector('.demo');
if (demoEl) demoObserver.observe(demoEl);

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
  const PCFG = { size: 2.6, density: 4, color: '#ffffff', highlight: '#8b5cf6', scatter: 150, gather: 1400, stagger: 380, repel: 38, radius: 110, drift: .7, font: 72, weight: 800 };

  const PT = {
    particles: [], raf: null, build: 0,
    gathering: false, gatherStart: 0,
    w: 0, h: 0, dpr: 1,
    pointer: { active: false, x: 0, y: 0, sx: 0, sy: 0 }
  };

  const clamp = (v, a, b) => Math.min(Math.max(v, a), b);
  const easeOutCubic = t => 1 - Math.pow(1 - t, 3);
  const hexToRgb = hex => {
    const c = hex.replace('#', '');
    return { r: parseInt(c.slice(0, 2), 16), g: parseInt(c.slice(2, 4), 16), b: parseInt(c.slice(4, 6), 16) };
  };
  const mixRgb = (a, b, t) => ({
    r: Math.round(a.r + (b.r - a.r) * t),
    g: Math.round(a.g + (b.g - a.g) * t),
    b: Math.round(a.b + (b.b - a.b) * t)
  });

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
    ctx.clearRect(0, 0, PT.w, PT.h);
    if (!reducedMotion) {
      ctx.shadowBlur = PCFG.size * 3;
      ctx.shadowColor = PCFG.highlight;
    }
    PT.pointer.sx += (PT.pointer.x - PT.pointer.sx) * 0.18;
    PT.pointer.sy += (PT.pointer.y - PT.pointer.sy) * 0.18;
    let complete = true;

    PT.particles.forEach(p => {
      let bx = p.tx, by = p.ty, progress = 1;
      if (PT.gathering) {
        const local = (now - PT.gatherStart - p.delay) / PCFG.gather;
        progress = clamp(local, 0, 1);
        const eased = easeOutCubic(progress);
        bx = p.sx + (p.tx - p.sx) * eased;
        by = p.sy + (p.ty - p.sy) * eased;
        if (progress < 1) complete = false;
      } else if (!reducedMotion) {
        const t = now * 0.001;
        bx += Math.sin(t * 0.9 + p.seed * 10) * PCFG.drift * p.depth;
        by += Math.cos(t * 0.75 + p.depth * 10) * PCFG.drift * p.depth;
      }
      if (PT.pointer.active && !reducedMotion) {
        const dx = bx - PT.pointer.sx, dy = by - PT.pointer.sy;
        const dist = Math.hypot(dx, dy);
        if (dist > 0 && dist < PCFG.radius) {
          const force = Math.pow(1 - dist / PCFG.radius, 2) * PCFG.repel;
          bx += (dx / dist) * force;
          by += (dy / dist) * force;
        }
      }
      const follow = reducedMotion ? 1 : 0.22;
      p.x += (bx - p.x) * follow;
      p.y += (by - p.y) * follow;

      ctx.globalAlpha = clamp(0.35 + progress * 0.65, 0, 1);
      ctx.fillStyle = p.color;
      if (p.size <= 2.1) {
        ctx.fillRect(p.x - p.size / 2, p.y - p.size / 2, p.size, p.size);
      } else {
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size / 2, 0, Math.PI * 2);
        ctx.fill();
      }
    });

    ctx.globalAlpha = 1;
    ctx.shadowBlur = 0;
    if (PT.gathering && complete) PT.gathering = false;
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

    const family = '"PingFang SC", "Microsoft YaHei", sans-serif';
    const off = document.createElement('canvas');
    const offCtx = off.getContext('2d', { willReadFrequently: true });

    // 技术名：大字，超宽自动缩小
    let nameSize = PCFG.font;
    const nameFont = s => `${PCFG.weight} ${s}px ${family}`;
    offCtx.font = nameFont(nameSize);
    const maxNameW = PT.w * 0.92;
    const nameW = offCtx.measureText(name).width;
    if (nameW > maxNameW) {
      nameSize = Math.max(30, nameSize * (maxNameW / nameW));
      offCtx.font = nameFont(nameSize);
    }

    // 描述：按画布宽度换行
    const descSize = 21;
    const charsPerLine = Math.max(10, Math.floor((PT.w * 0.92) / descSize));
    const descText = desc || '';
    const lines = [];
    for (let i = 0; i < descText.length; i += charsPerLine) {
      lines.push(descText.slice(i, i + charsPerLine));
    }

    // 离屏合成：名称居上、描述居下
    off.width = PT.w;
    off.height = PT.h;
    offCtx.fillStyle = '#fff';
    offCtx.textAlign = 'center';
    offCtx.textBaseline = 'middle';
    const nameY = Math.round(nameSize * 0.75);
    offCtx.font = nameFont(nameSize);
    offCtx.fillText(name, PT.w / 2, nameY);
    offCtx.font = `500 ${descSize}px ${family}`;
    lines.forEach((line, li) => {
      offCtx.fillText(line, PT.w / 2, nameY + 52 + li * 32);
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

    const maxParticles = 5200;
    const stride = Math.max(1, Math.ceil(targets.length / maxParticles));
    const baseRgb = hexToRgb(PCFG.color);
    const hlRgb = hexToRgb(PCFG.highlight);

    PT.particles = targets.filter((_, i) => i % stride === 0).map((t, i) => {
      const seed = ((i * 9301 + 49297) % 233280) / 233280;
      const depth = 0.45 + (((i * 233 + 97) % 1000) / 1000) * 0.9;
      const blend = clamp(t.x / Math.max(1, PT.w) + (seed - 0.5) * 0.35, 0, 1);
      const color = rgbCss(mixRgb(baseRgb, hlRgb, blend));
      return {
        // 散开态：全画布均匀随机分布（无矩形边界）
        x: reducedMotion ? t.x : Math.random() * PT.w,
        y: reducedMotion ? t.y : Math.random() * PT.h,
        sx: 0, sy: 0, tx: t.x, ty: t.y,
        size: Math.max(0.8, PCFG.size * (0.75 + t.alpha * 0.45)),
        color, seed, depth, delay: 0
      };
    });

    PT.pointer.x = PT.w / 2; PT.pointer.y = PT.h / 2;
    PT.pointer.sx = PT.pointer.x; PT.pointer.sy = PT.pointer.y;
    if (build !== PT.build) return;
    startGather(true);
  };
  const rgbCss = rgb => `rgb(${rgb.r}, ${rgb.g}, ${rgb.b})`;

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

  // 初始文字（等待字体就绪）
  if (ctx) {
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(() => setParticleText(PT.current || '智学职达'));
    } else {
      setParticleText(PT.current || '智学职达');
    }
  }
}

// ===== 管理端页面预览 · Tab 切换（CSS 模拟真实页面） =====
const adminShotUrl = document.getElementById('adminShotUrl');
if (adminShotUrl) {
  document.querySelectorAll('.shot-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.shot-tab').forEach(b => b.classList.remove('on'));
      btn.classList.add('on');
      document.querySelectorAll('.shot-page').forEach(p => p.classList.remove('on'));
      const page = document.getElementById(btn.dataset.page);
      if (page) page.classList.add('on');
      adminShotUrl.textContent = btn.dataset.url;
    });
  });
}

// ===== 「学练测评管」True Focus 逐词聚焦（移植自 reactbits TrueFocus） =====
const focusChain = document.getElementById('focusChain');
if (focusChain) {
  const fWords = focusChain.querySelectorAll('.focus-word');
  const fFrame = focusChain.querySelector('.focus-frame');
  let fIndex = 0;

  // 将取景框对齐到当前词（相对容器定位）
  const placeFrame = () => {
    const word = fWords[fIndex];
    if (!word || !fFrame) return;
    const pRect = focusChain.getBoundingClientRect();
    const wRect = word.getBoundingClientRect();
    fFrame.style.left = (wRect.left - pRect.left) + 'px';
    fFrame.style.top = (wRect.top - pRect.top) + 'px';
    fFrame.style.width = wRect.width + 'px';
    fFrame.style.height = wRect.height + 'px';
    fFrame.classList.add('on');
  };

  const focusStep = () => {
    fWords[fIndex].classList.remove('active');
    fIndex = (fIndex + 1) % fWords.length;
    fWords[fIndex].classList.add('active');
    placeFrame();
  };

  fWords[0].classList.add('active');
  // 等字体加载、布局稳定后先定位一次
  placeFrame();
  window.addEventListener('resize', placeFrame);
  setInterval(focusStep, 1500); // 0.5s 过渡 + 1s 停留
}

// ===== 学生端功能卡片椭圆轨道（移植自 OrbitImages：卡片替代图片） =====
const orbitStage = document.getElementById('orbitStage');
if (orbitStage) {
  const orbitCards = orbitStage.querySelectorAll('.orbit-card');
  const CARD_HALF_W = 105;
  const DURATION = 30;                 // 30s 一圈，与原组件一致
  const TILT = -6 * Math.PI / 180;     // 椭圆倾斜角（JS 坐标旋转，卡片保持直立）
  let paused = false;
  let angle0 = -Math.PI / 2;           // 第一张卡从最前方开始
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const isNarrow = () => window.innerWidth <= 900;

  // 椭圆摆位：以舞台中心为锚点，calc(-50% + x) 定位，杜绝宽度测量误差
  const layout = (theta) => {
    const W = orbitStage.getBoundingClientRect().width;
    if (!W) return;
    const rx = W / 2 - CARD_HALF_W - 16;
    const ry = 165;
    orbitCards.forEach((card, i) => {
      const t = theta + (i * Math.PI * 2) / orbitCards.length;
      const x = Math.cos(t) * rx;
      const y = Math.sin(t) * ry;
      // 倾斜：坐标整体旋转 -6°，卡片本身保持直立
      const xr = x * Math.cos(TILT) - y * Math.sin(TILT);
      const yr = x * Math.sin(TILT) + y * Math.cos(TILT);
      // y>0 为近处（放大/不透明），y<0 为远处（缩小/半透明）
      const depth = (Math.sin(t) + 1) / 2;
      const scale = 0.82 + depth * 0.24;
      card.style.transform =
        `translate(calc(-50% + ${xr.toFixed(1)}px), calc(-50% + ${yr.toFixed(1)}px)) scale(${scale.toFixed(3)})`;
      card.style.opacity = (0.5 + depth * 0.5).toFixed(2);
      card.style.zIndex = Math.round(depth * 20);
    });
  };

  const clearOrbit = () => {
    orbitCards.forEach(c => { c.style.transform = ''; c.style.opacity = ''; c.style.zIndex = ''; });
  };

  if (reducedMotion) {
    layout(angle0);
  } else {
    // 仅当鼠标停留在卡片内容（图标/文字）上时才暂停公转，靠近轨道不停
    orbitCards.forEach(c => {
      c.addEventListener('mouseenter', () => { paused = true; });
      c.addEventListener('mouseleave', () => { paused = false; });
    });
    window.addEventListener('resize', () => {
      const el = performance.now();
      layout(angle0 + (el / 1000 / DURATION) * Math.PI * 2);
    });
    let last = null;
    let elapsed = 0;
    const tick = (ts) => {
      if (last === null) last = ts;
      const dt = Math.min(ts - last, 100);
      last = ts;
      if (!isNarrow()) {
        if (!paused) elapsed += dt;
        layout(angle0 + (elapsed / 1000 / DURATION) * Math.PI * 2);
      } else {
        clearOrbit();
      }
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  }
}
