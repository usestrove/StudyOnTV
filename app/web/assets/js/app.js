(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var state = app.state;
  var rt = app.rt;

  function route() {
    app.clearNextTimer(); app.clearResumeTimer(); state.busy = false; app.hideFeedback();
    var hash = location.hash || '#/quiz';
    var fromGate = hash.indexOf('?reason=') >= 0;
    state.settingsGate = hash.indexOf('reason=settings') >= 0;
    if (hash.indexOf('#/pair') === 0) return app.pair();
    app.loadState(function (error) {
      if (error) { app.show('quiz'); $('qText').textContent = error; return; }
      if (state.settingsGate) {
        if (app.isSettingsLockActive()) { app.lock('settings'); return; }
        state.settingsGate = false;
      }
      if (app.isHubAvailable() && !state.learning.awaitingReview && (!state.round || hash.indexOf('#/hub') === 0)) {
        app.renderHub();
        return;
      }
      if (fromGate && !state.settingsGate && !state.learning.reason && state.learning.freeQuotaSeconds > 0 &&
          state.config.playMode !== 'practice') return app.start(null, true);
      // 答题途中被系统清掉再打开：服务端还停在同一题、当前也确实该答题时，恢复原来那组
      if (!state.settingsGate && hash.indexOf('#/lock') !== 0 && !state.learning.reason && !state.learning.free &&
          !state.learning.awaitingReview && !((state.children || []).length > 1 && !state.chosen)) {
        var restored = app.savedRound();
        if (restored) {
          // 同一会话里切走再回来（SOTV_RESUME）不提示；只有真的重启后恢复才提示
          var freshStart = !state.round || state.round.nonce !== restored.nonce;
          state.round = restored;
          app.show('quiz'); app.renderQuestion();
          if (freshStart) app.toast('已恢复到刚才的题目');
          return;
        }
      }
      if (hash.indexOf('#/lock') === 0 && !state.learning.free && !app.isPureStudyMode()) app.lock(state.learning.reason || 'timeup');
      else if ((state.children || []).length > 1 && !state.chosen) app.chooseChild();
      else app.start(null, fromGate);
    });
  }

  function resumeSync() {
    var hash = location.hash || '#/quiz';
    if (hash.indexOf('#/pair') === 0 || ((state.settingsGate || hash.indexOf('reason=settings') >= 0) && app.isSettingsLockActive())) {
      route();
      return;
    }
    if (state.screen !== 'quiz' || !state.round) {
      route();
      return;
    }
    var prevQuizGen = state.learning.quizGeneration || 0;
    var prevPure = app.isPureStudyMode();
    var prevFree = !!state.learning.free;
    var prevOvKey = app.overrideKey(state.learning);
    var prevAwaiting = !!state.learning.awaitingReview;
    var prevChild = state.activeChild || '';
    app.loadState(function (error) {
      if (error) return;
      if (state.screen !== 'quiz' || !state.round) return;
      var reason = state.learning.reason;
      var settingsBlocked = (state.settingsGate || reason === 'settings') && app.isSettingsLockActive();
      var forceLock = settingsBlocked ||
        (!app.isPureStudyMode() && reason === 'locked') ||
        (!app.isHubAvailable() && state.config.playMode !== 'practice' && reason &&
          ['rest', 'schedule', 'rounds', 'quota', 'feedback'].indexOf(reason) >= 0);
      if (forceLock) {
        app.clearNextTimer(); state.busy = false; state.awaiting = false; state.reviewed = false;
        state.round = null; app.clearRound(); app.hideFeedback();
        app.lock(settingsBlocked ? 'settings' : reason);
        return;
      }
      var invalidated =
        prevQuizGen !== (state.learning.quizGeneration || 0) ||
        prevPure !== app.isPureStudyMode() ||
        (prevChild && state.activeChild && prevChild !== state.activeChild) ||
        (!app.isPureStudyMode() && prevOvKey !== app.overrideKey(state.learning)) ||
        (!app.isPureStudyMode() && state.config.playMode !== 'practice' && prevFree !== !!state.learning.free) ||
        prevAwaiting !== !!state.learning.awaitingReview;
      if (invalidated) {
        app.clearNextTimer(); state.busy = false; state.awaiting = false; state.reviewed = false;
        state.round = null; app.clearRound(); app.hideFeedback();
        route();
      }
    });
  }

  function resetToHubOrStart() {
    app.clearNextTimer(); state.busy = false; state.awaiting = false; state.reviewed = false;
    state.round = null; app.hideFeedback();
    if (app.isHubAvailable()) app.renderHub(); else app.start();
  }

  function pollTick() {
    if (rt.pagePaused || document.hidden) return;
    var clock = $('clock'), time = new Date().toTimeString().slice(0, 5);
    if (clock.textContent !== time) clock.textContent = time;
    if (rt.stateLoads || !app.pollAllowed()) return;
    var version = state.config.version, quizGeneration = state.learning.quizGeneration || 0;
    var wasFree = !!state.learning.free, wasPure = app.isPureStudyMode(), prevOvKey = app.overrideKey(state.learning);
    app.loadState(function (error) {
      if (error) return;
      if (quizGeneration !== (state.learning.quizGeneration || 0)) { resetToHubOrStart(); return; }
      if (wasPure !== app.isPureStudyMode()) { resetToHubOrStart(); return; }
      if (state.settingsGate) {
        if (app.isSettingsLockActive()) return;
        state.settingsGate = false;
        if (app.isHubAvailable()) app.renderHub(); else app.start();
        return;
      }
      if (wasFree !== !!state.learning.free && !app.isPureStudyMode() && state.config.playMode !== 'practice') {
        resetToHubOrStart();
        return;
      }
      if (app.overrideKey(state.learning) !== prevOvKey && !app.isPureStudyMode()) {
        resetToHubOrStart();
        return;
      }
      if (state.screen === 'hub') {
        if (!app.isHubAvailable()) {
          if (state.learning.reason) app.lock(state.learning.reason); else app.start();
        } else {
          app.updateHubStats();
        }
        return;
      }
      if (state.feedback) { state.feedbackUntil = Date.now() + (state.learning.feedbackSeconds || 0) * 1000; app.updateFeedbackClock(); }
      if (state.screen === 'lock' && state.learning.reason === 'rest' && !app.isPureStudyMode()) { app.lock('rest'); return; }
      if (state.reviewed || state.feedback) return;
      if (state.awaiting) { if (!state.learning.awaitingReview) { if (app.isHubAvailable()) app.renderHub(); else app.start(); } return; }
      if (state.round && state.round.studyPhase === 'memorize') return;
      if (state.screen === 'lock' && (state.lockReason !== state.learning.reason || state.wasFree !== !!state.learning.free)) {
        state.wasFree = !!state.learning.free;
        if (app.isHubAvailable()) app.renderHub();
        else if (!state.learning.reason) app.start(null, true);
        else app.lock(state.learning.reason);
        return;
      }
      if (state.screen === 'quiz' && !app.isHubAvailable() && state.learning.reason && ['quota', 'rounds', 'schedule', 'rest', 'feedback'].indexOf(state.learning.reason) >= 0 && state.config.playMode !== 'practice') app.lock(state.learning.reason);
      else if (version !== state.config.version && state.screen === 'quiz' && !app.isHubAvailable()) app.start();
    }, true);
  }

  function boot() {
    app.show('quiz');
    app.bindEvents();
    var info = app.nativeCall('deviceInfo');
    try {
      if (info && JSON.parse(info).platform === 'android-tv') document.documentElement.classList.add('tv-device');
    } catch (e) {}
    // 先用上次的主题，避免加载配置前闪一下默认配色
    try { app.applyTheme(window.localStorage.getItem('sotv_mode')); } catch (e) { app.applyTheme('dark'); }
    // 启动时显示已缓存的更新信息（原生层在页面加载前拿到结果时，回调会丢失）
    try { var cached = app.nativeCall('updateInfo'); if (cached) window.SOTV_UPDATE(JSON.parse(cached)); } catch (e) {}
    route();
    window.setInterval(pollTick, 2000);
  }

  app.route = route;
  app.resumeSync = resumeSync;

  window.SOTV_INPUT_CANCEL = function () {
    state.unlockPending = false;
    state.unlockPendingAt = 0;
    state.settingsUnlockPending = false;
    state.settingsUnlockPendingAt = 0;
  };
  window.SOTV_INPUT = function (result, token) {
    // 原生侧靠这个标记确认注入成功，失败会重试（部分电视 WebView 切回来时 JS 尚未就绪）
    if (token) window.__sotvAck = token;
    if (app.handleUnlockInput(result)) return;
    if (!state.round || !state.round.question) { window._pendingInput = result; return; }
    state.draft = { nonce: state.round.nonce, text: result.text || '', drawing: result.drawing || '' };
    window._pendingInput = null;
    app.renderQuestion();
    // 遥控器输入完成后直接把焦点放到“提交答案”
    if (app.isTv() && state.draft.text) {
      var list = app.focusables();
      for (var i = 0; i < list.length; i++) if (list[i].getAttribute('data-action') === 'submit') { app.setFocus(i); break; }
    }
  };
  window.SOTV_UPDATE = function (info) {
    if (!info || !info.latest) return;
    window.currentUpdate = info;
    try { if (!info.force && window.localStorage.setItem && window.localStorage.getItem('sotv_update_dismissed') === info.latest) return; } catch (e) {}
    $('updateText').textContent = info.apkReady ? ('新版本 v' + info.latest + ' 已下载好') : ('发现新版本 v' + info.latest);
    if ($('updateGo')) $('updateGo').textContent = info.apkReady ? '立即安装' : '更新';
    $('updateTip').className = 'update-tip show';
  };
  // 朗读失败（没有系统语音、云端 Key 不对、语言不支持…）直接显示在电视上，不能再「点了没声音」
  window.SOTV_SPEECH_NOTICE = function (info) {
    if (!info || info.ok !== false) return;
    if (rt.lastListenAttempt) {
      var attempt = rt.lastListenAttempt;
      rt.lastListenAttempt = null;
      app.refundListen(attempt.key, attempt.item);
    }
    app.toast(String(info.message || '朗读失败，请重试'));
  };
  window.SOTV_NAVIGATION_BLOCKED = function (message) { app.toast(message || '现在暂不可用'); return true; };
  window.TV = {
    move: app.move,
    select: function () { app.activate(); },
    back: function () {
      if (app.isPinModalOpen()) { app.closePinModal(); return; }
      if (state.screen === 'pair') { app.nativeCall('backToQuiz'); return; }
      if ((state.screen === 'quiz' || (state.screen === 'lock' && state.lockReason !== 'settings')) && app.isHubAvailable()) {
        app.renderHub();
        return;
      }
      if (app.isPureStudyMode() || state.learning.canExit === true) app.nativeCall('exitToLauncher');
      else if (app.nativeCall('navigationBlocked') !== true) window.SOTV_NAVIGATION_BLOCKED();
    },
    state: state
  };
  window.SOTV_PAUSE = function () { rt.pagePaused = true; rt.pollGeneration++; };
  window.SOTV_RESUME = function () {
    rt.pagePaused = false;
    if (document.readyState === 'complete' && !state.busy && state.screen !== 'pair') resumeSync();
  };
  document.addEventListener('visibilitychange', function () { if (document.hidden) rt.pollGeneration++; });
  window.addEventListener('hashchange', route);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
