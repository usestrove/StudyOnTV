(function () {
  'use strict';
  var app = window.SOTVApp || {};
  window.SOTVApp = app;

  var $ = function (id) { return document.getElementById(id); };
  var all = function (selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); };
  var MEDIA_SRC_RE = /^(\/api\/media\/|https?:\/\/|data:image\/)/;
  var QUESTION_INPUT_GUARD_MS = 300;
  var ROUND_KEY = 'sotv_round';

  var state = {
    screen: '', round: null, config: {}, learning: {}, study: {},
    busy: false, selected: [], version: 0, awaiting: false, reviewed: false,
    review: null, draft: null, unlockPending: false, unlockPendingAt: 0,
    cardIndex: 0, cardRevealed: false, lastStartOpts: null, loaded: false
  };
  var rt = {
    pagePaused: false, pollGeneration: 0, stateLoads: 0, pendingPosts: 0,
    toastTimer: null, nextTimer: null, resumeTimer: null,
    taps: 0, lastTap: 0, upTaps: 0, lastUpTap: 0,
    renderedQuestionNonce: null, questionInputAllowedAt: 0,
    listenUsed: {}, lastListenAttempt: null
  };

  function clearResumeTimer() { window.clearTimeout(rt.resumeTimer); rt.resumeTimer = null; }
  function clearNextTimer() { window.clearTimeout(rt.nextTimer); rt.nextTimer = null; }
  function questionInputTime() {
    return window.performance && typeof window.performance.now === 'function' ? window.performance.now() : Date.now();
  }
  function questionInputBlocked() {
    return state.screen === 'quiz' && state.round && state.round.nonce && state.round.nonce === rt.renderedQuestionNonce &&
      questionInputTime() < rt.questionInputAllowedAt;
  }
  function markQuestionRendered(nonce) {
    // 新题刚显示时丢弃残留连点；同一题的输入回填 / 重绘不重新计时。
    if (rt.renderedQuestionNonce !== nonce) {
      rt.renderedQuestionNonce = nonce;
      rt.questionInputAllowedAt = questionInputTime() + QUESTION_INPUT_GUARD_MS;
    }
  }

  // 断电 / 被系统清后台后恢复当前这组题。用 localStorage 而不是 sessionStorage：
  // 进程被杀后 WebView 是新的会话，sessionStorage 活不下来。
  function saveRound(targetRound) {
    var r = targetRound || state.round;
    if (!r || r.studyPhase === 'memorize') return clearRound();
    try {
      window.localStorage.setItem(ROUND_KEY, JSON.stringify({
        round: r, quizGeneration: state.learning.quizGeneration || 0
      }));
    } catch (e) {}
  }
  function clearRound() {
    rt.listenUsed = {};
    rt.lastListenAttempt = null;
    try { window.localStorage.removeItem(ROUND_KEY); } catch (e) {}
  }
  function savedRound() {
    try {
      var saved = JSON.parse(window.localStorage.getItem(ROUND_KEY) || 'null');
      // nonce、roundId 与 quizGeneration 全部和服务端对得上才恢复，防止恢复已答完的旧 nonce 导致死循环
      if (saved && saved.round && saved.round.nonce && saved.round.question &&
          saved.quizGeneration === (state.learning.quizGeneration || 0) &&
          (!state.learning.activeNonce || saved.round.nonce === state.learning.activeNonce) &&
          (!state.learning.activeRoundId || saved.round.id === state.learning.activeRoundId)) return saved.round;
    } catch (e) {}
    return null;
  }

  function supportsGrid() {
    var style = document.documentElement.style;
    return !!(style && (typeof style.grid !== 'undefined' || typeof style.gridTemplateColumns !== 'undefined'));
  }
  function canVoice() { return !!(window.SOTVNative && typeof window.SOTVNative.answerVoice === 'function'); }
  function canRead() { return !!(window.SOTVNative && typeof window.SOTVNative.readInLanguage === 'function'); }
  function canReadRate() { return !!(window.SOTVNative && typeof window.SOTVNative.readWithRate === 'function'); }
  function isPureStudyMode() { return state.config && state.config.supervisionEnabled === false; }
  function isHubAvailable() { return isPureStudyMode() || !!(state.learning && state.learning.free); }
  function overrideKey(learning) {
    return learning && learning.override ? (learning.override.mode + ':' + learning.override.at) : '';
  }
  // 可用时段文案由设备按周计划算好（state.learning.scheduleText），页面不再自己拼旧版的 windowStart/windowEnd
  function scheduleText() { return (state.learning && state.learning.scheduleText) || ''; }
  // 朗读内容由家长在「设置 → 朗读」里定：只读题干、题干加选项、再加答案（答案在作答后才读）
  function readScope() { return (state.speech && state.speech.readScope) || 'question'; }
  // 公式里的 $ 符号和富文本标记 TTS 会念出来，朗读前去掉
  function speakable(text) {
    if (window.SOTV_PLAIN) return window.SOTV_PLAIN(text);
    return String(text == null ? '' : text).replace(/\$\$?/g, '').trim();
  }

  // 听力题：listen 只交给朗读，屏幕上不显示；listenTimes 限制本组最多播几次（默认 5 次）
  // 闪卡（memorize）与测验（quiz）阶段分开计数；朗读失败时自动退回本次计数
  function listenKey(item) {
    var phase = (state.round && state.round.studyPhase === 'memorize') ? 'memorize' : 'quiz';
    return phase + ':' + String((item && (item.id || item.q)) || '');
  }
  function listenCap(item) {
    var cap = Number(item && item.listenTimes);
    return cap >= 1 && cap <= 5 ? Math.floor(cap) : 5;
  }
  function listenLeft(item) { return Math.max(0, listenCap(item) - (rt.listenUsed[listenKey(item)] || 0)); }
  function refundListen(key, item) {
    if (rt.listenUsed[key] > 0) {
      rt.listenUsed[key] = rt.listenUsed[key] - 1;
      refreshListenBtn(item);
    }
  }
  // 没有原生桥（电脑浏览器打开家长面板同款页面）时用 Web Speech API 兜底
  function webSpeak(text, lang, onError) {
    if (!window.speechSynthesis || !window.SpeechSynthesisUtterance) return false;
    var utterance = new window.SpeechSynthesisUtterance(String(text || ''));
    utterance.lang = lang || 'en-US';
    if (typeof onError === 'function') utterance.onerror = onError;
    try { window.speechSynthesis.cancel(); window.speechSynthesis.speak(utterance); return true; } catch (e) { return false; }
  }
  function playListen(item) {
    if (!item || !item.listen || listenLeft(item) <= 0) return false;
    var key = listenKey(item);
    var lang = item.language || 'en-US';
    var rate = Number(item.listenRate);
    var usedNative = false;
    if (canReadRate() && rate >= 0.7 && rate <= 1.5) {
      nativeCall('readWithRate', [item.listen, lang, rate]);
      usedNative = true;
    } else if (canRead()) {
      nativeCall('readInLanguage', [item.listen, lang]);
      usedNative = true;
    } else {
      var refunded = false;
      var ok = webSpeak(item.listen, lang, function () {
        if (refunded) return;
        refunded = true;
        refundListen(key, item);
      });
      if (!ok) {
        toast('当前设备暂不支持朗读听力');
        return false;
      }
    }
    rt.listenUsed[key] = (rt.listenUsed[key] || 0) + 1;
    rt.lastListenAttempt = usedNative ? { key: key, item: item } : null;
    refreshListenBtn(item);
    return true;
  }
  // 只改按钮文案，不重渲染整页：重渲染会再触发一次自动朗读
  function refreshListenBtn(item) {
    var button = $('listenBtn');
    if (!button || !item) return;
    var left = listenLeft(item);
    button.textContent = left > 0 ? '▶ 播放听力（还可播 ' + left + ' 次）' : '听力已播放完';
    button.disabled = left <= 0;
    // 播完置灰后不能让遥控器焦点停在禁用按钮上，否则按 OK 只弹提示、选不了选项
    // 使用 ES5 兼容的 add/remove 代替二参 classList.toggle，兼容旧版电视 WebView
    if (left <= 0) {
      button.classList.add('answered');
      button.classList.remove('focusable');
      if (button.classList.contains('focused')) app.focusDefault();
    } else {
      button.classList.remove('answered');
      button.classList.add('focusable');
    }
  }
  function formatOptionAnswer(item, i) {
    var raw = (item && item.a ? item.a : [])[i];
    if (!raw || !String(raw).trim()) return '选项 ' + String.fromCharCode(65 + i);
    return String.fromCharCode(65 + i) + '. ' + raw;
  }
  function isEnglishQuestion(item) {
    return /^en(?:-|$)/i.test(item && item.language || '');
  }
  function joinSpokenParts(parts, item) {
    var english = isEnglishQuestion(item);
    return parts.filter(Boolean).reduce(function (spoken, part) {
      if (!spoken) return part;
      if (/[.!?。！？]["'”’）)]*$/.test(spoken)) return spoken + ' ' + part;
      return spoken + (english ? '. ' : '。') + part;
    }, '');
  }
  function formatSpokenOption(item, i) {
    var raw = (item && item.a ? item.a : [])[i];
    var text = speakable(raw);
    var letter = String.fromCharCode(65 + i);
    if (isEnglishQuestion(item)) return text ? letter + ', ' + text : 'Option ' + letter;
    return text ? letter + '、' + text : '选项 ' + letter;
  }
  function readText(item) {
    if (!item) return '';
    if (item.listen) return speakable(item.listen);
    var parts = [speakable(item.q)];
    if (readScope() !== 'question' && item.a && item.a.length) {
      (item.a || []).forEach(function (_, index) { parts.push(formatSpokenOption(item, index)); });
    }
    return joinSpokenParts(parts, item);
  }

  // 答题 / 锁定页的家长解锁入口（默认关闭，家长在「时间计划」里打开）
  function canUnlock() { return !!(window.SOTVNative && typeof window.SOTVNative.askParentPassword === 'function' && typeof window.SOTVNative.quizUnlock === 'function'); }
  function canUnlockSettings() { return !!(window.SOTVNative && typeof window.SOTVNative.askSettingsPassword === 'function' && typeof window.SOTVNative.settingsUnlock === 'function'); }
  function isSettingsLockActive() {
    var unlimited = state.learning && state.learning.override && state.learning.override.mode === 'indefinite';
    return !isPureStudyMode() && !unlimited && state.config.blockSettingsDuringControl !== false;
  }
  function nativeCall(name, args) {
    var bridge = window.SOTVNative;
    try { if (bridge && typeof bridge[name] === 'function') return bridge[name].apply(bridge, args || []); } catch (e) {}
    return null;
  }
  function request(path, body, callback) {
    var xhr = new XMLHttpRequest(), finished = false;
    function done(error, value) {
      if (finished) return;
      finished = true;
      if (body) rt.pendingPosts--;
      callback(error, value);
    }
    xhr.open(body ? 'POST' : 'GET', path, true);
    xhr.timeout = 10000;
    if (body) xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('X-Device-Token', nativeCall('apiToken') || '');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      var data;
      try { data = JSON.parse(xhr.responseText); } catch (e) { return done('连接设备失败'); }
      if (xhr.status < 200 || xhr.status >= 300 || data.error) return done(data.message || '请求失败，请重试', data);
      done(null, data);
    };
    xhr.onerror = function () { done('连接设备失败'); };
    xhr.ontimeout = function () { done('连接超时，请重试'); };
    if (body) { rt.pendingPosts++; rt.pollGeneration++; }
    xhr.send(body ? JSON.stringify(body) : null);
  }
  function toast(value) {
    var box = $('toast');
    if (!box) { box = document.createElement('div'); box.id = 'toast'; document.body.appendChild(box); }
    box.textContent = value; box.className = 'toast show';
    window.clearTimeout(rt.toastTimer);
    rt.toastTimer = window.setTimeout(function () { box.className = 'toast'; }, 3000);
  }
  function show(screen) {
    clearResumeTimer();
    if (screen !== 'quiz') nativeCall('stopReading');
    all('.screen').forEach(function (el) { el.className = 'screen'; });
    var target = $('screen-' + screen);
    if (target) target.className = 'screen active';
    state.screen = screen;
    document.body.className = 'on-' + screen;
  }
  function left2(seconds) {
    var m = Math.ceil(seconds / 60);
    return m >= 60 ? '还剩 ' + Math.floor(m / 60) + ' 小时' + (m % 60 ? ' ' + (m % 60) + ' 分' : '') : '还剩 ' + m + ' 分钟';
  }
  function unlockExpiry(override) {
    return override.untilShutdown ? '到本次关机' : left2(override.remainingSeconds);
  }
  function childName(id) {
    var list = state.children || [];
    for (var i = 0; i < list.length; i++) if (list[i].id === (id === undefined ? state.activeChild : id)) return list[i].name;
    return '';
  }
  function findChild(id) {
    var list = state.children || [];
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }
  function pill() {
    var c = state.config || {}, st = state.learning || {}, study = state.study || {};
    var who = (state.children && state.children.length > 1) ? (childName() + ' · ') : '';
    if (c.supervisionEnabled === false) {
      var streak = study.streakDays > 0 ? ' · 🔥 连续 ' + study.streakDays + ' 天' : '';
      $('topPill').textContent = who + '纯粹学习模式' + streak;
      return;
    }
    var left = Math.max(0, (c.roundsPerDay || 3) - (st.roundsDoneToday || 0));
    if (st.override && st.override.mode === 'unlock') { $('topPill').textContent = who + '家长已解锁 · ' + unlockExpiry(st.override); return; }
    if (st.override && st.override.mode === 'indefinite') { $('topPill').textContent = who + '管控已关闭'; return; }
    var point = st.activePoint;
    var pointName = point && point.name ? point.name : '';
    if (st.free) {
      if (!pointName) { $('topPill').textContent = who + '自由时段'; return; }
      $('topPill').textContent = who + (point.mode === 'exempt' ? '大人豁免 · ' : '网课时间 · ') + pointName + (point.until ? ' 至 ' + point.until : '');
      return;
    }
    var remSec = (st.remainingSeconds || 0) + (st.freeQuotaSeconds || 0);
    var remStr = remSec > 0 ? (remSec < 3600 ? '剩 ' + Math.ceil(remSec / 60) + ' 分钟' : '剩 ' + Math.floor(remSec / 3600) + ' 小时 ' + Math.ceil((remSec % 3600) / 60) + ' 分') + ' · ' : '';
    $('topPill').textContent = who + (pointName ? '网课时间 · ' + pointName + ' 至 ' + (point.until || '') + ' · ' : '') + remStr + '还剩 ' + left + ' 组' + (st.extraPool > 0 ? ' · 加练池 ' + st.extraPool + ' 题' : '');
  }
  function pollAllowed() {
    return !rt.pagePaused && !document.hidden && state.screen !== 'pair' && state.loaded &&
      !rt.pendingPosts && !(state.busy && !state.feedback) && !app.isPinModalOpen();
  }
  function loadState(callback, polling) {
    if (!polling) rt.pollGeneration++;
    var generation = rt.pollGeneration;
    var path = polling ? '/api/learning/state' + (state.screen === 'hub' ? '?study=1' : '') : '/api/state';
    rt.stateLoads++;
    request(path, null, function (error, result) {
      rt.stateLoads--;
      // 后台读请求不能覆盖切孩子、答题、页面恢复等显式操作的状态。
      if (polling && (generation !== rt.pollGeneration || !pollAllowed())) return;
      if (!error) {
        state.config = result.config || {};
        state.learning = result.learning || {};
        state.speech = result.speech || {};
        if (result.study || !polling) state.study = result.study || {};
        // 多孩子（#132）：孩子列表和当前是谁由设备端算好，页面只负责显示 / 切换
        state.children = result.children || [];
        state.activeChild = result.activeChild || '';
        state.loaded = true;
        pill(); applyTheme(state.config.theme); applyFont();
      }
      if (callback) callback(error, result);
    });
  }
  function applyFont() {
    var scale = Math.max(75, Math.min(200, +state.config.fontScale || 100));
    if (state.fontScale === scale) return;
    document.documentElement.style.fontSize = '';
    var base = parseFloat(window.getComputedStyle(document.documentElement).fontSize);
    document.documentElement.style.setProperty('--learn-scale', String(scale / 100));
    document.documentElement.style.fontSize = (base * scale / 100) + 'px'; state.fontScale = scale;
  }
  // 深色 / 浅色 / 跟随系统（电视系统的深色模式设置）
  function applyTheme(name) {
    if (name === 'light' || name === 'daylight') name = 'light';
    else if (name === 'system') { var info = {}; try { info = JSON.parse(nativeCall('deviceInfo') || '{}'); } catch (e) {} name = info.night === false ? 'light' : 'dark'; }
    else name = 'dark';
    if (document.documentElement.getAttribute('data-mode') === name) return;
    document.documentElement.setAttribute('data-mode', name);
    try { window.localStorage.setItem('sotv_mode', name); } catch (e) {}
  }
  // 用内联 SVG 代替 ⌛ 等字符：很多电视的字体里没有这些符号，会显示成空白
  var ICONS = {
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    hourglass: '<path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    pencil: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>'
  };
  function icon(el, name) {
    el.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + ICONS[name] + '</svg>';
  }
  function isTv() { return document.documentElement.classList.contains('tv-device'); }
  window.addEventListener('resize', function () {
    state.fontScale = 0; applyFont();
    if (app.fitQuiz) window.setTimeout(app.fitQuiz, 0);
  });

  app.$ = $; app.all = all; app.MEDIA_SRC_RE = MEDIA_SRC_RE; app.state = state; app.rt = rt;
  app.clearResumeTimer = clearResumeTimer; app.clearNextTimer = clearNextTimer;
  app.questionInputTime = questionInputTime; app.questionInputBlocked = questionInputBlocked; app.markQuestionRendered = markQuestionRendered;
  app.saveRound = saveRound; app.clearRound = clearRound; app.savedRound = savedRound;
  app.supportsGrid = supportsGrid; app.canVoice = canVoice; app.canRead = canRead; app.canReadRate = canReadRate;
  app.isPureStudyMode = isPureStudyMode; app.isHubAvailable = isHubAvailable; app.overrideKey = overrideKey;
  app.scheduleText = scheduleText; app.readScope = readScope; app.speakable = speakable;
  app.listenKey = listenKey; app.listenCap = listenCap; app.listenLeft = listenLeft; app.refundListen = refundListen;
  app.webSpeak = webSpeak; app.playListen = playListen; app.refreshListenBtn = refreshListenBtn;
  app.formatOptionAnswer = formatOptionAnswer; app.isEnglishQuestion = isEnglishQuestion;
  app.joinSpokenParts = joinSpokenParts; app.formatSpokenOption = formatSpokenOption; app.readText = readText;
  app.canUnlock = canUnlock; app.canUnlockSettings = canUnlockSettings; app.isSettingsLockActive = isSettingsLockActive;
  app.nativeCall = nativeCall; app.request = request; app.toast = toast; app.show = show;
  app.left2 = left2; app.unlockExpiry = unlockExpiry; app.childName = childName; app.findChild = findChild;
  app.pill = pill; app.pollAllowed = pollAllowed; app.loadState = loadState;
  app.applyFont = applyFont; app.applyTheme = applyTheme; app.icon = icon; app.isTv = isTv;
})();
