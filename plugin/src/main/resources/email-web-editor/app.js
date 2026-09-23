/* ============================================================
   邮件模板编辑器 SPA · 现代版
   - 通过 JBCefJSQuery 桥与插件 Java 端通信（list / load / save /
     delete / restore / runtime-data / copyToClipboard）
   - Quill 2 富文本 + 变量为 fluxvar 高亮文本（可编辑，复制时填值）
   - 自绘模板 popover、toast 栈、状态指示器
   ============================================================ */
(function () {
  'use strict';

  // ──────────────────────────────────────────────────────────
  //   全局状态
  // ──────────────────────────────────────────────────────────
  const state = {
    templates: [],
    defaultName: 'default',
    currentName: '',
    runtimeValues: {},
    lastSavedAt: 0,
    previewing: false,        // 是否处于"导入后填值预览"态（编辑器显示值而非 ${变量}）
    templateSnapshot: '',     // 进入预览前的 ${变量} 模板态 HTML，预览态保存时用它（避免把值存成模板）
  };

  const DEFAULT_DISPLAY_LABEL = '默认模板';
  const RAW_HTML_KEYS = {
    '更新包': true,
    '更新jar包': true,
    '更新war包': true,
    '更新包地址': true,
    '备份包': true,
    '备份包地址': true,
  };
  const VAR_NAMES = ['项目', '任务', '客服', '更新模式', 'FTP版本来源', '更新包', '更新jar包', '更新war包', '更新包地址', '备份包', '备份包地址'];

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
  // ──────────────────────────────────────────────────────────
  //   插件桥：所有数据通道走 JBCefJSQuery（替代原来的 REST fetch）
  //   window.fluxBridge 由 JCEF 宿主在页面加载完成后注入；下面把原来的
  //   RESTful (path, method, body) 翻译成 {op,...} 桥消息，这样上层
  //   apiListTemplates / apiLoadTemplate / ... 一行都不用改。
  // ──────────────────────────────────────────────────────────
  function bridgeCall(payload) {
    return new Promise(function (resolve, reject) {
      if (typeof window.fluxBridge !== 'function') {
        reject(new Error('插件桥未就绪，请重新打开邮件模板编辑器'));
        return;
      }
      window.fluxBridge(JSON.stringify(payload), function (response) {
        try {
          const data = response ? JSON.parse(response) : null;
          if (data && data.error) reject(new Error(data.error));
          else resolve(data);
        } catch (e) {
          reject(new Error('桥响应解析失败：' + e.message));
        }
      }, function (errCode, errMsg) {
        reject(new Error('桥调用失败：' + (errMsg || errCode)));
      });
    });
  }

  /* 把原 REST 风格的 (path, method, body) 翻译成桥消息 {op,...}。
     返回结构与原 server 端保持一致：list→{templates,defaultName}、
     load/restore→{name,content}、save/delete→{ok:true}、runtimeData→{key:val}。 */
  function restToBridgePayload(path, method, body) {
    if (path === '/api/runtime-data') return { op: 'runtimeData' };
    if (path === '/api/templates') return { op: 'list' };
    const prefix = '/api/templates/';
    if (path.indexOf(prefix) === 0) {
      const rest = path.substring(prefix.length);
      const restoreSuffix = '/restore';
      if (rest.slice(-restoreSuffix.length) === restoreSuffix) {
        const rn = decodeURIComponent(rest.substring(0, rest.length - restoreSuffix.length));
        return { op: 'restore', name: rn };
      }
      const name = decodeURIComponent(rest);
      if (method === 'POST') return { op: 'new', name: name };
      if (method === 'PUT') {
        let content = '';
        try { content = body ? (JSON.parse(body).content || '') : ''; } catch (e) { content = ''; }
        return { op: 'save', name: name, content: content };
      }
      if (method === 'DELETE') return { op: 'delete', name: name };
      return { op: 'load', name: name };
    }
    throw new Error('未知 API 路径：' + path);
  }

  async function api(path, opts) {
    opts = opts || {};
    const method = (opts.method || 'GET').toUpperCase();
    return bridgeCall(restToBridgePayload(path, method, opts.body));
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

  // ──────────────────────────────────────────────────────────
  //   页内自定义弹窗（替代 JCEF 原生 confirm / prompt 的丑壳：
  //   原生框标题会暴露 file:///jbcefbrowser/… 且按钮是英文 Cancel/OK）
  // ──────────────────────────────────────────────────────────
  function showModal(opts) {
    return new Promise(resolve => {
      const overlay = document.createElement('div');
      overlay.className = 'modal-overlay';
      const card = document.createElement('div');
      card.className = 'modal-card';
      card.setAttribute('role', 'dialog');
      card.setAttribute('aria-modal', 'true');

      const msg = document.createElement('div');
      msg.className = 'modal-message';
      msg.textContent = opts.message || '';
      card.appendChild(msg);

      let input = null;
      if (opts.input) {
        input = document.createElement('input');
        input.className = 'modal-input';
        input.type = 'text';
        if (opts.placeholder) input.placeholder = opts.placeholder;
        card.appendChild(input);
      }

      const actions = document.createElement('div');
      actions.className = 'modal-actions';
      const cancelBtn = document.createElement('button');
      cancelBtn.className = 'modal-btn modal-cancel';
      cancelBtn.textContent = opts.cancelText || '取消';
      const okBtn = document.createElement('button');
      okBtn.className = 'modal-btn modal-ok' + (opts.danger ? ' danger' : '');
      okBtn.textContent = opts.okText || '确定';
      actions.appendChild(cancelBtn);
      actions.appendChild(okBtn);
      card.appendChild(actions);

      overlay.appendChild(card);
      document.body.appendChild(overlay);

      if (input) { input.focus(); input.select(); } else { okBtn.focus(); }

      function finish(ok) {
        document.removeEventListener('keydown', onKey, true);
        overlay.remove();
        resolve({ ok: ok, value: input ? input.value.trim() : '' });
      }
      function onKey(e) {
        if (e.key === 'Escape') { e.preventDefault(); finish(false); }
        else if (e.key === 'Enter') { e.preventDefault(); finish(true); }
      }
      document.addEventListener('keydown', onKey, true);
      cancelBtn.addEventListener('click', () => finish(false));
      okBtn.addEventListener('click', () => finish(true));
      overlay.addEventListener('mousedown', e => { if (e.target === overlay) finish(false); });
    });
  }

  /** 自定义确认框，返回 Promise<boolean>；danger=true 时确定按钮为红色（删除等危险操作）。 */
  function showConfirm(message, danger) {
    return showModal({ message: message, danger: !!danger }).then(r => r.ok);
  }

  /** 自定义输入框，返回 Promise<string|null>（取消或空输入返回 null）。 */
  function showPrompt(message, placeholder) {
    return showModal({ message: message, input: true, placeholder: placeholder })
      .then(r => (r.ok && r.value) ? r.value : null);
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

  /* 变量高亮：带 data-key 的 inline format（非 atomic embed），让变量是真实可编辑文本——
     可拖选跨越、可手动增删改；橙色仅作编辑器内视觉区分，复制/保存时脱掉。
     data-key 记住"这是哪个变量"：导入时 span 内文本换成值后，保存仍能凭它还原回 ${key}。
     必须仿 Quill 内置 link blot 补齐 create/formats/format 三件套——否则 clipboard.convert
     不认 class="fluxvar"，高亮会整体丢失（变成黑色普通文本）。 */
  const Inline = Quill.import('blots/inline');
  class FluxVar extends Inline {
    static create(value) {
      const node = super.create(value);
      node.setAttribute('data-key', value == null ? '' : String(value));
      return node;
    }
    static formats(node) {
      return node.getAttribute('data-key') || '';
    }
    format(name, value) {
      if (name === this.statics.blotName && value) {
        this.domNode.setAttribute('data-key', String(value));
      } else {
        super.format(name, value);
      }
    }
  }
  FluxVar.blotName = 'fluxvar';
  FluxVar.tagName = 'SPAN';
  FluxVar.className = 'fluxvar';
  Quill.register(FluxVar);

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
            /* 变量以"高亮可编辑文本"插入：光标可自由进出、可拖选跨越、可手动改写。
               fluxvar 的值＝变量名（落到 data-key），「导入数据」会把 span 内文本换成实际值，
               凭 data-key 仍能在保存时还原回 ${key}。user source 入 history，可撤销。 */
            const token = '${' + key + '}';
            if (range.length) quill.deleteText(range.index, range.length, Quill.sources.USER);
            quill.insertText(range.index, token, { fluxvar: key }, Quill.sources.USER);
            quill.setSelection(range.index + token.length, Quill.sources.SILENT);
            // 清除"待输入格式"里的 fluxvar，避免变量后继续打字也被染成橙色
            quill.format('fluxvar', false, Quill.sources.SILENT);
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
      history: {
        delay: 800,
        maxStack: 200,
        userOnly: true,
      },
    },
  });

  const Delta = Quill.import('delta');
  /* 加载/粘贴时把带 .fluxvar 的 span 标成 fluxvar inline format（值＝data-key，橙色高亮）。
     变量本体是 span 内的真实文本（${xxx} 或导入后的值），不是 atomic embed。 */
  quill.clipboard.addMatcher('span.fluxvar', function (node, delta) {
    const key = node.getAttribute('data-key') || '';
    return delta.compose(new Delta().retain(delta.length(), { fluxvar: key || true }));
  });

  /* 新输入的文字自动套默认 字体/字号，避免"无样式裸文本"导致复制到企微样式丢失。
     行距是 block 级，已由编辑器 CSS 默认 + 复制时 computed 内联覆盖，无需在此补。
     formatText 用 silent，不进 history 栈——撤销只回退文字本身；silent 触发的
     text-change 被下面的 source 守卫挡掉，不会递归。 */
  const DEFAULT_FONT = 'PingFang SC';
  const DEFAULT_SIZE = '10.5pt';
  quill.on('text-change', function (delta, oldDelta, source) {
    if (source !== 'user') return;
    const inserts = [];
    let index = 0;
    (delta.ops || []).forEach(function (op) {
      if (op.retain != null) {
        index += (typeof op.retain === 'number' ? op.retain : 1);
      } else if (typeof op.insert === 'string') {
        if (op.insert.length) inserts.push([index, op.insert.length]);
        index += op.insert.length;
      } else if (op.insert != null) {
        index += 1;
      }
    });
    if (!inserts.length) return;
    inserts.forEach(function (pair) {
      const idx = pair[0], len = pair[1];
      const fmt = quill.getFormat(idx, len);
      if (!fmt.font) quill.formatText(idx, len, 'font', DEFAULT_FONT, 'silent');
      if (!fmt.size) quill.formatText(idx, len, 'size', DEFAULT_SIZE, 'silent');
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
  // SPAN：模糊匹配 font-size / font-family。变量高亮 span.fluxvar 已被前面的专用 matcher
  // 标成 fluxvar inline format，这里跳过它的 font 推断（变量文本的字体继承外层）。
  quill.clipboard.addMatcher('SPAN', function (node, delta) {
    if (node.classList && node.classList.contains('fluxvar')) return delta;
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
  /* 模板渲染：每个 ${key} 按"是否有值 + 编辑态 / 导入预览态"分形态——
       · 已填充（导入到值）→ 直接渲染值本身，不套 fluxvar 高亮，显示为普通正文色；
       · 未填充 + 编辑态（没点过导入，valuesMap 空）→ <span class="fluxvar">${key}</span> 橙色占位，供查看 / 编辑；
       · 未填充 + 预览态（已点导入，valuesMap 非空）→ 渲染为空、不显示 ${key} 占位符，
         避免没值的变量把 "${xxx}" 字面带进最终邮件。
     橙色专门表示"此变量还没值"。预览态保存用的是导入前快照（见 saveBtn）、复制时本就脱掉 span，
     故已填值 / 预览态空值都无需带 data-key 回写，不影响保存。valuesMap 同时存入 state 判定 previewing。 */
  function loadTemplate(templateHtml, valuesMap) {
    valuesMap = valuesMap || {};
    const previewing = Object.keys(valuesMap).length > 0;
    const rendered = (templateHtml || '').replace(
      /\$\{([^}\s]+)\}/g,
      function (_, key) {
        const safeKey = escapeHtml(key);
        const v = valuesMap[key];
        if (v !== undefined && v !== null && v !== '') {
          let inner = RAW_HTML_KEYS[key] ? String(v) : escapeHtml(v);
          if (MULTILINE_INDENT_KEYS[key]) {
            inner = inner.replace(/<br\s*\/?>/gi, '<br>' + INDENT_FULLWIDTH_SPACES);
          }
          return inner;
        }
        // 未填充：预览态不显示占位符（渲染空）；编辑态保留 ${key} 橙色占位供查看 / 编辑
        if (previewing) return '';
        return '<span class="fluxvar" data-key="' + safeKey + '">${' + safeKey + '}</span>';
      }
    );
    const delta = quill.clipboard.convert({ html: rendered });
    quill.setContents(delta, 'silent');
    state.runtimeValues = valuesMap;
    state.previewing = previewing;
  }

  function serializeTemplate() {
    const clone = quill.root.cloneNode(true);
    // 保存写出的永远是 ${key} 模板：把 fluxvar span（无论显示 ${key} 还是导入的值）
    // 凭 data-key 还原成 ${key} 纯文本，存储格式与后端 / 默认模板一致。
    restoreFluxVarsToTokens(clone);
    return clone.innerHTML;
  }

  /* 多行路径变量：续行需要注入"等宽缩进"才能在 WeCom 里保持对齐。
     方案：导入填值时把多行值内部的 <br> 换成 <br> + 跟标签等宽的全角空格。
     全角空格数需与模板里该变量左侧标签的宽度匹配（默认模板调整后再按标签宽度校准）。 */
  const MULTILINE_INDENT_KEYS = { '更新包地址': true, '备份包地址': true };
  const INDENT_FULLWIDTH_SPACES = '　　　　　　　'; // 7 个全角空格

  /* 保存用：fluxvar span → ${data-key} 纯文本（还原成模板占位符，丢弃当前展示的值）。 */
  function restoreFluxVarsToTokens(rootEl) {
    rootEl.querySelectorAll('span.fluxvar').forEach(span => {
      const key = span.getAttribute('data-key') || '';
      span.replaceWith(document.createTextNode('${' + key + '}'));
    });
  }

  /* 复制用：脱掉 fluxvar span 外壳、保留内部 HTML（导入后是值、未导入是 ${key}）。
     用 outerHTML=innerHTML 保留值里的 <br> 等；去掉 class 后橙色不会被内联进最终邮件。 */
  function unwrapFluxVars(rootEl) {
    rootEl.querySelectorAll('span.fluxvar').forEach(span => {
      span.outerHTML = span.innerHTML;
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

  /* 出站样式内联（选择性 computed-style 内联）：
     编辑器靠外部 CSS（.ql-editor / .ql-editor p {...}）渲染默认样式，复制出去的 HTML 片段
     丢了这套外部 CSS → 企微 / Outlook 改用自己的默认值 → 段距撑大、行高撑高、颜色变样。
     这里把视觉关键属性从 computed style 取真实值、一次性内联到每个元素，做到所见即所得，
     替代过去逐项补 margin/line-height 的打地鼠。
     - font-size / font-family 不走 computed：保留 Quill 写的 pt / 字体（对齐企微中文字号制），
       computed 会把它们变 px / 长字体栈反而失真；bold / italic 走 <strong>/<em> 标签无需内联。
     - getComputedStyle 必须在 DOM 上取值，复制片段不在 DOM，故把内容克隆进一个继承了编辑器
       CSS 的隐藏 .ql-editor 容器取值，再把计算值映射回 rootEl 对应元素（rootEl 本体不动，
       内联失败也不会破坏复制内容）。 */
  const COMPUTED_PROPS = [
    'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
    'line-height', 'text-align',
    'color', 'background-color', 'font-weight', 'font-style'
  ];
  function inlineComputedStyles(rootEl) {
    let host = null;
    try {
      host = document.createElement('div');
      host.className = 'ql-container ql-snow';
      host.setAttribute('aria-hidden', 'true');
      host.style.cssText = 'position:absolute;left:-99999px;top:0;visibility:hidden;width:'
        + (quill.root.clientWidth || 800) + 'px;';
      const editor = document.createElement('div');
      editor.className = 'ql-editor';
      editor.innerHTML = rootEl.innerHTML;   // 克隆内容到隐藏容器；rootEl 本体不动
      host.appendChild(editor);
      document.body.appendChild(host);

      const srcEls = editor.querySelectorAll('*');
      const dstEls = rootEl.querySelectorAll('*');   // 同一份 HTML，结构与顺序一一对应
      const n = Math.min(srcEls.length, dstEls.length);
      for (let i = 0; i < n; i++) {
        const cs = getComputedStyle(srcEls[i]);
        for (const prop of COMPUTED_PROPS) {
          const v = cs.getPropertyValue(prop);
          if (v && v !== 'normal' && v !== 'none' && v !== 'auto' && v !== 'rgba(0, 0, 0, 0)') {
            dstEls[i].style.setProperty(prop, v);
          }
        }
      }
    } catch (e) {
      /* 内联失败不阻断复制：rootEl 内容完好，降级为“未内联”（顶多段距回到旧问题，绝不丢内容） */
    } finally {
      if (host && host.parentNode) {
        host.parentNode.removeChild(host);
      }
    }
  }

  /* 复制 / 导出统一走这条管线：脱掉变量高亮外壳（保留当前展示内容）→ 段首缩进 → computed 内联。
     导入预览态下已填变量是普通正文（值）、未填变量已渲染为空（不带 ${key} 占位）；故复制即得
     "已填项是值、未填项留空"的邮件，不会把 ${xxx} 字面带出去。模板态（未导入）下全是 ${key} 橙色
     占位 span，unwrapFluxVars 脱壳后复制即得带占位符的模板（与旧版"未导入复制出占位"一致）。 */
  function processForClipboard(rootEl) {
    unwrapFluxVars(rootEl);
    applyParagraphIndent(rootEl);
    inlineComputedStyles(rootEl);
  }

  /* 渲染最终邮件 HTML：DOM 处理（脱高亮外壳 + 缩进 + 内联）后取 innerHTML。 */
  function renderFinalHtml(rootEl) {
    processForClipboard(rootEl);
    return rootEl.innerHTML;
  }

  function serializeRendered() {
    const clone = quill.root.cloneNode(true);
    return renderFinalHtml(clone);
  }

  // updateChipValue 已移除：变量不再是 chip；导入时由 loadTemplate 把已填充变量就地渲染成普通正文，
  // 仅未填充的保留 fluxvar 橙色占位符。

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
  /* 新建：初始内容由 Java 端取插件内置默认填充（POST 不带 /restore 后缀 → op:new）。 */
  const apiNewTemplate    = name       => api('/api/templates/' + encodeURIComponent(name),
                                          { method: 'POST' });
  const apiDeleteTemplate = name       => api('/api/templates/' + encodeURIComponent(name),
                                          { method: 'DELETE' });
  const apiRestoreTemplate= name       => api('/api/templates/' + encodeURIComponent(name)
                                          + '/restore', { method: 'POST' });
  const apiRuntimeData    = ()         => api('/api/runtime-data');
  /* 复制到剪贴板：JCEF 里 navigator.clipboard 可能被禁，统一走桥让 Java 端
     用 HtmlClipboardTransferable 写系统剪贴板，跨平台表现一致。 */
  const apiCopyToClipboard = (html, plain) =>
                                          bridgeCall({ op: 'copyToClipboard', html: html, plain: plain });

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
    const name = await showPrompt('新模板名称（中英文 / 数字 / 下划线 / 横线）：');
    if (!name) return;
    const trimmed = name.trim();
    if (!trimmed) return;
    if (state.templates.includes(trimmed)) {
      toast('已存在同名模板', 'error');
      return;
    }
    try {
      // 新建模板的初始内容统一取插件内置默认（由 Java 端填充），不再复制当前编辑器内容。
      await apiNewTemplate(trimmed);
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
    const msg = '确认把模板「' + displayLabelOf(name)
      + '」恢复为插件出厂默认内容？\n当前内容会被覆盖，无法撤销。';
    if (!(await showConfirm(msg))) return;
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
    if (!(await showConfirm('确认删除模板「' + displayLabelOf(name) + '」？无法恢复。', true))) return;
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
      // 预览态下编辑器显示的是值，保存要用进入预览前的 ${变量} 模板快照，避免把值存成模板。
      const html = state.previewing ? state.templateSnapshot : serializeTemplate();
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
      // 导入＝把编辑器里的 ${变量} 就地填成实际值（可见预览）。先取回当前 ${变量} 模板态
      // （含用户刚才的编辑）存为快照，再带值重渲染——保存时用快照，保证存的仍是模板。
      const merged = Object.assign({}, state.runtimeValues, values);
      state.templateSnapshot = state.previewing ? state.templateSnapshot : serializeTemplate();
      loadTemplate(state.templateSnapshot, merged);
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
      // JCEF 里 navigator.clipboard / execCommand 对系统剪贴板的写入都不可靠，
      // 统一走桥交给 Java 端的 HtmlClipboardTransferable 写系统剪贴板。
      await apiCopyToClipboard(html, plain);
      flashBtn($('#copyBtn'), '✓ 已复制');
      toast('已复制到剪贴板', 'success');
    } catch (e) {
      toast('复制失败：' + e.message, 'error', 4000);
    }
  });

  // 关闭：经桥回调 Java 端关闭对话框（窗口标题栏的系统关闭按钮也仍可用）
  $('#closeBtn').addEventListener('click', () => {
    bridgeCall({ op: 'close' }).catch(() => { /* 关闭动作失败无需提示用户 */ });
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
      /* 跟 serializeRendered 走同一条管线：脱变量高亮外壳（保留当前展示内容）+ 段首缩进 + 内联。
         预览态选区里是值、模板态是 ${变量}，Ctrl+C 都能带出且缩进不丢。 */
      const html = renderFinalHtml(container);
      const plain = document.createElement('div');
      plain.innerHTML = html;
      ev.clipboardData.setData('text/plain', plain.textContent || '');
      ev.clipboardData.setData('text/html', html);
      ev.preventDefault();
    } catch (e) { /* fall back */ }
  }
  quill.root.addEventListener('copy', interceptCopy, true);
  quill.root.addEventListener('cut', interceptCopy, true);

  // ──────────────────────────────────────────────────────────
  //   键盘快捷键：Cmd/Ctrl+S 保存
  // ──────────────────────────────────────────────────────────
  /* 键盘快捷键（capture 阶段处理，并对 z/y 做 stopPropagation 阻止 Quill 自带绑定重复触发）：
     - Cmd/Ctrl+S 保存
     - Cmd/Ctrl+Z 撤销 / Cmd/Ctrl+Shift+Z 或 Ctrl+Y 重做
     JCEF/CEF 里 Quill 自带的 undo 绑定有时收不到事件，这里显式驱动 history 模块兜底。 */
  document.addEventListener('keydown', e => {
    const mod = e.metaKey || e.ctrlKey;
    if (!mod) return;
    const k = e.key.toLowerCase();
    const history = quill.getModule('history');
    if (k === 's') {
      e.preventDefault();
      $('#saveBtn').click();
    } else if (k === 'z') {
      e.preventDefault();
      e.stopPropagation();
      if (history) { e.shiftKey ? history.redo() : history.undo(); }
    } else if (k === 'y') {
      e.preventDefault();
      e.stopPropagation();
      if (history) history.redo();
    }
  }, true);

  // ──────────────────────────────────────────────────────────
  //   引导
  // ──────────────────────────────────────────────────────────
  async function bootstrap() {
    /* 不预加载 runtime data —— 初次打开时 chip 都是空占位，值只有点「导入数据」
       时才统一刷新。这样用户对"什么时候 chip 被填"有明确控制。 */
    state.runtimeValues = {};
    await refreshTemplateList();
    await reloadEditorWithCurrent();
  }

  // 不自启动：把入口挂到 window，由 JCEF 宿主在注入 window.fluxBridge 之后立即调用，
  // 确保 bootstrap 拉模板 / 数据时桥已就绪。
  window.__fluxBootstrap = bootstrap;
})();
