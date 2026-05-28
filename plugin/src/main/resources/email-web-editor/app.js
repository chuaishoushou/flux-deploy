/* ============================================================
   邮件模板编辑器 SPA · 现代版
   - REST 同步插件本地 server
   - Quill 2 富文本 + FluxField embed 变量 chip
   - 自绘模板 popover、toast 栈、状态指示器
   - 所有 API 请求带 X-Flux-Token header（从 location.hash 解析）
   ============================================================ */
(function () {
  'use strict';

  // ──────────────────────────────────────────────────────────
  //   全局状态
  // ──────────────────────────────────────────────────────────
  const state = {
    token: '',
    templates: [],
    defaultName: 'default',
    currentName: '',
    runtimeValues: {},
    lastSavedAt: 0,
  };

  const DEFAULT_DISPLAY_LABEL = '默认模板';
  const RAW_HTML_KEYS = {
    '更新包': true,
    '更新包路径': true,
    '备份包': true,
    '备份包路径': true,
  };
  const VAR_NAMES = ['项目', '任务', '客服', '更新模式', '更新包', '更新包路径', '备份包', '备份包路径'];

  // ──────────────────────────────────────────────────────────
  //   工具
  // ──────────────────────────────────────────────────────────
  const $ = sel => document.querySelector(sel);
  const $$ = sel => Array.from(document.querySelectorAll(sel));

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/["\\]/g, '\\$&');
  }
  function displayLabelOf(name) {
    return name === state.defaultName ? DEFAULT_DISPLAY_LABEL : name;
  }
  function nameOf(label) {
    return label === DEFAULT_DISPLAY_LABEL ? state.defaultName : label;
  }
  function parseToken() {
    const m = (location.hash || '').match(/token=([0-9a-fA-F]+)/);
    state.token = m ? m[1] : '';
  }

  async function api(path, opts) {
    opts = opts || {};
    const headers = Object.assign({}, opts.headers || {});
    headers['X-Flux-Token'] = state.token;
    if (opts.body != null && !headers['Content-Type']) {
      headers['Content-Type'] = 'application/json';
    }
    const r = await fetch(path, Object.assign({}, opts, { headers: headers }));
    if (!r.ok) {
      const text = await r.text().catch(() => '');
      throw new Error('HTTP ' + r.status + ' ' + (text || r.statusText));
    }
    const ct = r.headers.get('Content-Type') || '';
    return ct.includes('application/json') ? r.json() : r.text();
  }

  // ──────────────────────────────────────────────────────────
  //   Toast 系统（栈式，多个并存）
  // ──────────────────────────────────────────────────────────
  function toast(message, kind, durationMs) {
    const stack = $('#toastStack');
    if (!stack) return;
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    const icon = kind === 'success' ? '✓' : kind === 'error' ? '✕' : kind === 'warn' ? '⚠' : 'ℹ';
    el.innerHTML = '<span class="toast-ico">' + icon + '</span>'
      + '<span class="toast-msg"></span>';
    el.querySelector('.toast-msg').textContent = message;
    stack.appendChild(el);
    const t = setTimeout(() => dismiss(el), durationMs || 2500);
    el.addEventListener('click', () => { clearTimeout(t); dismiss(el); });
    function dismiss(node) {
      node.classList.add('leaving');
      setTimeout(() => node.remove(), 250);
    }
  }

  function flashBtn(btn, doneText) {
    const label = btn.querySelector('span:not(.tb-ico)');
    if (!label) return;
    const orig = label.textContent;
    label.textContent = doneText;
    btn.classList.add('done');
    setTimeout(() => {
      label.textContent = orig;
      btn.classList.remove('done');
    }, 1500);
  }

  // ──────────────────────────────────────────────────────────
  //   Quill 配置
  // ──────────────────────────────────────────────────────────
  const Font = Quill.import('attributors/style/font');
  Font.whitelist = [
    // 中文字体
    'Microsoft YaHei', 'SimSun', 'SimHei', 'KaiTi', 'PingFang SC',
    // 邮件常见英文字体（企业微信 / Outlook / QQ 邮箱默认都常用这几个，加白名单是为了
    // 从这些客户端粘贴过来时 Quill 保留 font-family 不剥掉）
    'Tahoma', 'Arial', 'Helvetica', 'Verdana',
    'Times New Roman', 'Georgia', 'Courier New',
  ];
  Quill.register(Font, true);

  /* 字号：完全对齐 WeCom 中文字号制（pt 单位）。
     用户选「小四」→ 输出 <span style="font-size:12pt"> → 粘到 WeCom，WeCom 直接认成自己的"小四"
     视觉零偏差。下拉显示走 CSS ::before 的中文标签。 */
  const SIZE_VALUES = ['9pt', '10.5pt', '12pt', '14pt', '16pt', '18pt', '22pt', '26pt'];
  const Size = Quill.import('attributors/style/size');
  Size.whitelist = SIZE_VALUES;
  Quill.register(Size, true);

  const Align = Quill.import('attributors/style/align');
  Align.whitelist = ['right', 'center', 'justify'];
  Quill.register(Align, true);

  /* 行距：通过 Parchment 注册 block 级 StyleAttributor，覆盖编辑器全局 CSS 默认值
     （inline style 优先级高于 stylesheet）。WeCom 常用 5 档（单倍 / 1.15 / 1.5 / 2 / 3）。
     段距走 CSS 默认（p margin 8px，已按 WeCom 量级校准），不暴露到工具栏。 */
  const Parchment = Quill.import('parchment');
  const LINE_HEIGHT_VALUES = ['1', '1.15', '1.5', '2', '3'];
  const LineHeight = new Parchment.StyleAttributor('lineheight', 'line-height', {
    scope: Parchment.Scope.BLOCK,
    whitelist: LINE_HEIGHT_VALUES
  });
  Quill.register(LineHeight, true);

  const Embed = Quill.import('blots/embed');
  class FluxField extends Embed {
    static create(value) {
      const node = super.create(value);
      const key = (value && value.key) || '';
      const empty = !!(value && value.empty);
      const content = (value && value.value != null) ? value.value : '';
      node.setAttribute('data-key', key);
      node.setAttribute('contenteditable', 'false');
      if (empty) {
        node.classList.add('ph-empty');
        node.innerHTML = '${' + key + '}';
      } else {
        node.innerHTML = String(content);
      }
      return node;
    }
    static value(node) {
      return {
        key: node.getAttribute('data-key') || '',
        empty: node.classList.contains('ph-empty'),
        value: node.innerHTML,
        raw: true,
      };
    }
  }
  FluxField.blotName = 'flux-field';
  FluxField.tagName = 'SPAN';
  FluxField.className = 'ph';
  Quill.register(FluxField);

  const quill = new Quill('#editor', {
    theme: 'snow',
    placeholder: '在此处编辑邮件正文……',
    modules: {
      toolbar: {
        container: [
          [{ 'flux-var': VAR_NAMES.slice() }],
          [{ 'font': Font.whitelist }],
          [{ 'size': Size.whitelist }],
          [{ 'lineheight': LINE_HEIGHT_VALUES }],
          ['bold', 'italic', 'underline', 'strike'],
          [{ 'color': [] }, { 'background': [] }],
          [{ 'align': [] }],
          [{ 'list': 'ordered' }, { 'list': 'bullet' }],
          [{ 'indent': '-1' }, { 'indent': '+1' }],
          ['link'],
          ['clean'],
        ],
        handlers: {
          'flux-var': function (key) {
            if (!key) return;
            const range = quill.getSelection(true);
            if (!range) return;
            /* 手动插入：永远是空 chip 占位（${变量名} 灰色斜体）
               值的注入只通过「导入数据」按钮触发，不在这里偷偷读 state.runtimeValues。
               这样用户对模板里有哪些变量、什么时候被填，有明确的"事件分离"。 */
            quill.insertEmbed(range.index, 'flux-field', {
              key: key, empty: true, value: '', raw: true
            }, Quill.sources.USER);
            quill.setSelection(range.index + 1, Quill.sources.SILENT);
            setTimeout(() => {
              const label = document.querySelector('.ql-flux-var .ql-picker-label');
              if (label) {
                label.classList.remove('ql-active');
                label.removeAttribute('data-value');
              }
              document.querySelectorAll('.ql-flux-var .ql-picker-item')
                .forEach(it => it.classList.remove('ql-selected'));
            }, 0);
          },
        },
      },
    },
  });

  const Delta = Quill.import('delta');
  quill.clipboard.addMatcher('span.ph', function (node) {
    return new Delta().insert({
      'flux-field': {
        key: node.getAttribute('data-key') || '',
        empty: node.classList.contains('ph-empty'),
        value: node.innerHTML,
        raw: true,
      }
    });
  });

  // ──────────────────────────────────────────────────────────
  //   粘贴兼容：把"非白名单值"模糊映射到白名单（line-height / font-size / font-family）
  //   原因：Quill 严格白名单——白名单外的 line-height: 16px / font-size: 14px /
  //   font-family: "PingFang SC", sans-serif 等会被默认 matcher 全部丢弃，
  //   表现为"从邮件粘贴到编辑器，样式全没了"。下面这套 helper 把常见 unit / 字体栈
  //   折算回白名单内的最接近值，让"邮件 → 编辑器"也能往返。
  // ──────────────────────────────────────────────────────────
  function nearestNumeric(whitelist, target) {
    return whitelist.reduce((best, v) =>
      Math.abs(parseFloat(v) - target) < Math.abs(parseFloat(best) - target) ? v : best
    );
  }
  function nearestLineHeight(raw) {
    if (!raw) return null;
    const s = String(raw).trim().toLowerCase();
    if (s === 'normal' || s === '') return null;
    if (LINE_HEIGHT_VALUES.includes(s)) return s;
    let num;
    if (s.endsWith('%')) num = parseFloat(s) / 100;
    else if (/^[0-9.]+$/.test(s)) num = parseFloat(s);
    else return null; // px / em / rem 缺少字号 base，无法精准换算
    return isNaN(num) ? null : nearestNumeric(LINE_HEIGHT_VALUES, num);
  }
  function nearestSize(raw) {
    if (!raw) return null;
    const s = String(raw).trim();
    if (SIZE_VALUES.includes(s)) return s;
    let pt;
    if (s.endsWith('pt'))      pt = parseFloat(s);
    else if (s.endsWith('px')) pt = parseFloat(s) * 0.75;          // 1px = 0.75pt
    else if (s.endsWith('em')) pt = parseFloat(s) * 12;            // 按默认 12pt 起算
    else if (s.endsWith('%'))  pt = (parseFloat(s) / 100) * 12;    // 同上
    else return null;
    if (isNaN(pt)) return null;
    const nearest = nearestNumeric(SIZE_VALUES.map(v => parseFloat(v)), pt);
    return nearest + 'pt';
  }
  function matchFontFamily(stack) {
    if (!stack) return null;
    // 字体栈逐项匹配。企微邮件里常见 "system-ui, 'PingFang SC', 苹方-简, ..."，
    // 第一个不在白名单时往后走，找到第一个匹配的就用。
    const names = String(stack).split(',').map(s =>
      s.trim().replace(/^["']|["']$/g, '').toLowerCase()
    );
    for (const name of names) {
      if (!name) continue;
      const match = Font.whitelist.find(f => f.toLowerCase() === name);
      if (match) return match;
    }
    return null;
  }
  function applyInlineFormat(delta, format) {
    (delta.ops || []).forEach(op => {
      if (typeof op.insert === 'string' && op.insert.replace(/\n/g, '').length > 0) {
        // 关键：HTML 嵌套时内层 span 的样式覆盖外层。这里 matcher 先跑内层、后跑外层，
        // 所以"外层 format 不能覆盖内层已经写好的"——内层的 op.attributes 优先，外层做 fallback。
        op.attributes = Object.assign({}, format, op.attributes || {});
      }
    });
    return delta;
  }
  /* 把块级 format 挂到 delta 的"块尾 \n"上。
     时机要点：P/DIV matcher 触发时，传进来的 delta 只装段内文本 ops，**末尾还没有 \n**
     （\n 是 Quill 在 matcher 跑完之后补的"块边界"）。所以如果只改"已有 \n"，多数情况
     都找不到 → 块 format 永远写不进去。这里改成：找得到就改，找不到就 append 一个
     带 format 的 \n 当块尾。Quill 后续的 deltaEndsWith 检测不会再重复补一个。 */
  function attachBlockFormat(delta, format) {
    const ops = delta.ops || [];
    for (let i = ops.length - 1; i >= 0; i--) {
      const op = ops[i];
      if (typeof op.insert === 'string' && op.insert.includes('\n')) {
        op.attributes = Object.assign({}, op.attributes || {}, format);
        return delta;
      }
    }
    return delta.insert('\n', format);
  }

  // 不再丢弃空段落（<p><br></p> / <p></p> / 纯空白段）。之前丢弃导致用户在编辑器里敲的
  // 空行一经"切换模板 → 重新加载（clipboard.convert）"就消失。保留 = Quill 默认行为，
  // 也更贴合所见即所得：粘贴源里的空行原样保留，由用户自己控制增删。
  quill.clipboard.addMatcher('P', function (node, delta) {
    const lh = nearestLineHeight(node.style && node.style.lineHeight);
    if (lh) attachBlockFormat(delta, { lineheight: lh });
    return delta;
  });
  quill.clipboard.addMatcher('DIV', function (node, delta) {
    const lh = nearestLineHeight(node.style && node.style.lineHeight);
    if (lh) attachBlockFormat(delta, { lineheight: lh });
    return delta;
  });
  // SPAN：模糊匹配 font-size / font-family。注意 span.ph（变量 chip）已经被前面的
  // 专用 matcher 截走（返回的是 flux-field embed，insert 是对象而非字符串），
  // 这里的 string-only 过滤天然跳过 chip，不冲突。
  quill.clipboard.addMatcher('SPAN', function (node, delta) {
    if (node.classList && node.classList.contains('ph')) return delta;
    const fmt = {};
    const size = nearestSize(node.style && node.style.fontSize);
    if (size) fmt.size = size;
    const font = matchFontFamily(node.style && node.style.fontFamily);
    if (font) fmt.font = font;
    if (Object.keys(fmt).length > 0) applyInlineFormat(delta, fmt);
    return delta;
  });

  // ──────────────────────────────────────────────────────────
  //   模板序列化 / 反序列化
  // ──────────────────────────────────────────────────────────
  /* 模板加载：永远渲染为空 chip 占位（${字段名} 灰色斜体）。
     值的注入只通过「导入数据」按钮的 updateChipValue 路径走。
     这样行为可预测：只要重新加载 / 切换 / 恢复默认 模板，chip 就回到占位态；
     之前导入的快照值也作废（state.runtimeValues 清空）。 */
  function loadTemplate(templateHtml) {
    const rendered = (templateHtml || '').replace(
      /\$\{([^}\s]+)\}/g,
      function (_, key) {
        const safeKey = escapeHtml(key);
        return '<span class="ph ph-empty" data-key="' + safeKey
          + '" contenteditable="false">${' + safeKey + '}</span>';
      }
    );
    const delta = quill.clipboard.convert({ html: rendered });
    quill.setContents(delta, 'silent');
    state.runtimeValues = {};
  }

  function serializeTemplate() {
    const clone = quill.root.cloneNode(true);
    clone.querySelectorAll('.ph').forEach(chip => {
      const key = chip.getAttribute('data-key') || '';
      chip.parentNode.replaceChild(document.createTextNode('${' + key + '}'), chip);
    });
    return clone.innerHTML;
  }

  /* 多行路径变量：续行需要注入"等宽缩进"才能在 WeCom 里保持对齐。
     编辑器里之所以续行能对齐，是因为 chip 的 display:inline-block 让 <br> 续行靠在
     chip 左边（= 标签右侧）；脱壳之后这层包裹没了，续行就回到左边沿。
     方案：脱壳前把内部 <br> 换成 <br> + 跟标签等宽的全角空格。
     标签 "更新包路径：" / "备份包路径：" 都是 6 个全角字 + 1 全角冒号 = 7 个全角字符宽。 */
  const MULTILINE_INDENT_KEYS = { '更新包路径': true, '备份包路径': true };
  const INDENT_FULLWIDTH_SPACES = '　　　　　　　'; // 7 个全角空格

  /* chip 脱壳：把 .ph 元素替换成内部文本（多行路径 chip 还要给续行加全角空格缩进） */
  function unwrapChips(rootEl) {
    rootEl.querySelectorAll('.ph').forEach(chip => {
      if (chip.classList.contains('ph-empty')) {
        chip.outerHTML = '';
        return;
      }
      let inner = chip.innerHTML;
      const key = chip.getAttribute('data-key') || '';
      if (MULTILINE_INDENT_KEYS[key]) {
        inner = inner.replace(/<br\s*\/?>/gi, '<br>' + INDENT_FULLWIDTH_SPACES);
      }
      chip.outerHTML = inner;
    });
  }

  /* 段首前导空白 → 全角空格 `　`（U+3000）
     之前用 CSS text-indent：纯视觉缩进、前面无真实字符 → 鼠标光标跳过、无法选中。
     改成真实的全角空格字符：
       - 是真实字符，可选、方向键可定位
       - 不属于 ASCII 空白，WeCom / Outlook 等客户端不会规范化吞掉
       - 视觉宽度按"中文 1 字宽"折算，符合国标"首行缩进 2 字"
     按视觉宽度折算：全角空格=1字宽，nbsp=0.5字宽，普通空格=0.25字宽，
     累加后四舍五入到最接近的"整数字宽"，输出对应数量的 U+3000。 */
  function applyParagraphIndent(rootEl) {
    rootEl.querySelectorAll('p').forEach(p => {
      const firstText = findFirstTextNode(p);
      if (!firstText) return;
      const text = firstText.textContent || '';
      let idx = 0;
      let widthChars = 0;
      while (idx < text.length) {
        const code = text.charCodeAt(idx);
        if (code === 0x3000 || code === 0x2003) {
          widthChars += 1;
        } else if (code === 0x00A0) {
          widthChars += 0.5;
        } else if (code === 0x20) {
          widthChars += 0.25;
        } else {
          break;
        }
        idx++;
      }
      if (idx === 0) return;
      const fullWidthCount = Math.max(1, Math.round(widthChars));
      firstText.textContent = '　'.repeat(fullWidthCount) + text.slice(idx);
    });
  }

  function findFirstTextNode(el) {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
    return walker.nextNode();
  }

  /* 复制 / 导出统一走这条管线：clone DOM → 脱壳 chip → 段首缩进转 text-indent */
  function processForClipboard(rootEl) {
    unwrapChips(rootEl);
    applyParagraphIndent(rootEl);
  }

  function serializeRendered() {
    const clone = quill.root.cloneNode(true);
    processForClipboard(clone);
    return clone.innerHTML;
  }

  function updateChipValue(key, value) {
    const chips = quill.root.querySelectorAll(
      '.ph[data-key="' + cssEscape(key) + '"]');
    chips.forEach(chip => {
      const empty = (value === undefined || value === null || value === '');
      if (empty) {
        chip.classList.add('ph-empty');
        chip.innerHTML = '${' + escapeHtml(key) + '}';
      } else {
        chip.classList.remove('ph-empty');
        chip.innerHTML = RAW_HTML_KEYS[key] ? String(value) : escapeHtml(value);
      }
    });
  }

  function stripHtml(html) {
    const div = document.createElement('div');
    div.innerHTML = html;
    return div.textContent || div.innerText || '';
  }

  // ──────────────────────────────────────────────────────────
  //   API 包装
  // ──────────────────────────────────────────────────────────
  const apiListTemplates  = ()         => api('/api/templates');
  const apiLoadTemplate   = name       => api('/api/templates/' + encodeURIComponent(name));
  const apiSaveTemplate   = (name, c)  => api('/api/templates/' + encodeURIComponent(name),
                                          { method: 'PUT', body: JSON.stringify({ content: c }) });
  const apiDeleteTemplate = name       => api('/api/templates/' + encodeURIComponent(name),
                                          { method: 'DELETE' });
  const apiRestoreTemplate= name       => api('/api/templates/' + encodeURIComponent(name)
                                          + '/restore', { method: 'POST' });
  const apiRuntimeData    = ()         => api('/api/runtime-data');

  // ──────────────────────────────────────────────────────────
  //   模板 popover：自绘下拉列表
  // ──────────────────────────────────────────────────────────
  const tplPillBtn = $('#tplPillBtn');
  const tplPopover = $('#tplPopover');
  const tplPopoverInner = $('#tplPopoverInner');

  function openPopover() {
    tplPopover.hidden = false;
    requestAnimationFrame(() => {
      tplPopover.classList.add('open');
      tplPillBtn.setAttribute('aria-expanded', 'true');
    });
  }
  function closePopover() {
    tplPopover.classList.remove('open');
    tplPillBtn.setAttribute('aria-expanded', 'false');
    setTimeout(() => {
      if (!tplPopover.classList.contains('open')) tplPopover.hidden = true;
    }, 200);
  }
  function togglePopover() {
    if (tplPopover.hidden || !tplPopover.classList.contains('open')) {
      openPopover();
    } else {
      closePopover();
    }
  }

  tplPillBtn.addEventListener('click', e => {
    e.stopPropagation();
    togglePopover();
  });
  document.addEventListener('click', e => {
    if (tplPopover.hidden) return;
    if (tplPopover.contains(e.target) || tplPillBtn.contains(e.target)) return;
    closePopover();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !tplPopover.hidden) closePopover();
  });

  function renderTemplateList() {
    tplPopoverInner.innerHTML = '';
    if (state.templates.length === 0) {
      tplPopoverInner.innerHTML = '<div class="tpl-item" style="color:var(--fg-muted);">暂无模板</div>';
      return;
    }
    state.templates.forEach(n => {
      const item = document.createElement('button');
      item.className = 'tpl-item' + (n === state.currentName ? ' selected' : '');
      const isDefault = n === state.defaultName;
      item.innerHTML =
        '<svg class="tpl-check" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
        + ' stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">'
        + '<polyline points="20 6 9 17 4 12"/></svg>'
        + '<span class="tpl-name"></span>'
        + (isDefault ? '<span class="tpl-badge">默认</span>' : '');
      item.querySelector('.tpl-name').textContent = displayLabelOf(n);
      item.addEventListener('click', async () => {
        closePopover();
        if (n === state.currentName) return;
        state.currentName = n;
        $('#currentTplLabel').textContent = displayLabelOf(n);
        await reloadEditorWithCurrent();
        renderTemplateList();
      });
      tplPopoverInner.appendChild(item);
    });
  }

  // ──────────────────────────────────────────────────────────
  //   模板加载流程
  // ──────────────────────────────────────────────────────────
  async function refreshTemplateList(selectName) {
    try {
      const data = await apiListTemplates();
      state.templates = data.templates || [];
      state.defaultName = data.defaultName || 'default';
      if (selectName && state.templates.includes(selectName)) {
        state.currentName = selectName;
      } else if (state.templates.length) {
        state.currentName = state.templates[0];
      }
      $('#currentTplLabel').textContent = displayLabelOf(state.currentName);
      renderTemplateList();
    } catch (e) {
      toast('加载模板列表失败：' + e.message, 'error', 4000);
    }
  }

  async function reloadEditorWithCurrent() {
    if (!state.currentName) return;
    try {
      const tpl = await apiLoadTemplate(state.currentName);
      loadTemplate(tpl.content);
    } catch (e) {
      toast('加载模板失败：' + e.message, 'error', 4000);
    }
  }

  // ──────────────────────────────────────────────────────────
  //   action 按钮
  // ──────────────────────────────────────────────────────────
  $('#newTplBtn').addEventListener('click', async () => {
    closePopover();
    const name = prompt('新模板名称（中英文 / 数字 / 下划线 / 横线）：');
    if (!name) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    if (state.templates.includes(trimmed)) {
      toast('已存在同名模板', 'error');
      return;
    }
    try {
      const current = serializeTemplate();
      await apiSaveTemplate(trimmed, current);
      await refreshTemplateList(trimmed);
      await reloadEditorWithCurrent();
      toast('已新建并切换到「' + trimmed + '」', 'success');
    } catch (e) {
      toast('新建失败：' + e.message, 'error', 4000);
    }
  });

  $('#restoreBtn').addEventListener('click', async () => {
    closePopover();
    const name = state.currentName;
    if (!name) return;
    const isDefault = name === state.defaultName;
    const msg = isDefault
      ? '确认把「默认模板」恢复为插件出厂内置内容？\n当前内容会被覆盖，无法撤销。'
      : '确认把模板「' + displayLabelOf(name)
        + '」恢复为<当前默认模板>的内容？\n当前内容会被覆盖。';
    if (!confirm(msg)) return;
    try {
      const data = await apiRestoreTemplate(name);
      loadTemplate(data.content);
      toast('已恢复默认', 'success');
    } catch (e) {
      toast('恢复失败：' + e.message, 'error', 4000);
    }
  });

  $('#deleteBtn').addEventListener('click', async () => {
    closePopover();
    const name = state.currentName;
    if (!name) return;
    if (name === state.defaultName) {
      toast('默认模板不可删除（可点"恢复默认"重置内容）', 'warn', 4000);
      return;
    }
    if (!confirm('确认删除模板「' + displayLabelOf(name) + '」？无法恢复。')) return;
    try {
      await apiDeleteTemplate(name);
      await refreshTemplateList(state.defaultName);
      await reloadEditorWithCurrent();
      toast('已删除', 'success');
    } catch (e) {
      toast('删除失败：' + e.message, 'error', 4000);
    }
  });

  $('#saveBtn').addEventListener('click', async () => {
    if (!state.currentName) return;
    try {
      const html = serializeTemplate();
      await apiSaveTemplate(state.currentName, html);
      state.lastSavedAt = Date.now();
      flashBtn($('#saveBtn'), '✓ 已保存');
    } catch (e) {
      toast('保存失败：' + e.message, 'error', 4000);
    }
  });

  $('#importBtn').addEventListener('click', async () => {
    try {
      const values = await apiRuntimeData();
      if (!values || Object.keys(values).length === 0) {
        toast('暂无可导入内容：请先在插件主面板填任务 / 客服，或先部署一次', 'warn', 5000);
        return;
      }
      state.runtimeValues = Object.assign({}, state.runtimeValues, values);
      Object.keys(values).forEach(k => updateChipValue(k, values[k]));
      flashBtn($('#importBtn'), '✓ 已导入');
      toast('已导入 ' + Object.keys(values).length + ' 个变量', 'success');
    } catch (e) {
      toast('导入失败：' + e.message, 'error', 4000);
    }
  });

  $('#copyBtn').addEventListener('click', async () => {
    const html = serializeRendered();
    const plain = stripHtml(html);
    try {
      if (window.ClipboardItem && navigator.clipboard && navigator.clipboard.write) {
        const item = new ClipboardItem({
          'text/html': new Blob([html], { type: 'text/html' }),
          'text/plain': new Blob([plain], { type: 'text/plain' }),
        });
        await navigator.clipboard.write([item]);
      } else {
        const tmp = document.createElement('div');
        tmp.innerHTML = html;
        tmp.style.position = 'fixed';
        tmp.style.left = '-9999px';
        document.body.appendChild(tmp);
        const range = document.createRange();
        range.selectNodeContents(tmp);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        document.execCommand('copy');
        sel.removeAllRanges();
        document.body.removeChild(tmp);
      }
      flashBtn($('#copyBtn'), '✓ 已复制');
      toast('已复制到剪贴板', 'success');
    } catch (e) {
      toast('复制失败：' + e.message, 'error', 4000);
    }
  });

  // ──────────────────────────────────────────────────────────
  //   复制事件拦截（Ctrl+A / Cmd+A 复制时让 chip 输出真实文本）
  // ──────────────────────────────────────────────────────────
  function interceptCopy(ev) {
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;
    const range = sel.getRangeAt(0);
    if (range.collapsed) return;
    try {
      const frag = range.cloneContents();
      const container = document.createElement('div');
      container.appendChild(frag);
      /* 跟 serializeRendered 走同一条处理管线：chip 脱壳 + 段首缩进转 text-indent。
         避免 Ctrl+C 复制时缩进丢失（之前直接 setData(container.innerHTML)，
         WeCom 把前导 nbsp 规范化掉 → 视觉缩进消失） */
      processForClipboard(container);
      ev.clipboardData.setData('text/plain', container.textContent || '');
      ev.clipboardData.setData('text/html', container.innerHTML);
      ev.preventDefault();
    } catch (e) { /* fall back */ }
  }
  quill.root.addEventListener('copy', interceptCopy, true);
  quill.root.addEventListener('cut', interceptCopy, true);

  // ──────────────────────────────────────────────────────────
  //   键盘快捷键：Cmd/Ctrl+S 保存
  // ──────────────────────────────────────────────────────────
  document.addEventListener('keydown', e => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      $('#saveBtn').click();
    }
  });

  // ──────────────────────────────────────────────────────────
  //   引导
  // ──────────────────────────────────────────────────────────
  async function bootstrap() {
    parseToken();
    if (!state.token) {
      toast('未检测到 token，请从插件按钮跳转打开本页面', 'error', 5000);
      return;
    }
    /* 不预加载 runtime data —— 跟 JCEF 版语义一致：初次打开时 chip 都是空占位，
       值只有点「导入数据」时才统一刷新。这样用户对"什么时候 chip 被填"有明确控制。 */
    state.runtimeValues = {};
    await refreshTemplateList();
    await reloadEditorWithCurrent();
  }

  bootstrap();
})();
