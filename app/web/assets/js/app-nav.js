(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var all = app.all;
  var state = app.state;
  var rt = app.rt;

  function focusables() {
    if (app.isPinModalOpen()) {
      return all('#pinModal .focusable').filter(function (el) { return !el.disabled && (el.offsetWidth > 0 || el.offsetHeight > 0); });
    }
    var screen = document.querySelector('.screen.active');
    var list = screen ? Array.prototype.slice.call(screen.querySelectorAll('.focusable')) : [];
    // 答题反馈面板弹出时遮挡了底部页脚，排除 .quiz-foot 里的按钮，防止遥控器焦点落到面板背后消失
    var fb = $('qFeedback');
    if (fb && fb.className.indexOf('show') >= 0 && screen) {
      var foot = screen.querySelector('.quiz-foot');
      if (foot) list = list.filter(function (el) { return !foot.contains(el); });
    }
    // 右上角更新提示也要能用遥控器选中
    if ($('updateTip').className.indexOf('show') >= 0) list = list.concat(all('#updateTip .focusable'));
    // 禁用的按钮不进焦点链：听力播完的「播放听力」不能把遥控器焦点吸住
    return list.filter(function (el) { return !el.disabled && (el.offsetWidth > 0 || el.offsetHeight > 0); });
  }

  function reveal(el) {
    // 旧版电视 WebView 不支持 scrollIntoView({block}) 参数，手动滚动到可见
    var r = el.getBoundingClientRect(), h = window.innerHeight || document.documentElement.clientHeight;
    var stage = $('stage');
    // #141：电视上 body 不滚动，放不下时滚动的是 #stage
    if (stage && stage.scrollHeight > stage.clientHeight + 1) {
      var s = stage.getBoundingClientRect();
      if (r.top < s.top + 12) stage.scrollTop += r.top - s.top - 12;
      else if (r.bottom > s.bottom - 12) stage.scrollTop += r.bottom - s.bottom + 12;
      return;
    }
    if (r.top < 0) window.scrollBy(0, r.top - 24); else if (r.bottom > h) window.scrollBy(0, r.bottom - h + 24);
  }

  function focusKey(el) {
    return el.getAttribute('data-opt') !== null ? 'opt' + el.getAttribute('data-opt') : el.getAttribute('data-action') || el.id || '';
  }

  function setFocus(index) {
    var list = focusables();
    all('.focused').forEach(function (el) { el.classList.remove('focused'); });
    if (list[index]) {
      list[index].classList.add('focused');
      reveal(list[index]);
      state.focusKey = focusKey(list[index]);
      if (list[index].tagName !== 'INPUT' && list[index].tagName !== 'TEXTAREA') {
        try { list[index].focus({ preventScroll: true }); } catch (e) { try { list[index].focus(); } catch (e2) {} }
      }
    }
  }

  function focusDefault() { if (app.isTv()) setFocus(0); }

  // 返回 true 表示焦点移动了；false 表示已到边缘（原生层据此判断“在最上方连按 ↑”打开家长入口）
  function move(dir) {
    var list = focusables(), index = -1, best = -1, score = 1e12, bestAligned = false;
    list.forEach(function (el, i) { if (el.classList.contains('focused')) index = i; });
    if (index < 0) { if (!list.length) return false; setFocus(0); return true; }
    var current = list[index];
    // 多选最后一行按下固定到提交；offsetTop 不受焦点放大和页面滚动影响。
    if (dir === 'down' && state.screen === 'quiz' && state.round && state.round.question && state.round.question.type === 'multiple' && current.hasAttribute('data-opt')) {
      var lastRow = true, submitIndex = -1;
      list.forEach(function (el, i) {
        if (el.hasAttribute('data-opt') && el.offsetTop > current.offsetTop) lastRow = false;
        if (el.getAttribute('data-action') === 'submit') submitIndex = i;
      });
      if (lastRow && submitIndex >= 0) { setFocus(submitIndex); return true; }
    }
    var rect = current.getBoundingClientRect();
    list.forEach(function (el, i) {
      if (i === index) return;
      var r = el.getBoundingClientRect(), dx = r.left + r.width / 2 - rect.left - rect.width / 2, dy = r.top + r.height / 2 - rect.top - rect.height / 2;
      var primary = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      var secondary = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      // 优先同一行 / 列的控件，避免下方横跨两列的提交按钮抢走左右导航。
      var aligned = dir === 'left' || dir === 'right' ? r.top < rect.bottom && r.bottom > rect.top : r.left < rect.right && r.right > rect.left;
      var distance = primary + secondary * 1.8;
      if (primary > 8 && ((aligned && !bestAligned) || (aligned === bestAligned && distance < score))) {
        score = distance; best = i; bestAligned = aligned;
      }
    });
    if (best >= 0) { setFocus(best); return true; }
    return false;
  }

  function activate(el) {
    if (!el) el = (app.isPinModalOpen() ? document.querySelector('#pinModal.show .focused') : null) || document.querySelector('.screen.active .focused') || document.querySelector('.focused');
    if (!el) return;
    if (el.hasAttribute('data-opt')) return app.choose(+el.getAttribute('data-opt'));
    var action = el.getAttribute('data-action');
    if (app.handlePinAction(action, el)) return;
    if (action === 'submit') app.submit();
    if (action === 'next') app.proceed();
    if (action === 'back-hub') { app.renderHub(); return; }
    if (action === 'start-study') {
      app.start({ focus: el.getAttribute('data-focus') || 'quiz', memorizeFirst: el.getAttribute('data-memorize') === '1' });
      return;
    }
    if (action === 'hub-switch-child') { app.handleHubSwitchChild(el); return; }
    if (action === 'reveal-card') { app.revealCard(); return; }
    if (action === 'prev-card') { app.prevCard(); return; }
    if (action === 'next-card') { app.nextCard(); return; }
    if (action === 'rate-card') { app.rateCard(el); return; }
    if (action === 'finish-memorize') { app.finishMemorize(); return; }
    if (action === 'review-continue') app.reviewContinue();
    if (action === 'voice') app.nativeCall('answerVoice', [state.draft ? state.draft.text : '', state.round && state.round.question ? state.round.question.q : '', state.round && state.round.question ? state.round.question.language || '' : '']);
    if (action === 'handwrite') app.nativeCall('answerInput', [false]);
    if (action === 'choose-level') app.start(el.getAttribute('data-level'));
    if (action === 'type-answer' && !state.busy) app.nativeCall('answerText', [state.draft ? state.draft.text : '', state.round && state.round.question ? state.round.question.q : '']);
    if (action === 'update-go') { var u = window.currentUpdate; if (u) app.nativeCall('installUpdate', [u.url, u.latest, u.notes || '']); }
    if (action === 'update-close') { $('updateTip').className = 'update-tip'; try { if (window.currentUpdate) window.localStorage.setItem('sotv_update_dismissed', window.currentUpdate.latest); } catch (e) {} if (el.classList.contains('focused')) focusDefault(); }
    if (action === 'parent-unlock') app.requestParentUnlock();
    if (action === 'settings-unlock') app.requestSettingsUnlock();
    if (action === 'open-local-admin') { app.openLocalAdmin(); return; }
    if (action === 'copy-pair-url') { app.copyPairUrl(); return; }
    if (action === 'permissions' || action === 'password') app.nativeCall(action === 'permissions' ? 'openPermissions' : 'changePassword');
    if (action === 'clear-drawing') { state.draft.drawing = ''; app.renderQuestion(); }
    if (action === 'watch-lesson') app.nativeCall('watchLesson');
    if (action === 'play-audio' && state.round) {
      var audioTarget = state.round.studyPhase === 'memorize' && state.round.studyCards ? state.round.studyCards[state.cardIndex || 0] : state.round.question;
      if (audioTarget) app.nativeCall('playAudio', [audioTarget.audio || '']);
    }
    if (action === 'play-listen' && state.round) {
      var listenTarget = state.round.studyPhase === 'memorize' && state.round.studyCards ? state.round.studyCards[state.cardIndex || 0] : state.round.question;
      if (listenTarget && !app.playListen(listenTarget)) app.toast('这道题的听力已经播放完 ' + app.listenCap(listenTarget) + ' 次');
    }
    if (action === 'read-aloud') {
      if (app.readCardAloud()) return;
      app.readQuizAloud();
    }
    // retry 用于错误 / 空题库的重试，start-quiz 用于「时间到了」页的「开始答题」；两者都重新取题
    if (action === 'retry') app.start(state.lastStartOpts);
    if (action === 'start-quiz') app.start();
    if (action === 'start-as-child') app.handleStartAsChild(el);
    if (action === 'skip-review') app.skipReview();
    if (action === 'return-learning') app.nativeCall('backToQuiz');
    if (action === 'home') app.nativeCall('exitToLauncher');
    if (action === 'resume') { app.clearResumeTimer(); app.nativeCall('resumePrevious'); }
    if (action === 'quiz') { if (state.screen === 'pair') app.nativeCall('backToQuiz'); else if (app.isHubAvailable()) app.renderHub(); else app.start(); }
  }

  function bindEvents() {
    document.querySelector('.brand').addEventListener('click', function () {
      var now = Date.now(); rt.taps = now - rt.lastTap < 900 ? rt.taps + 1 : 1; rt.lastTap = now;
      if (rt.taps >= 5) { rt.taps = 0; app.nativeCall('openParent'); }
    });
    document.addEventListener('click', function (event) {
      var el = event.target;
      while (el && el !== document.body) {
        if (el.classList && el.classList.contains('focusable')) { activate(el); return; }
        el = el.parentNode;
      }
    });
    // 只有家长页 / 实体键盘会走到这里（学习页的遥控器按键由原生层转发给 window.TV）
    document.addEventListener('keydown', function (event) {
      if (app.isPinModalOpen() && app.handlePinKeydown(event)) return;
      if (event.target.tagName === 'INPUT' || (event.target.tagName === 'TEXTAREA' && !event.target.readOnly)) return;
      if (event.keyCode === 27 && state.screen === 'quiz' && app.isHubAvailable()) {
        app.hideFeedback();
        state.busy = false;
        app.renderHub();
        event.preventDefault();
        return;
      }
      var dir = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' }[event.keyCode];
      if (dir) {
        var moved = move(dir);
        if (dir === 'up' && !moved && !event.repeat && state.screen !== 'pair') {
          var now = Date.now(); rt.upTaps = now - rt.lastUpTap < 900 ? rt.upTaps + 1 : 1; rt.lastUpTap = now;
          if (rt.upTaps >= 5) { rt.upTaps = 0; app.nativeCall('openParent'); }
        } else if (!event.repeat) rt.upTaps = 0;
      } else if (event.keyCode === 13 || event.keyCode === 23) { if (!event.repeat) activate(); } else return;
      event.preventDefault();
    });
  }

  app.focusables = focusables;
  app.reveal = reveal;
  app.focusKey = focusKey;
  app.setFocus = setFocus;
  app.focusDefault = focusDefault;
  app.move = move;
  app.activate = activate;
  app.bindEvents = bindEvents;
})();
