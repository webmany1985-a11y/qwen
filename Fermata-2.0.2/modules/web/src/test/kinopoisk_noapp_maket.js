// Макет окна Кинопоиска «Открыть в приложении?» (05.11.2026): сценарий assets/kinopoisk_noapp.js должен нажать
// «Остаться в браузере» в окне про приложение и не трогать другие кнопки. Запуск: node kinopoisk_noapp_maket.js
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const JS = fs.readFileSync(path.join(__dirname, '../main/assets/kinopoisk_noapp.js'), 'utf8');
const PAGE = `<!doctype html><html><body><h1>Фильм</h1>
<button id="other" onclick="window.otherClicked=1">Остаться в браузере</button>
<script>setTimeout(()=>{const d=document.createElement('div');d.className='Modal_root__x';d.innerHTML=
'<p>Смотреть удобнее в мобильном приложении Кинопоиска</p><button id="open" onclick="window.openClicked=1">Открыть приложение</button>'+
'<button id="stay" onclick="window.stayClicked=1;this.parentNode.remove()">Остаться в браузере</button>';document.body.appendChild(d);},1500);</script></body></html>`;
(async () => {
  const b = await chromium.launch(); const p = await b.newPage(); const logs = [];
  p.on('console', m => logs.push(m.text()));
  await p.route('https://hd.kinopoisk.ru/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: PAGE }));
  await p.addInitScript(JS);
  await p.goto('https://hd.kinopoisk.ru/film/1');
  await p.waitForTimeout(4000);
  const r = await p.evaluate(() => ({ stay: !!window.stayClicked, open: !!window.openClicked, other: !!window.otherClicked, modal: !!document.querySelector('.Modal_root__x') }));
  const ok = r.stay && !r.open && !r.other && !r.modal && logs.some(l => l.includes('[JARVIS-KP] app prompt dismissed'));
  console.log((ok ? 'OK' : 'FAIL') + ' ' + JSON.stringify(r) + ' ' + logs.filter(l => l.includes('JARVIS-KP')).join(' / '));
  await b.close(); process.exit(ok ? 0 : 1);
})();
