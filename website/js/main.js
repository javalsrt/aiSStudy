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

// ===== 技术架构马赛克（移植自 uiverse csemzepp）=====
// 中央 3×3 立方体阵列 + 上/中/下三排技术方块，悬停显示技术详情
const techMosaic = document.getElementById('techMosaic');
if (techMosaic) {
  // 中央 3×3 立方体（纯装饰，与参考一致）
  const center = document.createElement('div');
  center.className = 'mosaic-center';
  for (let r = 0; r < 3; r++) {
    for (let c = 0; c < 3; c++) {
      const s = document.createElement('span');
      s.style.setProperty('--gx', ((c - 1) * 62) + 'px');
      s.style.setProperty('--gy', ((r - 1) * 56) + 'px');
      center.appendChild(s);
    }
  }
  techMosaic.appendChild(center);

  // 三排技术方块数据：上=前端 / 中=后端核心算法 / 下=底层模型
  const TECH_ROWS = [
    { color: '#7dd3fc', items: [
      { n: 'Android App', d: 'Java 原生开发的学生端应用，覆盖刷题、专注训练与 AI 答疑全场景。' },
      { n: 'React 管理端', d: 'React 18 + Vite 构建的教师管理后台，组件化开发、路由懒加载。' },
      { n: '微信小程序', d: 'uni-app 跨端开发，轻量触达学生用户，与 App 数据同源。' },
      { n: 'Tailwind CSS', d: '原子化 CSS 框架，统一 Web 端设计语言与视觉规范。' },
      { n: 'ECharts 可视化', d: '六维能力雷达、学情趋势与统计图表的高性能渲染。' }
    ]},
    { color: '#93c5fd', items: [
      { n: 'Spring Boot', d: 'Controller / Service / Mapper 三层架构，统一 REST API 网关。' },
      { n: 'RAG 检索增强', d: '按已学章节向量检索教材内容注入 Prompt，保证 AI 出题不超纲。' },
      { n: 'JWT 鉴权 + 限流', d: '无状态令牌认证，配合令牌桶算法实现接口并发控制。' },
      { n: 'WebSocket', d: '专注度数据的实时双向推送通道，秒级同步学习状态。' },
      { n: 'AI 语义判分', d: '大模型按要点覆盖与语义一致性判定主观题得分，拒绝死板比对。' }
    ]},
    { color: '#c4b5fd', items: [
      { n: 'BGE-M3', d: 'BGE-M3（BAAI General Embedding M3），是北京智源人工智能研究院（BAAI）开源的多语言全能文本嵌入（Embedding）模型。' },
      { n: 'DeepSeek V4 Flash', d: '大语言模型驱动的智能出题与答疑引擎，支持流式输出。' },
      { n: '向量相似度检索', d: '基于余弦相似度的章节向量匹配，Top-K 召回最相关教材片段。' },
      { n: 'MySQL 8.0', d: '业务数据、题库与出题缓存指纹的持久化存储。' },
      { n: 'Docker Compose', d: '前端、后端、数据库与 Nginx 网关一键容器化编排部署。' }
    ]}
  ];

  const tip = document.getElementById('cubeTip');
  const tipName = document.getElementById('cubeTipName');
  const tipDesc = document.getElementById('cubeTipDesc');
  const isoDescText = document.getElementById('isoDescText');
  const isoDescDot = document.querySelector('.iso-desc-dot');
  const isoDescBox = document.getElementById('isoDesc');
  const ISO_DEFAULT = isoDescText ? isoDescText.textContent : '';

  TECH_ROWS.forEach((row, r) => {
    row.items.forEach((item, c) => {
      const s = document.createElement('span');
      s.className = 'cube';
      s.style.setProperty('--gx', ((c - 2) * 102) + 'px');
      s.style.setProperty('--gy', ((r - 1) * 92) + 'px');
      s.style.setProperty('--cc', row.color);
      s.dataset.name = item.n;
      s.dataset.desc = item.d;
      techMosaic.appendChild(s);

      s.addEventListener('mouseenter', () => {
        s.classList.add('active');
        if (tip && tipName && tipDesc) {
          tipName.textContent = item.n;
          tipDesc.textContent = item.d;
          // 提示框定位到方块上方（相对未旋转的 stage）
          const mRect = techMosaic.getBoundingClientRect();
          const sRect = s.getBoundingClientRect();
          tip.style.left = (sRect.left - mRect.left + sRect.width / 2 - 150) + 'px';
          tip.style.top = (sRect.top - mRect.top - tip.offsetHeight - 16) + 'px';
          tip.classList.add('on');
        }
        if (isoDescText && isoDescBox) {
          isoDescText.textContent = item.d;
          isoDescText.setAttribute('data-title', item.n);
          const rowColor = row.color;
          isoDescBox.style.setProperty('--iso-c', rowColor);
          if (isoDescDot) {
            isoDescDot.style.background = rowColor;
            isoDescDot.style.boxShadow = `0 0 12px ${rowColor}`;
          }
        }
      });
      s.addEventListener('mouseleave', () => {
        s.classList.remove('active');
        if (tip) tip.classList.remove('on');
        if (isoDescText) {
          isoDescText.textContent = ISO_DEFAULT;
          isoDescText.removeAttribute('data-title');
          isoDescBox.style.removeProperty('--iso-c');
          if (isoDescDot) { isoDescDot.style.background = ''; isoDescDot.style.boxShadow = ''; }
        }
      });
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
