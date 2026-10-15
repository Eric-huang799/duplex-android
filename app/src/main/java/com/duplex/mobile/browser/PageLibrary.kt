package com.duplex.mobile.browser

/**
 * 注入到页面里的操作库（移植自桌面版 src/main/page-scripts.ts）。
 * 桌面版通过 CDP Runtime.evaluate 注入；手机端通过 WebView.evaluateJavascript 注入，
 * 逻辑保持一致：snapshot 的 [eN] ref 机制、resolve/focus/scroll/query 等。
 */
object PageLibrary {
    const val VERSION = 3

    val JS: String = """
(function () {
  if (window.__cb && window.__cb.v === 3) return;
  var cb = {};
  cb.v = 3;
  var clean = function (s, m) { return (s == null ? '' : String(s)).replace(/\s+/g, ' ').trim().slice(0, m || 60); };
  var resolveEl = function (target) {
    if (/^e\d+$/.test(target)) {
      return (window.__cobrowse && window.__cobrowse.refMap && window.__cobrowse.refMap.get(target)) || null;
    }
    try { return document.querySelector(target); } catch (e) { return null; }
  };

  cb.vw = function () { return window.innerWidth; };
  cb.exists = function (sel) { try { return !!document.querySelector(sel); } catch (e) { return false; } };
  cb.hasText = function (t) { return (document.body ? document.body.innerText : '').indexOf(t) >= 0; };

  cb.snapshot = function () {
    var MAX_LINES = 400;
    var refMap = new Map();
    var nextRef = 1;
    var lines = [];
    var truncated = false;
    var emittedCount = 0;
    var SKIP = new Set(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE','META','LINK','HEAD','BR','WBR','SVG','svg']);
    var INTERACTIVE = new Set(['A','BUTTON','INPUT','SELECT','TEXTAREA','SUMMARY','DETAILS','LABEL','OPTION','IFRAME']);
    var isVisible = function (el) {
      try {
        var r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return false;
        var cs = getComputedStyle(el);
        return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
      } catch (e) { return false; }
    };
    var fmtTag = function (el) {
      var tag = el.tagName.toLowerCase();
      var s = tag;
      if (el.id) s += '#' + clean(el.id, 32);
      var cls = '';
      if (typeof el.className === 'string') cls = el.className;
      var parts = cls.split(/\s+/).filter(Boolean);
      if (parts.length) {
        s += '.' + parts.slice(0, 2).map(function (c) { return c.slice(0, 24); }).join('.');
        if (parts.length > 2) s += '+' + (parts.length - 2);
      }
      return s;
    };
    var directText = function (el) {
      var t = '';
      for (var i = 0; i < el.childNodes.length; i++) {
        var n = el.childNodes[i];
        if (n.nodeType === 3) t += n.textContent;
      }
      return t;
    };
    var walk = function (el, depth) {
      if (truncated) return;
      var tag = el.tagName;
      if (!tag || SKIP.has(tag)) return;
      if (!isVisible(el)) return;
      var role = el.getAttribute('role');
      var ariaLabel = el.getAttribute('aria-label');
      var interactive = INTERACTIVE.has(tag) || !!role;
      var hasId = !!el.id;
      var childEls = Array.prototype.slice.call(el.children);
      var leaf = childEls.length === 0;
      var text = clean(leaf ? el.textContent : directText(el), 60);
      var meaningful = interactive || hasId || !!ariaLabel || text.length >= 2;
      var emitted = false;
      if (meaningful) {
        if (emittedCount >= MAX_LINES) { truncated = true; return; }
        var ref = 'e' + (nextRef++);
        refMap.set(ref, el);
        var line = '  '.repeat(depth) + '[' + ref + '] <' + fmtTag(el) + '>';
        if (tag === 'INPUT') {
          var it = (el.type || 'text').toLowerCase();
          line += ' type=' + it;
          if (it !== 'password' && el.value) line += ' value="' + clean(el.value, 60) + '"';
          if (el.placeholder) line += ' placeholder="' + clean(el.placeholder, 60) + '"';
        } else if (tag === 'TEXTAREA') {
          line += ' type=textarea';
          if (el.value) line += ' value="' + clean(el.value, 60) + '"';
          if (el.placeholder) line += ' placeholder="' + clean(el.placeholder, 60) + '"';
        } else if (tag === 'SELECT') {
          var sel = el.options && el.selectedIndex >= 0 ? el.options[el.selectedIndex] : null;
          if (sel) line += ' selected="' + clean(sel.textContent, 40) + '"';
        } else if (tag === 'A') {
          var href = el.getAttribute('href');
          if (href) line += ' href="' + clean(href, 120) + '"';
        } else if (tag === 'IFRAME') {
          line += ' src="' + clean(el.getAttribute('src'), 120) + '"';
        }
        if (role) line += ' role=' + role;
        if (el.disabled) line += ' [disabled]';
        if (text) line += ' "' + text + '"';
        lines.push(line);
        emittedCount++;
        emitted = true;
      }
      for (var i = 0; i < childEls.length; i++) {
        if (truncated) break;
        walk(childEls[i], emitted ? depth + 1 : depth);
      }
      if (!truncated && el.shadowRoot) {
        var sc = Array.prototype.slice.call(el.shadowRoot.children);
        for (var j = 0; j < sc.length; j++) {
          if (truncated) break;
          walk(sc[j], depth + 1);
        }
      }
    };
    try { walk(document.body, 0); } catch (e) { lines.push('(outline error: ' + e.message + ')'); }
    window.__cobrowse = window.__cobrowse || {};
    window.__cobrowse.refMap = refMap;
    var header = [
      'url: ' + location.href,
      'title: ' + document.title,
      'viewport: ' + innerWidth + 'x' + innerHeight + '  scrollY: ' + Math.round(scrollY) + ' / page: ' + Math.round(document.documentElement.scrollHeight),
      'refs: [eN] usable with click/type until next snapshot',
      '---'
    ].join('\n');
    var footer = truncated ? '\n--- (truncated at ' + MAX_LINES + ' lines; ' + (nextRef - 1) + ' refs total - use query/get_html for the rest)' : '';
    return header + '\n' + lines.join('\n') + footer;
  };

  cb.resolve = function (target) {
    var el = resolveEl(target);
    if (!el) return { error: 'element not found: ' + target + ' (call snapshot again)' };
    if (!el.isConnected) return { error: 'element is detached (page changed); call snapshot again' };
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) {}
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    var visible = r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && cs.opacity !== '0';
    var x = Math.min(Math.max(r.x + r.width / 2, 1), innerWidth - 2);
    var y = Math.min(Math.max(r.y + r.height / 2, 1), innerHeight - 2);
    return {
      ok: true,
      x: x, y: y,
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
      visible: visible,
      tag: el.tagName.toLowerCase(),
      text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80),
      value: (el.value != null ? String(el.value) : undefined)
    };
  };

  cb.focus = function (target, clear) {
    var el = resolveEl(target);
    if (!el) return { error: 'element not found: ' + target + ' (call snapshot again)' };
    if (!el.isConnected) return { error: 'element is detached; call snapshot again' };
    try { el.scrollIntoView({ block: 'center', behavior: 'instant' }); } catch (e) {}
    try { el.focus({ preventScroll: true }); } catch (e) { try { el.focus(); } catch (e2) {} }
    var isField = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
    var editable = !!el.isContentEditable;
    if (clear) {
      if (isField) { try { el.select(); } catch (e) {} }
      else if (editable) { try { document.execCommand('selectAll', false, undefined); } catch (e) {} }
    }
    var r = el.getBoundingClientRect();
    var label = (el.textContent || el.getAttribute('placeholder') || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
    return {
      ok: true, field: isField, editable: editable, tag: el.tagName.toLowerCase(),
      text: label.slice(0, 40),
      rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) }
    };
  };

  cb.insertText = function (value) {
    var el = document.activeElement;
    if (!el) return { error: 'no focused element' };
    if (el.tagName === 'SELECT') return { error: 'active element is a <select>; use select_option' };
    try {
      if (document.execCommand && document.execCommand('insertText', false, value)) {
        return { ok: true, via: 'execCommand' };
      }
    } catch (e) {}
    try {
      var isField = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
      if (isField) {
        var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
        if (desc && desc.set) {
          desc.set.call(el, value);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
          return { ok: true, via: 'setter' };
        }
      }
      if (el.isContentEditable) {
        el.textContent = value;
        el.dispatchEvent(new InputEvent('input', { bubbles: true }));
        return { ok: true, via: 'contenteditable' };
      }
    } catch (e) {}
    return { error: 'cannot insert text into the focused element' };
  };

  cb.scroll = function (sel, dy, dx) {
    if (sel) {
      var el = resolveEl(sel);
      if (!el) return { error: 'element not found: ' + sel };
      el.scrollIntoView({ block: 'center', behavior: 'instant' });
    } else {
      window.scrollBy(dx || 0, dy || 0);
    }
    return {
      scrollX: Math.round(window.scrollX), scrollY: Math.round(window.scrollY),
      pageHeight: Math.round(document.documentElement.scrollHeight), viewportHeight: innerHeight
    };
  };

  cb.query = function (sel, limit) {
    var els = [];
    try { els = Array.prototype.slice.call(document.querySelectorAll(sel)); } catch (e) { return { error: 'invalid selector: ' + sel }; }
    var total = els.length;
    var out = els.slice(0, limit).map(function (el) {
      var r = el.getBoundingClientRect();
      var cs = getComputedStyle(el);
      var cls = [];
      if (typeof el.className === 'string') cls = el.className.split(/\s+/).filter(Boolean).slice(0, 3);
      var isField = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
      return {
        tag: el.tagName.toLowerCase(),
        id: el.id || undefined,
        classes: cls.length ? cls : undefined,
        role: el.getAttribute('role') || undefined,
        text: (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 120) || undefined,
        href: el.getAttribute ? (el.getAttribute('href') || undefined) : undefined,
        src: el.getAttribute ? (el.getAttribute('src') || undefined) : undefined,
        value: isField ? String(el.value).slice(0, 80) : undefined,
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
        visible: r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none'
      };
    });
    return { total: total, returned: out.length, elements: out };
  };

  cb.outer = function (sel) {
    var el = resolveEl(sel);
    if (!el) return { error: 'element not found: ' + sel };
    return { html: el.outerHTML, tag: el.tagName.toLowerCase() };
  };

  cb.body = function () {
    var clone = document.body.cloneNode(true);
    clone.querySelectorAll('script,style,noscript,template,link,meta,svg').forEach(function (n) {
      if (n.tagName && n.tagName.toLowerCase() === 'svg') { n.replaceWith('[svg]'); } else { n.remove(); }
    });
    clone.querySelectorAll('*').forEach(function (n) {
      n.removeAttribute('style');
      Array.prototype.slice.call(n.attributes || []).forEach(function (a) {
        if (a.name.indexOf('on') === 0) n.removeAttribute(a.name);
      });
    });
    return clone.innerHTML.replace(/<!--[\s\S]*?-->/g, '').replace(/\n{3,}/g, '\n\n');
  };

  cb.pressEnter = function () {
    var el = document.activeElement;
    if (!el) return { error: 'no focused element' };
    try {
      el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
      el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
    } catch (e) {}
    try {
      if (el.form && el.type !== 'search') {
        if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit();
        return { ok: true, via: 'form' };
      }
    } catch (e) {}
    return { ok: true, via: 'keydown' };
  };

  /* 后台输入：不聚焦元素（不弹输入法），直接写值并派发 input/change。 */
  cb.typeInto = function (target, value, clear) {
    var el = resolveEl(target);
    if (!el) return { error: 'element not found: ' + target + ' (call snapshot again)' };
    if (!el.isConnected) return { error: 'element is detached; call snapshot again' };
    var isField = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
    var oldVal = isField && el.value != null ? String(el.value) : '';
    var finalVal = clear ? value : oldVal + value;
    var ok = false, via = '';
    try {
      if (isField) {
        var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
        if (desc && desc.set) {
          desc.set.call(el, finalVal);
          el.dispatchEvent(new Event('input', { bubbles: true }));
          el.dispatchEvent(new Event('change', { bubbles: true }));
          ok = true; via = 'setter';
        }
      } else if (el.isContentEditable) {
        el.textContent = finalVal;
        el.dispatchEvent(new InputEvent('input', { bubbles: true }));
        ok = true; via = 'contenteditable';
      }
    } catch (e) {}
    if (!ok) {
      /* 兜底：聚焦 + execCommand（可能短暂弹输入法），随后立刻失焦 */
      try {
        el.focus();
        if (clear) { try { el.select(); } catch (e2) {} }
        ok = !!(document.execCommand && document.execCommand('insertText', false, value));
        via = 'execCommand';
      } catch (e) {}
      try { el.blur(); } catch (e3) {}
    }
    var nowVal = isField ? String(el.value) : (el.isContentEditable ? String(el.textContent) : '');
    return { ok: ok, via: via, value: nowVal.slice(0, 60), length: nowVal.length };
  };

  /* 后台提交：不依赖焦点，直接对元素派发 Enter 并尝试表单提交。 */
  cb.submitFrom = function (target) {
    var el = resolveEl(target);
    if (!el) return { error: 'element not found: ' + target };
    var mk = function (t) { return new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }); };
    try { el.dispatchEvent(mk('keydown')); el.dispatchEvent(mk('keypress')); el.dispatchEvent(mk('keyup')); } catch (e) {}
    var form = el.form || (el.closest ? el.closest('form') : null);
    if (form) {
      try {
        if (form.requestSubmit) form.requestSubmit(); else form.submit();
        return { ok: true, via: 'form' };
      } catch (e) {}
    }
    return { ok: true, via: 'keydown' };
  };

  /* 页面内可视化 overlay：AI 光标 / 元素高亮 / 底部状态条（shadow DOM 隔离样式）。 */
  cb.overlay = (function () {
    var HOST = '__cb_overlay_host';
    function ensure() {
      var host = document.getElementById(HOST);
      if (host && host.__root) return host.__root;
      host = document.createElement('div');
      host.id = HOST;
      host.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:2147483646;pointer-events:none;';
      var root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;
      var style = document.createElement('style');
      style.textContent =
        '.c{position:fixed;width:26px;height:26px;margin:-13px 0 0 -13px;border-radius:50%;border:2.5px solid rgba(66,133,244,.95);background:rgba(66,133,244,.18);box-shadow:0 0 0 3px rgba(66,133,244,.28);transition:left .18s ease,top .18s ease,transform .12s;pointer-events:none;}' +
        '.c.pulse{transform:scale(.72);}' +
        '.hl{position:fixed;border:2px solid rgba(255,152,0,.95);background:rgba(255,152,0,.13);border-radius:6px;box-sizing:border-box;pointer-events:none;transition:all .12s;}' +
        '.st{position:fixed;left:50%;bottom:16px;transform:translateX(-50%);max-width:86vw;background:rgba(32,33,36,.92);color:#fff;font:13px/1.4 Roboto,sans-serif;padding:8px 14px;border-radius:18px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;opacity:0;transition:opacity .18s;pointer-events:none;}' +
        '.st.show{opacity:1;}';
      root.appendChild(style);
      var c = document.createElement('div'); c.className = 'c'; c.style.display = 'none';
      var hl = document.createElement('div'); hl.className = 'hl'; hl.style.display = 'none';
      var st = document.createElement('div'); st.className = 'st';
      root.appendChild(c); root.appendChild(hl); root.appendChild(st);
      (document.body || document.documentElement).appendChild(host);
      host.__root = root;
      return root;
    }
    var timers = {};
    function clearTimer(k) { if (timers[k]) { clearTimeout(timers[k]); timers[k] = null; } }
    return {
      cursor: function (x, y, pulse) {
        try {
          var r = ensure(); var c = r.querySelector('.c');
          c.style.display = 'block'; c.style.left = x + 'px'; c.style.top = y + 'px';
          if (pulse) { c.classList.add('pulse'); setTimeout(function () { c.classList.remove('pulse'); }, 140); }
          clearTimer('c'); timers['c'] = setTimeout(function () { c.style.display = 'none'; }, 5000);
        } catch (e) {}
      },
      highlight: function (x, y, w, h, ttl) {
        try {
          var r = ensure(); var el = r.querySelector('.hl');
          el.style.display = 'block'; el.style.left = x + 'px'; el.style.top = y + 'px';
          el.style.width = w + 'px'; el.style.height = h + 'px';
          clearTimer('hl'); timers['hl'] = setTimeout(function () { el.style.display = 'none'; }, ttl || 5000);
        } catch (e) {}
      },
      status: function (text, ttl) {
        try {
          var r = ensure(); var s = r.querySelector('.st');
          s.textContent = text; s.classList.add('show');
          clearTimer('st'); timers['st'] = setTimeout(function () { s.classList.remove('show'); }, ttl || 4000);
        } catch (e) {}
      },
      clear: function () {
        try {
          var host = document.getElementById(HOST);
          if (host && host.__root) {
            host.__root.querySelector('.c').style.display = 'none';
            host.__root.querySelector('.hl').style.display = 'none';
            host.__root.querySelector('.st').classList.remove('show');
          }
        } catch (e) {}
      }
    };
  })();

  /* 页面标注（触摸圈选）：框/圈/箭头/点选；标记固定到文档坐标、跟随滚动；
     完成/退出事件与结构化摘要由 Kotlin 侧轮询获取（对应桌面版标注功能）。 */
  cb.annot = (function () {
    var HOST = '__cb_annot_host';
    var LINE = '#ff8f00';
    var FILL = 'rgba(255,143,0,.14)';
    var active = false;
    var tool = 'box';
    var marks = [];
    var events = [];
    var els = null;
    var drag = null;
    var rafId = 0;
    var seq = 0;

    function clean(s, m) { return (s == null ? '' : String(s)).replace(/\s+/g, ' ').trim().slice(0, m || 60); }

    function anchorAt(cx, cy) {
      var el = null;
      try {
        if (els && els.layer) els.layer.style.pointerEvents = 'none';
        var list = [];
        if (document.elementsFromPoint) list = document.elementsFromPoint(cx, cy);
        else { var e0 = document.elementFromPoint(cx, cy); if (e0) list = [e0]; }
        for (var i = 0; i < list.length; i++) {
          var e = list[i];
          if (!e) continue;
          if (e.id === '__cb_overlay_host' || e.id === HOST) continue;
          el = e; break;
        }
      } catch (err) { el = null; }
      finally { if (els && els.layer) els.layer.style.pointerEvents = 'auto'; }
      if (!el || el.nodeType !== 1) return { tag: '', selector: '', text: '' };
      return {
        tag: el.tagName.toLowerCase(),
        selector: selectorOf(el),
        text: clean(el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.textContent || '')
      };
    }

    function selectorOf(el) {
      try {
        if (el.id) {
          var id = el.id;
          try { id = (window.CSS && CSS.escape) ? CSS.escape(id) : id; } catch (e) {}
          return '#' + id;
        }
        var parts = [];
        var cur = el;
        var depth = 0;
        while (cur && cur.nodeType === 1 && depth < 3) {
          var tag = cur.tagName.toLowerCase();
          var parent = cur.parentElement;
          if (parent) {
            var idx = 1, cnt = 0;
            for (var i = 0; i < parent.children.length; i++) {
              var c = parent.children[i];
              if (c.tagName === cur.tagName) { cnt++; if (c === cur) idx = cnt; }
            }
            parts.unshift(cnt > 1 ? (tag + ':nth-of-type(' + idx + ')') : tag);
          } else { parts.unshift(tag); }
          cur = parent;
          depth++;
        }
        return parts.join(' > ');
      } catch (e) { return ''; }
    }

    function ensure() {
      var host = document.getElementById(HOST);
      if (host && host.__root) return host.__root;
      host = document.createElement('div');
      host.id = HOST;
      host.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:2147483647;';
      var root = host.attachShadow ? host.attachShadow({ mode: 'open' }) : host;
      var style = document.createElement('style');
      style.textContent =
        '.layer{position:fixed;left:0;top:0;right:0;bottom:0;display:none;touch-action:none;cursor:crosshair;background:rgba(30,60,120,.07);}' +
        '.pill{position:fixed;left:50%;top:10px;transform:translateX(-50%);display:none;align-items:center;gap:4px;background:rgba(32,33,36,.95);color:#fff;border-radius:22px;padding:5px 8px;font:13px Roboto,sans-serif;box-shadow:0 4px 18px rgba(0,0,0,.35);pointer-events:auto;}' +
        '.pill button{border:0;background:transparent;color:#cfd3dc;font-size:15px;width:32px;height:32px;border-radius:50%;cursor:pointer;padding:0;}' +
        '.pill button.on{background:rgba(255,143,0,.9);color:#202124;}' +
        '.pill .hint{font-size:12px;color:#aeb3bd;padding:0 6px;max-width:44vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}' +
        '.pill .done{width:auto;padding:0 12px;border-radius:16px;background:#2e7d32;color:#fff;font-size:13px;}' +
        '.pill .exit{color:#ffb4ab;}' +
        '.draft{position:fixed;display:none;border:2px dashed ' + LINE + ';background:' + FILL + ';border-radius:6px;pointer-events:none;box-sizing:border-box;}' +
        '.draft.circle{border-radius:50%;}' +
        '.mk{position:fixed;pointer-events:none;box-sizing:border-box;}' +
        '.mk .shape{position:absolute;left:0;top:0;right:0;bottom:0;border:2px solid ' + LINE + ';background:' + FILL + ';border-radius:6px;box-sizing:border-box;}' +
        '.mk.circle .shape{border-radius:50%;}' +
        '.mk.point .dot{position:absolute;left:-8px;top:-8px;width:16px;height:16px;border-radius:50%;background:rgba(255,143,0,.35);border:2px solid ' + LINE + ';box-sizing:border-box;}' +
        '.mk .badge{position:absolute;left:-9px;top:-9px;min-width:18px;height:18px;border-radius:9px;background:' + LINE + ';color:#202124;font:11px/18px Roboto,sans-serif;text-align:center;padding:0 4px;box-sizing:border-box;}' +
        '.mk .del{position:absolute;right:-11px;top:-11px;width:22px;height:22px;border:0;border-radius:50%;background:#d93025;color:#fff;font:13px/20px Roboto,sans-serif;cursor:pointer;pointer-events:auto;padding:0;}' +
        '.mk svg{position:absolute;left:0;top:0;overflow:visible;}';
      root.appendChild(style);
      var layer = document.createElement('div');
      layer.className = 'layer';
      var marksEl = document.createElement('div');
      var draft = document.createElement('div');
      draft.className = 'draft';
      var pill = document.createElement('div');
      pill.className = 'pill';
      pill.innerHTML =
        '<button data-tool="box" class="on" title="框选">□</button>' +
        '<button data-tool="circle" title="圈选">○</button>' +
        '<button data-tool="arrow" title="箭头">➤</button>' +
        '<button data-tool="point" title="点选元素">⌖</button>' +
        '<span class="hint">拖动圈选，或点选元素</span>' +
        '<button class="done">完成</button>' +
        '<button class="exit">✕</button>';
      root.appendChild(layer);
      root.appendChild(marksEl);
      root.appendChild(draft);
      root.appendChild(pill);
      (document.body || document.documentElement).appendChild(host);
      host.__root = root;
      els = { host: host, root: root, layer: layer, draft: draft, marksEl: marksEl, pill: pill };
      bind();
      return root;
    }

    function pt(e) { return { x: e.clientX, y: e.clientY }; }

    function updateDraft() {
      if (!drag) return;
      var x = Math.min(drag.x0, drag.x1);
      var y = Math.min(drag.y0, drag.y1);
      els.draft.style.left = x + 'px';
      els.draft.style.top = y + 'px';
      els.draft.style.width = Math.abs(drag.x1 - drag.x0) + 'px';
      els.draft.style.height = Math.abs(drag.y1 - drag.y0) + 'px';
    }

    function bind() {
      var layer = els.layer;
      layer.addEventListener('pointerdown', function (e) {
        if (!active) return;
        e.preventDefault();
        var p = pt(e);
        drag = { x0: p.x, y0: p.y, x1: p.x, y1: p.y };
        try { layer.setPointerCapture(e.pointerId); } catch (err) {}
        if (tool !== 'point') {
          els.draft.style.display = 'block';
          els.draft.className = 'draft' + (tool === 'circle' ? ' circle' : '');
          updateDraft();
        }
      });
      layer.addEventListener('pointermove', function (e) {
        if (!drag) return;
        var p = pt(e);
        drag.x1 = p.x;
        drag.y1 = p.y;
        if (tool !== 'point') updateDraft();
      });
      layer.addEventListener('pointerup', function (e) {
        if (!drag) return;
        var d = drag;
        drag = null;
        els.draft.style.display = 'none';
        var p = pt(e);
        d.x1 = p.x;
        d.y1 = p.y;
        if (tool === 'point' || (Math.abs(d.x1 - d.x0) < 8 && Math.abs(d.y1 - d.y0) < 8)) {
          addPoint(p.x, p.y);
        } else {
          addRegion(tool, d.x0, d.y0, d.x1, d.y1);
        }
      });
      layer.addEventListener('pointercancel', function () {
        drag = null;
        els.draft.style.display = 'none';
      });
      window.addEventListener('scroll', onScroll, { passive: true, capture: true });
      window.addEventListener('resize', onScroll, { passive: true });
      els.pill.addEventListener('click', function (e) {
        var t = e.target;
        if (!t || !t.getAttribute) return;
        var toolBtn = t.getAttribute('data-tool');
        if (toolBtn) { tool = toolBtn; setToolUI(); return; }
        if (t.className === 'done') { finish(); return; }
        if (t.className === 'exit') { exitAll(); return; }
      });
    }

    function setToolUI() {
      if (!els) return;
      var btns = els.pill.querySelectorAll('button[data-tool]');
      for (var i = 0; i < btns.length; i++) {
        btns[i].className = btns[i].getAttribute('data-tool') === tool ? 'on' : '';
      }
    }

    function addRegion(t, x0, y0, x1, y1) {
      var anchor = anchorAt((x0 + x1) / 2, (y0 + y1) / 2);
      var m = {
        idx: ++seq, tool: t,
        docX: Math.min(x0, x1) + window.scrollX,
        docY: Math.min(y0, y1) + window.scrollY,
        w: Math.round(Math.abs(x1 - x0)),
        h: Math.round(Math.abs(y1 - y0)),
        anchor: anchor,
        raw: { x0: x0, y0: y0, x1: x1, y1: y1 }
      };
      marks.push(m);
      renderMark(m);
      reposition();
    }

    function addPoint(x, y) {
      var anchor = anchorAt(x, y);
      var m = {
        idx: ++seq, tool: 'point',
        docX: x + window.scrollX,
        docY: y + window.scrollY,
        w: 0, h: 0,
        anchor: anchor
      };
      marks.push(m);
      renderMark(m);
      reposition();
    }

    function renderMark(m) {
      try {
        var el = document.createElement('div');
        el.className = 'mk ' + m.tool;
        if (m.tool === 'arrow') {
          var minX = Math.min(m.raw.x0, m.raw.x1);
          var minY = Math.min(m.raw.y0, m.raw.y1);
          var w = Math.max(Math.abs(m.raw.x1 - m.raw.x0), 1);
          var h = Math.max(Math.abs(m.raw.y1 - m.raw.y0), 1);
          var ax = m.raw.x0 - minX, ay = m.raw.y0 - minY;
          var bx = m.raw.x1 - minX, by = m.raw.y1 - minY;
          el.style.width = w + 'px';
          el.style.height = h + 'px';
          var ang = Math.atan2(by - ay, bx - ax);
          var HL = 14;
          var p1x = bx - HL * Math.cos(ang - 0.5), p1y = by - HL * Math.sin(ang - 0.5);
          var p2x = bx - HL * Math.cos(ang + 0.5), p2y = by - HL * Math.sin(ang + 0.5);
          var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
          svg.setAttribute('width', String(w));
          svg.setAttribute('height', String(h));
          var ln = document.createElementNS('http://www.w3.org/2000/svg', 'line');
          ln.setAttribute('x1', String(ax));
          ln.setAttribute('y1', String(ay));
          ln.setAttribute('x2', String(bx));
          ln.setAttribute('y2', String(by));
          ln.setAttribute('stroke', LINE);
          ln.setAttribute('stroke-width', '3');
          var head = document.createElementNS('http://www.w3.org/2000/svg', 'polygon');
          head.setAttribute('points', bx + ',' + by + ' ' + p1x + ',' + p1y + ' ' + p2x + ',' + p2y);
          head.setAttribute('fill', LINE);
          svg.appendChild(ln);
          svg.appendChild(head);
          el.appendChild(svg);
        } else if (m.tool === 'point') {
          el.style.width = '0px';
          el.style.height = '0px';
          var dot = document.createElement('div');
          dot.className = 'dot';
          el.appendChild(dot);
        } else {
          el.style.width = Math.max(m.w, 6) + 'px';
          el.style.height = Math.max(m.h, 6) + 'px';
          var shape = document.createElement('div');
          shape.className = 'shape';
          el.appendChild(shape);
        }
        var badge = document.createElement('div');
        badge.className = 'badge';
        badge.textContent = String(m.idx);
        el.appendChild(badge);
        var del = document.createElement('button');
        del.className = 'del';
        del.textContent = '×';
        del.addEventListener('click', function (e) {
          e.stopPropagation();
          removeMark(m);
        });
        el.appendChild(del);
        els.marksEl.appendChild(el);
        m.el = el;
      } catch (e) {}
    }

    function removeMark(m) {
      var i = marks.indexOf(m);
      if (i >= 0) marks.splice(i, 1);
      if (m.el && m.el.parentNode) m.el.parentNode.removeChild(m.el);
    }

    function clearMarks() {
      for (var i = 0; i < marks.length; i++) {
        if (marks[i].el && marks[i].el.parentNode) marks[i].el.parentNode.removeChild(marks[i].el);
      }
      marks = [];
      seq = 0;
    }

    function reposition() {
      for (var i = 0; i < marks.length; i++) {
        var m = marks[i];
        if (!m.el) continue;
        m.el.style.left = (m.docX - window.scrollX) + 'px';
        m.el.style.top = (m.docY - window.scrollY) + 'px';
      }
    }

    function onScroll() {
      if (rafId) return;
      if (!window.requestAnimationFrame) { try { reposition(); } catch (e) {} return; }
      rafId = window.requestAnimationFrame(function () {
        rafId = 0;
        try { reposition(); } catch (e) {}
      });
    }

    function finish() {
      if (!active) return;
      active = false;
      drag = null;
      try {
        els.layer.style.display = 'none';
        els.draft.style.display = 'none';
        els.pill.style.display = 'none';
      } catch (e) {}
      events.push({ type: 'done', count: marks.length });
    }

    function exitAll() {
      active = false;
      drag = null;
      try {
        if (!els) ensure();
        els.layer.style.display = 'none';
        els.draft.style.display = 'none';
        els.pill.style.display = 'none';
      } catch (e) {}
      clearMarks();
      events.push({ type: 'exit' });
    }

    return {
      enter: function (t) {
        ensure();
        if (t) tool = t;
        active = true;
        drag = null;
        els.layer.style.display = 'block';
        els.pill.style.display = 'flex';
        setToolUI();
      },
      finish: finish,
      exit: exitAll,
      clearMarks: clearMarks,
      count: function () { return marks.length; },
      summary: function () {
        var out = [];
        for (var i = 0; i < marks.length; i++) {
          var m = marks[i];
          out.push({
            index: m.idx,
            tool: m.tool,
            x: Math.round(m.docX),
            y: Math.round(m.docY),
            w: m.w,
            h: m.h,
            anchor: m.anchor
          });
        }
        return JSON.stringify(out);
      },
      poll: function () {
        var ev = events.slice();
        events = [];
        return JSON.stringify(ev);
      },
      setTool: function (t) {
        tool = t;
        if (els) setToolUI();
      },
      isActive: function () { return active; }
    };
  })();

  window.__cb = cb;
})();
"""
}
