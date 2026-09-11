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
    { n: 'WebSocket', d: '基于 STOMP over WebSocket 的双向实时通信通道，毫秒级推送专注度指标、聊天消息与系统通知，服务端心跳机制保障长连接稳定性。' },
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
  const tip = document.getElementById('cubeTip');
  const tipName = document.getElementById('cubeTipName');
  const tipDesc = document.getElementById('cubeTipDesc');
  const stage = document.getElementById('isoStage');

  const allSpans = techMosaic.querySelectorAll('.cl span');
  allSpans.forEach((s, idx) => {
    const tech = TECHS[idx % TECHS.length];
    s.addEventListener('mouseenter', () => {
      if (tip && tipName && tipDesc && stage) {
        tipName.textContent = tech.n;
        tipDesc.textContent = tech.d;
        const stRect = stage.getBoundingClientRect();
        const sRect = s.getBoundingClientRect();
        const left = sRect.left - stRect.left + sRect.width / 2 - 150;
        tip.style.left = Math.max(0, left) + 'px';
        tip.style.top = Math.max(0, sRect.top - stRect.top - tip.offsetHeight - 14) + 'px';
        tip.classList.add('on');
      }
    });
    s.addEventListener('mouseleave', () => {
      if (tip) tip.classList.remove('on');
    });
  });
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
