/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0. See the LICENSE file.
 *
 * CitrusBrowser 独自の FuckAdBlock・BlockAdBlock（広告ブロックの検知ライブラリ）の代替。
 * 公開 API どおりに振る舞い、検査の結果は常に「検知しなかった」にする。
 * 根拠は各ライブラリの README（MIT、2026-11-20 参照）に書かれた API だけ。他の広告ブロッカーの資源は参照していない。
 *   https://github.com/sitexw/FuckAdBlock
 *   https://github.com/sitexw/BlockAdBlock
 */
(function () {
  'use strict';
  var W = window;

  function Detector(options) {
    this._options = { checkOnLoad: true, resetOnEnd: true, loopCheckTime: 50, loopMaxNumber: 5, baitClass: '', baitStyle: '', debug: false };
    this._handlers = { detected: [], notDetected: [] };
    this._checking = false;
    if (options !== undefined) this.setOption(options);
    var self = this;
    if (this._options.checkOnLoad) {
      var start = function () { setTimeout(function () { self.check(false); }, 1); };
      if (document.readyState === 'complete') start();
      else W.addEventListener('load', start, { once: true });
    }
  }
  Detector.prototype.setOption = function (options, value) {
    if (value !== undefined) { this._options[options] = value; return this; }
    if (options && typeof options === 'object') for (var key in options) this._options[key] = options[key];
    return this;
  };
  // 検査は常に「検知しなかった」で終える。実行中なら false を返す（README の check の戻り値）。
  Detector.prototype.check = function () {
    if (this._checking) return false;
    this._checking = true;
    var self = this;
    setTimeout(function () { self._checking = false; self.emitEvent(false); }, 1);
    return true;
  };
  Detector.prototype.emitEvent = function (detected) {
    var list = (detected === true ? this._handlers.detected : this._handlers.notDetected).slice();
    for (var i = 0; i < list.length; i++) {
      try { list[i](); } catch (e) { /* ページ側の処理の例外は、ほかの処理へ広げない */ }
    }
    if (this._options.resetOnEnd) this.clearEvent();
    return this;
  };
  Detector.prototype.clearEvent = function () { this._handlers.detected = []; this._handlers.notDetected = []; return this; };
  Detector.prototype.on = function (detected, fn) {
    if (typeof fn === 'function') (detected === true ? this._handlers.detected : this._handlers.notDetected).push(fn);
    return this;
  };
  Detector.prototype.onDetected = function (fn) { return this.on(true, fn); };
  Detector.prototype.onNotDetected = function (fn) { return this.on(false, fn); };

  // README のとおり、読込前に fuckAdBlock = false とされていたら自動の生成をしない。
  function install(constructorName, instanceName) {
    var Constructor = function (options) { Detector.call(this, options); };
    Constructor.prototype = Detector.prototype;
    W[constructorName] = Constructor;
    if (W[instanceName] === false || (W[instanceName] && typeof W[instanceName].onNotDetected === 'function')) return;
    W[instanceName] = new W[constructorName]();
  }
  install('FuckAdBlock', 'fuckAdBlock');
  install('BlockAdBlock', 'blockAdBlock');
})();
