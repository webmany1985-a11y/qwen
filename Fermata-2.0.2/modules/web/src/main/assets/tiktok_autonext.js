// Вкладка TikTok в JARVIS Auto: когда видео досмотрено — свайп на следующее.
// Лента мобильного tiktok.com построена на Swiper; клавиши и прокрутка её не
// листают, листает только касание — поэтому свайп собирается из TouchEvent.
// Проверено на tiktok.com 16.10.2026 (мобильная версия, Chromium).
(function () {
  if (window.__jarvisTt) return;
  var S = { lastSrc: null, lastT: 0, fired: null, firedAt: 0 };

  function video() {
    return document.querySelector('[data-e2e="video-slide-active"] video') ||
      document.querySelector('video');
  }

  // Окно «Воспользуйтесь всеми функциями приложения» — «Не сейчас»
  function closePopups() {
    var c = document.querySelector(
      '[data-e2e="alt-middle-cta-cancel-btn"],[data-e2e="modal-close-inner-button"]');
    if (c && c.offsetParent !== null) c.click();
  }

  function swipe() {
    var el = document.querySelector('[data-e2e="video-slide-active"]') ||
      document.querySelector('.swiper') || document.body;
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y0 = r.top + r.height * 0.75, y1 = r.top + r.height * 0.2;
    var id = Date.now() % 100000;
    function ev(type, y) {
      var t = new Touch({ identifier: id, target: el, clientX: x, clientY: y, pageX: x, pageY: y });
      var list = (type === 'touchend') ? [] : [t];
      return new TouchEvent(type, { touches: list, targetTouches: list, changedTouches: [t],
        bubbles: true, cancelable: true });
    }
    el.dispatchEvent(ev('touchstart', y0));
    var i = 0;
    (function step() {
      i++;
      el.dispatchEvent(ev('touchmove', y0 + (y1 - y0) * i / 8));
      if (i < 8) setTimeout(step, 16); else el.dispatchEvent(ev('touchend', y1));
    })();
  }

  function next() {
    closePopups();
    swipe();
  }

  setInterval(function () {
    closePopups();
    var v = video();
    if (!v) return;
    var src = v.currentSrc || v.src;
    if (src !== S.lastSrc) { S.lastSrc = src; S.lastT = 0; }
    // Видео в ленте зациклены: без этого «конец» не наступает
    if (v.loop) v.loop = false;
    var d = v.duration, t = v.currentTime;
    var atEnd = v.ended || (d > 1 && isFinite(d) && t >= d - 0.3);
    var wrapped = S.lastT > 2 && t < S.lastT - 2 && !v.seeking;
    S.lastT = t;
    if (!(atEnd || wrapped)) return;
    var now = Date.now();
    // Повтор, если свайп не сработал (то же видео стоит в конце дольше 4 с)
    if (S.fired === src && now - S.firedAt < 4000) return;
    S.fired = src;
    S.firedAt = now;
    next();
  }, 500);

  window.__jarvisTt = { next: next };
})();
