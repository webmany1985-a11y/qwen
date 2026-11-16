// Вкладка «Кинопоиск» JARVIS Auto (пожелание 05.11.2026: «выскакивает „открыть в мобильном приложении или остаться
// в браузере“ — это нужно исправить»; 15.11.2026: «всё ещё появляется перейти в мобильное приложение»).
// Сайт предлагает приложение по-разному (снято 15.11.2026 с www.kinopoisk.ru, экран 800×360, мобильная версия):
//  1) окно «Смотрите кино даже без интернета в приложении» — «Открыть в приложении» / «Остаться в браузере»;
//  2) окно «А в приложении Кинопоиск ещё удобнее» — «В приложение» / «Спасибо, в другой раз» и крестик «Закрыть»;
//  3) плавающая кнопка «Открыть в приложении» внизу страницы фильма — это рекламное место сайта
//     (#foxadtouch_bottom_banner, надпись внутри iframe, по тексту её не найти) — прячется вместе с подложкой.
// Окна (1, 2) закрываются кнопкой отказа, а если её нет — крестиком; кнопка (3) прячется. Только там, где говорится
// о приложении. В журнал ([JARVIS-KP]) — что сделано, чтобы следующий вариант было видно по журналу.
(function () {
  if (window.__jarvisKpNoApp) return;
  window.__jarvisKpNoApp = 1;
  var STAY = /^((остаться|продолжить|смотреть|открыть)\s+(в\s+)?браузер[а-я]*|не\s+сейчас|нет,?\s*спасибо|позже|в\s+браузере|(спасибо,?\s*)?в\s+другой\s+раз|не\s+надо|закрыть)$/i;
  var CLOSE = '[aria-label*="акрыть"],[aria-label*="lose"],[class*="closeButton"],[class*="CloseButton"],[data-tid*="close"]';
  var GO_APP = /^(открыть|смотреть|перейти|скачать|продолжить)?\s*в\s+(мобильно[ем]\s+)?приложени[еия]\s*(кинопоиск)?$/i;
  var BOX = '[role=dialog],[aria-modal],[class*=odal],[class*=opup],[class*=anner],[class*=heet],[class*=verlay],[class*=rawer],[class*=portal]';
  var CLICKABLE = 'button,a,[role=button],[tabindex]';
  var text = function (e) { return (e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim(); };
  var visible = function (e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  var log = function (m) { try { console.log('[JARVIS-KP] ' + m); } catch (x) { } };
  // Окно про приложение, в котором лежит элемент: рамка окна (BOX) или ближайший небольшой блок (до 6 уровней вверх,
  // текст до 500 знаков) со словом «приложение»; вся страница не считается (макет 05.11.2026: иначе нажималась чужая
  // кнопка)
  var appBox = function (e) {
    var box = e.closest(BOX);
    if (box && box !== document.body) return /прилож/i.test(text(box)) ? box : null;
    var p = e.parentElement;
    for (var k = 0; p && p !== document.body && k < 6; k++, p = p.parentElement) {
      var t = text(p);
      if (t.length > 500) return null;
      if (/прилож/i.test(t)) return p;
    }
    return null;
  };
  var unlock = function () {
    if (getComputedStyle(document.body).overflow === 'hidden') document.body.style.setProperty('overflow', 'auto', 'important');
  };
  // Плавающая кнопка «Открыть в приложении» не в окне: спрятать её вместе с закреплённой подложкой (не выше 4 уровней
  // и не крупнее четверти экрана — чтобы не спрятать шапку или страницу)
  var hidePill = function (e) {
    var target = e;
    for (var p = e, k = 0; p && p !== document.body && k < 4; k++, p = p.parentElement) {
      var r = p.getBoundingClientRect();
      if (r.width * r.height > innerWidth * innerHeight / 4 || text(p).length > 60) break;
      target = p;
      var pos = getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') break;
    }
    target.style.setProperty('display', 'none', 'important');
    log('app button hidden: «' + text(e) + '»');
  };
  var last = 0;
  var scan = function () {
    var now = Date.now();
    if (now - last < 400) return;
    last = now;
    hideBanner();
    try { hideAds(); } catch (x) { }
    try { reportOverlay(); } catch (x) { }
    var els = document.querySelectorAll(CLICKABLE);
    var goApp = [];
    for (var i = 0; i < els.length; i++) {
      var e = els[i], t = text(e);
      if (!t || t.length > 40 || !visible(e)) continue;
      if (GO_APP.test(t)) { goApp.push(e); continue; }
      if (!STAY.test(t) || !appBox(e)) continue;
      try { e.click(); } catch (x) { }
      log('app prompt dismissed: «' + t + '»');
      setTimeout(unlock, 600);
      return;
    }
    for (var j = 0; j < goApp.length; j++) {
      var g = goApp[j], box = g.closest(BOX);
      if (box && box !== document.body) {
        // Окно без кнопки отказа — крестик, а нет его — спрятать окно
        var c = box.querySelector(CLOSE);
        if (c && visible(c)) {
          try { c.click(); } catch (x) { }
          log('app prompt closed: ' + (c.getAttribute('aria-label') || c.className));
        } else {
          box.style.setProperty('display', 'none', 'important');
          log('app prompt hidden: «' + text(box).slice(0, 60) + '»');
        }
        setTimeout(unlock, 600);
        return;
      }
      hidePill(g);
    }
  };
  // Нижнее рекламное место (вариант 3): стилем, до появления, и в обходе — закреплённую подложку
  var BANNER = '[id^="foxadtouch_bottom"]';
  var hideBanner = function () {
    var b = document.querySelector(BANNER);
    if (!b || b.__jarvisHidden) return;
    b.__jarvisHidden = true;
    var target = b;
    for (var p = b.parentElement, k = 0; p && p !== document.body && k < 3; k++, p = p.parentElement) {
      if (getComputedStyle(p).position === 'fixed') { target = p; break; }
    }
    target.style.setProperty('display', 'none', 'important');
    log('bottom app banner hidden');
  };
  // Блок рекламы с пометкой «Реклама» (16.11.2026, снимок пользователя: плакат поверх главной, «РЕКЛАМА», «⋯» и ✕;
  // на www.kinopoisk.ru это закреплённый 200×300 div.styles_root… styles_adfoxSpecialPo…):
  // от надписи вверх — до закреплённого/плавающего блока не крупнее 3/4 экрана; страница и шапка не прячутся
  var AD_MARK = /^(реклама|ad|advertisement)$/i;
  var hideAds = function () {
    var l = document.querySelectorAll('span,div,p,small');
    for (var i = 0; i < l.length; i++) {
      var e = l[i];
      if (e.children.length || e.__jarvisAd || !AD_MARK.test(text(e))) continue;
      e.__jarvisAd = true;
      // Сама надпись тоже бывает в закреплённой обёртке (53×12, снято 16.11.2026) — останавливаться только на
      // закреплённом блоке размером с баннер (от 120×80) или на блоке AdFox
      var target = null;
      for (var p = e.parentElement, k = 0; p && p !== document.body && k < 10; k++, p = p.parentElement) {
        var r = p.getBoundingClientRect();
        if (r.width * r.height > innerWidth * innerHeight * 0.75 || text(p).length > 300) break;
        target = p;
        if (/adfox/i.test(String(p.className)) || /^adfox_/.test(p.id)) break;
        var pos = getComputedStyle(p).position;
        if ((pos === 'fixed' || pos === 'sticky' || pos === 'absolute') && r.width >= 120 && r.height >= 80) break;
      }
      if (target) {
        target.style.setProperty('display', 'none', 'important');
        log('ad hidden: ' + (target.id || target.className || target.tagName).toString().slice(0, 60));
      }
    }
  };
  // Окно с плеером поверх страницы (16.11.2026, снимок пользователя: трейлер на весь экран, крестик сайта вверху,
  // «нету кнопки назад»): закреплённый блок на ≥60% экрана с видео или встроенным плеером. Приложению — открыт / закрыт
  // (Fermata.overlay): пока открыт, круглая кнопка — «Назад» и вызывает __jarvisKpClose.
  var overlayEl = function () {
    var l = document.querySelectorAll('video,iframe');
    for (var i = 0; i < l.length; i++) {
      for (var p = l[i].parentElement, k = 0; p && p !== document.body && k < 12; k++, p = p.parentElement) {
        if (getComputedStyle(p).position !== 'fixed') continue;
        var r = p.getBoundingClientRect();
        if (r.width * r.height >= innerWidth * innerHeight * 0.6 && getComputedStyle(p).display !== 'none') return p;
        break;
      }
    }
    return null;
  };
  var overlayOn = false;
  var reportOverlay = function () {
    if (window.top !== window) return;
    var on = !!overlayEl();
    if (on === overlayOn) return;
    overlayOn = on;
    try { if (window.Fermata && window.Fermata.overlay) window.Fermata.overlay(on); } catch (x) { }
    log('player overlay ' + (on ? 'open' : 'closed'));
  };
  var CLOSE_RE = /закрыть|close|назад|back|выйти|exit/i;
  window.__jarvisKpClose = function () {
    var o = overlayEl();
    if (!o) { reportOverlay(); return false; }
    // 1) кнопка закрытия самого сайта: по подписи или классу, видимая
    var l = o.querySelectorAll('button,a,[role=button],[aria-label],[class*=lose],[class*=ack]');
    for (var i = 0; i < l.length; i++) {
      var e = l[i], t = (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '') + ' ' + String(e.className);
      if (!CLOSE_RE.test(t) || !visible(e)) continue;
      try { e.click(); } catch (x) { }
      log('player overlay: close clicked');
      setTimeout(window.__jarvisKpCheck, 700);
      return true;
    }
    // 2) Esc
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
      document.dispatchEvent(new KeyboardEvent('keyup', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
    } catch (x) { }
    setTimeout(window.__jarvisKpCheck, 700);
    return true;
  };
  // 3) не закрылось — спрятать окно, остановить видео в нём
  window.__jarvisKpCheck = function () {
    var o = overlayEl();
    if (o) {
      var v = o.querySelectorAll('video');
      for (var i = 0; i < v.length; i++) { try { v[i].pause(); } catch (x) { } }
      var f = o.querySelectorAll('iframe');
      for (var j = 0; j < f.length; j++) { try { f[j].src = 'about:blank'; } catch (x) { } }
      o.style.setProperty('display', 'none', 'important');
      log('player overlay hidden');
    }
    reportOverlay();
  };
  var style = function () {
    var st = document.createElement('style');
    st.textContent = BANNER + ',div:has(> ' + BANNER + '),[id^="adfox_"],[id^="yandex_rtb"],[class*="adfoxSpecial"]{display:none!important}';
    (document.head || document.documentElement).appendChild(st);
  };
  var start = function () {
    try { style(); } catch (x) { }
    try { new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true }); } catch (x) { }
    setInterval(scan, 1500);
  };
  if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start);
})();
