package com.duplex.mobile.browser

/**
 * 注入到页面里的操作库（移植自桌面版 src/main/page-scripts.ts）。
 * 桌面版通过 CDP Runtime.evaluate 注入；手机端通过 WebView.evaluateJavascript 注入，
 * 逻辑保持一致：snapshot 的 [eN] ref 机制、resolve/focus/scroll/query 等。
 */
object PageLibrary {
    const val VERSION = 1

    val JS: String = """
(function () {
  if (window.__cb && window.__cb.v === 1) return;
  var cb = {};
  cb.v = 1;
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

  window.__cb = cb;
})();
"""
}
