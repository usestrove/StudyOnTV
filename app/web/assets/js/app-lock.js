(function () {
  'use strict';
  var app = window.SOTVApp;
  var $ = app.$;
  var state = app.state;

  function lock(reason) {
    app.nativeCall('stopReading');
    if (app.isPureStudyMode() && reason !== 'settings') { app.renderHub(); return; }
    state.lockReason = reason;
    app.show('lock'); state.round = null; app.clearRound();
    if (reason === 'feedback') { app.start(); return; }
    if (reason === 'rest') {
      app.icon($('lockIcon'), 'clock'); $('lockTitle').textContent = '休息一下';
      $('lockDesc').textContent = '还需休息 ' + Math.ceil((state.learning.restSeconds || 0) / 60) + ' 分钟';
      $('lockNote').textContent = '休息结束后继续原有学习计划，余额和每日额度不会重置'; $('lockActions').innerHTML = ''; app.focusDefault(); return;
    }
    if (reason === 'settings') {
      app.icon($('lockIcon'), 'hourglass');
      $('lockTitle').textContent = '管控期间暂不能打开系统设置';
      $('lockDesc').textContent = '家长验证管理密码后可临时进入设置 2 分钟，或返回继续。';
      $('lockNote').textContent = '';
      $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="return-learning">返回学习页</button>' +
        (app.canUnlockSettings() ? '<button class="btn btn-ghost focusable" data-action="settings-unlock">家长验证进入设置</button>' : '');
      appendUnlockButton();
      app.focusDefault(); return;
    }
    if (reason === 'locked') {
      var ov = state.learning.override || {};
      app.icon($('lockIcon'), 'lock');
      $('lockTitle').textContent = '家长已暂停使用';
      $('lockDesc').textContent = ov.remainingSeconds > 0 ? app.left2(ov.remainingSeconds) + '后自动恢复' : '需要使用时，请找家长解锁';
      $('lockNote').textContent = '';
      $('lockActions').innerHTML = '';
      appendUnlockButton();
      app.focusDefault(); return;
    }
    app.icon($('lockIcon'), reason === 'schedule' ? 'clock' : 'hourglass');
    $('lockTitle').textContent = reason === 'schedule' ? '还没到可用时间' : reason === 'rounds' ? '今天的任务已全部完成' : reason === 'quota' ? '今天的使用时间已用完' : '使用时间到了';
    var when = '';
    if (state.learning.nextAvailableAt) {
      // 服务端按家长设置的时区算好文案，设备时区不对时也能和规则对得上
      when = state.learning.nextAtText || '';
      if (!when) {
        // 和服务端同一个口径：今明两天说「今天 / 明天」，再远显示日期（Issue #147）
        var date = new Date(state.learning.nextAvailableAt);
        var hm = ('0' + date.getHours()).slice(-2) + ':' + ('0' + date.getMinutes()).slice(-2);
        var today = new Date(); today.setHours(0, 0, 0, 0);
        var days = Math.round((new Date(date.getFullYear(), date.getMonth(), date.getDate()) - today) / 86400000);
        when = days === 0 ? '今天 ' + hm : days === 1 ? '明天 ' + hm : (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日 ' + hm;
      }
    }
    $('lockDesc').textContent = when ? '下次可用：' + when : reason === 'timeup' ? '完成学习任务后才能使用设备' : '现在暂不可用';
    $('lockNote').textContent = state.config.allDay ? '' : (app.scheduleText() ? '可用时段 ' + app.scheduleText() : '');
    if (reason === 'timeup' && (state.children || []).length > 1) {
      // 多个孩子时用「开始学习 · 名字」代替「开始答题」：谁开始就记在谁头上
      $('lockActions').innerHTML = '';
      app.appendChildChooser(true);
      $('lockNote').textContent = '选择自己的名字开始，进度和使用时间按人分开记录';
    } else {
      $('lockActions').innerHTML = reason === 'timeup' ? '<button class="btn btn-primary focusable" data-action="start-quiz">开始答题</button>' : '';
    }
    appendUnlockButton();
    app.focusDefault();
  }

  /**
   * 锁定页的「家长验证解锁」（Issue #201）：家长直接用遥控器输密码临时解锁 30 分钟，
   * 不必再切到手机面板。只有家长在设置里打开 allowQuizUnlock 才显示，默认关闭。
   */
  function appendUnlockButton() {
    if (!state.config.allowQuizUnlock || !app.canUnlock()) return;
    var button = document.createElement('button');
    button.className = 'btn btn-ghost focusable';
    button.setAttribute('data-action', 'parent-unlock');
    button.textContent = '家长验证解锁';
    $('lockActions').appendChild(button);
  }

  function pair() {
    var raw = app.nativeCall('lanInfo'), info;
    try { info = JSON.parse(raw); } catch (e) {}
    if (!info) { app.nativeCall('openParent'); return; }
    app.show('pair');
    var port = info.port || window.location.port || 8080;
    var localUrl = 'http://127.0.0.1:' + port + '/admin/';
    var lanUrl = info.ip ? ('http://' + info.ip + ':' + port + '/admin/') : '';
    var displayUrl = lanUrl || localUrl;
    state.pairUrl = displayUrl;
    state.pairLocalUrl = localUrl;
    var urlEl = $('pairUrl');
    if (urlEl) {
      urlEl.textContent = displayUrl;
      if (urlEl.tagName === 'A') urlEl.setAttribute('href', localUrl);
    }
    if (!lanUrl) {
      $('pairQr').textContent = '未连接 WiFi，可在本机直接打开管理面板';
    } else {
      var qr = window.qrcode(0, 'M'); qr.addData(lanUrl); qr.make(); $('pairQr').innerHTML = qr.createImgTag(8, 8);
    }
    app.focusDefault();
  }

  function openLocalAdmin() {
    window.location.href = state.pairLocalUrl || '/admin/';
  }

  function copyPairUrl() {
    var copyTarget = state.pairUrl || state.pairLocalUrl || '';
    if (!copyTarget) { app.toast('暂无可复制的地址'); return; }
    var copied = app.nativeCall('copyText', [copyTarget]);
    if (copied === true) app.toast('已复制地址：' + copyTarget);
    else app.toast('复制失败，请手动输入地址');
  }

  function requestParentUnlock() {
    if (!app.canUnlock()) { app.toast('当前设备不支持密码解锁'); return; }
    state.unlockPending = true;
    state.unlockPendingAt = Date.now();
    var pendingAt = state.unlockPendingAt;
    // 原生页没打开 / 用户一直没提交时不能让标记一直挂着，否则下一次答题输入会被当成密码
    window.setTimeout(function () {
      if (state.unlockPendingAt === pendingAt) { state.unlockPending = false; state.unlockPendingAt = 0; }
    }, 180000);
    app.nativeCall('askParentPassword');
  }

  function requestSettingsUnlock() {
    if (!app.canUnlockSettings()) { app.toast('当前设备不支持密码验证'); return; }
    state.settingsUnlockPending = true;
    state.settingsUnlockPendingAt = Date.now();
    var settingsPendingAt = state.settingsUnlockPendingAt;
    window.setTimeout(function () {
      if (state.settingsUnlockPendingAt === settingsPendingAt) { state.settingsUnlockPending = false; state.settingsUnlockPendingAt = 0; }
    }, 180000);
    app.nativeCall('askSettingsPassword');
  }

  function handleUnlockInput(result) {
    if (state.settingsUnlockPending) {
      state.settingsUnlockPending = false;
      state.settingsUnlockPendingAt = 0;
      var sPass = (result && result.text) || '';
      if (!sPass) { app.toast('已取消验证'); return true; }
      var sReply = null;
      try { sReply = JSON.parse(app.nativeCall('settingsUnlock', [sPass]) || 'null'); } catch (e) {}
      if (sReply && sReply.ok) {
        state.settingsGate = false;
        state.lockReason = '';
        app.toast('验证通过，已临时放行系统设置 2 分钟');
        return true;
      }
      app.toast(sReply && sReply.error === 'wrong' ? '密码错误，连续错误 5 次后需等待一分钟' : '验证失败，请重试');
      app.focusDefault();
      return true;
    }
    // 家长解锁：回传的是密码，不能写进答题草稿
    if (state.unlockPending) {
      state.unlockPending = false;
      state.unlockPendingAt = 0;
      var pass = (result && result.text) || '';
      if (!pass) { app.toast('已取消解锁'); return true; }
      var reply = null;
      try { reply = JSON.parse(app.nativeCall('quizUnlock', [pass]) || 'null'); } catch (e) {}
      if (reply && reply.ok) {
        if (reply.status) state.learning = reply.status;
        state.lockReason = '';
        app.toast('已临时解锁 30 分钟');
        app.nativeCall('resumePrevious');
        app.route();
        return true;
      }
      app.toast(reply && reply.error === 'wrong' ? '密码错误，连续错误 5 次后需等待一分钟' : '解锁失败，请确认家长已在设置里开启');
      app.focusDefault();
      return true;
    }
    return false;
  }

  app.lock = lock;
  app.appendUnlockButton = appendUnlockButton;
  app.pair = pair;
  app.openLocalAdmin = openLocalAdmin;
  app.copyPairUrl = copyPairUrl;
  app.requestParentUnlock = requestParentUnlock;
  app.requestSettingsUnlock = requestSettingsUnlock;
  app.handleUnlockInput = handleUnlockInput;
})();
