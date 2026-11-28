(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var state = app.state;

  function isPinModalOpen() {
    var modal = $('pinModal');
    return !!(modal && modal.className.indexOf('show') >= 0);
  }
  function openPinModal(kid, afterMode, errorMsg) {
    var modal = $('pinModal'), input = $('pinInput'), err = $('pinError');
    if (!modal || !input) return;
    state.pinTarget = { id: kid.id, name: kid.name || app.childName(kid.id) || '该孩子', afterMode: afterMode || 'hub' };
    if ($('pinTitle')) $('pinTitle').textContent = '切换到「' + state.pinTarget.name + '」';
    if (err) err.textContent = errorMsg || '';
    input.value = '';
    input.readOnly = true;
    input.setAttribute('inputmode', 'none');
    modal.className = 'pin-modal show';
    app.setFocus(0);
  }
  function closePinModal() {
    var modal = $('pinModal'), input = $('pinInput'), err = $('pinError');
    if (modal) modal.className = 'pin-modal';
    if (input) { input.value = ''; try { input.blur(); } catch (e) {} }
    if (err) err.textContent = '';
    state.pinTarget = null;
    app.focusDefault();
  }
  function performChildSwitch(kidId, pin, afterMode, fromModal) {
    if (state.busy) return;
    var body = { id: kidId };
    if (fromModal || (pin !== undefined && pin !== null && pin !== '')) body.pin = String(pin || '');
    state.busy = true;
    app.request('/api/children/switch', body, function (error, result) {
      state.busy = false;
      if (error) {
        var code = result && result.error;
        if (code === 'child_pin_required' || code === 'child_pin_invalid') {
          var msg = (result && result.message) || (code === 'child_pin_invalid' ? '密码错误，请重试' : '请输入切换密码');
          if (!isPinModalOpen()) {
            var targetKid = app.findChild(kidId) || { id: kidId, name: app.childName(kidId) || '该孩子' };
            openPinModal(targetKid, afterMode, msg);
          } else {
            if ($('pinError')) $('pinError').textContent = msg;
            if (code === 'child_pin_invalid' && $('pinInput')) $('pinInput').value = '';
            app.focusDefault();
          }
          return;
        }
        if (isPinModalOpen() && $('pinError')) $('pinError').textContent = error;
        else app.toast(error);
        return;
      }
      if (isPinModalOpen()) closePinModal();
      state.children = result.items || [];
      state.activeChild = result.active || '';
      state.chosen = true;
      app.pill();
      if (afterMode === 'hub') {
        app.loadState(function () { renderHub(); });
      } else if (afterMode === 'start') {
        app.loadState(function () { if (app.isHubAvailable()) renderHub(); else app.start(); });
      } else {
        app.loadState(function () { if (app.isHubAvailable()) renderHub(); else app.route(); });
      }
    });
  }

  function updateHubStats() {
    var study = state.study || {};
    var streak = study.streakDays || 0;
    var todayAns = study.todayAnswered || 0;
    var todayCor = study.todayCorrect || 0;
    var acc = todayAns > 0 ? Math.round(todayCor * 100 / todayAns) : 0;
    var mastered = study.masteredCount || 0;
    var due = study.dueCount || 0;
    var wrong = study.wrongCount || 0;
    var totalQ = study.totalQuestions || 0;
    if ($('hubStreak')) $('hubStreak').textContent = '🔥 连续学习 ' + streak + ' 天';
    if ($('hubToday')) $('hubToday').textContent = todayAns > 0 ? ('今日已练 ' + todayAns + ' 题（正确率 ' + acc + '%）') : '今日尚未开始练习';
    if ($('hubMastered')) $('hubMastered').textContent = '已掌握 ' + mastered + ' 题';
    if ($('hubDueBadge')) {
      $('hubDueBadge').textContent = due > 0 ? (due + ' 道到期') : '今日已清';
      $('hubDueBadge').className = 'hub-card-badge' + (due > 0 ? '' : ' hub-badge-ok');
    }
    if ($('hubWrongBadge')) {
      $('hubWrongBadge').textContent = wrong > 0 ? (wrong + ' 道错题') : '已清零 🎉';
      $('hubWrongBadge').className = 'hub-card-badge ' + (wrong > 0 ? 'hub-badge-warn' : 'hub-badge-ok');
    }
    if ($('hubCardBadge')) $('hubCardBadge').textContent = totalQ > 0 ? ('共 ' + totalQ + ' 题') : '闪卡速记';
    if ($('hubTitle')) {
      var ov = state.learning && state.learning.override;
      $('hubTitle').textContent = app.isPureStudyMode() ? '自主学习中心' : (ov && ov.mode === 'unlock' ? '家长已临时解锁 · 自主练习' : '自由学习时间');
    }
    if ($('hubSubtitle')) {
      var ov2 = state.learning && state.learning.override;
      $('hubSubtitle').textContent = app.isPureStudyMode()
        ? '先背再测、定时复习与错题清零，每天进步一点点'
        : (ov2 && ov2.mode === 'unlock'
          ? app.unlockExpiry(ov2) + '，随时挑个模式巩固知识'
          : (app.scheduleText() ? app.scheduleText() + ' 为学习时段，现在可自由练习或返回桌面' : '随时开始背诵与刷题，也可以返回桌面'));
    }
  }

  function renderHub() {
    app.nativeCall('stopReading');
    state.round = null;
    state.awaiting = false;
    state.reviewed = false;
    state.lockReason = '';
    state.wasFree = !!(state.learning && state.learning.free);
    app.clearRound();
    app.hideFeedback();
    app.show('hub');
    updateHubStats();
    var kidsBox = $('hubChildren');
    if (kidsBox) {
      kidsBox.innerHTML = '';
      if ((state.children || []).length > 1) {
        (state.children || []).forEach(function (kid) {
          var btn = document.createElement('button');
          var isAct = kid.id === state.activeChild || !!kid.active;
          btn.className = 'btn ' + (isAct ? 'btn-primary' : 'btn-ghost') + ' focusable hub-child-btn';
          btn.setAttribute('data-action', 'hub-switch-child');
          btn.setAttribute('data-child', kid.id);
          btn.textContent = (isAct ? '✓ ' : '') + (kid.hasPin ? '🔒 ' : '') + kid.name;
          kidsBox.appendChild(btn);
        });
      }
    }
    var actionsBox = $('hubActions');
    if (actionsBox) {
      actionsBox.innerHTML = '';
      if (app.isPureStudyMode() || (state.learning && (state.learning.canExit || state.learning.free))) {
        var homeBtn = document.createElement('button');
        homeBtn.className = 'btn btn-ghost focusable';
        homeBtn.setAttribute('data-action', 'home');
        homeBtn.textContent = '退出回桌面';
        actionsBox.appendChild(homeBtn);
      }
    }
    if ($('hubNote')) $('hubNote').textContent = '';
    app.focusDefault();
  }

  /**
   * 有多个孩子时，先问一句「是谁在学」再开始：每个人的进度、额度、历史都是分开的。
   * 只在每次打开学习页时问一次，同一轮答题不会反复打断。
   */
  function appendChildChooser(isStart) {
    (state.children || []).forEach(function (kid) {
      var button = document.createElement('button');
      button.className = 'btn ' + (kid.active ? 'btn-primary' : 'btn-ghost') + ' focusable';
      button.setAttribute('data-action', 'start-as-child');
      button.setAttribute('data-child', kid.id);
      var label = (kid.hasPin ? '🔒 ' : '') + kid.name;
      button.textContent = isStart ? '开始学习 · ' + label : label;
      $('lockActions').appendChild(button);
    });
  }

  function chooseChild() {
    state.lockReason = state.learning.reason || 'timeup'; // 让 2 秒轮询不要把它冲掉
    state.wasFree = !!state.learning.free;
    app.show('lock'); app.icon($('lockIcon'), 'pencil');
    $('lockTitle').textContent = '是谁在学？';
    $('lockDesc').textContent = '每个孩子单独记录学习进度和使用时间';
    $('lockActions').innerHTML = '';
    appendChildChooser(false);
    $('lockNote').textContent = '选自己的名字开始；家长可以在「设置 → 孩子」里添加或改名';
    app.focusDefault();
  }

  function handlePinAction(action, el) {
    if (action === 'pin-digit') {
      var pIn = $('pinInput');
      var digit = el.getAttribute('data-digit') || '';
      if (pIn && pIn.value.length < 20) pIn.value += digit;
      if ($('pinError')) $('pinError').textContent = '';
      return true;
    }
    if (action === 'pin-clear') {
      if ($('pinInput')) $('pinInput').value = '';
      if ($('pinError')) $('pinError').textContent = '';
      return true;
    }
    if (action === 'pin-back') {
      var pBack = $('pinInput');
      if (pBack && pBack.value.length > 0) pBack.value = pBack.value.slice(0, -1);
      if ($('pinError')) $('pinError').textContent = '';
      return true;
    }
    if (action === 'pin-cancel') {
      closePinModal();
      return true;
    }
    if (action === 'pin-confirm' && state.pinTarget) {
      var pVal = $('pinInput') ? $('pinInput').value : '';
      if (!pVal) {
        if ($('pinError')) $('pinError').textContent = '请输入切换密码';
        return true;
      }
      performChildSwitch(state.pinTarget.id, pVal, state.pinTarget.afterMode, true);
      return true;
    }
    return false;
  }

  function handlePinKeydown(event) {
    if (event.keyCode === 27) { closePinModal(); event.preventDefault(); return true; }
    if (event.target && event.target.id === 'pinInput' && !event.target.readOnly) {
      if (event.keyCode === 13) { app.activate($('pinConfirm')); event.preventDefault(); }
      return true;
    }
    if (event.keyCode >= 48 && event.keyCode <= 57) {
      var pKeyIn = $('pinInput');
      if (pKeyIn && pKeyIn.value.length < 20) pKeyIn.value += String(event.keyCode - 48);
      if ($('pinError')) $('pinError').textContent = '';
      event.preventDefault();
      return true;
    }
    if (event.keyCode >= 96 && event.keyCode <= 105) {
      var pNumIn = $('pinInput');
      if (pNumIn && pNumIn.value.length < 20) pNumIn.value += String(event.keyCode - 96);
      if ($('pinError')) $('pinError').textContent = '';
      event.preventDefault();
      return true;
    }
    if (event.keyCode === 8) {
      var pDelIn = $('pinInput');
      if (pDelIn && pDelIn.value.length > 0) pDelIn.value = pDelIn.value.slice(0, -1);
      if ($('pinError')) $('pinError').textContent = '';
      event.preventDefault();
      return true;
    }
    return false;
  }

  function handleHubSwitchChild(el) {
    if (state.busy) return;
    var hubKid = el.getAttribute('data-child');
    if (hubKid === state.activeChild) return;
    var hubKidObj = app.findChild(hubKid);
    if (hubKidObj && hubKidObj.hasPin) {
      openPinModal(hubKidObj, 'hub');
      return;
    }
    performChildSwitch(hubKid, '', 'hub', false);
  }

  function handleStartAsChild(el) {
    if (state.busy) return;
    var kidId = el.getAttribute('data-child');
    var wantStart = !state.chosen || state.lockReason === 'timeup';
    if (kidId === state.activeChild) {
      state.chosen = true;
      if (app.isHubAvailable()) renderHub(); else if (wantStart) app.start();
      return;
    }
    var kidObj = app.findChild(kidId);
    var afterMode = wantStart ? 'start' : 'route';
    if (kidObj && kidObj.hasPin) {
      openPinModal(kidObj, afterMode);
      return;
    }
    performChildSwitch(kidId, '', afterMode, false);
  }

  app.isPinModalOpen = isPinModalOpen; app.openPinModal = openPinModal; app.closePinModal = closePinModal;
  app.performChildSwitch = performChildSwitch; app.updateHubStats = updateHubStats; app.renderHub = renderHub;
  app.appendChildChooser = appendChildChooser; app.chooseChild = chooseChild;
  app.handlePinAction = handlePinAction; app.handlePinKeydown = handlePinKeydown;
  app.handleHubSwitchChild = handleHubSwitchChild; app.handleStartAsChild = handleStartAsChild;
})();
