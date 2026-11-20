/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0. See the LICENSE file.
 *
 * CitrusBrowser 独自の google.ima（Interactive Media Ads SDK for HTML5）の代替。
 * 広告の SDK を遮断したページで、プレーヤーが「有効な広告がない」場合と同じ流れで本編へ進めるようにする。
 * 根拠は Google が公開している API リファレンスだけ（2026-11-20 参照）。他の広告ブロッカーの資源は参照していない。
 *   https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/reference/js/google.ima
 *   https://developers.google.com/interactive-media-ads/docs/sdks/html5/client-side/get-started
 * 列挙の値（イベント名の文字列など）はリファレンスに記載がないため独自に定めた。ページは定数を通して比べる。
 */
(function () {
  'use strict';
  var W = window;
  W.google = W.google || {};
  if (W.google.ima && W.google.ima.AdsLoader) return;

  function later(fn) { setTimeout(fn, 0); }
  function noop() {}

  function makeEnum(names) {
    var result = {};
    for (var i = 0; i < names.length; i++) result[names[i]] = names[i].toLowerCase();
    return result;
  }

  // 登録されたリスナーへイベントを配る（optHandlerScope を指定された場合はその this で呼ぶ）。
  function EventTargetLike() { this.listeners = []; }
  EventTargetLike.prototype.addEventListener = function (type, listener, optCapture, optHandlerScope) {
    var types = Array.isArray(type) ? type : [type];
    for (var i = 0; i < types.length; i++) this.listeners.push({ type: types[i], fn: listener, scope: optHandlerScope });
  };
  EventTargetLike.prototype.removeEventListener = function (type, listener) {
    var types = Array.isArray(type) ? type : [type];
    this.listeners = this.listeners.filter(function (entry) { return !(types.indexOf(entry.type) !== -1 && entry.fn === listener); });
  };
  EventTargetLike.prototype.dispatch = function (event) {
    var targets = this.listeners.slice();
    for (var i = 0; i < targets.length; i++) {
      if (targets[i].type !== event.type) continue;
      try {
        if (typeof targets[i].fn === 'function') targets[i].fn.call(targets[i].scope || this, event);
        else if (targets[i].fn && typeof targets[i].fn.handleEvent === 'function') targets[i].fn.handleEvent(event);
      } catch (e) { /* ページ側の処理の例外は、ほかのリスナーへ広げない */ }
    }
  };

  var AdEventType = makeEnum(['AD_BREAK_FETCH_ERROR', 'AD_BREAK_READY', 'AD_BUFFERING', 'AD_CAN_PLAY', 'AD_METADATA', 'AD_PROGRESS',
    'ALL_ADS_COMPLETED', 'CLICK', 'COMPLETE', 'CONTENT_PAUSE_REQUESTED', 'CONTENT_RESUME_REQUESTED', 'DURATION_CHANGE',
    'FIRST_QUARTILE', 'IMPRESSION', 'INTERACTION', 'LINEAR_CHANGED', 'LOADED', 'LOG', 'MIDPOINT', 'PAUSED', 'RESUMED',
    'SKIPPABLE_STATE_CHANGED', 'SKIPPED', 'STARTED', 'THIRD_QUARTILE', 'USER_CLOSE', 'VIDEO_CLICKED', 'VIDEO_ICON_CLICKED',
    'VOLUME_CHANGED', 'VOLUME_MUTED']);

  function AdEvent(type, manager) { this.type = type; this.target = manager; }
  AdEvent.prototype.getAd = function () { return null; };
  AdEvent.prototype.getAdData = function () { return {}; };
  AdEvent.Type = AdEventType;

  // 広告を1件も再生しない AdsManager。start() で本編の再開と、全広告の完了を知らせる。
  function AdsManager() { EventTargetLike.call(this); this.volume = 1; this.destroyed = false; }
  AdsManager.prototype = Object.create(EventTargetLike.prototype);
  AdsManager.prototype.init = noop;
  AdsManager.prototype.start = function () {
    var manager = this;
    later(function () {
      if (manager.destroyed) return;
      manager.dispatch(new AdEvent(AdEventType.CONTENT_RESUME_REQUESTED, manager));
      manager.dispatch(new AdEvent(AdEventType.ALL_ADS_COMPLETED, manager));
    });
  };
  ['stop', 'pause', 'resume', 'skip', 'collapse', 'expand', 'focus', 'resize', 'discardAdBreak', 'updateAdsRenderingSettings',
    'configureAdsManager'].forEach(function (name) { AdsManager.prototype[name] = noop; });
  AdsManager.prototype.destroy = function () { this.destroyed = true; this.listeners = []; };
  AdsManager.prototype.setVolume = function (volume) { this.volume = volume; };
  AdsManager.prototype.getVolume = function () { return this.volume; };
  AdsManager.prototype.getRemainingTime = function () { return -1; };
  AdsManager.prototype.getAdSkippableState = function () { return false; };
  AdsManager.prototype.getCuePoints = function () { return []; };
  AdsManager.prototype.isCustomClickTrackingUsed = function () { return false; };
  AdsManager.prototype.isCustomPlaybackUsed = function () { return false; };

  function AdsManagerLoadedEvent(context) { this.type = AdsManagerLoadedEvent.Type.ADS_MANAGER_LOADED; this.context = context; }
  AdsManagerLoadedEvent.prototype.getAdsManager = function () { return new AdsManager(); };
  AdsManagerLoadedEvent.prototype.getUserRequestContext = function () { return this.context; };
  AdsManagerLoadedEvent.Type = makeEnum(['ADS_MANAGER_LOADED']);

  function AdError(message, code) { this.message = message; this.code = code; }
  AdError.prototype = Object.create(Error.prototype);
  AdError.prototype.getErrorCode = function () { return this.code; };
  AdError.prototype.getVastErrorCode = function () { return AdError.ErrorCode.UNKNOWN_ERROR; };
  AdError.prototype.getType = function () { return AdError.Type.AD_LOAD; };
  AdError.prototype.getMessage = function () { return this.message; };
  AdError.prototype.getInnerError = function () { return null; };
  AdError.Type = makeEnum(['AD_LOAD', 'AD_PLAY']);
  AdError.ErrorCode = {
    ADS_REQUEST_NETWORK_ERROR: 1012, ASSET_FALLBACK_FAILED: 1021, AUTOPLAY_DISALLOWED: 1205, COMPANION_AD_LOADING_FAILED: 603,
    COMPANION_REQUIRED_ERROR: 602, CONSENT_MANAGEMENT_PROVIDER_NOT_READY: 1300, FAILED_TO_REQUEST_ADS: 1005, INVALID_AD_TAG: 1013,
    INVALID_ARGUMENTS: 1101, NONLINEAR_DIMENSIONS_ERROR: 501, OVERLAY_AD_LOADING_FAILED: 502, OVERLAY_AD_PLAYING_FAILED: 500,
    PROTECTED_AUDIENCE_API_ERROR: 1014, STREAM_AUTHORIZATION_FAILED: 1024, STREAM_INITIALIZATION_FAILED: 1020,
    STREAM_INITIALIZATION_TIMEOUT: 1023, UNKNOWN_AD_RESPONSE: 1010, UNKNOWN_ERROR: 900, UNSUPPORTED_URL: 1022,
    VAST_ASSET_NOT_FOUND: 1007, VAST_EMPTY_RESPONSE: 1009, VAST_LINEAR_ASSET_MISMATCH: 403, VAST_LOAD_TIMEOUT: 301,
    VAST_MALFORMED_RESPONSE: 100, VAST_MEDIA_LOAD_TIMEOUT: 402, VAST_NO_ADS_AFTER_WRAPPER: 303, VAST_NONLINEAR_ASSET_MISMATCH: 503,
    VAST_PROBLEM_DISPLAYING_MEDIA_FILE: 405, VAST_SCHEMA_VALIDATION_ERROR: 101, VAST_TOO_MANY_REDIRECTS: 302,
    VAST_TRAFFICKING_ERROR: 200, VAST_UNEXPECTED_DURATION_ERROR: 202, VAST_UNEXPECTED_LINEARITY: 201,
    VAST_UNSUPPORTED_VERSION: 102, VAST_WRAPPER_ERROR: 300, VIDEO_PLAY_ERROR: 400, VPAID_ERROR: 901,
  };
  function AdErrorEvent(error, context) { this.type = AdErrorEvent.Type.AD_ERROR; this.error = error; this.context = context; }
  AdErrorEvent.prototype.getError = function () { return this.error; };
  AdErrorEvent.prototype.getUserRequestContext = function () { return this.context; };
  AdErrorEvent.Type = makeEnum(['AD_ERROR']);

  function ImaSdkSettings() { this.values = {}; }
  ['Locale', 'Ppid', 'PlayerType', 'PlayerVersion', 'SessionId', 'NumRedirects', 'PreloadTimeSeconds', 'AutoPlayAdBreaks',
    'VpaidMode', 'VpaidAllowed', 'CompanionBackfill', 'DisableCustomPlaybackForIOS10Plus', 'FeatureFlags'].forEach(function (name) {
    ImaSdkSettings.prototype['set' + name] = function (value) { this.values[name] = value; };
    ImaSdkSettings.prototype['get' + name] = function () { return this.values[name]; };
  });
  ImaSdkSettings.VpaidMode = makeEnum(['DISABLED', 'ENABLED', 'INSECURE']);
  ImaSdkSettings.CompanionBackfillMode = makeEnum(['ALWAYS', 'ON_MASTER_AD']);
  var settings = new ImaSdkSettings();

  function AdDisplayContainer() {}
  AdDisplayContainer.prototype.initialize = noop;
  AdDisplayContainer.prototype.destroy = noop;

  // requestAds() は、広告のない AdsManager を非同期で渡す（ページが addEventListener を済ませてから届くように）。
  function AdsLoader() { EventTargetLike.call(this); this.destroyed = false; }
  AdsLoader.prototype = Object.create(EventTargetLike.prototype);
  AdsLoader.prototype.requestAds = function (request, context) {
    var loader = this;
    later(function () { if (!loader.destroyed) loader.dispatch(new AdsManagerLoadedEvent(context)); });
  };
  AdsLoader.prototype.contentComplete = noop;
  AdsLoader.prototype.getSettings = function () { return settings; };
  AdsLoader.prototype.getVersion = function () { return '3'; };
  AdsLoader.prototype.destroy = function () { this.destroyed = true; this.listeners = []; };

  function AdsRequest() {}
  ['setAdWillAutoPlay', 'setAdWillPlayMuted', 'setContinuousPlayback'].forEach(function (name) { AdsRequest.prototype[name] = noop; });
  function AdsRenderingSettings() {}
  function CompanionAdSelectionSettings() {}

  W.google.ima = {
    AdDisplayContainer: AdDisplayContainer,
    AdsLoader: AdsLoader,
    AdsRequest: AdsRequest,
    AdsRenderingSettings: AdsRenderingSettings,
    CompanionAdSelectionSettings: CompanionAdSelectionSettings,
    AdEvent: AdEvent,
    AdError: AdError,
    AdErrorEvent: AdErrorEvent,
    AdsManagerLoadedEvent: AdsManagerLoadedEvent,
    ImaSdkSettings: ImaSdkSettings,
    ViewMode: makeEnum(['NORMAL', 'FULLSCREEN']),
    UiElements: makeEnum(['AD_ATTRIBUTION', 'COUNTDOWN']),
    OmidAccessMode: makeEnum(['DOMAIN', 'FULL', 'LIMITED']),
    settings: settings,
  };
})();
