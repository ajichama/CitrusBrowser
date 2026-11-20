/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0. See the LICENSE file.
 *
 * CitrusBrowser 独自の AdSense の広告コード（adsbygoogle.js）の代替。
 * ページの `(adsbygoogle = window.adsbygoogle || []).push({})` を受け付け、広告枠を「埋まらなかった枠」として印を付ける。
 * 根拠は Google の公開ヘルプだけ（2026-11-20 参照）。他の広告ブロッカーの資源は参照していない。
 *   https://support.google.com/adsense/answer/10762946 （ins.adsbygoogle の data-ad-status="unfilled"）
 * loaded は、この代替が読み込まれたことを示す独自の印。
 */
(function () {
  'use strict';
  var W = window;
  var queued = Array.isArray(W.adsbygoogle) ? W.adsbygoogle.slice() : [];
  if (W.adsbygoogle && !Array.isArray(W.adsbygoogle) && W.adsbygoogle.loaded) return;

  // まだ印のない広告枠を1つ、埋まらなかった枠にする（push 1回につき1枠）。
  function markNextSlot() {
    var slots = document.querySelectorAll('ins.adsbygoogle:not([data-ad-status])');
    if (slots.length === 0) return;
    slots[0].setAttribute('data-ad-status', 'unfilled');
  }

  function handle(params) {
    // ページ単位の設定（enable_page_level_ads 等）は枠を消費しない。
    if (params && typeof params === 'object' && (params.google_ad_client || params.enable_page_level_ads)) return;
    markNextSlot();
  }

  var shim = {
    loaded: true,
    push: function (params) { handle(params); },
  };
  W.adsbygoogle = shim;
  function drain() { for (var i = 0; i < queued.length; i++) handle(queued[i]); queued = []; }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', drain, { once: true });
  else drain();
})();
