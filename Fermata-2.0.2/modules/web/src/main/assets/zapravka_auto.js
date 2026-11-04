// Вкладка «Заправка» (пожелание 04.11.2026): «чтобы кнопка автоматом нажималась и в приложении сразу открывалась
// нужная часть, где именно с заправкой». Вставляется во все кадры страницы (addDocumentStartJavaScript).
//  1) веб-Telegram (главный кадр): «Запустить» бота, если он ещё не запущен, затем кнопка меню бота — она открывает
//     мини-приложение. Классы — по исходникам morethanwords/tweb (src/components/chat/input.ts):
//     .chat-input-control-button («BotStart»), .new-message-bot-commands (botMenuButton → chat.openWebApp).
//     Окно согласия с условиями мини-приложений (первый запуск) не нажимается — согласие даёт человек.
//  2) приложение заправки (кадр внутри веб-Telegram, чужой сайт): скрыто, пока сценарий проходит «Заправка» →
//     «Ближайшая АЗС» → «Заправиться»; показывается на «Выборе номера колонки» (за 30 с не дошло — как есть,
//     в журнал — короткие надписи кнопок без цифр). Колонку, сумму и оплату сценарий не нажимает.
(function () {
  if (window.__jarvisZp) return;
  window.__jarvisZp = 1;
  var host = location.hostname || '';
  var isTg = /(^|\.)telegram\.org$/i.test(host) || /^t\.me$/i.test(host);
  var log = function (m) { try { console.log('[JARVIS-ZP] ' + m); } catch (e) { } };
  var visible = function (el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  var text = function (el) { return (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim(); };
  var all = function (sel) { return [].slice.call(document.querySelectorAll(sel)); };

  if (window === window.top && isTg) {
    var started = false, t0 = Date.now();
    var iv = setInterval(function () {
      if (Date.now() - t0 > 60000) { clearInterval(iv); log('tg: app button not found in 60 s'); return; }
      var appFrame = all('iframe').filter(function (f) {
        var s = f.getAttribute('src') || '';
        return /^https:/i.test(s) && !/telegram\.org/i.test(s);
      })[0];
      if (appFrame) { clearInterval(iv); log('tg: app opened'); return; }
      var start = all('.chat-input-control-button').filter(function (b) {
        return visible(b) && /^(start|запустить|начать|старт)$/i.test(text(b));
      })[0];
      if (start && !started) { started = true; log('tg: start bot'); start.click(); return; }
      var menu = document.querySelector('.new-message-bot-commands');
      if (menu && visible(menu) && text(menu) && !document.querySelector('.popup-web-app')) {
        clearInterval(iv);
        log('tg: open app button «' + text(menu).slice(0, 30) + '»');
        menu.click();
        // Ждём кадр приложения: не открылся (окно согласия, другой вид чата) — в журнал
        var t2 = Date.now();
        var iv3 = setInterval(function () {
          var f = all('iframe').filter(function (x) { return !/telegram\.org/i.test(x.getAttribute('src') || ''); })[0];
          if (f) { clearInterval(iv3); log('tg: app opened'); }
          else if (Date.now() - t2 > 20000) {
            clearInterval(iv3);
            log('tg: app frame not shown in 20 s' + (document.querySelector('.popup') ? ' (a popup is open — terms?)' : ''));
          }
        }, 1000);
      }
    }, 700);
    return;
  }

  if (window !== window.top && !isTg) {
    // Цепочка приложения А-100 (скриншоты пользователя 04.11.2026): главная («Заправить авто», внизу «Заправка»)
    // → карта («Ближайшая АЗС», «Заправка по QR коду») → карточка станции («Заправиться») → «Выбор номера колонки».
    // Пользователь видит сразу выбор колонки (пожелание: «чтобы вкладка сразу открывалась, где выведены номер
    // колонки и литры, а все промежуточные этапы проходил скрипт»). Колонку, сумму, литры и оплату выбирает
    // человек — на странице колонки сценарий ничего не нажимает. Пока идёт цепочка, приложение скрыто
    // (visibility: раскладка остаётся, нажатия работают) под надписью «Открываю заправку…».
    var TARGET = /выбор номера колонки/i;
    var STEPS = [
      { name: 'Заправиться', re: /^заправиться$/i, level: 2 },
      { name: 'Ближайшая АЗС', re: /^ближайшая азс$/i, level: 1 },
      { name: 'Заправка', re: /^заправка$/i, level: 0 },
      { name: 'Заправить авто', re: /^заправить авто$/i, level: 0 }
    ];
    var style = document.createElement('style');
    style.textContent = 'html{background:#fff!important}body{visibility:hidden!important}' +
      '#__jarvisZpCover{visibility:visible!important;position:fixed;inset:0;display:flex;align-items:center;' +
      'justify-content:center;font:600 22px sans-serif;color:#555;background:#fff;z-index:2147483647}';
    // В начале загрузки кадра корня документа ещё нет: стиль — как только он появится, до первой отрисовки
    var addStyle = function () {
      var root = document.head || document.documentElement;
      if (root && !style.isConnected) root.appendChild(style);
      return style.isConnected;
    };
    if (!addStyle()) {
      var mo = new MutationObserver(function () { if (addStyle()) mo.disconnect(); });
      mo.observe(document, { childList: true, subtree: true });
    }
    var cover = null, shown = false;
    var addCover = function () {
      if (cover || shown || !document.body) return;
      cover = document.createElement('div');
      cover.id = '__jarvisZpCover';
      cover.textContent = 'Открываю заправку…';
      document.body.appendChild(cover);
    };
    document.addEventListener('DOMContentLoaded', addCover);
    var done = [];
    var reveal = function (why) {
      if (shown) return;
      shown = true;
      if (cover) cover.remove();
      style.remove();
      log('app ' + host + ': shown (' + why + (done.length ? '; steps: ' + done.join(' → ') : '') + ')');
    };
    var mine = function (el) { return cover && (el === cover || cover.contains(el) || el.contains(cover)); };
    var find = function (re) {
      var c = all('button,a,[role=button],[onclick],li,div,span,h1,h2,h3').filter(function (el) {
        var s = text(el);
        return s && s.length <= 40 && re.test(s) && visible(el) && !mine(el);
      });
      c.sort(function (a, b) { return text(a).length - text(b).length; });
      return c[0];
    };
    var t1 = Date.now(), lastClick = 0, lastStep = null, level = 0, tries = {};
    var iv2 = setInterval(function () {
      addCover();
      var now = Date.now();
      if (find(TARGET)) { clearInterval(iv2); reveal('pump choice'); return; }
      // Всё — не дольше 30 с; одна и та же кнопка — не чаще раза в 4 с (ждём, пока откроется следующий экран)
      if (now - t1 > 30000) {
        clearInterval(iv2);
        var labels = all('button,a,[role=button],[onclick]').filter(visible).filter(function (el) { return !mine(el); })
          .map(text).filter(function (s) { return s && s.length <= 30 && !/\d{3,}/.test(s); }).slice(0, 25);
        log('app ' + host + ': pump choice not reached in 30 s; labels: ' + labels.join(' | '));
        reveal('timeout');
        return;
      }
      if (now - lastClick < 1500) return;
      for (var i = 0; i < STEPS.length; i++) {
        var st = STEPS[i];
        // Только вперёд: нижняя «Заправка» видна на всех экранах — назад к карте не возвращаться
        if (st.level < level) continue;
        var el = find(st.re);
        if (!el) continue;
        if (st.name === lastStep && now - lastClick < 4000) return;
        // Дважды нажали — следующий экран не открылся (нет местоположения, ошибка сайта): показать как есть
        if ((tries[st.name] || 0) >= 2) {
          clearInterval(iv2);
          log('app ' + host + ': stuck at «' + st.name + '» after 2 clicks');
          reveal('stuck');
          return;
        }
        tries[st.name] = (tries[st.name] || 0) + 1;
        var target = (el.closest && el.closest('button,a,[role=button],[onclick]')) || el;
        lastStep = st.name;
        level = st.level;
        lastClick = now;
        done.push(st.name);
        log('app ' + host + ': click «' + st.name + '»');
        target.click();
        return;
      }
    }, 300);
  }
})();
