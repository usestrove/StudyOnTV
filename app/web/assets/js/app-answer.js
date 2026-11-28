(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var all = app.all;
  var state = app.state;
  var rt = app.rt;

  var PRAISE = ['太棒了！', '答对啦！', '完全正确！', '真厉害！', '好样的，继续保持！', '漂亮！你越来越熟练了'];
  var ENCOURAGE = ['没关系，记住正确答案就好', '差一点点，下一题加油！', '错误也是学习的一部分', '别灰心，再接再厉！'];

  function phrase(list, fallback) {
    var pool = (list && list.length ? list : fallback).filter(function (v) { return v && String(v).trim(); });
    if (!pool.length) pool = fallback;
    return String(pool[Math.floor(Math.random() * pool.length)]);
  }

  function choose(index) {
    // 保护期只丢弃选项选择和提交，朗读、输入等其他操作仍可使用。
    if (state.busy || !state.round || app.questionInputBlocked()) return;
    if (state.round.question.type === 'multiple') {
      var pos = state.selected.indexOf(index);
      if (pos < 0) state.selected.push(index); else state.selected.splice(pos, 1);
      all('#qOptions [data-opt]').forEach(function (el) {
        if (state.selected.indexOf(+el.getAttribute('data-opt')) >= 0) el.classList.add('selected');
        else el.classList.remove('selected');
      });
      // 多选只切换选中状态，保留当前焦点，选完后由用户移到提交按钮。
    } else { state.selected = [index]; submit(); }
  }

  function submit() {
    if (state.busy || !state.round || app.questionInputBlocked()) return;
    var round = state.round, item = round.question, written = $('writtenAnswer');
    // 必听题：一次都没播过不让提交，避免不看听力直接猜
    if (item.listenRequired && item.listen && app.listenLeft(item) === app.listenCap(item)) { app.toast('请先播放听力再作答'); return; }
    if (written ? (!written.value.trim() && !state.draft.drawing) : !state.selected.length) { app.toast('请先填写或选择答案'); return; }
    state.busy = true;
    app.request('/api/quiz/answer', { roundId: round.id, nonce: round.nonce, selections: state.selected, text: written ? written.value : '', drawing: state.draft ? state.draft.drawing : '' }, function (error, result) {
      if (error) {
        state.busy = false;
        if (String(error).indexOf('题目已更新') >= 0 || String(error).indexOf('round_missing') >= 0) {
          app.clearRound();
          state.round = null;
          hideFeedback();
          app.toast('题目已更新，正在加载最新题目');
          app.start(state.lastStartOpts);
          return;
        }
        app.toast(error);
        return;
      }
      if (result.pending) { state.busy = false; pending(result.reviewId); return; }
      if (result.blocked) { state.busy = false; app.lock(result.blocked); return; }
      // 家长刚删掉这道题时后端返回可恢复结果：重新取题，不要停在旧题上
      if (result.recovered) {
        hideFeedback(); state.draft = null;
        if (result.quizEmpty) return app.quizEmpty(result.quizEmptyMessage);
        state.busy = false; app.start(state.lastStartOpts); return;
      }
      if (!result.correctIndices) { state.busy = false; hideFeedback(); app.start(state.lastStartOpts); return; }
      state.learning = result.status || state.learning;
      if (result.study) state.study = result.study;
      // 服务端已切到下一题或结清本轮：立即同步本地缓存，避免反馈页期间页面重建恢复出旧 nonce
      if (result.finished) app.clearRound();
      else if (result.next && result.next.nonce) app.saveRound(result.next);
      app.pill();
      all('#qOptions [data-opt]').forEach(function (el) {
        var i = +el.getAttribute('data-opt');
        if (result.correctIndices.indexOf(i) >= 0) el.className += ' right';
        else if (state.selected.indexOf(i) >= 0) el.className += ' wrong';
      });
      state.feedback = result;
      showFeedback(result, item, written);
      var auto = +state.config.autoNextSeconds || 0;
      if (auto > 0) rt.nextTimer = window.setTimeout(proceed, Math.max(auto, (result.status && result.status.feedbackSeconds) || 0) * 1000 + 200);
    });
  }

  // 答题结果显示在题目下方（参考多邻国），孩子看完后自己按“继续”进入下一题（Issue #3）
  function showFeedback(result, item, written) {
    var box = $('qFeedback'), c = state.config, right = !!result.correct;
    var title = right ? phrase(c.praisePhrases, PRAISE) : phrase(c.encouragePhrases, ENCOURAGE);
    var streakCount = (result.next && result.next.streak) || result.streak || 0;
    if (right && streakCount >= 3) title = '🔥 连续答对 ' + streakCount + ' 题！' + title;
    var answer = '';
    if (!right) {
      if (written) answer = result.answerText;
      else answer = (result.correctIndices || []).map(function (i) { return app.formatOptionAnswer(item, i); }).join('　');
    }
    box.className = 'feedback show ' + (right ? 'is-right' : 'is-wrong');
    $('fbIcon').textContent = right ? '✓' : '!';
    $('fbTitle').textContent = title;
    if (answer) {
      if (window.SOTV_RICH) window.SOTV_RICH($('fbAnswer'), '正确答案：' + answer);
      else $('fbAnswer').textContent = '正确答案：' + answer;
    } else {
      $('fbAnswer').innerHTML = '';
    }
    if (window.SOTV_RICH && result.tip) window.SOTV_RICH($('fbTip'), '解析：' + result.tip);
    else $('fbTip').textContent = result.tip ? '解析：' + result.tip : '';
    // 作答后自动朗读：按朗读范围读正确答案，并且只要有解析就统一朗读解析
    if (state.speech && state.speech.autoRead) {
      var fbParts = [];
      if (app.readScope() === 'questionOptionsAnswer') {
        var spokenAnswer = written ? app.speakable(result.answerText || '')
          : (result.correctIndices || []).map(function (i) { return app.formatSpokenOption(item, i); }).join('，');
        if (spokenAnswer) fbParts.push('正确答案：' + spokenAnswer);
      }
      var spokenTip = result.tip ? app.speakable(result.tip) : '';
      if (spokenTip) fbParts.push('解析：' + spokenTip);
      if (fbParts.length) app.nativeCall('readInLanguage', [fbParts.join('。'), item.language || '']);
    }
    var fbRead = $('fbReadAloud');
    if (fbRead) fbRead.style.display = app.canRead() ? '' : 'none';
    var go = $('fbNext');
    state.feedbackUntil = Date.now() + ((result.status && result.status.feedbackSeconds) || 0) * 1000;
    go.textContent = result.finished ? '查看结果' : '继续';
    updateFeedbackClock();
    go.className = 'btn ' + (right ? 'btn-primary' : 'btn-danger') + ' focusable';
    // 选项已经作答，焦点留在“继续”上（反馈层展开时跳过被遮挡的页脚按钮）
    all('#qOptions .focusable').forEach(function (el) { el.classList.remove('focusable'); el.classList.add('answered'); });
    var list = app.focusables(); for (var i = 0; i < list.length; i++) if (list[i] === go) { app.setFocus(i); break; }
    if (!app.isTv()) go.focus();
  }

  function hideFeedback() { $('qFeedback').className = 'feedback'; state.feedback = null; }

  function updateFeedbackClock() {
    if (!state.feedback) return;
    var seconds = Math.max(0, Math.ceil(((state.feedbackUntil || 0) - Date.now()) / 1000)), go = $('fbNext');
    go.disabled = seconds > 0;
    go.textContent = seconds > 0 ? '查看解析 · ' + seconds + ' 秒' : state.feedback.finished ? '查看结果' : '继续';
  }
  window.setInterval(updateFeedbackClock, 500);

  function proceed() {
    var result = state.feedback;
    if (!result || state.continuing || (state.feedbackUntil || 0) > Date.now()) return;
    state.continuing = true;
    app.request('/api/quiz/continue', {}, function (error, response) {
      state.continuing = false;
      if (error) { app.toast(error); return; }
      result.status = response.status; state.learning = response.status || state.learning;
      if (response.study) state.study = response.study;
      proceedReady(result);
    });
  }

  function proceedReady(result) {
    if (!result) return;
    app.clearNextTimer();
    hideFeedback();
    state.busy = false;
    if (state.screen !== 'quiz') return;
    if (result.finished) {
      var prevRound = state.round;
      state.round = null; app.clearRound();
      if (app.isPureStudyMode() || (state.learning && state.learning.free && !(result.rewardMinutes > 0))) {
        app.loadState();
        app.show('lock');
        app.icon($('lockIcon'), 'check');
        $('lockTitle').textContent = result.passed === false ? '本组练习结束' : '本组闯关完成 🎉';
        $('lockDesc').textContent = result.passed === false
          ? '错题已自动加入复习本，先背一遍解析再挑战更容易拿满分！'
          : (prevRound && prevRound.focus === 'wrong' ? '错题巩固完成！坚持复习就不容易再错啦' : '干得漂亮！你的记忆掌握度又提升了一截');
        $('lockNote').textContent = '';
        var againFocus = (prevRound && prevRound.focus) || 'quiz';
        var againMem = prevRound && prevRound.focus === 'wrong' ? ' data-memorize="1"' : '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="back-hub">返回学习中心</button>' +
          '<button class="btn btn-ghost focusable" data-action="start-study" data-focus="' + againFocus + '"' + againMem + '>再来一组</button>' +
          '<button class="btn btn-ghost focusable" data-action="home">返回桌面</button>';
        app.focusDefault();
        return;
      }
      if (result.passed) {
        if (result.status && result.status.reason) { app.lock(result.status.reason); app.toast('奖励已保存，达到可用时段后使用'); return; }
        var reward = Math.round((result.rewardMinutes || 0) * 10) / 10;
        state.lockReason = '';
        app.show('lock'); $('lockTitle').textContent = '任务完成'; app.icon($('lockIcon'), 'check');
        $('lockDesc').textContent = reward > 0 ? '已解锁 ' + reward + ' 分钟' : '今天的时间额度已经领满，先使用已有的时间吧'; $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="resume">开始使用</button>';
        app.focusDefault();
        // 解锁后回到解题前正在看的应用，不要跳回桌面（#68 / #74）
        rt.resumeTimer = window.setTimeout(function () {
          rt.resumeTimer = null;
          if (state.screen === 'lock' && !state.lockReason) app.nativeCall('resumePrevious');
        }, 2500);
      } else { app.toast('本组未通过，再试一组'); app.start(state.lastStartOpts); }
    } else if (result.next) { state.round = result.next; app.renderQuestion(); }
    else app.quizEmpty(result.quizEmptyMessage);
  }

  function reviewed(result) {
    state.awaiting = false; state.reviewed = true; state.review = result;
    app.show('lock'); app.icon($('lockIcon'), result.result.correct ? 'check' : 'pencil');
    $('lockTitle').textContent = '批改完成 · ' + result.score + ' 分';
    $('lockDesc').textContent = result.feedback || '已保存本次批改结果';
    $('lockNote').textContent = result.result.correct ? '本题通过' : '查看反馈后继续练习';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="review-continue">' + (result.result.finished && result.result.passed && !app.isPureStudyMode() ? '继续使用' : '继续答题') + '</button>';
    if (window.SOTV_MATH) window.SOTV_MATH($('lockDesc'));
    app.focusDefault();
  }

  function pending(id) {
    state.awaiting = true; state.reviewed = false;
    app.show('lock'); app.icon($('lockIcon'), 'check'); $('lockTitle').textContent = '答案已保存';
    $('lockDesc').textContent = '等待家长或 AI 批改，通过后继续任务';
    $('lockNote').textContent = '';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">查看批改结果</button><button class="btn btn-ghost focusable" data-action="skip-review">先跳过这题</button>' +
      (app.isHubAvailable() ? '<button class="btn btn-ghost focusable" data-action="back-hub">返回学习中心</button>' : '');
    app.focusDefault();
  }

  function reviewContinue() {
    if (state.busy) return;
    state.busy = true;
    app.request('/api/quiz/review', { reviewId: state.review.reviewId }, function (error, result) {
      state.busy = false;
      if (error) { app.toast(error); return; }
      var finished = state.review.result.finished && state.review.result.passed;
      state.reviewed = false;
      if (finished && !app.isPureStudyMode() && !result.status.reason && result.status.remainingSeconds > 0) app.nativeCall('resumePrevious');
      else if (app.isHubAvailable()) app.renderHub();
      else app.start();
    });
  }

  function skipReview() {
    if (state.busy) return;
    state.busy = true;
    app.request('/api/quiz/skip', {}, function (error) {
      state.busy = false;
      if (error) { app.toast(error); return; }
      state.awaiting = false; state.reviewed = false;
      if (app.isHubAvailable()) app.renderHub(); else app.start();
    });
  }

  function readQuizAloud() {
    // 听力题点击「朗读」时只走听力播放（受 listenTimes / listenRate 约束，不剧透题干与选项）
    var readItem = state.round ? state.round.question : null;
    var feedback = state.feedback;
    if (readItem && readItem.listen) {
      if (!feedback) {
        if (!app.playListen(readItem)) app.toast('这道题的听力已经播放完 ' + app.listenCap(readItem) + ' 次');
        return;
      }
      var listenParts = [];
      if (app.readScope() === 'questionOptionsAnswer') {
        var listenCorrect = (feedback.correctIndices || []).map(function (i) {
          return app.formatSpokenOption(readItem, i);
        }).join('，') || app.speakable(feedback.answerText || '');
        if (listenCorrect) listenParts.push('正确答案：' + listenCorrect);
      }
      if (feedback.tip) {
        var listenTip = app.speakable(feedback.tip);
        if (listenTip) listenParts.push('解析：' + listenTip);
      }
      if (listenParts.length) {
        rt.lastListenAttempt = null;
        app.nativeCall('readInLanguage', [listenParts.join('。'), readItem.language || '']);
        return;
      }
      if (!app.playListen(readItem)) app.toast('这道题的听力已经播放完 ' + app.listenCap(readItem) + ' 次');
      return;
    }
    // 作答后这一下就是「再读一遍」：题干 + 选项（按家长设置的朗读范围）+ 正确答案 + 解析
    var spoken = readItem ? app.readText(readItem) : '';
    if (feedback && readItem) {
      if (app.readScope() === 'questionOptionsAnswer') {
        var correct = (feedback.correctIndices || []).map(function (i) {
          return app.formatSpokenOption(readItem, i);
        }).join('，') || app.speakable(feedback.answerText || '');
        if (correct) spoken = app.joinSpokenParts([spoken, '正确答案：' + correct], readItem);
      }
      if (feedback.tip) {
        var fbManualTip = app.speakable(feedback.tip);
        if (fbManualTip) spoken = app.joinSpokenParts([spoken, '解析：' + fbManualTip], readItem);
      }
    }
    rt.lastListenAttempt = null;
    app.nativeCall('readInLanguage', [spoken, readItem ? readItem.language || '' : '']);
  }

  app.choose = choose; app.submit = submit; app.showFeedback = showFeedback;
  app.hideFeedback = hideFeedback; app.updateFeedbackClock = updateFeedbackClock;
  app.proceed = proceed; app.proceedReady = proceedReady;
  app.reviewed = reviewed; app.pending = pending;
  app.reviewContinue = reviewContinue; app.skipReview = skipReview; app.readQuizAloud = readQuizAloud;
})();
