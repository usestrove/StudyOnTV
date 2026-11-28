(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var state = app.state;
  var rt = app.rt;

  function formatCardAnswerFallback(card) {
    if (!card) return '';
    if (card.answerDisplay) return card.answerDisplay;
    if (card.type === 'subjective') return card.modelAnswer || '';
    if (card.type === 'text' && Array.isArray(card.answers)) return card.answers.join(' / ');
    if (Array.isArray(card.a)) {
      if (card.type === 'multiple' && Array.isArray(card.correctIndices)) {
        return card.correctIndices.map(function (i) { return app.formatOptionAnswer(card, i); }).join('　');
      }
      if (typeof card.k === 'number') return app.formatOptionAnswer(card, card.k);
    }
    return '';
  }

  function renderStudyCard() {
    var round = state.round;
    if (!round || !Array.isArray(round.studyCards) || !round.studyCards.length) {
      if (app.isHubAvailable()) app.renderHub(); else app.quizEmpty();
      return;
    }
    app.hideFeedback();
    var cards = round.studyCards;
    var idx = Math.max(0, Math.min(state.cardIndex || 0, cards.length - 1));
    state.cardIndex = idx;
    var card = cards[idx];
    if (window.SOTV_RICH) window.SOTV_RICH($('qText'), card.q);
    else $('qText').textContent = card.q;
    $('qIndex').textContent = idx + 1;
    $('qTotal').textContent = '/ ' + cards.length;
    $('qMode').textContent = (round.focus === 'memorize' ? '闪卡背诵' : round.focus === 'wrong' ? '错题先背再测' : '先背再测') + ' · 背诵 ' + (idx + 1) + '/' + cards.length;
    $('qPool').textContent = '';
    $('qDots').textContent = round.focus === 'memorize' ? '先回想答案，翻面后选择记忆程度' : '先记住答案与解析，背完马上小测';
    var qLength = app.speakable(card.q).length;
    $('qText').className = 'question' + (qLength > 120 ? ' longer' : qLength > 56 ? ' long' : '');
    app.renderMedia(card);
    if (!state.cardRevealed) {
      if (card.listen && state.speech && state.speech.autoRead) app.playListen(card);
      else if (state.speech && state.speech.autoRead) app.nativeCall('readInLanguage', [app.speakable(card.q), card.language || '']);
      else app.nativeCall('stopReading');
    }

    var options = $('qOptions');
    options.innerHTML = '';
    options.className = 'options solo study-card-area' + (app.supportsGrid() ? '' : ' no-grid');

    if (Array.isArray(card.a) && card.a.length > 0 && card.type !== 'boolean') {
      var preview = document.createElement('div');
      preview.className = 'study-choices-preview';
      options.appendChild(preview);
      card.a.forEach(function (val, i) {
        var row = document.createElement('div');
        row.className = 'study-choice-item';
        var ltr = document.createElement('span');
        ltr.className = 'study-choice-letter';
        ltr.textContent = String.fromCharCode(65 + i) + '.';
        var body = document.createElement('div');
        body.className = 'opt-body';
        row.appendChild(ltr);
        row.appendChild(body);
        preview.appendChild(row);
        var optImg = Array.isArray(card.optionImages) ? card.optionImages[i] : '';
        if (optImg && app.MEDIA_SRC_RE.test(optImg)) {
          var img = document.createElement('img');
          img.className = 'opt-image';
          img.src = optImg;
          img.alt = '选项 ' + String.fromCharCode(65 + i);
          img.onload = function () { window.setTimeout(app.fitQuiz, 0); };
          body.appendChild(img);
        }
        if (String(val || '').trim()) {
          var txt = document.createElement('span');
          txt.className = 'opt-text';
          body.appendChild(txt);
          if (window.SOTV_RICH) window.SOTV_RICH(txt, val); else txt.textContent = val;
        }
      });
    }

    if (!state.cardRevealed) {
      var frontRow = document.createElement('div');
      frontRow.className = 'study-reveal-row';
      var revealBtn = document.createElement('button');
      revealBtn.className = 'btn btn-primary focusable';
      revealBtn.setAttribute('data-action', 'reveal-card');
      revealBtn.textContent = '看答案与解析 (OK)';
      frontRow.appendChild(revealBtn);
      if (idx > 0 && round.focus !== 'memorize') {
        var prevFront = document.createElement('button');
        prevFront.className = 'btn btn-ghost focusable';
        prevFront.setAttribute('data-action', 'prev-card');
        prevFront.textContent = '上一张';
        frontRow.appendChild(prevFront);
      }
      if (app.isHubAvailable()) {
        var exitHubBtn = document.createElement('button');
        exitHubBtn.className = 'btn btn-ghost focusable';
        exitHubBtn.setAttribute('data-action', 'back-hub');
        exitHubBtn.textContent = '返回学习中心';
        frontRow.appendChild(exitHubBtn);
      }
      options.appendChild(frontRow);
    } else {
      var backBox = document.createElement('div');
      backBox.className = 'study-card-back';
      options.appendChild(backBox);
      var ansLabel = document.createElement('div');
      ansLabel.className = 'study-back-label';
      ansLabel.textContent = '正确答案';
      var ansBody = document.createElement('div');
      ansBody.className = 'study-back-answer';
      backBox.appendChild(ansLabel);
      backBox.appendChild(ansBody);
      var ansStr = formatCardAnswerFallback(card);
      if (window.SOTV_RICH) window.SOTV_RICH(ansBody, ansStr || '请参考下方解析');
      else ansBody.textContent = ansStr || '请参考下方解析';

      if (card.tip && String(card.tip).trim()) {
        var tipBody = document.createElement('div');
        tipBody.className = 'study-back-tip';
        backBox.appendChild(tipBody);
        if (window.SOTV_RICH) window.SOTV_RICH(tipBody, '重点解析：' + card.tip);
        else tipBody.textContent = '重点解析：' + card.tip;
      }

      if (round.focus === 'memorize') {
        var iv = card.intervals || {};
        var rates = [
          { q: 1, cls: 'rate-again', title: '重来', sub: iv.again || '10分钟' },
          { q: 3, cls: 'rate-hard', title: '困难', sub: iv.hard || '1天' },
          { q: 4, cls: 'rate-good', title: '良好', sub: iv.good || '3天' },
          { q: 5, cls: 'rate-easy', title: '简单', sub: iv.easy || '7天' }
        ];
        var rateGrid = document.createElement('div');
        rateGrid.className = 'anki-rate-grid';
        rates.forEach(function (r) {
          var b = document.createElement('button');
          b.className = 'btn btn-ghost focusable anki-rate-btn ' + r.cls;
          b.setAttribute('data-action', 'rate-card');
          b.setAttribute('data-quality', String(r.q));
          var t1 = document.createElement('span');
          t1.className = 'rate-title';
          t1.textContent = r.title;
          var t2 = document.createElement('span');
          t2.className = 'rate-sub';
          t2.textContent = r.sub;
          b.appendChild(t1);
          b.appendChild(t2);
          rateGrid.appendChild(b);
        });
        options.appendChild(rateGrid);
      } else {
        var navRow = document.createElement('div');
        navRow.className = 'memorize-nav-row';
        var isLast = idx + 1 >= cards.length;
        var nextBtn = document.createElement('button');
        nextBtn.className = 'btn btn-primary focusable';
        nextBtn.setAttribute('data-action', isLast ? 'finish-memorize' : 'next-card');
        nextBtn.textContent = isLast ? '背完了，开始小测！' : '记住了，下一张';
        navRow.appendChild(nextBtn);
        if (idx > 0) {
          var prevBtn = document.createElement('button');
          prevBtn.className = 'btn btn-ghost focusable';
          prevBtn.setAttribute('data-action', 'prev-card');
          prevBtn.textContent = '上一张';
          navRow.appendChild(prevBtn);
        }
        if (app.isHubAvailable()) {
          var hubBtn = document.createElement('button');
          hubBtn.className = 'btn btn-ghost focusable';
          hubBtn.setAttribute('data-action', 'back-hub');
          hubBtn.textContent = '返回学习中心';
          navRow.appendChild(hubBtn);
        }
        options.appendChild(navRow);
      }
    }

    if (window.SOTV_MATH) window.SOTV_MATH(options);
    var readAloudBtn = $('readAloudBtn');
    if (readAloudBtn) {
      readAloudBtn.className = 'btn btn-ghost focusable read-aloud-btn';
      readAloudBtn.style.display = app.canRead() ? '' : 'none';
    }
    if (state.cardRevealed && round.focus === 'memorize' && app.isTv()) {
      // 默认把焦点落在「良好」上，按 OK 即可顺畅翻下一张，左右可切换重来 / 困难 / 简单
      var fList = app.focusables();
      var goodIdx = 0;
      for (var fi = 0; fi < fList.length; fi++) {
        if (fList[fi].getAttribute('data-quality') === '4') { goodIdx = fi; break; }
      }
      app.setFocus(goodIdx);
    } else {
      app.focusDefault();
    }
    window.setTimeout(app.fitQuiz, 0);
  }

  function finishFlashcards(count) {
    app.nativeCall('stopReading');
    state.round = null;
    app.clearRound();
    app.loadState();
    app.show('lock');
    app.icon($('lockIcon'), 'check');
    $('lockTitle').textContent = '闪卡背诵完成 🎉';
    $('lockDesc').textContent = '本组共巩固 ' + count + ' 张卡片，已按记忆曲线安排下次复习时间';
    $('lockNote').textContent = '';
    $('lockActions').innerHTML =
      (app.isHubAvailable() ? '<button class="btn btn-primary focusable" data-action="back-hub">返回学习中心</button>' : '') +
      '<button class="btn ' + (app.isHubAvailable() ? 'btn-ghost' : 'btn-primary') + ' focusable" data-action="start-study" data-focus="memorize">再来一组</button>' +
      (app.isPureStudyMode() || (state.learning && state.learning.canExit) ? '<button class="btn btn-ghost focusable" data-action="home">返回桌面</button>' : '');
    app.focusDefault();
  }

  function revealCard() {
    state.cardRevealed = true;
    renderStudyCard();
    if (state.speech && state.speech.autoRead && state.round && state.round.studyCards) {
      var curCard = state.round.studyCards[state.cardIndex || 0];
      if (curCard) {
        var cardParts = [];
        if (app.readScope() === 'questionOptionsAnswer') {
          var spAns = app.speakable(formatCardAnswerFallback(curCard));
          if (spAns) cardParts.push('正确答案：' + spAns);
        }
        var spTip = curCard.tip ? app.speakable(curCard.tip) : '';
        if (spTip) cardParts.push('解析：' + spTip);
        if (cardParts.length) app.nativeCall('readInLanguage', [cardParts.join('。'), curCard.language || '']);
      }
    }
  }

  function prevCard() {
    if (!state.round || !state.round.studyCards || state.cardIndex <= 0) return;
    state.cardIndex -= 1;
    state.cardRevealed = false;
    renderStudyCard();
  }

  function nextCard() {
    if (!state.round || !state.round.studyCards || state.cardIndex + 1 >= state.round.studyCards.length) return;
    state.cardIndex += 1;
    state.cardRevealed = false;
    renderStudyCard();
  }

  function rateCard(el) {
    if (state.busy || !state.round || !state.round.studyCards) return;
    var reqRoundId = state.round.id;
    var rCard = state.round.studyCards[state.cardIndex || 0];
    var quality = +el.getAttribute('data-quality') || 4;
    var totalCards = state.round.studyCards.length;
    state.busy = true;
    app.request('/api/quiz/rate', { roundId: reqRoundId, qid: rCard ? rCard.id : '', quality: quality }, function (error, result) {
      state.busy = false;
      if (!state.round || state.round.id !== reqRoundId || state.screen !== 'quiz') return;
      if (error) { app.toast(error); return; }
      if (result && result.study) state.study = result.study;
      if (result && Array.isArray(result.studyCards)) state.round.studyCards = result.studyCards;
      if ((result && result.finished) || state.cardIndex + 1 >= state.round.studyCards.length) {
        finishFlashcards(totalCards);
      } else {
        state.cardIndex += 1;
        state.cardRevealed = false;
        renderStudyCard();
      }
    });
  }

  function finishMemorize() {
    if (state.busy || !state.round) return;
    var memRoundId = state.round.id;
    state.busy = true;
    app.request('/api/quiz/memorized', { roundId: memRoundId }, function (error, response) {
      state.busy = false;
      if (!state.round || state.round.id !== memRoundId || state.screen !== 'quiz') return;
      if (error) { app.toast(error); return; }
      var nextRound = (response && response.round) ? response.round : response;
      if (nextRound && nextRound.status) state.learning = nextRound.status;
      if (nextRound && nextRound.question) {
        state.round = nextRound;
        state.cardIndex = 0;
        state.cardRevealed = false;
        app.show('quiz');
        app.renderQuestion();
        app.toast('开始小测，检验刚才背的内容！');
      } else if (app.isHubAvailable()) {
        app.renderHub();
      } else {
        app.start();
      }
    });
  }

  function readCardAloud() {
    if (!state.round || state.round.studyPhase !== 'memorize' || !state.round.studyCards) return false;
    var mCard = state.round.studyCards[state.cardIndex || 0];
    if (mCard) {
      if (mCard.listen && !state.cardRevealed) {
        if (!app.playListen(mCard)) app.toast('这道题的听力已经播放完 ' + app.listenCap(mCard) + ' 次');
        return true;
      }
      var cardSpoken = mCard.listen ? '' : app.speakable(mCard.q);
      if (state.cardRevealed) {
        var cardAns = app.speakable(formatCardAnswerFallback(mCard));
        if (cardAns) cardSpoken += (cardSpoken ? '。' : '') + '正确答案：' + cardAns;
        if (mCard.tip) cardSpoken += (cardSpoken ? '。' : '') + '解析：' + app.speakable(mCard.tip);
      }
      if (cardSpoken) {
        rt.lastListenAttempt = null;
        app.nativeCall('readInLanguage', [cardSpoken, mCard.language || '']);
      }
    }
    return true;
  }

  app.formatCardAnswerFallback = formatCardAnswerFallback;
  app.renderStudyCard = renderStudyCard;
  app.finishFlashcards = finishFlashcards;
  app.revealCard = revealCard;
  app.prevCard = prevCard;
  app.nextCard = nextCard;
  app.rateCard = rateCard;
  app.finishMemorize = finishMemorize;
  app.readCardAloud = readCardAloud;
})();
