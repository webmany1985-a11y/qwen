// Вкладка «Заправка» (пожелание 04.11.2026): «чтобы кнопка автоматом нажималась и в приложении сразу открывалась
// нужная часть, где именно с заправкой». Вставляется во все кадры страницы (addDocumentStartJavaScript).
//  1) веб-Telegram (главный кадр): «Запустить» бота, если он ещё не запущен, затем кнопка меню бота — она открывает
//     мини-приложение. Классы — по исходникам morethanwords/tweb (src/components/chat/input.ts):
//     .chat-input-control-button («BotStart»), .new-message-bot-commands (botMenuButton → chat.openWebApp).
//     Окно согласия с условиями мини-приложений (первый запуск) не нажимается — согласие даёт человек.
//  2) приложение заправки (кадр внутри веб-Telegram, чужой сайт): пункт «Заправка» (иначе со словом «Заправ…») — один раз.
//     Не нашёлся за 45 с — в журнал только короткие надписи кнопок (без цифр: суммы, номера карт не пишутся).
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
    var t1 = Date.now(), done = false;
    var iv2 = setInterval(function () {
      if (done) return;
      if (Date.now() - t1 > 45000) {
        clearInterval(iv2);
        var labels = all('button,a,[role=button],[onclick]').filter(visible).map(text).filter(function (s) {
          return s && s.length <= 30 && !/\d{3,}/.test(s);
        }).slice(0, 25);
        log('app ' + host + ': «Заправ» not found; labels: ' + labels.join(' | '));
        return;
      }
      var cands = all('button,a,[role=button],[onclick],li,div,span').filter(function (el) {
        var s = text(el);
        return s && s.length <= 40 && /заправ/i.test(s) && visible(el);
      });
      if (!cands.length) return;
      // Сначала пункт ровно «Заправка» (нижняя вкладка А-100 — карта, «Ближайшая АЗС», «Заправка по QR коду»;
      // скриншоты пользователя 04.11.2026), иначе самая короткая надпись («Заправить авто» на главной).
      // Нажимается ближайший кликабельный предок.
      var exact = function (el) { return /^заправка$/i.test(text(el)) ? 0 : 1; };
      cands.sort(function (a, b) { return (exact(a) - exact(b)) || (text(a).length - text(b).length); });
      var el = cands[0];
      var target = (el.closest && el.closest('button,a,[role=button],[onclick]')) || el;
      done = true;
      clearInterval(iv2);
      log('app ' + host + ': click «' + text(el) + '»');
      target.click();
    }, 700);
  }
})();
