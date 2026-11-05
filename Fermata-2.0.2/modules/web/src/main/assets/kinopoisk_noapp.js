// Вкладка «Кинопоиск» JARVIS Auto (пожелание 05.11.2026: «выскакивает „открыть в мобильном приложении или остаться
// в браузере“ — это нужно исправить»). Сайт предлагает приложение окном; сценарий сам выбирает «остаться в браузере»
// (или «не сейчас») — только в окне, где говорится о приложении. В журнал ([JARVIS-KP]) — надпись нажатой кнопки.
// Разметка окна из облака не видна (сайт там открывается как для США) — поиск по надписям, не по классам.
(function () {
  if (window.__jarvisKpNoApp) return;
  window.__jarvisKpNoApp = 1;
  var STAY = /^((остаться|продолжить|смотреть|открыть)\s+(в\s+)?браузер[а-я]*|не\s+сейчас|нет,?\s*спасибо|позже|в\s+браузере)$/i;
  var BOX = '[role=dialog],[aria-modal],[class*=odal],[class*=opup],[class*=anner],[class*=heet],[class*=verlay],[class*=rawer]';
  var text = function (e) { return (e.innerText || e.textContent || '').replace(/\s+/g, ' ').trim(); };
  // Кнопка — в окне про приложение: рамка окна (BOX) или ближайший небольшой блок (до 6 уровней вверх, текст до 500
  // знаков) со словом «приложение»; вся страница не считается (макет 05.11.2026: иначе нажималась чужая кнопка)
  var aboutApp = function (e) {
    var box = e.closest(BOX);
    if (box && box !== document.body) return /прилож/i.test(text(box));
    var p = e.parentElement;
    for (var k = 0; p && p !== document.body && k < 6; k++, p = p.parentElement) {
      var t = text(p);
      if (t.length > 500) return false;
      if (/прилож/i.test(t)) return true;
    }
    return false;
  };
  var last = 0;
  var scan = function () {
    var now = Date.now();
    if (now - last < 400) return;
    last = now;
    var els = document.querySelectorAll('button,a,[role=button],[tabindex]');
    for (var i = 0; i < els.length; i++) {
      var e = els[i], t = text(e);
      if (!t || t.length > 40 || !STAY.test(t)) continue;
      var r = e.getBoundingClientRect();
      if (!r.width || !r.height) continue;
      if (!aboutApp(e)) continue;
      try { e.click(); } catch (x) { }
      try { console.log('[JARVIS-KP] app prompt dismissed: «' + t + '»'); } catch (x) { }
      return;
    }
  };
  var start = function () {
    try { new MutationObserver(scan).observe(document.documentElement, { childList: true, subtree: true }); } catch (x) { }
    setInterval(scan, 1500);
  };
  if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start);
})();
