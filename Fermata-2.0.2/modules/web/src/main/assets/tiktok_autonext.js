// Вкладка TikTok в JARVIS Auto: когда видео досмотрено — свайп на следующее.
// Лента мобильного tiktok.com построена на Swiper; клавиши и прокрутка её не
// листают, листает только касание — поэтому свайп собирается из TouchEvent.
// Проверено на tiktok.com 16.10.2026 (мобильная версия, Chromium).
// В машине вкладка открывает версию для компьютера (широкий экран, нет окна
// «Открыть TikTok»): там лента листается кнопкой feed-navigation-next
// (проверено 17.10.2026, 960×420).
(function () {
  if (window.__jarvisTt) return;
  var S = { lastSrc: null, lastT: 0, fired: null, firedAt: 0, key: null, keySince: 0, userAt: 0 };
  // Пока вы листаете сами, скрипт не вмешивается (замечание 16.10.2026:
  // при ручном перелистывании лента подвисала)
  var USER_QUIET_MS = 4000;
  ['touchstart', 'mousedown', 'wheel', 'keydown'].forEach(function (t) {
    document.addEventListener(t, function (e) {
      if (e.isTrusted) S.userAt = Date.now();
    }, { capture: true, passive: true });
  });
  // Сколько стоять на публикации без видео (фото-слайд-шоу, трансляция,
  // реклама) и сверх длины видео, прежде чем листать принудительно.
  // Зависание на одной публикации — замечание 16.10.2026
  var NO_VIDEO_MS = 20000, OVER_MS = 8000, UNKNOWN_MS = 90000;
  function log(m) { try { console.log('[JARVIS-TT] ' + m); } catch (e) {} }

  // Что сейчас на экране: видео — по адресу, иначе — по первой картинке слайда
  function slideKey() {
    // Версия для компьютера: какая публикация ленты сейчас сверху
    var arts = document.querySelectorAll('article');
    if (arts.length && !document.querySelector('[data-e2e="video-slide-active"]')) {
      var bi = -1, bd = 1e9;
      for (var k = 0; k < arts.length; k++) {
        var d = Math.abs(arts[k].getBoundingClientRect().top);
        if (d < bd) { bd = d; bi = k; }
      }
      if (bi >= 0) return 'a:' + (arts[bi].getAttribute('data-scroll-index') || bi);
    }
    var s = document.querySelector('[data-e2e="video-slide-active"]');
    var v = video();
    if (v && (v.currentSrc || v.src)) return 'v:' + (v.currentSrc || v.src);
    var img = s && s.querySelector('img');
    if (img && img.src) return 'i:' + img.src;
    return 'u:' + location.href;
  }

  function isDesktop() {
    return !!document.querySelector('button[data-e2e="feed-navigation-next"]') ||
      (!document.querySelector('[data-e2e="video-slide-active"]') && !!document.querySelector('article'));
  }

  // Видео на экране: в мобильной ленте — активный слайд, в версии для
  // компьютера — то, что больше всего видно
  function video() {
    var v = document.querySelector('[data-e2e="video-slide-active"] video');
    if (v) return v;
    var best = null, area = 0, vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      var w = Math.min(r.right, innerWidth) - Math.max(r.left, 0);
      var h = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
      if (w > 0 && h > 0 && w * h > area) { area = w * h; best = vs[i]; }
    }
    return best;
  }

  // Версия для компьютера: окно «Войдите в TikTok». Его «Пропустить» ведёт
  // не в ленту, а в регистрацию («Когда у вас день рождения?», проверено
  // 21.10.2026) — поэтому окно не нажимается, а прячется вместе с затемнением.
  function skipLogin() {
    var d = document.querySelectorAll('[role="dialog"]');
    for (var i = 0; i < d.length; i++) {
      if (d[i].__jarvisHidden || d[i].offsetParent === null) continue;
      var t = d[i].textContent || '';
      if (t.indexOf('Войдите в TikTok') < 0 && t.indexOf('Log in to TikTok') < 0) continue;
      // Прятать весь слой окна (с затемнением), а не только его содержимое
      var e = d[i].closest('[class*="ModalContainer"]');
      if (!e) {
        for (var x = d[i]; x && x !== document.body; x = x.parentElement) {
          if (getComputedStyle(x).position === 'fixed') e = x;
        }
      }
      (e || d[i]).style.setProperty('display', 'none', 'important');
      d[i].__jarvisHidden = true;
      document.body.style.overflow = '';
      log('login dialog hidden');
    }
  }


  // Способы перелистнуть версию для компьютера. Какой сработает в машине —
  // заранее неизвестно (журнал 20.10.2026: кнопка ленту не сдвинула), поэтому
  // если после перелистывания видео то же, в следующий раз берётся следующий
  // способ; сработавший запоминается.
  var METHODS = ['button', 'column', 'wheel', 'key', 'scroll'];
  var D = { m: 0, keyAt: null, checkAt: 0 };

  function feedBox() {
    var v = video(), e = v;
    while (e && e !== document.body) {
      var st = getComputedStyle(e);
      if ((st.overflowY === 'auto' || st.overflowY === 'scroll') && e.scrollHeight > e.clientHeight) return e;
      e = e.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  // Экран машины низкий (800×320…400), а версия для компьютера рассчитана на
  // высоту от ~600: карточка видео не помещалась, кнопки «вверх/вниз» уходили
  // за край (замечание 21.10.2026). Браузеру сообщается экран 640 точек в
  // высоту, и страница целиком вписывается в экран машины; меню слева скрыто,
  // чтобы видео было крупнее. Проверено 21.10.2026 на 800×400.
  function fitCarScreen() {
    if (window.__jarvisFit || !isDesktop() || innerHeight >= 600) return;
    window.__jarvisFit = true;
    var w = Math.ceil(640 * innerWidth / innerHeight);
    var m = document.querySelector('meta[name="viewport"]');
    if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); }
    m.content = 'width=' + w;
    var st = document.createElement('style');
    st.textContent = '[class*="DivSideNavPlaceholderContainer"],[class*="DivSideNavContainer"]' +
      '{display:none!important}';
    document.head.appendChild(st);
    log('car screen fit: width ' + w);
  }

  function nextDesktop() {
    var how = METHODS[D.m % METHODS.length];
    D.keyAt = slideKey();
    D.checkAt = Date.now() + 3000;
    log('desktop next: ' + how);
    if (how === 'button') {
      var b = document.querySelector('button[data-e2e="feed-navigation-next"]');
      if (b && !b.disabled) { b.click(); return; }
      log('desktop next: no button');
    }
    if (how === 'wheel' || how === 'button') {
      var v = video(), t = v || document.elementFromPoint(innerWidth / 2, innerHeight / 2) || document.body;
      var r = t.getBoundingClientRect();
      t.dispatchEvent(new WheelEvent('wheel', { deltaY: 300, deltaMode: 0, bubbles: true, cancelable: true,
        clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 }));
      return;
    }
    if (how === 'column') {
      // Колонка ленты: прокрутка у неё скрыта, но двигать её можно
      var c = document.querySelector('[class*="DivColumnListContainer"]');
      if (c) { c.scrollTop += c.clientHeight; return; }
      log('desktop next: no column');
    }
    if (how === 'key') {
      var ev = { key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40, bubbles: true };
      (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown', ev));
      document.dispatchEvent(new KeyboardEvent('keydown', ev));
      document.dispatchEvent(new KeyboardEvent('keyup', ev));
      return;
    }
    var box = feedBox();
    box.scrollBy({ top: box.clientHeight || innerHeight, behavior: 'smooth' });
  }

  // Проверка через 3 с: лента сдвинулась — способ хороший, нет — следующий
  function checkDesktopNext() {
    if (!D.checkAt || Date.now() < D.checkAt) return;
    D.checkAt = 0;
    if (slideKey() !== D.keyAt) {
      D.fails = 0;
      log('desktop next ok: ' + METHODS[D.m % METHODS.length]);
    } else {
      D.m++;
      D.fails = (D.fails || 0) + 1;
      log('desktop next failed, will try ' + METHODS[D.m % METHODS.length]);
      // Сразу ещё раз другим способом — но не по кругу без конца
      if (D.fails < METHODS.length) S.keySince = Date.now() - 1e9;
      else D.fails = 0;
    }
  }


  // Окно «Воспользуйтесь всеми функциями приложения». Бывает с «Не сейчас»,
  // бывает только с «Открыть TikTok» (16.10.2026, телефон) — тогда прячем само
  // окно: поднимаемся от его содержимого до слоя на весь экран без видео.
  function closePopups() {
    var c = document.querySelector(
      '[data-e2e="alt-middle-cta-cancel-btn"],[data-e2e="modal-close-inner-button"]');
    if (c && c.offsetParent !== null) { c.click(); return; }
    var m = document.querySelector('[data-e2e="middle-cta-container"]');
    // Уже спрятанное окно повторно не разбирать: полный обход страницы каждые
    // полсекунды тормозил ленту
    if (!m || m.offsetParent === null || m.__jarvisHidden) return;
    m.__jarvisHidden = true;
    log('app promo hidden');
    // Выше ленты, меню и крупных контейнеров не подниматься: на широком экране
    // машины видео в ленте может ещё не быть, и без этих ограничений пряталась
    // вся страница — белый экран (замечание 16.10.2026)
    var e = m, layer = null;
    while (e && e !== document.body && e.parentElement !== document.body) {
      if (e.querySelector('video,[data-e2e="video-slide-active"],.swiper,nav,a[href]')) break;
      if (e.getElementsByTagName('*').length > 150) break;
      var st = getComputedStyle(e);
      if (st.position === 'fixed' || st.position === 'absolute') layer = e;
      e = e.parentElement;
    }
    (layer || m).style.setProperty('display', 'none', 'important');
    // Затемнение под окном — отдельный слой на весь экран без содержимого
    var all = document.querySelectorAll('body *');
    for (var i = 0; i < all.length; i++) {
      var x = all[i];
      if (x.childElementCount || x.textContent.trim()) continue;
      var cs = getComputedStyle(x);
      if (cs.position !== 'fixed') continue;
      var r = x.getBoundingClientRect();
      if (r.width >= innerWidth * 0.9 && r.height >= innerHeight * 0.9 &&
          cs.backgroundColor.indexOf('rgba') === 0) x.style.setProperty('display', 'none', 'important');
    }
    document.body.style.overflow = '';
  }

  function swipe() {
    // Лента на Swiper: если его объект доступен — листать им, без касаний
    // (искусственное касание TikTok мог принять за нажатие на видео)
    var sw = document.querySelector('.swiper');
    if (sw && sw.swiper && typeof sw.swiper.slideNext === 'function') {
      sw.swiper.slideNext();
      return;
    }
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
    if (isDesktop()) nextDesktop(); else swipe();
  }

  setInterval(function () {
    var now = Date.now();
    if (now - S.userAt < USER_QUIET_MS) {
      // Вы листаете сами: таймер страховки — заново, своё перелистывание — нет
      S.keySince = now;
      S.lastSrc = null;
      return;
    }
    closePopups();
    skipLogin();
    if (isDesktop()) { fitCarScreen(); checkDesktopNext(); }
    var key = slideKey();
    if (key !== S.key) { S.key = key; S.keySince = now; }
    var v = video();
    // Страховка от зависания: публикация без видео или видео, которое так и
    // не дошло до «конца», листается по времени
    var d0 = v ? v.duration : NaN;
    // Видео без известной длины (ещё грузится) — ждать дольше, чтобы не
    // перелистнуть недосмотренное
    var limit = !v ? NO_VIDEO_MS : (isFinite(d0) && d0 > 0) ? d0 * 1000 + OVER_MS : UNKNOWN_MS;
    if (now - S.keySince > limit) {
      log('stuck ' + Math.round((now - S.keySince) / 1000) + 's on ' + key.slice(0, 2) + ', next');
      S.keySince = now;
      next();
      return;
    }
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
    // Повтор, если свайп не сработал (то же видео стоит в конце дольше 4 с)
    if (S.fired === src && now - S.firedAt < 4000) return;
    S.fired = src;
    S.firedAt = now;
    log((atEnd ? 'ended' : 'wrapped') + ', next');
    next();
  }, 500);

  window.__jarvisTt = { next: next };
})();
