// Вкладка «Заправка» JARVIS Auto (пожелания пользователя 04.11.2026). Вставляется во все кадры страницы
// (addDocumentStartJavaScript); перед сценарием ZapravkaAuto ставит переменную __JZP_BG — фото АЗС (data:).
//
// 1) веб-Telegram (главный кадр): «Запустить» бота, если не запущен, затем кнопка меню бота — она открывает
//    мини-приложение. Классы — по исходникам morethanwords/tweb (src/components/chat/input.ts):
//    .chat-input-control-button («BotStart»), .new-message-bot-commands (botMenuButton → chat.openWebApp).
//    Окно согласия с условиями мини-приложений (первый запуск) не нажимается — согласие даёт человек.
//    Поверх всей вкладки — своя оболочка: пока идёт путь — логотип А-100; на выборе колонки — фото АЗС,
//    АЗС и адрес, прозрачные кнопки колонок, ввод литров или суммы своей клавиатурой (в машине экранной
//    клавиатуры нет), «Далее». Оболочка во весь экран, а не внутри окна приложения: на широком экране
//    машины веб-Telegram показывает мини-приложение небольшим окном.
// 2) приложение заправки (кадр внутри веб-Telegram, чужой сайт). Путь по скриншотам пользователя: главная →
//    нижняя «Заправка» (запасной путь — «Заправить авто») → «Ближайшая АЗС» → карточка станции (номер и адрес
//    запоминаются) → «Заправиться» → «Выбор номера колонки». По «Далее» оболочки: колонка на ленте приложения,
//    «Выбрать сумму/литры», «Литры»/«Сумма» и количество (или «До полного бака»), «Перейти к оплате».
//    Предупреждение «далеко от заправки», выбор топлива, если появится, и оплату сценарий не нажимает —
//    приложение показывается как есть, решает человек.
// Кадры говорят друг с другом через postMessage: сообщения { jzp: 1, … }, источник проверяется
// (главный кадр принимает только от кадра приложения, кадр приложения — только от главного).
// В журнал ([JARVIS-ZP]) — шаги и короткие надписи кнопок без цифр; суммы и номера карт не пишутся.
(function () {
  if (window.__jarvisZp) return;
  window.__jarvisZp = 1;
  var host = location.hostname || '';
  var isTg = /(^|\.)telegram\.org$/i.test(host) || /^t\.me$/i.test(host);
  var log = function (m) { try { console.log('[JARVIS-ZP] ' + m); } catch (e) { } };
  var visible = function (el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  var text = function (el) { return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim(); };
  var all = function (sel) { return [].slice.call(document.querySelectorAll(sel)); };
  var CLICKABLE = 'button,a,[role=button],[onclick]';
  // Касание целиком: веб-Telegram на сенсорных устройствах слушает у кнопок mousedown, а не click
  // (tweb src/helpers/dom/clickEvent.ts: CLICK_EVENT_NAME = IS_TOUCH_SUPPORTED ? 'mousedown' : 'click');
  // журнал 04.11.2026 15:00: el.click() по «Открыть» приложение не открывал
  var tap = function (el, px, py) {
    var r = el.getBoundingClientRect();
    var x = px != null ? px : r.left + r.width / 2, y = py != null ? py : r.top + r.height / 2;
    var o = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0, buttons: 1 };
    try { el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ pointerType: 'touch', isPrimary: true }, o))); } catch (e) { }
    el.dispatchEvent(new MouseEvent('mousedown', o));
    o.buttons = 0;
    try { el.dispatchEvent(new PointerEvent('pointerup', Object.assign({ pointerType: 'touch', isPrimary: true }, o))); } catch (e) { }
    el.dispatchEvent(new MouseEvent('mouseup', o));
    el.dispatchEvent(new MouseEvent('click', o));
  };

  if (window === window.top && isTg) topFrame();
  else if (window !== window.top && !isTg) appFrame();

  // ---------------------------------------------------------------- веб-Telegram и оболочка
  function topFrame() {
    // Сайт приложения заправки: только его сообщениям оболочка верит (АЗС и адрес — от настоящего приложения А-100)
    var APP_ORIGINS = ['https://tgminiapp.a-100.by'];
    var started = false, menuClicked = false, t0 = Date.now(), appWin = null, revealed = false, opened = false;
    var st = { phase: 'off', station: '', address: '', near: false, pumps: [], pump: 0, mode: 'l', value: '', full: false };
    // Вход в Telegram (страница входа веб-Telegram: класс has-auth-pages, блок #auth-pages) — заставку убрать
    // Пока веб-Telegram загружается, признак страницы входа стоит и у вошедшего (снимается в bootstrapIm.ts) —
    // вход признаётся, только если признак держится дольше 5 с и чата так и не появилось (журнал 04.11.2026, 14:47:
    // ложный «вход» отключил вкладку)
    // Признак has-auth-pages держится и у вошедшего, пока веб-Telegram грузится (на телефоне — дольше 5 с; журнал 16:00
    // и 16:10). Вход — только если видна сама форма: в #auth-pages поле ввода (номер, код, пароль) или QR-код
    // (tweb src/pages/cards/SignInCard.tsx, SignQRCard.tsx), и чата нет
    var loginShown = function () {
      var auth = document.getElementById('auth-pages');
      if (!auth || document.querySelector('.chat-input,.new-message-bot-commands')) return false;
      return [].slice.call(auth.querySelectorAll('input,canvas')).some(function (el) {
        var r = el.getBoundingClientRect(); return r.width > 20 && r.height > 10;
      });
    };
    // Окно веб-Telegram (разрешение на местоположение, условия мини-приложений) — заставка не должна его закрывать
    var tgPopup = function () {
      return all('.popup').filter(function (p) { return visible(p) && !p.querySelector('iframe'); })[0];
    };
    // Список чатов не должен мелькнуть ни на миг (пожелание 04.11.2026): содержимое веб-Telegram скрыто с первой
    // же отрисовки (стиль — как только появится корень документа), видны только заставка и окна веб-Telegram
    // (.popup: разрешение местоположения, условия). Вход в Telegram или показ приложения — стиль снимается.
    var hideEl = document.createElement('style');
    hideEl.textContent = 'html,body{background:#f4f4f4!important}body>*:not(#__jzp){visibility:hidden!important}' +
      '.popup,.popup *{visibility:visible!important}';
    var addHide = function () {
      var root = document.head || document.documentElement;
      if (root && !hideEl.isConnected && !revealed && !opened) root.appendChild(hideEl);
      return hideEl.isConnected;
    };
    var early = new MutationObserver(function () {
      if (revealed) { early.disconnect(); return; }
      addHide();
      if (document.body && !ov) showLoading();
      if (hideEl.isConnected && ov) early.disconnect();
    });
    early.observe(document, { childList: true, subtree: true });
    addHide();
    var iv = setInterval(function () {
      if (revealed) { clearInterval(iv); return; }
      if (loginShown()) { log('tg: login page — splash removed'); clearInterval(iv); reveal('login'); return; }
      if (opened) { clearInterval(iv); return; }
      if (Date.now() - t0 > 60000) { clearInterval(iv); log('tg: app button not found in 60 s'); reveal('no app'); return; }
      // Сначала кнопка приложения; «Старт» — только если её нет (иначе боту каждый раз уходит лишний /start)
      var menu = document.querySelector('.new-message-bot-commands');
      if (menu && visible(menu) && text(menu)) {
        if (!menuClicked) {
          menuClicked = true;
          log('tg: open app button «' + text(menu).slice(0, 30) + '»');
          tap(menu);
          setTimeout(function () {
            if (!opened && !revealed) {
              log('tg: app did not report in 25 s' + (tgPopup() ? ' (a popup is open — terms?)' : ''));
              reveal('no app');
            }
          }, 25000);
        }
        return;
      }
      // «Старт» бота не нажимается: кнопка «Открыть» есть и без него, а /start засорял чат (скриншот 04.11.2026 16:10)
    }, 700);

    // Окно веб-Telegram поверх — оболочку на время спрятать, затем вернуть
    // Окно закрылось — заставка сразу, при изменении страницы (до следующего кадра); и каждый кадр — на всякий случай
    var popCheck = function () {
      if (revealed || !ov) return;
      var pop = !!tgPopup();
      if (pop && ov.style.display !== 'none') { ov.style.display = 'none'; log('tg: telegram popup — splash hidden'); }
      else if (!pop && ov.style.display === 'none') { ov.style.display = ''; }
      if (pop) lastMsg = Date.now();
    };
    new MutationObserver(popCheck).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style'] });
    (function pv() { if (revealed) return; popCheck(); requestAnimationFrame(pv); })();

    // Нет вестей от приложения 40 с — оболочку убрать: человек видит веб-Telegram как есть
    var lastMsg = Date.now();
    function watchdog() {
      var w = setInterval(function () {
        if (revealed) { clearInterval(w); return; }
        if (st.phase !== 'shell' && Date.now() - lastMsg > 40000) { clearInterval(w); log('tg: no news from app in 40 s'); reveal('silent'); }
      }, 2000);
    }

    window.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || d.jzp !== 1 || revealed || e.source === window) return;
      if (APP_ORIGINS.indexOf(e.origin) < 0) { log('tg: message from other site ignored: ' + e.origin); return; }
      appWin = e.source;
      lastMsg = Date.now();
      if (!opened) {
        opened = true;
        // Окно приложения не прятать: спрятанное окно чужого сайта браузер не отрисовывает, и переходы приложения
        // (анимации, карта) стоят (журнал 04.11.2026 15:13: застряло на «Заправка»; в 14:47 без скрытия путь прошёл).
        // Экран закрывает непрозрачная заставка, список чатов не мелькнёт
        try { early.disconnect(); } catch (x) { }
        hideEl.remove();
        log('tg: app opened');
        showLoading();
        watchdog();
      }
      if (d.type === 'station') { st.station = d.station || ''; st.address = d.address || ''; st.near = !!d.near; }
      else if (d.type === 'pumps') {
        st.pumps = (d.pumps || []).filter(function (n) { return /^\d{1,2}$/.test(String(n)); });
        if (!st.pumps.length) st.pumps = [1, 2, 3, 4, 5, 6, 7, 8];
        st.phase = 'shell';
        render();
      } else if (d.type === 'busy') { showLoading(); }
      else if (d.type === 'reveal') { log('tg: reveal (' + (d.why || '') + ')'); reveal(d.why); }
    });

    var send = function (o) { if (appWin) { o.jzp = 1; appWin.postMessage(o, '*'); } };

    // --- разметка оболочки
    var ov = null, styleEl = null;
    function ensure() {
      if (ov) return ov;
      styleEl = document.createElement('style');
      styleEl.textContent = CSS;
      document.head.appendChild(styleEl);
      ov = document.createElement('div');
      ov.id = '__jzp';
      document.body.appendChild(ov);
      return ov;
    }
    function reveal(why) {
      if (revealed) return;
      revealed = true;
      try { early.disconnect(); } catch (e) { }
      if (hideEl) hideEl.remove();
      if (ov) ov.remove();
      if (styleEl) styleEl.remove();
      ov = null;
    }
    function showLoading() {
      if (revealed) return;
      ensure().innerHTML = '<div class="z-load">' + LOGO + '<div class="z-ring"></div></div>';
      st.phase = st.phase === 'shell' ? 'busy' : 'loading';
    }
    function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
    function render() {
      if (revealed) return;
      var o = ensure();
      var unit = st.mode === 'l' ? 'л' : 'Б';
      var shown = st.full ? 'До полного' : (st.value || '0');
      var ready = st.pump && (st.full || parseFloat((st.value || '0').replace(',', '.')) > 0);
      var quick = st.mode === 'l' ? ['20', '40'] : ['50', '100'];
      o.innerHTML =
        '<div class="z-bg" style="background-image:url(' + (window.__JZP_BG || '') + ')"></div><div class="z-shade"></div>' +
        '<div class="z-g z-hdr">' + KLEVER + '<div><div class="z-st">' +
        esc(st.station || 'АЗС не определена') + '</div><div class="z-ad">' + esc(st.address || 'проверьте станцию в приложении') + '</div></div></div>' +
        (st.near ? '<div class="z-g z-near">✓ ближайшая по местоположению</div>' : '') +
        '<div class="z-g z-app" data-a="app">Приложение</div>' +
        '<div class="z-pumps">' + st.pumps.map(function (n) {
          return '<div class="z-p' + (String(n) === String(st.pump) ? ' sel' : '') + '" data-p="' + esc(n) + '">' + esc(n) + '</div>';
        }).join('') + '</div>' +
        '<div class="z-row"><div class="z-g z-seg' + (st.mode === 'l' ? ' on' : '') + '" data-m="l">Литры</div>' +
        '<div class="z-g z-seg' + (st.mode === 's' ? ' on' : '') + '" data-m="s">Сумма</div>' +
        '<div class="z-g z-inp" data-a="kb">' + esc(shown) + (st.full ? '' : '<small>' + unit + '</small>') + '<span class="z-caret"></span></div>' +
        quick.map(function (q) { return '<div class="z-g z-q" data-q="' + q + '">' + q + '</div>'; }).join('') +
        '<div class="z-g z-q' + (st.full ? ' on' : '') + '" data-a="full">Полный</div></div>' +
        '<div class="z-go' + (ready ? '' : ' off') + '" data-a="go">ДАЛЕЕ ›</div>' +
        (st.kb ? keypad() : '');
      o.onclick = onClick;
    }
    function keypad() {
      var k = ['1', '2', '3', '4', '5', '6', '7', '8', '9', ',', '0', '⌫'];
      return '<div class="z-kbw" data-a="kbclose"><div class="z-g z-kb">' +
        '<div class="z-kbd">' + esc(st.value || '0') + ' ' + (st.mode === 'l' ? 'л' : 'Б') + '</div>' +
        k.map(function (x) { return '<div class="z-k" data-k="' + x + '">' + x + '</div>'; }).join('') +
        '<div class="z-k z-ok" data-a="kbok">Готово</div></div></div>';
    }
    function onClick(e) {
      var el = e.target.closest('[data-p],[data-m],[data-q],[data-k],[data-a]');
      if (!el) return;
      e.stopPropagation();
      var a = el.getAttribute('data-a');
      if (el.hasAttribute('data-p')) { st.pump = el.getAttribute('data-p'); }
      else if (el.hasAttribute('data-m')) { st.mode = el.getAttribute('data-m'); st.value = ''; st.full = false; }
      else if (el.hasAttribute('data-q')) { st.value = el.getAttribute('data-q'); st.full = false; }
      else if (el.hasAttribute('data-k')) {
        var k = el.getAttribute('data-k'), v = st.value || '';
        if (k === '⌫') v = v.slice(0, -1);
        else if (k === ',') { if (v.indexOf(',') < 0) v = (v || '0') + ','; }
        else if (v.replace(',', '').length < 5) v = (v === '0' ? '' : v) + k;
        st.value = v; st.full = false;
      }
      else if (a === 'kb') { st.kb = true; }
      else if (a === 'kbok') { st.kb = false; }
      else if (a === 'kbclose') { if (e.target === el) st.kb = false; else return; }
      else if (a === 'full') { st.full = !st.full; st.value = ''; }
      else if (a === 'app') { log('tg: user asked for the app'); send({ cmd: 'show' }); reveal('user'); return; }
      else if (a === 'go') {
        if (el.classList.contains('off')) return;
        log('tg: go (pump chosen, ' + (st.full ? 'full tank' : (st.mode === 'l' ? 'litres' : 'sum')) + ')');
        send({ cmd: 'go', pump: String(st.pump), mode: st.full ? 'full' : st.mode, value: st.value || '' });
        showLoading();
        return;
      }
      render();
    }
  }

  // ---------------------------------------------------------------- приложение заправки
  function appFrame() {
    var post = function (o) { o.jzp = 1; try { window.top.postMessage(o, '*'); } catch (e) { } };
    // Только то, что на экране: «Заправка» есть и за краем экрана (карусель) — нажатие туда ничего не даёт
    // (журнал 04.11.2026 15:31: дважды «Заправка», приложение осталось на главной)
    var onScreen = function (el) {
      var r = el.getBoundingClientRect();
      return r.bottom > 0 && r.right > 0 && r.top < innerHeight && r.left < innerWidth;
    };
    var find = function (re, sel) {
      var c = all(sel || 'button,a,[role=button],[onclick],li,div,span,h1,h2,h3').filter(function (el) {
        var s = text(el);
        return s && s.length <= 40 && re.test(s) && visible(el) && onScreen(el);
      });
      c.sort(function (a, b) { return text(a).length - text(b).length; });
      return c[0];
    };
    var press = function (el) { ((el.closest && el.closest(CLICKABLE)) || el).click(); };
    var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
    var waitFor = async function (fn, ms) {
      var t = Date.now();
      while (Date.now() - t < ms) { var v = fn(); if (v) return v; await sleep(250); }
      return null;
    };
    var labels = function () {
      return all(CLICKABLE).filter(visible).map(text).filter(function (s) {
        return s && s.length <= 30 && !/\d{3,}/.test(s);
      }).slice(0, 25).join(' | ');
    };
    var reveal = function (why) { log('app ' + host + ': reveal (' + why + ')'); post({ type: 'reveal', why: why }); };
    post({ type: 'hello' }); // вкладка узнаёт, что приложение открылось
    // Для журнала: идут ли кадры отрисовки (в спрятанном окне браузер их останавливает)
    var rafCount = 0;
    (function rf() { rafCount++; requestAnimationFrame(rf); })();
    var diag = function () {
      var c0 = rafCount, t = Date.now();
      setTimeout(function () {
        log('app ' + host + ': diag — frames/s ' + Math.round((rafCount - c0) * 1000 / (Date.now() - t)) +
          ', visibility ' + document.visibilityState + ', size ' + innerWidth + 'x' + innerHeight + ', labels: ' + labels());
      }, 1000);
    };

    var TARGET = /выбор номера колонки/i;
    var STEPS = [
      { name: 'Заправиться', re: /^заправиться$/i, level: 2 },
      { name: 'Ближайшая АЗС', re: /^ближайшая азс$/i, level: 1, wait: 35000 }, // местоположение определяется до 35 с (журнал 14:47, 15:32)
      // Первая страница после входа — плитка «Заправить авто» (так заправляется пользователь, напоминание 04.11.2026);
      // нижняя «Заправка» — запасной путь. Карта (Яндекс) грузится не сразу
      { name: 'Заправить авто', re: /^заправить\s+авто$/i, level: 0, wait: 10000 },
      { name: 'Заправка', re: /^заправка$/i, level: 0, wait: 10000 }
    ];
    var station = '', address = '', near = false;

    // Карточка станции: «АЗС №14» и строка адреса рядом («ул. Тростенецкая, 16»)
    var readStation = function () {
      var nameEl = find(/^азс\s*№\s*\d+/i, 'div,span,h1,h2,h3,h4,p,b,strong');
      if (!nameEl) return false;
      var name = text(nameEl);
      var box = nameEl;
      for (var i = 0; i < 3 && box.parentElement; i++) box = box.parentElement;
      var leaves = [].slice.call(box.querySelectorAll('*')).filter(function (el) {
        return !el.children.length && visible(el);
      }).map(text);
      var k = leaves.indexOf(name), addr = '';
      for (var j = k + 1; j >= 1 && j < leaves.length && j < k + 6; j++) {
        var s = leaves[j];
        if (s && s !== name && s.length >= 5 && s.length <= 60 && /[а-яё]/i.test(s) &&
          !/яндекс|заправиться|открыть|маршрут/i.test(s)) { addr = s; break; }
      }
      station = name; address = addr;
      post({ type: 'station', station: station, address: address, near: near });
      return true;
    };

    var t1 = Date.now(), lastClick = 0, lastStep = null, level = 0, tries = {}, done = [], lastAlive = 0;
    // После «Ближайшей АЗС» карта ставит станцию в центр, а карточка с «Заправиться» появляется только по нажатию
    // на значок станции (подсказка пользователя и скриншоты 04.11.2026; журнал 15:51 — «Ближайшая АЗС» ×3 без карточки).
    // Значок — отдельным элементом карты (ищем ближайший к центру) либо нарисован: тогда касание точки над центром
    var markerTries = 0, lastMarker = 0;
    var MARKER_OFFSETS = [-30, -15, -45, 0, -60, -30, -20, -40];
    var tapMarker = function () {
      var maps = all('[class*="ymaps"],[class*="map"],[class*="Map"],canvas').filter(function (el) {
        var r = el.getBoundingClientRect(); return onScreen(el) && r.width * r.height > innerWidth * innerHeight * 0.3;
      });
      maps.sort(function (a, b) {
        var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
        return ra.width * ra.height - rb.width * rb.height; // самый узкий из больших — сама карта
      });
      var btn = find(/^ближайшая азс$/i);
      var mr = maps[0] ? maps[0].getBoundingClientRect() : { left: 0, top: 0, width: innerWidth, height: btn ? btn.getBoundingClientRect().top : innerHeight };
      var cx = mr.left + mr.width / 2, cy = mr.top + Math.min(mr.height, btn ? btn.getBoundingClientRect().top - mr.top : mr.height) / 2;
      var marks = all('[class*="placemark"],[class*="Placemark"],[class*="marker"],[class*="Marker"],ymaps img,ymaps svg,[class*="ymaps"] img').filter(function (el) {
        var r = el.getBoundingClientRect();
        return onScreen(el) && r.width >= 16 && r.width <= 140 && r.height >= 16 && r.height <= 160;
      });
      var dist = function (el) { var r = el.getBoundingClientRect(); return Math.hypot(r.left + r.width / 2 - cx, r.top + r.height / 2 - cy); };
      marks.sort(function (a, b) { return dist(a) - dist(b); });
      markerTries++; lastMarker = Date.now();
      if (marks[0] && dist(marks[0]) < 120 && markerTries % 2 === 1) {
        var m = marks[0];
        log('app ' + host + ': tap station marker (element ' + m.tagName.toLowerCase() + ', ' + Math.round(dist(m)) + ' px from centre)');
        tap(m);
        return;
      }
      var x = cx, y = cy + MARKER_OFFSETS[(markerTries - 1) % MARKER_OFFSETS.length];
      var at = document.elementFromPoint(x, y);
      log('app ' + host + ': tap map at centre ' + Math.round(x) + ',' + Math.round(y) + ' (' + (at ? at.tagName.toLowerCase() : '—') + ', markers found ' + marks.length + ')');
      if (at) tap(at, x, y);
    };
    setTimeout(diag, 3000);
    var iv = setInterval(function () {
      var now = Date.now();
      // «Я работаю» — вкладка не снимет заставку, пока ждём местоположение
      if (now - lastAlive > 5000) { lastAlive = now; post({ type: 'alive' }); }
      if (find(TARGET)) { clearInterval(iv); log('app ' + host + ': pump choice reached; steps: ' + done.join(' → ')); onPumpScreen(); return; }
      if (find(/^азс\s*№\s*\d+/i, 'div,span,h1,h2,h3,h4,p,b,strong') && !station) readStation();
      if (now - t1 > 100000) { clearInterval(iv); log('app ' + host + ': pump choice not reached in 100 s'); diag(); setTimeout(function () { reveal('timeout'); }, 1200); return; }
      if (now - lastClick < 1500) return;
      // Станция в центре карты — нажать её значок (раз в 3,5 с, до 8 раз), пока не появится карточка с «Заправиться»
      if (lastStep === 'Ближайшая АЗС' && !find(/^заправиться$/i) && now - lastClick > 3500 && now - lastMarker > 3500 && markerTries < 8) {
        tapMarker();
        return;
      }
      for (var i = 0; i < STEPS.length; i++) {
        var s = STEPS[i];
        if (s.level < level) continue; // только вперёд: нижняя «Заправка» видна на всех экранах
        var el = find(s.re);
        if (!el) continue;
        if (s.name === lastStep && now - lastClick < (s.wait || 4000)) return;
        if ((tries[s.name] || 0) >= 2) { clearInterval(iv); log('app ' + host + ': stuck at «' + s.name + '»'); diag(); setTimeout(function () { reveal('stuck'); }, 1200); return; }
        if (tries[s.name]) { log('app ' + host + ': «' + s.name + '» again'); diag(); }
        tries[s.name] = (tries[s.name] || 0) + 1;
        if (s.name === 'Заправиться') readStation();
        if (s.name === 'Ближайшая АЗС') near = true;
        lastStep = s.name; lastClick = now; level = s.level; done.push(s.name);
        log('app ' + host + ': click «' + s.name + '»');
        press(el);
        return;
      }
    }, 300);

    // Лента колонок: элементы, вся надпись которых — номер (1–2 цифры), ниже «Номер колонки», выше «Вы выбрали»
    var pumpTiles = function () {
      var head = find(/^номер колонки$/i, 'div,span,h3,h4,p');
      var chosen = find(/^вы выбрали$/i, 'div,span,h3,h4,p');
      var top = head ? head.getBoundingClientRect().bottom : 0;
      var bottom = chosen ? chosen.getBoundingClientRect().top : 1e9;
      return all('div,span,li,button').filter(function (el) {
        var s = text(el), r = el.getBoundingClientRect();
        return /^\d{1,2}$/.test(s) && r.width > 0 && r.height > 0 && r.top >= top - 5 && r.bottom <= bottom + 5;
      });
    };
    var selectedPump = function () {
      var m = (document.body.innerText || '').match(/Вы выбрали[\s\S]{0,80}?Номер колонки\s*(\d{1,2})/i);
      return m ? m[1] : '';
    };
    var onPumpScreen = function () {
      var nums = [];
      pumpTiles().forEach(function (el) { var n = text(el); if (nums.indexOf(n) < 0) nums.push(n); });
      nums.sort(function (a, b) { return a - b; });
      if (!station) readStation();
      post({ type: 'station', station: station, address: address, near: near });
      post({ type: 'pumps', pumps: nums, selected: selectedPump() });
      log('app ' + host + ': pumps on screen: ' + nums.length);
    };

    window.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || d.jzp !== 1 || e.source !== window.top) return;
      if (d.cmd === 'go') go(String(d.pump || ''), d.mode, String(d.value || ''));
    });

    // Поле ввода, которым управляет сайт (React, Vue): значение — через родной сеттер, затем события input/change
    var setInput = function (inp, v) {
      var proto = Object.getPrototypeOf(inp);
      var desc = Object.getOwnPropertyDescriptor(proto, 'value') || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
      inp.focus();
      desc.set.call(inp, v);
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      inp.dispatchEvent(new Event('change', { bubbles: true }));
      inp.blur();
    };
    var isOff = function (b) {
      var x = (b.closest && b.closest(CLICKABLE)) || b;
      return x.disabled || x.getAttribute('aria-disabled') === 'true' || /disabled/i.test(x.className || '') ||
        getComputedStyle(x).pointerEvents === 'none';
    };

    var go = async function (pump, mode, value) {
      post({ type: 'busy' });
      // 1) колонка: нажимать плитку с номером, пока «Вы выбрали — Номер колонки» не покажет её (лента двигается)
      for (var i = 0; i < 10 && selectedPump() !== pump; i++) {
        var tiles = pumpTiles().filter(function (el) { return text(el) === pump; });
        if (!tiles.length) break;
        tiles.sort(function (a, b) {
          var ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
          return rb.width * rb.height - ra.width * ra.height;
        });
        press(tiles[0]);
        await sleep(700);
      }
      if (selectedPump() !== pump) { log('app ' + host + ': pump not selected'); reveal('pump'); return; }
      log('app ' + host + ': pump selected');
      var toQty = find(/^выбрать сумму\s*\/\s*литры$/i);
      if (!toQty) { reveal('no qty button'); return; }
      press(toQty);
      // 2) количество; не открылось (например, экран выбора топлива) — показать приложение
      var qty = await waitFor(function () { return find(/выберите количество/i); }, 8000);
      if (!qty) { log('app ' + host + ': quantity screen not reached; labels: ' + labels()); reveal('no qty screen'); return; }
      if (mode === 'full') {
        var full = find(/^до полного бака$/i);
        if (!full) { reveal('no full'); return; }
        press(full);
      } else {
        var tg = find(mode === 's' ? /^сумма$/i : /^литры$/i);
        if (tg) { press(tg); await sleep(400); }
        var inp = await waitFor(function () {
          return all('input').filter(function (x) { return visible(x) && !/hidden|checkbox|radio/i.test(x.type); })[0];
        }, 3000);
        if (!inp) { reveal('no input'); return; }
        setInput(inp, value.replace(',', '.'));
      }
      // 3) «Перейти к оплате» — когда сайт её включит; дальше (предупреждение, оплата) — человек
      var pay = await waitFor(function () { var b = find(/^перейти к оплате$/i); return b && !isOff(b) ? b : null; }, 6000);
      if (!pay) { log('app ' + host + ': pay button stayed off'); reveal('pay off'); return; }
      log('app ' + host + ': to payment');
      press(pay);
      await sleep(600);
      reveal('payment');
    };
  }

  // ---------------------------------------------------------------- оформление (утверждено 04.11.2026)
  // Логотип А-100 на загрузке — картинка от пользователя (04.11.2026: «логотип поменяй на этот»), без белого фона;
  // нет картинки — надпись
  var LOGO = window.__JZP_LOGO ? '<img class="z-a100" alt="А-100" src="' + window.__JZP_LOGO + '">' :
    '<div class="z-a100" style="font:900 italic 64px sans-serif;color:#e3262b">А-100</div>';
  var KLEVER = window.__JZP_KLEVER ? '<img class="z-kl" alt="" src="' + window.__JZP_KLEVER + '">' :
    '<div class="z-logo"><i></i><i></i><i></i><i></i></div>';
  var CSS = '#__jzp{position:fixed;inset:0;z-index:2147483647;overflow:hidden;font-family:"Roboto","DejaVu Sans",sans-serif;color:#fff;background:#0b0d10;' +
    '-webkit-user-select:none;user-select:none}' +
    '#__jzp *{box-sizing:border-box}' +
    '.z-load{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:radial-gradient(circle,#ffffff 0,#f4f4f4 60%,#e9e9e9)}' +
    '.z-a100{height:min(56vh,250px);width:auto;animation:zpl 1.4s ease-in-out infinite}' +
    '.z-kl{width:clamp(30px,10vh,42px);height:auto;flex:none;filter:drop-shadow(0 0 6px #7cc24299)}' +
    '@keyframes zpl{50%{transform:scale(1.05);filter:drop-shadow(0 0 14px #7cc24288)}}' +
    '.z-ring{position:absolute;width:min(60vw,380px);height:min(60vw,380px);border-radius:50%;border:3px solid #7cc24233;border-top-color:#e3262b;animation:zrot 1.1s linear infinite}' +
    '@keyframes zrot{to{transform:rotate(360deg)}}' +
    '.z-bg{position:absolute;inset:-6px;background:center 30%/cover;animation:zkb 16s ease-in-out infinite alternate}' +
    '@keyframes zkb{to{transform:scale(1.025) translate(-4px,-2px)}}' +
    '.z-shade{position:absolute;inset:0;background:linear-gradient(180deg,#0000 0%,#0000 40%,#0005 62%,#000a 100%)}' +
    '.z-g{position:absolute;background:#0000002e;border:1.5px solid #ffffffaa;box-shadow:0 4px 18px #0007,inset 0 1px 0 #ffffff88;text-shadow:0 2px 8px #000c}' +
    '.z-hdr{left:2vw;top:3vh;border-radius:18px;padding:1.2vh 2vw 1.2vh 1.4vw;display:flex;align-items:center;gap:1.5vw;max-width:62vw}' +
    '.z-logo{width:34px;height:34px;flex:none;display:grid;grid-template-columns:1fr 1fr;gap:2px;transform:rotate(45deg)}' +
    '.z-logo i{background:#7cc242;border-radius:50% 50% 50% 0;box-shadow:0 0 8px #7cc242}' +
    '.z-st{font-size:clamp(18px,6.5vh,30px);font-weight:900;line-height:1.05}.z-ad{font-size:clamp(13px,4vh,18px);margin-top:3px}' +
    '.z-near{right:2vw;top:3vh;border-radius:12px;padding:6px 10px;font-size:12px;background:#7cc24240;border-color:#7cc242}' +
    '.z-app{right:2vw;top:calc(3vh + 38px);border-radius:12px;padding:6px 12px;font-size:13px}' +
    '.z-pumps{position:absolute;left:2vw;right:2vw;top:39vh;display:flex;gap:1vw}' +
    '.z-p{flex:1;height:17.5vh;min-height:44px;border-radius:16px;display:flex;align-items:center;justify-content:center;font-size:clamp(22px,8.5vh,36px);font-weight:800;' +
    'background:#00000033;border:1.5px solid #ffffffaa;text-shadow:0 2px 8px #000c;box-shadow:0 4px 16px #0006;transition:transform .25s,background .25s}' +
    '.z-p.sel{background:#f15a22d0;border-color:#ffd2bf;box-shadow:0 0 26px #f15a22,0 6px 18px #0008;transform:translateY(-8px) scale(1.06);animation:zps 1.6s ease-in-out infinite}' +
    '@keyframes zps{50%{box-shadow:0 0 40px #f15a22,0 6px 18px #0008;transform:translateY(-11px) scale(1.07)}}' +
    '.z-row{position:absolute;left:2vw;right:2vw;top:62.5vh;height:15.5vh;display:flex;gap:1vw}' +
    '.z-row .z-g{position:relative}' +
    '.z-seg{border-radius:14px;padding:0 2vw;display:flex;align-items:center;font-size:clamp(13px,4.2vh,17px)}.z-seg.on{background:#ffffffe0;color:#1f2a1f;font-weight:800;text-shadow:none}' +
    '.z-inp{flex:1;border-radius:16px;font-size:clamp(22px,11vh,44px);font-weight:800;display:flex;align-items:center;justify-content:flex-end;padding:0 2vw;background:#0000004d}' +
    '.z-inp small{font-size:18px;margin-left:6px}.z-caret{width:3px;height:60%;background:#fff;margin-left:6px;animation:zcr 1s steps(2) infinite}@keyframes zcr{50%{opacity:0}}' +
    '.z-q{border-radius:14px;padding:0 1.5vw;display:flex;align-items:center;font-size:clamp(12px,3.8vh,15px);background:#7cc24233;border-color:#7cc242}.z-q.on{background:#7cc242cc}' +
    '.z-go{position:absolute;left:2vw;right:2vw;bottom:3vh;height:14vh;min-height:40px;border-radius:16px;background:linear-gradient(90deg,#f15a22e6,#ff7a3de6);border:1.5px solid #ffd2bf;' +
    'display:flex;align-items:center;justify-content:center;font-size:clamp(16px,5.5vh,22px);font-weight:900;letter-spacing:3px;box-shadow:0 6px 24px #f15a2299;overflow:hidden;transition:opacity .2s}' +
    '.z-go:after{content:"";position:absolute;top:0;bottom:0;width:70px;background:linear-gradient(90deg,transparent,#fff7,transparent);animation:zsh 2.6s infinite}' +
    '@keyframes zsh{from{left:-70px}to{left:110%}}.z-go.off{opacity:.45;filter:grayscale(.6)}.z-go.off:after{display:none}' +
    '.z-kbw{position:absolute;inset:0;background:#0007;display:flex;align-items:center;justify-content:center}' +
    '.z-kb{position:relative;width:min(70vw,420px);border-radius:20px;padding:10px;display:grid;grid-template-columns:repeat(3,1fr);gap:8px;background:#000000a0;animation:zin .2s ease-out}' +
    '@keyframes zin{from{transform:scale(.9);opacity:0}}' +
    '.z-kbd{grid-column:1/4;text-align:right;font-size:clamp(20px,9vh,36px);font-weight:800;padding:0 10px}' +
    '.z-k{height:clamp(32px,10vh,48px);border-radius:12px;border:1.5px solid #ffffff88;display:flex;align-items:center;justify-content:center;font-size:clamp(16px,6vh,24px);font-weight:800;background:#ffffff14}' +
    '.z-ok{grid-column:1/4;background:#f15a22d0;border-color:#ffd2bf}' +
    '@media (orientation:portrait){.z-hdr{max-width:none;right:2vw}.z-near{top:auto;bottom:calc(3vh + 9vh + 10px);right:2vw}.z-app{top:auto;bottom:calc(3vh + 9vh + 46px);left:2vw;right:auto}' +
    '.z-pumps{top:22vh;flex-wrap:wrap}.z-p{flex:0 0 calc(25% - 1vw);height:10vh}.z-row{top:48vh;height:9vh;flex-wrap:wrap}.z-go{height:9vh}}';
})();
