// Макет окна TikTok «Воспользуйтесь всеми функциями приложения» (телефон, 15.11.2026): сценарий
// assets/tiktok_autonext.js должен ПРЯТАТЬ окно, ничего в нём не нажимая («Не сейчас» у TikTok тоже уводит в
// приложение), прятать плашку «Открыть приложение» в углу и не трогать чужое. Запуск: node tiktok_popup_maket.js
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const JS = fs.readFileSync(path.join(__dirname, '../main/assets/tiktok_autonext.js'), 'utf8');
const popup = (e2e) => `<div class="DivModal" style="position:fixed;inset:0;background:rgba(0,0,0,.5)">
<div class="DivBox"><button class="BtnX" onclick="window.jvClicked=1"><svg width=20 height=20></svg></button>
<h2>Воспользуйтесь всеми функциями приложения</h2><p>В приложении вас ждет больше видео и полезных функций</p>
<button onclick="window.jvClicked=1">Открыть TikTok</button>
<div ${e2e ? 'data-e2e="alt-middle-cta-cancel-btn"' : ''} onclick="window.jvClicked=1">Не сейчас</div></div></div>`;
const PILL = '<a id="pill" href="#" style="position:fixed;right:0;top:60px" onclick="window.jvClicked=1"><img width=16 height=16>Открыть приложение</a>';
const page = (p) => `<!doctype html><html><body><div class="swiper"><div data-e2e="video-slide-active"><video loop></video></div></div>
<div id="other" onclick="window.jvOther=1">Не сейчас</div><div id="page">Лента</div>${PILL}${p}</body></html>`;
(async () => {
  const b = await chromium.launch(); let all = true;
  for (const [name, html] of [['с меткой', page(popup(true))], ['без меток', page(popup(false))]]) {
    const p = await b.newPage({ viewport: { width: 393, height: 780 } }); const logs = [];
    p.on('console', m => logs.push(m.text()));
    await p.route('https://www.tiktok.com/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: html }));
    await p.goto('https://www.tiktok.com/foryou');
    await p.evaluate(JS);
    await p.waitForTimeout(2500);
    const r = await p.evaluate(() => ({
      modal: getComputedStyle(document.querySelector('.DivModal')).display !== 'none' &&
        document.querySelector('.DivBox').getBoundingClientRect().height > 0,
      pill: document.getElementById('pill').getBoundingClientRect().width > 0,
      page: document.getElementById('page').getBoundingClientRect().height > 0,
      clicked: !!window.jvClicked, other: !!window.jvOther }));
    const ok = !r.modal && !r.pill && r.page && !r.clicked && !r.other;
    all = all && ok;
    console.log((ok ? 'OK   ' : 'FAIL ') + name + ' ' + JSON.stringify(r) + ' ' + logs.filter(l => l.includes('JARVIS-TT')).join(' / '));
    await p.close();
  }
  await b.close(); process.exit(all ? 0 : 1);
})();
