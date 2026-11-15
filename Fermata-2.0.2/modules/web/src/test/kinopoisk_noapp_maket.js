// Макеты предложений приложения на Кинопоиске: сценарий assets/kinopoisk_noapp.js должен закрыть их и не трогать
// чужие кнопки. Запуск: node kinopoisk_noapp_maket.js (нужен playwright).
//  1 — окно «Открыть приложение» / «Остаться в браузере» (05.11.2026);
//  2 — окно «А в приложении Кинопоиск ещё удобнее»: «В приложение» / «Спасибо, в другой раз» (15.11.2026, снято с сайта);
//  3 — то же окно только с «В приложение» и крестиком «Закрыть»;
//  4 — нижнее рекламное место #foxadtouch_bottom_banner (надпись в iframe) на закреплённой подложке.
// Чужие кнопки «Остаться в браузере» и «Закрыть» вне окна про приложение нажиматься не должны.
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const JS = fs.readFileSync(path.join(__dirname, '../main/assets/kinopoisk_noapp.js'), 'utf8');
const OTHER = '<h1>Фильм</h1><button id="other" onclick="window.otherClicked=1">Остаться в браузере</button>' +
  '<button id="other2" aria-label="Закрыть" onclick="window.otherClicked=1">x</button><div id="page" style="height:2000px">Описание</div>';
const later = (html, lock = true) => `<script>setTimeout(()=>{const d=document.createElement('div');d.innerHTML=${JSON.stringify(html)};
document.body.appendChild(d.firstChild);${lock ? "document.body.style.overflow='hidden';" : ''}},1500);</script>`;
const CASES = [
  { name: '1 остаться', html: later('<div class="Modal_root__x"><p>Смотреть удобнее в мобильном приложении Кинопоиска</p>' +
      '<button onclick="window.openClicked=1">Открыть приложение</button>' +
      '<button onclick="window.okClicked=1;document.body.style.overflow=\'\';this.parentNode.remove()">Остаться в браузере</button></div>'),
    gone: '.Modal_root__x' },
  { name: '2 в другой раз', html: later('<div class="styles_portalRoot__Z4U64" role="dialog"><div class="styles_sheet__dYc_j">' +
      '<button class="styles_closeButton__5HPhu" aria-label="Закрыть" onclick="window.closeClicked=1"></button>' +
      '<div class="styles_title__VL0nB">А в приложении<br>Кинопоиск ещё удобнее</div><div class="styles_actions__bWAxg">' +
      '<div><button onclick="window.openClicked=1"><span>В приложение</span></button></div>' +
      '<button onclick="window.okClicked=1;this.closest(\'[role=dialog]\').remove()">Спасибо, в другой раз</button></div></div></div>'),
    gone: '[role=dialog]' },
  { name: '3 крестик', html: later('<div class="styles_portalRoot__Z4U64" role="dialog"><div class="styles_sheet__dYc_j">' +
      '<button class="styles_closeButton__5HPhu" aria-label="Закрыть" onclick="window.okClicked=1;this.closest(\'[role=dialog]\').remove()"></button>' +
      '<div class="styles_title__VL0nB">А в приложении Кинопоиск ещё удобнее</div>' +
      '<button onclick="window.openClicked=1"><span>В приложение</span></button></div></div>'),
    gone: '[role=dialog]' },
  { name: '4 нижняя плашка', html: later('<div class="styles_root__bFryr" style="position:fixed;bottom:10px;left:40%;width:279px;height:86px">' +
      '<div id="foxadtouch_bottom_banner"><div style="width:279px;height:86px"><iframe srcdoc="Открыть в приложении"></iframe></div></div></div>', false),
    hidden: '.styles_root__bFryr', noClick: true },
];
(async () => {
  const b = await chromium.launch(); let all = true;
  for (const c of CASES) {
    const p = await b.newPage(); const logs = [];
    p.on('console', m => logs.push(m.text()));
    await p.route('https://www.kinopoisk.ru/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8',
      body: `<!doctype html><html><body>${OTHER}${c.html}</body></html>` }));
    await p.addInitScript(JS);
    await p.goto('https://www.kinopoisk.ru/film/1/');
    await p.waitForTimeout(4500);
    const r = await p.evaluate((c) => ({
      ok: !!window.okClicked, open: !!window.openClicked, other: !!window.otherClicked, close: !!window.closeClicked,
      left: c.gone ? !!document.querySelector(c.gone) : false,
      shown: c.hidden ? getComputedStyle(document.querySelector(c.hidden)).display !== 'none' : false,
      page: getComputedStyle(document.getElementById('page')).display !== 'none',
      ovf: getComputedStyle(document.body).overflow }), c);
    const ok = (c.noClick || r.ok) && !r.open && !r.other && !r.close && !r.left && !r.shown && r.page && r.ovf !== 'hidden' &&
      logs.some(l => l.includes('[JARVIS-KP]'));
    all = all && ok;
    console.log((ok ? 'OK   ' : 'FAIL ') + c.name + ' ' + JSON.stringify(r) + ' ' + logs.filter(l => l.includes('JARVIS-KP')).join(' / '));
    await p.close();
  }
  await b.close(); process.exit(all ? 0 : 1);
})();
