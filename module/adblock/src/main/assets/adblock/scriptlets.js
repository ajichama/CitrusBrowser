/*
 * Copyright (C) 2026 CitrusBrowser contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * 各フレームの文書開始時に実行する scriptlet の独自実装。
 * uBO と名前・引数だけを合わせており、仕様は uBO wiki の公開文書（Resources-Library）だけを参照した。
 * uBO の scriptlet のソースは参照していない。
 */
(function () {
  'use strict';
  var BRIDGE = '__CITRUS_SCRIPTLET_BRIDGE__';
  var KEY = '__CITRUS_SCRIPTLET_KEY__';
  var W = window;
  var bridge;
  try { bridge = W[BRIDGE]; } catch (e) { return; }
  // ページのスクリプトからブリッジを使えないよう、取得後に外す。
  try { delete W[BRIDGE]; } catch (e) { /* 外せない環境では鍵の照合だけで守る */ }
  if (!bridge || typeof bridge.get !== 'function') return;
  var href;
  try { href = String(location.href); } catch (e) { return; }
  if (!/^https?:/i.test(href)) return;
  var calls;
  try { calls = JSON.parse(bridge.get(KEY, href)); } catch (e) { return; }
  if (!Array.isArray(calls) || calls.length === 0) return;

  // ページのスクリプトより先に、組み込み関数を確保しておく。
  var doc = document;
  var defineProperty = Object.defineProperty;
  var getOwnDescriptor = Object.getOwnPropertyDescriptor;
  var getPrototypeOf = Object.getPrototypeOf;
  var fnToString = Function.prototype.toString;
  var reflectApply = Reflect.apply;
  var ProxyCtor = W.Proxy;
  var addListener = EventTarget.prototype.addEventListener;
  var removeListener = EventTarget.prototype.removeEventListener;
  var MutationObserverCtor = W.MutationObserver;
  // prevent-setTimeout 等が置き換える前の setTimeout。scriptlet 自身の遅延処理に使う。
  var nativeSetTimeout = W.setTimeout;
  function later(fn, ms) { return reflectApply(nativeSetTimeout, W, [fn, ms]); }
  var errorId = 'citrus' + Math.random().toString(36).slice(2);

  function abort() { throw new ReferenceError(errorId); }

  // 空・未指定はすべてに一致。/.../ は正規表現、それ以外は部分一致の文字列。不正な正規表現は null。
  function toMatcher(source) {
    if (source === undefined || source === '') return function () { return true; };
    var m = /^\/(.+)\/([imsu]*)$/.exec(source);
    if (m) {
      var re;
      try { re = new RegExp(m[1], m[2]); } catch (e) { return null; }
      return function (text) { return re.test(String(text)); };
    }
    return function (text) { return String(text).indexOf(source) !== -1; };
  }

  function functionText(value) {
    try {
      if (typeof value === 'function') return reflectApply(fnToString, value, []);
      if (value && typeof value.handleEvent === 'function') return reflectApply(fnToString, value.handleEvent, []);
      return String(value);
    } catch (e) { return ''; }
  }

  function isObjectLike(value) {
    return value !== null && (typeof value === 'object' || typeof value === 'function');
  }

  // 位置引数の後ろにある「名前, 値」の組を読む。
  function namedTokens(list, start) {
    var result = {};
    for (var i = start; i + 1 < list.length; i += 2) result[list[i]] = list[i + 1];
    return result;
  }

  // 1: すぐ、2: DOMContentLoaded、3: load
  function stageOf(value) {
    switch (String(value === undefined ? '' : value)) {
      case 'interactive': case 'end': case '2': return 2;
      case 'complete': case 'idle': case '3': return 3;
      default: return 1;
    }
  }

  function runAt(stage, fn) {
    var state = doc.readyState;
    if (stage === 1 || (stage === 2 && state !== 'loading') || (stage === 3 && state === 'complete')) { fn(); return; }
    var target = stage === 2 ? doc : W;
    var type = stage === 2 ? 'DOMContentLoaded' : 'load';
    var handler = function () {
      reflectApply(removeListener, target, [type, handler, true]);
      fn();
    };
    reflectApply(addListener, target, [type, handler, true]);
  }

  // 継承した記述子も含めて探す（document.cookie のような prototype 上のアクセサを壊さないため）。
  function findDescriptor(object, prop) {
    var current = object;
    while (current) {
      var desc = getOwnDescriptor(current, prop);
      if (desc) return { desc: desc, own: current === object };
      current = getPrototypeOf(current);
    }
    return null;
  }

  /**
   * `a.b.c` の最終段に install(owner, prop) を仕掛ける。途中段がまだない場合は、代入された時点で続きを仕掛ける。
   * prototype 上のアクセサや変更できない途中段は上書きしない（本来の動作を壊さない）。
   */
  function trapChain(owner, chain, install) {
    var dot = chain.indexOf('.');
    if (dot === -1) { install(owner, chain); return; }
    var prop = chain.slice(0, dot);
    var rest = chain.slice(dot + 1);
    var found = findDescriptor(owner, prop);
    var current;
    try { current = owner[prop]; } catch (e) { return; }
    if (isObjectLike(current)) { trapChain(current, rest, install); return; }
    if (found && !(found.own && 'value' in found.desc && found.desc.writable && found.desc.configurable)) return;
    var value = current;
    try {
      defineProperty(owner, prop, {
        configurable: true,
        enumerable: true,
        get: function () { return value; },
        set: function (next) {
          value = next;
          if (isObjectLike(next)) trapChain(next, rest, install);
        }
      });
    } catch (e) { /* 定義できないオブジェクトでは何もしない */ }
  }

  // 最終段の記述子を差し替える。元の値・アクセサは handlers から参照できるようにする。
  function replaceProperty(owner, prop, makeHandlers) {
    var found = findDescriptor(owner, prop);
    if (found && found.own && found.desc.configurable === false) return;
    var original = found ? found.desc : null;
    var holder = { value: original && 'value' in original ? original.value : undefined };
    var getOriginal = function (receiver) {
      if (original && original.get) return reflectApply(original.get, receiver, []);
      return holder.value;
    };
    var setOriginal = function (receiver, next) {
      if (original && original.set) { reflectApply(original.set, receiver, [next]); return; }
      holder.value = next;
    };
    var handlers = makeHandlers(getOriginal, setOriginal, holder);
    if (!handlers) return;
    try {
      defineProperty(owner, prop, { configurable: true, enumerable: true, get: handlers.get, set: handlers.set });
    } catch (e) { /* 定義できないオブジェクトでは何もしない */ }
  }

  var scriptlets = {};

  // set-constant: プロパティを決められた値に固定する。
  scriptlets['set-constant'] = function (args) {
    var chain = args[0];
    var raw = args[1];
    if (!chain || raw === undefined) return;
    var value;
    switch (raw) {
      case 'undefined': value = undefined; break;
      case 'null': value = null; break;
      case 'false': value = false; break;
      case 'true': value = true; break;
      case 'noopFunc': value = function () {}; break;
      case 'trueFunc': value = function () { return true; }; break;
      case 'falseFunc': value = function () { return false; }; break;
      case 'throwFunc': value = function () { throw new Error(errorId); }; break;
      case '': case "''": value = ''; break;
      case '[]': value = []; break;
      case '{}': value = {}; break;
      default:
        if (!/^\d+$/.test(raw) || Number(raw) > 0x7FFF) return;
        value = Number(raw);
    }
    // 3番目の位置引数（旧形式の実行時期）と、名前つきの runAt・as を読む。
    var tokenStart = 2;
    var legacyStage = 1;
    if (args.length > 2 && ['runAt', 'as', 'stack', 'log', 'debug'].indexOf(args[2]) === -1) {
      legacyStage = stageOf(args[2]);
      tokenStart = 3;
    }
    var tokens = namedTokens(args, tokenStart);
    var stage = tokens.runAt !== undefined ? stageOf(tokens.runAt) : legacyStage;
    switch (tokens.as) {
      case 'function': { var v1 = value; value = function () { return v1; }; break; }
      case 'callback': { var v2 = value; value = function () { return function () { return v2; }; }; break; }
      case 'resolved': value = Promise.resolve(value); break;
      case 'rejected': { var rejected = Promise.reject(value); rejected.catch(function () {}); value = rejected; break; }
      default: break;
    }
    var isEmpty = function (v) { return v === undefined || v === null; };
    runAt(stage, function () {
      trapChain(W, chain, function (owner, prop) {
        replaceProperty(owner, prop, function (getOriginal, setOriginal) {
          var existing;
          try { existing = getOriginal(owner); } catch (e) { return null; }
          // 型の異なる既存の値は置き換えない（wiki の成立条件）。
          if (!isEmpty(existing) && !isEmpty(value) && typeof existing !== typeof value) return null;
          var active = true;
          var current = value;
          return {
            get: function () { return current; },
            set: function (next) {
              // 型の異なる値の代入だけは受け入れ、以後は通常のプロパティとして振る舞う。
              if (active && !isEmpty(value) && !isEmpty(next) && typeof next !== typeof value) {
                active = false;
                current = next;
              } else if (!active) {
                current = next;
              }
            }
          };
        });
      });
    });
  };

  // abort-on-property-read: 読み取られたら、そのスクリプトを中断する。書き込みは無視する。
  scriptlets['abort-on-property-read'] = function (args) {
    if (!args[0]) return;
    trapChain(W, args[0], function (owner, prop) {
      replaceProperty(owner, prop, function () {
        return { get: abort, set: function () {} };
      });
    });
  };

  // abort-on-property-write: 書き込まれたら、そのスクリプトを中断する。
  scriptlets['abort-on-property-write'] = function (args) {
    if (!args[0]) return;
    trapChain(W, args[0], function (owner, prop) {
      replaceProperty(owner, prop, function (getOriginal) {
        return { get: function () { return getOriginal(this); }, set: abort };
      });
    });
  };

  // data: URI の src は、URL エンコードまたは base64 を解いてから照合する。
  function decodedSource(src) {
    var m = /^data:([^,]*?)(;base64)?,(.*)$/i.exec(src);
    if (!m) return src;
    try { return m[2] ? atob(m[3]) : decodeURIComponent(m[3]); } catch (e) { return src; }
  }

  // abort-current-script: 条件に合う <script> の実行中にプロパティへ触れたら中断する。
  scriptlets['abort-current-script'] = function (args) {
    var chain = args[0];
    if (!chain) return;
    var textMatches = toMatcher(args[1]);
    var srcMatches = toMatcher(args[2]);
    if (!textMatches || !srcMatches) return;
    var check = function () {
      var script = doc.currentScript;
      if (!script || script.nodeName !== 'SCRIPT') return;
      var src = script.src || '';
      var hasText = args[1] !== undefined && args[1] !== '';
      var hasSrc = args[2] !== undefined && args[2] !== '';
      // 条件がない場合は、wiki の説明どおりインラインのスクリプトだけを対象にする。
      if (!hasText && !hasSrc && src !== '') return;
      if (hasSrc && !srcMatches(decodedSource(src))) return;
      if (hasText && !textMatches(script.textContent || '')) return;
      abort();
    };
    trapChain(W, chain, function (owner, prop) {
      replaceProperty(owner, prop, function (getOriginal, setOriginal) {
        return {
          get: function () { check(); return getOriginal(this); },
          set: function (next) { check(); setOriginal(this, next); }
        };
      });
    });
  };

  function delayMatcher(source) {
    if (source === '') return function () { return true; };
    var m = /^(\d*)-(\d*)$/.exec(source);
    if (m) {
      var low = m[1] === '' ? -Infinity : Number(m[1]);
      var high = m[2] === '' ? Infinity : Number(m[2]);
      return function (delay) { return delay >= low && delay <= high; };
    }
    if (/^\d+$/.test(source)) {
      var exact = Number(source);
      return function (delay) { return delay === exact; };
    }
    return null;
  }

  // prevent-setTimeout / prevent-setInterval: 条件に合う呼び出しの処理を空の関数に置き換える。
  function defuseTimer(name, args) {
    var needle = args[0] === undefined ? '' : args[0];
    var delay = args[1] === undefined ? '' : args[1];
    // 引数がない場合、wiki ではログ出力だけなので、何もしない。
    if (needle === '' && delay === '') return;
    var notNeedle = needle.charAt(0) === '!';
    if (notNeedle) needle = needle.slice(1);
    var notDelay = delay.charAt(0) === '!';
    if (notDelay) delay = delay.slice(1);
    var needleMatches = toMatcher(needle);
    var delayMatches = delayMatcher(delay);
    if (!needleMatches || !delayMatches || !ProxyCtor) return;
    var original = W[name];
    if (typeof original !== 'function') return;
    W[name] = new ProxyCtor(original, {
      apply: function (target, thisArg, callArgs) {
        var text = functionText(callArgs[0]);
        var ms = Number(callArgs[1]) || 0;
        if (needleMatches(text) !== notNeedle && delayMatches(ms) !== notDelay) {
          var replaced = Array.prototype.slice.call(callArgs);
          replaced[0] = function () {};
          return reflectApply(target, thisArg, replaced);
        }
        return reflectApply(target, thisArg, callArgs);
      }
    });
  }
  scriptlets['prevent-setTimeout'] = function (args) { defuseTimer('setTimeout', args); };
  scriptlets['prevent-setInterval'] = function (args) { defuseTimer('setInterval', args); };

  // remove-node-text: 名前と内容が条件に合うノードの文字列を空にする（script なら実行前に中身を消す）。
  scriptlets['remove-node-text'] = function (args) {
    var nodeName = args[0];
    if (!nodeName || args[1] === undefined || !MutationObserverCtor) return;
    nodeName = nodeName.toLowerCase();
    var matches = toMatcher(args[1]);
    var tokens = namedTokens(args, 2);
    var includes = tokens.includes !== undefined ? toMatcher(tokens.includes) : null;
    var excludes = tokens.excludes !== undefined ? toMatcher(tokens.excludes) : null;
    if (!matches || (tokens.includes !== undefined && !includes) || (tokens.excludes !== undefined && !excludes)) return;
    var limit = parseInt(tokens.sedCount, 10) || 0;
    var stay = tokens.stay === '1';
    var quitAfter = parseInt(tokens.quitAfter, 10) || 0;
    var count = 0;
    var observer = null;
    var stop = function () { if (observer) { observer.disconnect(); observer = null; } };
    var check = function (node) {
      if (!observer || !node || String(node.nodeName).toLowerCase() !== nodeName) return;
      var text = node.nodeType === 3 ? node.nodeValue : node.textContent;
      text = text || '';
      if (text === '' || !matches(text) || (includes && !includes(text)) || (excludes && excludes(text))) return;
      if (node.nodeType === 3) node.nodeValue = ''; else node.textContent = '';
      count++;
      if (limit > 0 && count >= limit) stop();
    };
    var walk = function (root) {
      check(root);
      if (root.nodeType !== 1) return;
      var walker = doc.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
      var node;
      while (observer && (node = walker.nextNode())) check(node);
    };
    observer = new MutationObserverCtor(function (records) {
      for (var i = 0; i < records.length && observer; i++) {
        var added = records[i].addedNodes;
        for (var j = 0; j < added.length && observer; j++) {
          var node = added[j];
          walk(node);
          // script の本文は、要素の追加後に文字ノードとして届くことがある。
          if (node.nodeType === 3 && node.parentNode) check(node.parentNode);
        }
      }
    });
    observer.observe(doc, { childList: true, subtree: true });
    if (doc.documentElement) walk(doc.documentElement);
    if (!stay) {
      runAt(2, function () {
        if (quitAfter > 0) later(stop, quitAfter); else stop();
      });
    }
  };

  // set-local-storage-item / set-session-storage-item: 決められた値だけを保存する。
  var storageValues = ['', 'undefined', 'null', 'false', 'true', 'on', 'off', 'yes', 'no', 'accept', 'reject',
    'accepted', 'rejected', 'allowed', 'denied', '{}', '[]', '""', "''"];
  function setStorageItem(kind, args) {
    var key = args[0];
    var raw = args[1];
    if (!key || raw === undefined) return;
    var storage;
    try { storage = W[kind]; } catch (e) { return; }
    if (!storage) return;
    try {
      if (raw === '$remove$') { storage.removeItem(key); return; }
      var value;
      if (storageValues.indexOf(raw) !== -1) {
        value = raw === '""' || raw === "''" ? '' : raw;
      } else if (/^-?\d+$/.test(raw) && Math.abs(Number(raw)) <= 32767) {
        value = String(Number(raw));
      } else {
        return;
      }
      storage.setItem(key, value);
    } catch (e) { /* 容量超過や保存の禁止では何もしない */ }
  }
  scriptlets['set-local-storage-item'] = function (args) { setStorageItem('localStorage', args); };
  scriptlets['set-session-storage-item'] = function (args) { setStorageItem('sessionStorage', args); };

  // addEventListener-defuser: 条件に合うイベント処理の登録を無視する。
  scriptlets['addEventListener-defuser'] = function (args) {
    var type = args[0] === undefined ? '' : args[0];
    var pattern = args[1] === undefined ? '' : args[1];
    // 引数がない場合、wiki ではログ出力だけなので、何もしない。
    if ((type === '' && pattern === '') || !ProxyCtor) return;
    var typeMatches = toMatcher(type);
    var handlerMatches = toMatcher(pattern);
    if (!typeMatches || !handlerMatches) return;
    var tokens = namedTokens(args, 2);
    var selector = tokens.elements || '';
    var targetMatches = function (target) {
      if (selector === '') return true;
      if (selector === 'window') return target === W;
      if (selector === 'document') return target === doc;
      try { return target instanceof Element && target.matches(selector); } catch (e) { return false; }
    };
    runAt(stageOf(tokens.runAt), function () {
      var proto = EventTarget.prototype;
      proto.addEventListener = new ProxyCtor(proto.addEventListener, {
        apply: function (target, thisArg, callArgs) {
          if (typeMatches(String(callArgs[0])) && handlerMatches(functionText(callArgs[1])) && targetMatches(thisArg)) return undefined;
          return reflectApply(target, thisArg, callArgs);
        }
      });
    });
  };

  // ---- 第2弾（同じく uBO wiki の Resources-Library の公開仕様だけを参照） ----

  // 先頭の ! を否定として扱う一致関数。空は「すべてに一致」。
  function negatableMatcher(source) {
    source = source === undefined ? '' : String(source);
    var negate = source.charAt(0) === '!';
    var matches = toMatcher(negate ? source.slice(1) : source);
    if (!matches) return null;
    return function (text) { return matches(text) !== negate; };
  }

  function proxyFunction(owner, name, apply) {
    var original = owner[name];
    if (typeof original !== 'function' || !ProxyCtor) return false;
    owner[name] = new ProxyCtor(original, { apply: apply });
    return true;
  }

  // prevent-window-open: 条件に合う window.open を開かない。秒数を指定すると、見えない偽の window を返す。
  scriptlets['prevent-window-open'] = function (args) {
    var pattern = args[0] === undefined ? '' : args[0];
    if (pattern === 'debug') return;
    var matches = negatableMatcher(pattern);
    if (!matches) return;
    var seconds = parseInt(args[1], 10);
    var useObject = String(args[2] || '').indexOf('obj') !== -1;
    proxyFunction(W, 'open', function (target, thisArg, callArgs) {
      var text = Array.prototype.map.call(callArgs, function (a) { return String(a); }).join(' ');
      if (!matches(text)) return reflectApply(target, thisArg, callArgs);
      if (!(seconds > 0)) return null;
      // 広告の URL は読み込まず、空のページを入れた見えない枠を偽の window として返す。
      var decoy = doc.createElement(useObject ? 'object' : 'iframe');
      if (useObject) decoy.data = 'about:blank'; else decoy.src = 'about:blank';
      decoy.setAttribute('style', 'position:fixed;top:-99px;left:-99px;width:1px;height:1px;border:0;opacity:0;pointer-events:none');
      (doc.body || doc.documentElement).appendChild(decoy);
      later(function () { decoy.remove(); }, seconds * 1000);
      return decoy.contentWindow || null;
    });
  };

  // prevent-fetch / prevent-xhr の条件: 空白区切りで、すべて一致したときだけ止める。
  var fetchOptionNames = ['url', 'method', 'mode', 'credentials', 'cache', 'redirect', 'referrer', 'referrerPolicy',
    'integrity', 'keepalive', 'priority', 'body', 'headers'];
  function requestConditions(source, names) {
    if (source === undefined || source === '') return null;
    var conditions = [];
    var parts = String(source).split(/\s+/);
    for (var i = 0; i < parts.length; i++) {
      if (parts[i] === '') continue;
      var m = /^([A-Za-z]+):(.*)$/.exec(parts[i]);
      var name = m && names.indexOf(m[1]) !== -1 ? m[1] : 'url';
      var matcher = negatableMatcher(m && name === m[1] ? m[2] : parts[i]);
      if (!matcher) return null;
      conditions.push({ name: name, test: matcher });
    }
    return function (props) {
      for (var j = 0; j < conditions.length; j++) {
        if (!conditions[j].test(props[conditions[j].name] === undefined ? '' : String(props[conditions[j].name]))) return false;
      }
      return true;
    };
  }

  // 応答本文の指示（true / emptyObj / emptyArr / emptyStr / length:min[-max]）。未知の指示は空文字列。
  function responseBody(directive) {
    var chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    function randomText(n) { var s = ''; for (var i = 0; i < n; i++) s += chars.charAt(Math.floor(Math.random() * chars.length)); return s; }
    switch (directive) {
      case 'true': return randomText(10);
      case 'emptyObj': return '{}';
      case 'emptyArr': return '[]';
      default: break;
    }
    var m = /^length:(\d+)(?:-(\d+))?$/.exec(directive || '');
    if (m) {
      var low = Math.min(Number(m[1]), 500000);
      var high = Math.min(m[2] ? Number(m[2]) : low, 500000);
      return randomText(low + Math.floor(Math.random() * (Math.max(high, low) - low + 1)));
    }
    return '';
  }

  // 応答の種類（ok / type / statusText）。JSON 文字列、または type の値だけを受け付ける。外側の引用符は外す。
  function responseShape(source) {
    if (source === undefined || source === '') return {};
    var text = String(source).replace(/^'(.*)'$/, '$1');
    if (text.charAt(0) === '{') {
      try { var parsed = JSON.parse(text); return parsed && typeof parsed === 'object' ? parsed : {}; } catch (e) { return {}; }
    }
    return { type: text };
  }

  scriptlets['prevent-fetch'] = function (args) {
    var matches = requestConditions(args[0], fetchOptionNames);
    if (!matches || typeof W.fetch !== 'function' || typeof W.Response !== 'function') return;
    var directive = args[1] === undefined ? '' : args[1];
    var shape = responseShape(args[2]);
    proxyFunction(W, 'fetch', function (target, thisArg, callArgs) {
      var input = callArgs[0];
      var init = callArgs[1] && typeof callArgs[1] === 'object' ? callArgs[1] : {};
      var isRequest = typeof W.Request === 'function' && input instanceof W.Request;
      var props = { url: isRequest ? input.url : String(input), method: init.method || (isRequest ? input.method : 'GET') };
      for (var i = 0; i < fetchOptionNames.length; i++) {
        var name = fetchOptionNames[i];
        if (name !== 'url' && name !== 'method' && init[name] !== undefined) props[name] = init[name];
      }
      if (!matches(props)) return reflectApply(target, thisArg, callArgs);
      var response = new W.Response(responseBody(directive), { status: 200, statusText: shape.statusText === undefined ? 'OK' : String(shape.statusText) });
      try {
        defineProperty(response, 'url', { value: props.url });
        if (shape.type !== undefined) defineProperty(response, 'type', { value: String(shape.type) });
        if (shape.ok !== undefined) defineProperty(response, 'ok', { value: shape.ok === true || shape.ok === 'true' });
      } catch (e) { /* 定義できない環境では既定の値のまま */ }
      return Promise.resolve(response);
    });
  };

  scriptlets['prevent-xhr'] = function (args) {
    var matches = requestConditions(args[0], ['method', 'url']);
    var XHR = W.XMLHttpRequest;
    if (!matches || typeof XHR !== 'function') return;
    var directive = args[1] === undefined ? '' : args[1];
    var requests = new WeakMap();
    proxyFunction(XHR.prototype, 'open', function (target, thisArg, callArgs) {
      requests.set(thisArg, { method: String(callArgs[0] || 'GET'), url: String(callArgs[1] || '') });
      return reflectApply(target, thisArg, callArgs);
    });
    proxyFunction(XHR.prototype, 'send', function (target, thisArg, callArgs) {
      var props = requests.get(thisArg);
      if (!props || !matches(props)) return reflectApply(target, thisArg, callArgs);
      // 通信せず、空の応答を受け取ったように振る舞う。
      var text = responseBody(directive);
      var type = thisArg.responseType;
      var body = text;
      if (type === 'json') { try { body = JSON.parse(text); } catch (e) { body = null; } }
      else if (type === 'arraybuffer') body = new ArrayBuffer(0);
      else if (type === 'blob') body = new Blob([]);
      else if (type === 'document') body = null;
      var values = { readyState: 4, status: 200, statusText: 'OK', response: body, responseURL: props.url };
      if (type === '' || type === 'text') values.responseText = text;
      try {
        Object.keys(values).forEach(function (key) { defineProperty(thisArg, key, { value: values[key], configurable: true }); });
      } catch (e) { return reflectApply(target, thisArg, callArgs); }
      later(function () {
        ['readystatechange', 'load', 'loadend'].forEach(function (type2) {
          try { thisArg.dispatchEvent(new Event(type2)); } catch (e) { /* 続ける */ }
        });
      }, 1);
      return undefined;
    });
  };

  // adjust-setTimeout / adjust-setInterval: 条件に合う呼び出しの遅延を倍率で変える（既定 1000ms・0.05倍）。
  function adjustTimer(name, args) {
    var matches = toMatcher(args[0] === undefined ? '' : args[0]);
    if (!matches) return;
    var delayArg = args[1] === undefined || args[1] === '' ? '1000' : args[1];
    if (delayArg !== '*' && !/^\d+$/.test(delayArg)) return;
    var boost = args[2] === undefined || args[2] === '' ? 0.05 : parseFloat(args[2]);
    if (!isFinite(boost)) return;
    boost = Math.min(Math.max(boost, 0.02), 50);
    proxyFunction(W, name, function (target, thisArg, callArgs) {
      var delay = Number(callArgs[1]) || 0;
      if ((delayArg === '*' || delay === Number(delayArg)) && matches(functionText(callArgs[0]))) {
        var replaced = Array.prototype.slice.call(callArgs);
        replaced[1] = Math.round(delay * boost);
        return reflectApply(target, thisArg, replaced);
      }
      return reflectApply(target, thisArg, callArgs);
    });
  }
  scriptlets['adjust-setTimeout'] = function (args) { adjustTimer('setTimeout', args); };
  scriptlets['adjust-setInterval'] = function (args) { adjustTimer('setInterval', args); };

  // prevent-requestAnimationFrame: 条件に合うコールバックを空の関数に置き換える。
  scriptlets['prevent-requestAnimationFrame'] = function (args) {
    if (args[0] === undefined || args[0] === '') return;
    var matches = negatableMatcher(args[0]);
    if (!matches) return;
    proxyFunction(W, 'requestAnimationFrame', function (target, thisArg, callArgs) {
      if (!matches(functionText(callArgs[0]))) return reflectApply(target, thisArg, callArgs);
      return reflectApply(target, thisArg, [function () {}]);
    });
  };

  // abort-on-stack-trace: プロパティに触れたときの呼び出し履歴が条件に合えば中断する。
  scriptlets['abort-on-stack-trace'] = function (args) {
    var chain = args[0];
    var needle = args[1] === undefined ? '' : args[1];
    if (!chain || needle === '') return;
    var negate = needle.charAt(0) === '!';
    var body = negate ? needle.slice(1) : needle;
    var matches = body === 'inlineScript' ? null : toMatcher(body);
    if (body !== 'inlineScript' && !matches) return;
    var check = function () {
      var hit;
      if (body === 'inlineScript') {
        var script = doc.currentScript;
        hit = !!script && script.nodeName === 'SCRIPT' && !script.src;
      } else {
        var stack = '';
        try { stack = String(new Error().stack); } catch (e) { stack = ''; }
        hit = matches(stack);
      }
      if (hit !== negate) abort();
    };
    trapChain(W, chain, function (owner, prop) {
      replaceProperty(owner, prop, function (getOriginal, setOriginal) {
        return {
          get: function () { check(); return getOriginal(this); },
          set: function (next) { check(); setOriginal(this, next); }
        };
      });
    });
  };

  // noeval-if: 内容が条件に合う eval を実行しない。noeval: eval をすべて実行しない。
  function guardEval(matches) {
    proxyFunction(W, 'eval', function (target, thisArg, callArgs) {
      if (matches(String(callArgs[0]))) return undefined;
      return reflectApply(target, thisArg, callArgs);
    });
  }
  scriptlets['noeval-if'] = function (args) {
    if (args[0] === undefined || args[0] === '') return;
    var matches = toMatcher(args[0]);
    if (matches) guardEval(matches);
  };
  scriptlets['noeval'] = function () { guardEval(function () { return true; }); };

  // json-prune: JSON.parse と Response.json の結果から、指定したプロパティを取り除く。
  function splitPath(path) {
    var parts = [];
    String(path).split('.').forEach(function (part) {
      var m = /^(.*?)((?:\[\])+)$/.exec(part);
      if (m) {
        if (m[1] !== '') parts.push(m[1]);
        for (var i = 0; i < m[2].length / 2; i++) parts.push('[]');
      } else if (part !== '') {
        parts.push(part);
      }
    });
    return parts;
  }
  function visitPath(value, parts, index, visit) {
    if (!isObjectLike(value)) return;
    var part = parts[index];
    var keys;
    if (part === '[]') keys = Array.isArray(value) ? Object.keys(value) : [];
    else if (part === '*') keys = Object.keys(value);
    else keys = Object.prototype.hasOwnProperty.call(value, part) ? [part] : [];
    for (var i = 0; i < keys.length; i++) {
      if (index === parts.length - 1) visit(value, keys[i]);
      else visitPath(value[keys[i]], parts, index + 1, visit);
    }
  }
  scriptlets['json-prune'] = function (args) {
    if (args[0] === undefined || args[0] === '') return;
    var prune = args[0].split(/\s+/).filter(Boolean).map(splitPath);
    // [-] と {-} は未対応。含む行は何もしない（遮断範囲を広げない）。
    if (/\[-\]|\{-\}/.test(args[0])) return;
    var required = [];
    if (args[1] !== undefined && args[1] !== '' && args[1].charAt(0) !== '/') {
      required = args[1].split(/\s+/).filter(Boolean).map(splitPath);
    }
    var stackMatches = args[2] === undefined || args[2] === '' ? null : negatableMatcher(args[2]);
    if (args[2] !== undefined && args[2] !== '' && !stackMatches) return;
    var pruneValue = function (value) {
      if (!isObjectLike(value)) return value;
      if (stackMatches) {
        var stack = '';
        try { stack = String(new Error().stack); } catch (e) { stack = ''; }
        if (!stackMatches(stack)) return value;
      }
      for (var i = 0; i < required.length; i++) {
        var found = false;
        visitPath(value, required[i], 0, function () { found = true; });
        if (!found) return value;
      }
      prune.forEach(function (parts) {
        if (parts.length === 0) return;
        visitPath(value, parts, 0, function (owner, key) { try { delete owner[key]; } catch (e) { /* 消せない値は残す */ } });
      });
      return value;
    };
    proxyFunction(JSON, 'parse', function (target, thisArg, callArgs) {
      return pruneValue(reflectApply(target, thisArg, callArgs));
    });
    if (typeof W.Response === 'function') {
      proxyFunction(W.Response.prototype, 'json', function (target, thisArg, callArgs) {
        return reflectApply(target, thisArg, callArgs).then(pruneValue);
      });
    }
  };

  // remove-attr / remove-class: 要素から属性・クラスを取り除く（既定は解析完了後に1回）。
  function removeFromElements(args, kind) {
    var names = String(args[0] || '').split('|').map(function (s) { return s.trim(); }).filter(Boolean);
    if (names.length === 0) return;
    var selector = args[1] && args[1] !== '' ? args[1] : names.map(function (n) {
      return kind === 'attr' ? '[' + n + ']' : '.' + (W.CSS && CSS.escape ? CSS.escape(n) : n);
    }).join(',');
    try { doc.createDocumentFragment().querySelector(selector); } catch (e) { return; }
    var behavior = String(args[2] || '').split(/\s+/);
    var applyOnce = function () {
      var nodes;
      try { nodes = doc.querySelectorAll(selector); } catch (e) { return; }
      for (var i = 0; i < nodes.length; i++) {
        for (var j = 0; j < names.length; j++) {
          if (kind === 'attr') nodes[i].removeAttribute(names[j]); else nodes[i].classList.remove(names[j]);
        }
      }
    };
    var start = function () {
      applyOnce();
      if (behavior.indexOf('stay') === -1 || !MutationObserverCtor) return;
      var pending = false;
      new MutationObserverCtor(function () {
        if (pending) return;
        pending = true;
        later(function () { pending = false; applyOnce(); }, 0);
      }).observe(doc, { childList: true, subtree: true, attributes: true,
        attributeFilter: kind === 'attr' ? names : ['class'] });
    };
    if (behavior.indexOf('asap') !== -1) runAt(1, start);
    else if (behavior.indexOf('complete') !== -1) runAt(3, start);
    else runAt(2, start);
  }
  scriptlets['remove-attr'] = function (args) { removeFromElements(args, 'attr'); };
  scriptlets['remove-class'] = function (args) { removeFromElements(args, 'class'); };

  // nowebrtc: RTCPeerConnection を、何も接続しない代わりの物にする。
  scriptlets['nowebrtc'] = function () {
    var Fake = function () {};
    var resolved = function () { return Promise.resolve(); };
    ['close', 'addEventListener', 'removeEventListener', 'addTrack', 'removeTrack'].forEach(function (m) { Fake.prototype[m] = function () {}; });
    ['createOffer', 'createAnswer', 'setLocalDescription', 'setRemoteDescription', 'addIceCandidate', 'getStats'].forEach(function (m) { Fake.prototype[m] = resolved; });
    Fake.prototype.createDataChannel = function () { return { close: function () {}, send: function () {}, addEventListener: function () {} }; };
    ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (name) {
      if (typeof W[name] === 'function') { try { W[name] = Fake; } catch (e) { /* 置き換えられない環境では何もしない */ } }
    });
  };

  // set-cookie: 決められた値だけを Cookie に保存する。
  var cookieValues = ['accept', 'reject', 'accepted', 'rejected', 'notaccepted', 'allow', 'disallow', 'deny', 'allowed',
    'denied', 'approved', 'disapproved', 'checked', 'unchecked', 'dismiss', 'dismissed', 'enable', 'disable', 'enabled',
    'disabled', 'essential', 'nonessential', 'forbidden', 'forever', 'hide', 'hidden', 'necessary', 'required', 'ok',
    'on', 'off', 'true', 't', 'false', 'f', 'yes', 'y', 'no', 'n', 'all', 'none', 'functional', 'granted', 'done',
    'decline', 'declined', 'closed', 'next', 'mandatory', 'disagree', 'agree'];
  function readCookie(name) {
    var parts = doc.cookie.split(';');
    for (var i = 0; i < parts.length; i++) {
      var eq = parts[i].indexOf('=');
      var key = (eq === -1 ? parts[i] : parts[i].slice(0, eq)).trim();
      if (key === name) return eq === -1 ? '' : parts[i].slice(eq + 1).trim();
    }
    return null;
  }
  scriptlets['set-cookie'] = function (args) {
    var name = args[0];
    var raw = args[1];
    if (!name || raw === undefined) return;
    raw = raw.replace(/^"(.*)"$/, '$1');
    var ok = cookieValues.indexOf(raw.toLowerCase()) !== -1 || (/^-?\d+$/.test(raw) && Math.abs(Number(raw)) <= 32767);
    if (!ok) return;
    var path = args[2] === undefined || args[2] === '' ? '/' : args[2];
    var tokens = namedTokens(args, 3);
    var encodedName = encodeURIComponent(name);
    var before = readCookie(encodedName);
    if (tokens.dontOverwrite === '1' && before !== null) return;
    var cookie = encodedName + '=' + encodeURIComponent(raw);
    if (path !== 'none') cookie += '; path=' + path;
    if (/^__(Secure|Host)-/.test(name)) cookie += '; Secure';
    try { doc.cookie = cookie; } catch (e) { return; }
    if (tokens.reload === '1' && before === null && readCookie(encodedName) !== null) {
      try { W.location.reload(); } catch (e) { /* 再読込できない環境では何もしない */ }
    }
  };

  // remove-cookie: 名前が条件に合う Cookie を、読込時と離脱前に消す。
  scriptlets['remove-cookie'] = function (args) {
    var matches = toMatcher(args[0] === undefined ? '' : args[0]);
    if (!matches) return;
    var tokens = namedTokens(args, 1);
    var removeMatching = function () {
      var host = location.hostname;
      var domains = ['', host, '.' + host];
      if (/^www\./.test(host)) domains.push(host.slice(4), '.' + host.slice(4));
      var paths = ['/', location.pathname];
      doc.cookie.split(';').forEach(function (part) {
        var eq = part.indexOf('=');
        var name = (eq === -1 ? part : part.slice(0, eq)).trim();
        if (name === '' || !matches(name)) return;
        domains.forEach(function (domain) {
          paths.forEach(function (path) {
            var cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=' + path;
            if (domain !== '') cookie += '; domain=' + domain;
            try { doc.cookie = cookie; } catch (e) { /* 続ける */ }
          });
        });
      });
    };
    if (tokens.when === 'scroll' || tokens.when === 'keydown') {
      reflectApply(addListener, W, [tokens.when, function () { later(removeMatching, 500); }, { passive: true, capture: true }]);
      return;
    }
    removeMatching();
    reflectApply(addListener, W, ['pagehide', removeMatching, true]);
    reflectApply(addListener, W, ['beforeunload', removeMatching, true]);
  };

  // window.name-defuser: 最上位の window の name を空にする（追跡対策）。
  scriptlets['window.name-defuser'] = function () {
    try { if (W === W.top) W.name = ''; } catch (e) { /* 続ける */ }
  };

  // disable-newtab-links: target を持つリンクを、同じタブで開くようにする。
  scriptlets['disable-newtab-links'] = function () {
    reflectApply(addListener, doc, ['click', function (event) {
      var link = event.target && event.target.closest ? event.target.closest('a[target]') : null;
      if (link && link.getAttribute('target') !== '_self') link.removeAttribute('target');
    }, true]);
  };

  // href-sanitizer: 中継用のリンクを、表示テキスト・属性・クエリから得た本来の https の URL に置き換える。
  scriptlets['href-sanitizer'] = function (args) {
    var selector = args[0];
    if (!selector) return;
    try { doc.createDocumentFragment().querySelector(selector); } catch (e) { return; }
    var source = args[1] === undefined ? '' : args[1];
    // 変換手順（-base64 等）を含む指定は未対応のため何もしない。
    if (/\s/.test(source.trim())) return;
    var extract = function (link) {
      var candidate;
      if (source === '') candidate = link.textContent;
      else if (/^\[.+\]$/.test(source)) candidate = link.getAttribute(source.slice(1, -1));
      else if (source.charAt(0) === '?') {
        try { candidate = new URL(link.href, location.href).searchParams.get(source.slice(1)); } catch (e) { candidate = null; }
      } else return null;
      if (!candidate) return null;
      try {
        var url = new URL(String(candidate).trim());
        return url.protocol === 'https:' ? url.href : null;
      } catch (e) { return null; }
    };
    var sanitize = function () {
      var links;
      try { links = doc.querySelectorAll(selector); } catch (e) { return; }
      for (var i = 0; i < links.length; i++) {
        if (links[i].nodeName !== 'A' || !links[i].hasAttribute('href')) continue;
        var href = extract(links[i]);
        if (href) links[i].setAttribute('href', href);
      }
    };
    runAt(2, sanitize);
    runAt(3, sanitize);
  };

  for (var i = 0; i < calls.length; i++) {
    var call = calls[i];
    if (!Array.isArray(call) || typeof scriptlets[call[0]] !== 'function') continue;
    try { scriptlets[call[0]](call.slice(1)); } catch (e) { /* 1つの失敗で他の scriptlet を止めない */ }
  }
})();
