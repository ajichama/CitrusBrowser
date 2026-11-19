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
        if (quitAfter > 0) setTimeout(stop, quitAfter); else stop();
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

  for (var i = 0; i < calls.length; i++) {
    var call = calls[i];
    if (!Array.isArray(call) || typeof scriptlets[call[0]] !== 'function') continue;
    try { scriptlets[call[0]](call.slice(1)); } catch (e) { /* 1つの失敗で他の scriptlet を止めない */ }
  }
})();
