(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var state = app.state;

  function buildStartPayload(arg) {
    if (!arg) return {};
    if (typeof arg === 'string') {
      var base = {};
      if (state.lastStartOpts) {
        if (state.lastStartOpts.focus) base.focus = state.lastStartOpts.focus;
        if (state.lastStartOpts.memorizeFirst) base.memorizeFirst = true;
      }
      base.level = arg;
      return base;
    }
    var out = {};
    if (arg.level) out.level = arg.level;
    if (arg.focus) out.focus = arg.focus;
    if (arg.memorizeFirst) out.memorizeFirst = true;
    return out;
  }

  function start(arg, fromGate) {
    if (state.busy) return;
    var payload = buildStartPayload(arg);
    if (payload.focus) state.lastStartOpts = payload;
    else if (!arg) state.lastStartOpts = null;
    if (fromGate === true) payload.fromGate = true;
    state.busy = true;
    app.request('/api/quiz/start', payload, function (error, result) {
      state.busy = false;
      if (error) {
        app.show('quiz'); $('qText').textContent = error;
        $('qOptions').className = 'options solo';
        $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">重试</button>' +
          (app.isHubAvailable() ? '<button class="btn btn-ghost focusable" data-action="back-hub">返回学习中心</button>' : '');
        app.focusDefault();
        return;
      }
      app.clearRound(); // 新的一轮 / 锁屏：上一组题的恢复点作废（下面真的出题时会重新保存）
      state.learning = result.status || state.learning;
      if (result.study) state.study = result.study;
      app.pill();
      if (result.wrongEmpty) {
        app.toast('太棒了！当前错题已全部清零 🎉');
        app.loadState(function () {
          if (app.isHubAvailable()) app.renderHub();
        });
        if (app.isHubAvailable()) app.renderHub();
        return;
      }
      if (result.dueEmpty) {
        app.toast('今日到期复习题已全部完成 🎉');
        app.loadState(function () {
          if (app.isHubAvailable()) app.renderHub();
        });
        if (app.isHubAvailable()) app.renderHub();
        return;
      }
      if (result.videoLesson) {
        state.lockReason = state.learning.reason; state.wasFree = !!state.learning.free;
        app.show('lock'); app.icon($('lockIcon'), 'pencil'); $('lockTitle').textContent = '公益视频学习';
        $('lockDesc').textContent = '完成 ' + Math.ceil(result.videoLesson.remainingSeconds / 60) + ' 分钟观看后领取使用时间';
        $('lockNote').textContent = '播放暂停时不累计，拖动进度不能跳过学习时长';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="watch-lesson">开始观看</button>';
        app.focusDefault(); return;
      }
      if (result.chooseLevel) {
        state.lockReason = state.learning.reason; state.wasFree = !!state.learning.free;
        app.show('lock'); app.icon($('lockIcon'), 'pencil'); $('lockTitle').textContent = '选择你的年级';
        $('lockDesc').textContent = '完成这一轮后可以重新选择'; $('lockNote').textContent = ''; $('lockActions').innerHTML = '';
        result.chooseLevel.forEach(function (value) {
          var button = document.createElement('button');
          button.className = 'btn btn-primary focusable';
          button.textContent = value;
          button.setAttribute('data-action', 'choose-level');
          button.setAttribute('data-level', value);
          $('lockActions').appendChild(button);
        });
        app.focusDefault(); return;
      }
      if (result.heldFeedback) {
        // 兜底：解析内容丢失时不要白屏（主屏会重新取题）
        if (!result.question) { window.setTimeout(function () { start(); }, 800); return; }
        state.round = { question: result.question, mode: 'round', answered: 0, target: 1 };
        app.show('quiz'); renderQuestion(); state.busy = true; state.feedback = result.result; state.feedback.status = result.status;
        app.showFeedback(state.feedback, result.question, result.question.type === 'text' || result.question.type === 'subjective'); return;
      }
      if (result.pending) return app.pending(result.reviewId);
      if (result.reviewed) return app.reviewed(result);
      state.awaiting = false; state.reviewed = false;
      if (result.studyPhase === 'memorize' && Array.isArray(result.studyCards) && result.studyCards.length > 0) {
        state.round = result;
        var nextIdx = -1;
        for (var ci = 0; ci < result.studyCards.length; ci++) {
          if (!result.studyCards[ci].rated) { nextIdx = ci; break; }
        }
        if (nextIdx < 0) {
          if (result.focus === 'memorize') { app.finishFlashcards(result.studyCards.length); return; }
          nextIdx = 0;
        }
        state.cardIndex = nextIdx;
        state.cardRevealed = false;
        app.show('quiz');
        app.renderStudyCard();
        return;
      }
      if (result.blocked) return app.lock(result.blocked);
      if (result.free && !payload.focus) {
        app.renderHub();
        return;
      }
      if (result.watching && !payload.focus) {
        state.lockReason = '';
        app.show('lock'); $('lockTitle').textContent = '已解锁使用时间';
        var watchSeconds = (state.learning.remainingSeconds || 0) + (state.learning.freeQuotaSeconds || 0);
        $('lockDesc').textContent = '还可以使用 ' + Math.ceil(watchSeconds / 60) + ' 分钟';
        app.icon($('lockIcon'), 'check'); $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>' +
          '<button class="btn btn-ghost focusable" data-action="start-study" data-focus="quiz">继续练题</button>';
        app.focusDefault(); return;
      }
      if (!result.question) {
        if (result.quizEmpty) return quizEmpty(result.quizEmptyMessage);
        if (app.isHubAvailable()) { app.renderHub(); return; }
        return quizEmpty('暂无可用题目，请先在家长端添加或启用题目');
      }
      state.round = result;
      app.show('quiz'); renderQuestion();
    });
  }

  // 题库被家长删空 / 答题范围没有题目：给出明确提示并允许重试，绝不能停在旧题上
  function quizEmpty(message) {
    state.round = null; state.busy = false; app.clearRound();
    app.show('quiz');
    $('qText').textContent = message || '题库为空，请由家长添加题目';
    $('qOptions').className = 'options solo';
    $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">重试</button>' +
      (app.isHubAvailable() ? '<button class="btn btn-ghost focusable" data-action="back-hub">返回学习中心</button>' : '');
    app.focusDefault();
  }

  function renderStars(difficulty) {
    var raw = Number(difficulty);
    var count = isFinite(raw) && raw > 0 ? Math.max(1, Math.min(5, Math.round(raw))) : 2;
    return new Array(count + 1).join('★');
  }

  function renderQuestion() {
    var round = state.round;
    if (round && round.studyPhase === 'memorize') { app.renderStudyCard(); return; }
    var item = round.question;
    app.saveRound(); // 记住当前这组题，App 被系统清掉后还能接着答
    if (window._pendingInput) { state.draft = { nonce: round.nonce, text: window._pendingInput.text || '', drawing: window._pendingInput.drawing || '' }; window._pendingInput = null; }
    else if (!state.draft || state.draft.nonce !== round.nonce) state.draft = { nonce: round.nonce, text: '', drawing: '' };
    state.selected = [];
    if (window.SOTV_RICH) window.SOTV_RICH($('qText'), item.q);
    else $('qText').textContent = item.q;
    $('qIndex').textContent = round.mode === 'count' ? round.correct || 0 : round.mode === 'streak' ? round.streak : round.mode === 'practice' ? round.answered + 1 : Math.min(round.answered + 1, round.target);
    $('qTotal').textContent = round.mode === 'practice' ? '题' : '/ ' + round.target;
    $('qMode').textContent = (round.extra ? '加练任务' : round.focus === 'wrong' ? '错题闯关' : round.focus === 'due' ? '记忆复习' : round.mode === 'streak' ? '连续答对' : round.mode === 'count' ? '答对指定题数' : round.mode === 'practice' ? '自由练习' : '本组任务') + ' · ' + renderStars(item.difficulty);
    $('qPool').textContent = round.extraPool > 0 ? '加练池还剩 ' + round.extraPool + ' 题' : '';
    $('qDots').textContent = round.mode === 'streak' ? '连续答对 ' + round.streak + ' 题' : '';
    var backHubBtn = $('quizBackHubBtn');
    if (backHubBtn) backHubBtn.style.display = app.isHubAvailable() ? '' : 'none';
    var qLength = app.speakable(item.q).length;
    $('qText').className = 'question' + (qLength > 120 ? ' longer' : qLength > 56 ? ' long' : '');
    if (item.listen) {
      // 听力题只播听力文本：题干和选项一律不进 TTS，否则选项被念出来等于给答案
      if (state.speech && state.speech.autoRead) app.playListen(item);
      else app.nativeCall('stopReading');
    } else if (state.speech && state.speech.autoRead) app.nativeCall('readInLanguage', [app.readText(item), item.language || '']);
    else app.nativeCall('stopReading');
    renderMedia(item);
    var options = $('qOptions'); options.innerHTML = '';
    var longest = 0, optionCount = (item.a || []).length;
    (item.a || []).forEach(function (value) { longest = Math.max(longest, String(value).length); });
    // 5 个以上选项用紧凑排版；只有 1 个选项时铺满一行；
    // 极老 WebView 不支持 CSS Grid，加 no-grid 退回原来的两列 flex
    options.className = 'options' + (optionCount > 4 ? ' many' : '') + (optionCount === 1 ? ' solo' : '') +
      (longest > 26 ? ' wordy' : '') + (app.supportsGrid() ? '' : ' no-grid');
    if (item.type === 'text' || item.type === 'subjective') {
      var input = document.createElement('textarea'); input.id = 'writtenAnswer'; input.className = 'written-answer';
      input.placeholder = item.type === 'subjective' ? '写下您的思路和答案…' : '请输入答案'; input.maxLength = 10000; input.autocomplete = 'off'; input.rows = 4;
      input.value = state.draft.text;
      input.oninput = function () { state.draft.text = input.value; };
      if (app.isTv()) {
        // 遥控器无法直接在网页输入框里打字：按 OK 打开原生输入框（可调出系统输入法 / 语音）
        input.readOnly = true; input.className += ' focusable'; input.setAttribute('data-action', 'type-answer');
        input.placeholder = '按 OK 键输入答案';
      }
      options.appendChild(input);
      if (app.isTv()) {
        // 已有草稿时 placeholder 不显示，操作方法必须常驻可见
        var hint = document.createElement('div'); hint.className = 'tv-input-hint';
        hint.textContent = '▶  遥控器 OK 键打开键盘输入';
        options.appendChild(hint);
      }
      var tools = document.createElement('div'); tools.className = 'input-tools';
      if (app.isTv()) {
        // 电视端只有原生层真的支持语音才给按钮，文案直接说明按 OK 会做什么
        if (app.canVoice()) {
          var voiceBtn = document.createElement('button'); voiceBtn.className = 'btn btn-ghost focusable';
          voiceBtn.setAttribute('data-action', 'voice'); voiceBtn.textContent = '按 OK 语音作答';
          tools.appendChild(voiceBtn);
        }
      } else {
        tools.innerHTML = '<button class="btn btn-ghost focusable" data-action="voice">语音输入 / 重录</button>' +
          '<button class="btn btn-ghost focusable" data-action="handwrite">手写作答</button>';
      }
      if (tools.childNodes.length) options.appendChild(tools);
      if (state.draft.drawing) {
        var image = document.createElement('img'); image.className = 'drawing-preview'; image.src = state.draft.drawing; options.appendChild(image);
        var clear = document.createElement('button'); clear.className = 'btn btn-ghost focusable'; clear.setAttribute('data-action', 'clear-drawing'); clear.textContent = '清除手写'; options.appendChild(clear);
      }
      addSubmit(options, '提交答案');
    } else {
      item.a.forEach(function (value, index) {
        var button = document.createElement('button'); button.className = 'option focusable';
        button.setAttribute('data-opt', index);
        var letter = document.createElement('span'); letter.className = 'letter'; letter.textContent = String.fromCharCode(65 + index);
        var body = document.createElement('span'); body.className = 'opt-body';
        button.appendChild(letter); button.appendChild(body); options.appendChild(button);
        var optImg = Array.isArray(item.optionImages) ? item.optionImages[index] : '';
        if (optImg && app.MEDIA_SRC_RE.test(optImg)) {
          var img = document.createElement('img'); img.className = 'opt-image'; img.src = optImg; img.alt = '选项 ' + String.fromCharCode(65 + index);
          img.onload = function () { window.setTimeout(fitQuiz, 0); };
          body.appendChild(img);
        }
        if (String(value || '').trim()) {
          var label = document.createElement('span'); label.className = 'opt-text';
          body.appendChild(label);
          if (window.SOTV_RICH) window.SOTV_RICH(label, value); else label.textContent = value;
        }
      });
      if (item.type === 'multiple') addSubmit(options, '确认所选答案');
      if (window.SOTV_MATH) window.SOTV_MATH(options);
    }
    // 「朗读」固定放在页脚，不再混进选项区，避免遥控器在选项之间误选
    var readAloudBtn = $('readAloudBtn');
    if (readAloudBtn) {
      readAloudBtn.className = 'btn btn-ghost focusable read-aloud-btn';
      readAloudBtn.style.display = app.canRead() ? '' : 'none';
    }
    app.focusDefault();
    // #141：排版要等公式 / 图片渲染完再量；这一步不会改变正常字号下的显示
    window.setTimeout(fitQuiz, 0);
    app.markQuestionRendered(round.nonce);
  }

  // #141：电视上系统字体被调大、或家长把「学习页字号」调大后，题干会被顶到页头后面、选项超出屏幕。
  // 先按原样量一次；放不下就换两档更紧凑的排版；还放不下就改成整页可滚动（舞台会跟着滚动）。
  function fitQuiz() {
    var wrap = document.querySelector('#screen-quiz .quiz-wrap');
    if (!wrap) return;
    if (!app.isTv() || !state.round || state.screen !== 'quiz') { wrap.className = 'quiz-wrap'; return; }
    var head = wrap.querySelector('.quiz-head'), foot = wrap.querySelector('.quiz-foot');
    var question = $('qText'), options = $('qOptions');
    // 前两档要求整屏放下；最后一档允许超出，交给 #stage 滚动
    var steps = ['', 'compact', 'compact-2'];
    for (var i = 0; i < steps.length; i++) {
      wrap.className = 'quiz-wrap' + (steps[i] ? ' ' + steps[i] : '');
      var headRect = head.getBoundingClientRect(), footRect = foot.getBoundingClientRect();
      var qRect = question.getBoundingClientRect(), optionsRect = options.getBoundingClientRect();
      if (qRect.top >= headRect.bottom + 6 && optionsRect.bottom <= footRect.top - 6) return;
    }
    wrap.className = 'quiz-wrap compact-2 scroll';
  }

  function renderMedia(item) {
    var box = $('qMedia'); box.innerHTML = '';
    var list = [], seen = {};
    if (Array.isArray(item.images)) item.images.forEach(function (u) { if (u && !seen[u]) { seen[u] = true; list.push(u); } });
    if (item.image && !seen[item.image]) list.unshift(item.image);
    list.forEach(function (src) {
      if (app.MEDIA_SRC_RE.test(src)) {
        var image = document.createElement('img');
        image.src = src; image.alt = '题目配图';
        image.className = 'question-media-image' + (list.length > 1 ? ' is-multi' : '');
        image.onload = function () { window.setTimeout(fitQuiz, 0); };
        box.appendChild(image);
      }
    });
    if (item.audio && /^(\/api\/media\/|https?:\/\/)/.test(item.audio)) {
      if (window.SOTVNative) { var play = document.createElement('button'); play.className = 'btn btn-ghost focusable'; play.setAttribute('data-action', 'play-audio'); play.textContent = '播放题目音频'; box.appendChild(play); }
      else { var audio = document.createElement('audio'); audio.src = item.audio; audio.controls = true; audio.className = 'focusable'; audio.preload = 'none'; box.appendChild(audio); }
    }
    // 听力题：不摆音频文件，点按钮让设备朗读 listen（listenTimes 次为上限）
    if (item.listen && !item.audio) {
      var left = app.listenLeft(item);
      var listenBtn = document.createElement('button');
      listenBtn.id = 'listenBtn';
      listenBtn.className = 'btn btn-ghost' + (left > 0 ? ' focusable' : ' answered');
      listenBtn.textContent = left > 0 ? '▶ 播放听力（还可播 ' + left + ' 次）' : '听力已播放完';
      listenBtn.disabled = left <= 0;
      if (window.SOTVNative) listenBtn.setAttribute('data-action', 'play-listen');
      else listenBtn.onclick = function () { app.playListen(item); };
      box.appendChild(listenBtn);
    }
  }

  function addSubmit(parent, value) {
    var button = document.createElement('button'); button.className = 'btn btn-primary focusable answer-submit';
    button.setAttribute('data-action', 'submit'); button.textContent = value; parent.appendChild(button);
  }

  app.buildStartPayload = buildStartPayload;
  app.start = start;
  app.quizEmpty = quizEmpty;
  app.renderStars = renderStars;
  app.renderQuestion = renderQuestion;
  app.fitQuiz = fitQuiz;
  app.renderMedia = renderMedia;
  app.addSubmit = addSubmit;
})();
