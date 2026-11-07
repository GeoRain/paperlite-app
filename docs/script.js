/* PaperLite 官网交互
   1) Material Symbols 图标按 data-icon 渲染
   2) 深浅色主题切换（跟随系统 + 手动覆盖，记忆到 localStorage）
   3) 多页面切换：侧边栏 / 顶栏 / 移动端底部导航三处联动
   4) 滚动时同步高亮当前区块 */

(function () {
  'use strict';

  /* ---------------------------------------------------- Material Symbols */

  // 把 <span class="msr" data-icon="xxx"> 换成真正的图标字形
  function renderIcons(root) {
    var nodes = (root || document).querySelectorAll('.msr[data-icon]');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var name = el.getAttribute('data-icon');
      if (!name) continue;
      el.textContent = name;
      el.setAttribute('aria-hidden', 'true');
    }
  }

  /* ---------------------------------------------------- 主题 */

  var STORAGE_KEY = 'paperlite-theme';
  var media = window.matchMedia('(prefers-color-scheme: dark)');

  function currentTheme() {
    // URL 参数优先，方便分享固定深浅色的链接（?theme=light / ?theme=dark）
    var q = '';
    try { q = new URLSearchParams(location.search).get('theme') || ''; } catch (e) { /* 忽略 */ }
    if (q === 'light' || q === 'dark') return q;

    var saved = null;
    try { saved = localStorage.getItem(STORAGE_KEY); } catch (e) { /* 隐私模式 */ }
    if (saved === 'light' || saved === 'dark') return saved;
    return media.matches ? 'dark' : 'light';
  }

  function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    var btn = document.getElementById('themeToggle');
    if (btn) {
      var ic = btn.querySelector('.msr');
      if (ic) {
        ic.textContent = theme === 'dark' ? 'light_mode' : 'dark_mode';
      }
      btn.setAttribute('aria-label', theme === 'dark' ? '切换到浅色模式' : '切换到深色模式');
    }
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'dark' ? '#000000' : '#FFFFFF');
  }

  function initTheme() {
    applyTheme(currentTheme());

    var btn = document.getElementById('themeToggle');
    if (btn) {
      btn.addEventListener('click', function () {
        var next = document.documentElement.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
        applyTheme(next);
        try { localStorage.setItem(STORAGE_KEY, next); } catch (e) { /* 忽略 */ }
      });
    }

    // 用户没有手动指定过时，跟随系统变化
    var onChange = function (e) {
      var saved = null;
      try { saved = localStorage.getItem(STORAGE_KEY); } catch (err) { /* 忽略 */ }
      if (!saved) applyTheme(e.matches ? 'dark' : 'light');
    };
    if (media.addEventListener) media.addEventListener('change', onChange);
    else if (media.addListener) media.addListener(onChange);
  }

  /* ---------------------------------------------------- 页面切换 */

  var SECTIONS = ['overview', 'features', 'channels', 'usage', 'desktop', 'notice', 'download', 'about'];

  function showSection(id) {
    if (SECTIONS.indexOf(id) < 0) id = 'overview';

    var pages = document.querySelectorAll('.page');
    for (var i = 0; i < pages.length; i++) {
      pages[i].classList.toggle('is-active', pages[i].id === id);
    }

    var groups = ['.nav-link', '.side-link', '.mnav'];
    for (var g = 0; g < groups.length; g++) {
      var links = document.querySelectorAll(groups[g]);
      for (var j = 0; j < links.length; j++) {
        var href = links[j].getAttribute('href') || '';
        links[j].classList.toggle('is-active', href === '#' + id);
      }
    }

    if (history.replaceState) history.replaceState(null, '', '#' + id);
  }

  function initNav() {
    // 所有带 hash 的站内链接统一接管
    var links = document.querySelectorAll('a[href^="#"]');
    for (var i = 0; i < links.length; i++) {
      links[i].addEventListener('click', function (e) {
        var href = this.getAttribute('href');
        if (!href || href === '#') return;
        var id = href.slice(1);
        if (SECTIONS.indexOf(id) < 0) return;
        e.preventDefault();
        showSection(id);
        window.scrollTo({ top: 0, behavior: 'smooth' });
      });
    }

    window.addEventListener('hashchange', function () {
      showSection(location.hash.slice(1));
    });

    var initial = location.hash.slice(1);
    showSection(SECTIONS.indexOf(initial) >= 0 ? initial : 'overview');
  }

  /* ---------------------------------------------------- 滚动进度条 */

  function initScrollBar() {
    var bar = document.getElementById('progress');
    if (!bar) return;

    function update() {
      var h = document.documentElement.scrollHeight - window.innerHeight;
      var pct = h > 0 ? (window.scrollY / h) * 100 : 0;
      bar.style.width = Math.min(100, Math.max(0, pct)) + '%';
    }

    window.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    update();
  }

  /* ---------------------------------------------------- 入场动画 */

  function initReveal() {
    var targets = document.querySelectorAll(
      '.f-card, .ch-item, .s-card, .stat, .note, .dlg-card, .ai-block, .ex-block'
    );

    if (!('IntersectionObserver' in window)) {
      for (var i = 0; i < targets.length; i++) targets[i].style.opacity = '1';
      return;
    }

    for (var j = 0; j < targets.length; j++) {
      targets[j].style.opacity = '0';
      targets[j].style.transform = 'translateY(16px)';
      targets[j].style.transition = 'opacity .5s cubic-bezier(.22,1,.36,1), transform .5s cubic-bezier(.22,1,.36,1)';
    }

    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry, idx) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        var delay = Math.min(idx * 45, 260);
        setTimeout(function () {
          el.style.opacity = '1';
          el.style.transform = 'none';
        }, delay);
        io.unobserve(el);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });

    for (var k = 0; k < targets.length; k++) io.observe(targets[k]);
  }

  /* ---------------------------------------------------- 启动 */

  function boot() {
    renderIcons();
    initTheme();
    initNav();
    initScrollBar();
    initReveal();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
