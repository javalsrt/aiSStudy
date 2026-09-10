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

// ===== 等轴测架构方块 · 悬停显示模块详解 =====
const isoBlocks = document.querySelectorAll('.iso-block');
const isoDescText = document.getElementById('isoDescText');
const isoDescDot = document.querySelector('.iso-desc-dot');
const isoDescBox = document.getElementById('isoDesc');
const ISO_DEFAULT = isoDescText ? isoDescText.textContent : '';

if (isoBlocks.length && isoDescText) {
  isoBlocks.forEach(block => {
    block.addEventListener('mouseenter', () => {
      isoDescText.textContent = block.dataset.desc;
      isoDescText.setAttribute('data-title', block.querySelector('.iso-label').firstChild.textContent + ' · 详细作用');
      isoDescBox.style.setProperty('--iso-c', getComputedStyle(block).getPropertyValue('--c'));
      if (isoDescDot) {
        isoDescDot.style.background = getComputedStyle(block).getPropertyValue('--c');
        isoDescDot.style.boxShadow = `0 0 12px ${getComputedStyle(block).getPropertyValue('--c')}`;
      }
    });
    block.addEventListener('mouseleave', () => {
      isoDescText.textContent = ISO_DEFAULT;
      isoDescText.removeAttribute('data-title');
      isoDescBox.style.removeProperty('--iso-c');
      if (isoDescDot) {
        isoDescDot.style.background = '';
        isoDescDot.style.boxShadow = '';
      }
    });
  });
}

// ===== 管理端截图预览 · Tab 切换 =====
const adminShot = document.getElementById('adminShot');
const adminShotUrl = document.getElementById('adminShotUrl');
if (adminShot && adminShotUrl) {
  document.querySelectorAll('.shot-tab').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.shot-tab').forEach(b => b.classList.remove('on'));
      btn.classList.add('on');
      adminShot.src = btn.dataset.src;
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
  const CARD_HALF_W = 125;
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
    orbitStage.addEventListener('mouseenter', () => { paused = true; });
    orbitStage.addEventListener('mouseleave', () => { paused = false; });
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
