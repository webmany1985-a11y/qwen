// Макет окна TikTok «Воспользуйтесь всеми функциями приложения» без служебных меток (телефон, 15.11.2026):
// сценарий assets/tiktok_autonext.js должен нажать «Не сейчас» (вариант 2 — крестик) и не трогать чужую «Не сейчас».
// Запуск: node tiktok_popup_maket.js (нужен playwright).
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const JS = fs.readFileSync(path.join(__dirname, '../main/assets/tiktok_autonext.js'), 'utf8');
const popup = (withNotNow) => `<div class="DivModal x1" style="position:fixed;inset:0;background:rgba(0,0,0,.5)">
<div class="DivBox"><div class="DivX"><button class="BtnX" onclick="window.jvClosed=1;this.closest('.DivModal').remove()"><svg width=20 height=20></svg></button></div>
<h2>Воспользуйтесь всеми функциями приложения</h2><p>В приложении вас ждет больше видео и полезных функций</p>
<button onclick="window.jvOpened=1">Открыть TikTok</button>
${withNotNow ? '<div class="DivNotNow" onclick="window.jvClosed=1;this.closest(\'.DivModal\').remove()">Не сейчас</div>' : ''}</div></div>`;
const page = (p) => `<!doctype html><html><body><div class="swiper"><div data-e2e="video-slide-active"><video loop></video></div></div>
<div id="other" onclick="window.jvOther=1">Не сейчас</div>${p}</body></html>`;
(async () => {
  const b = await chromium.launch(); let all = true;
  for (const [name, html] of [['не сейчас', page(popup(true))], ['крестик', page(popup(false))]]) {
    const p = await b.newPage({ viewport: { width: 393, height: 780 } }); const logs = [];
    p.on('console', m => logs.push(m.text()));
    await p.route('https://www.tiktok.com/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: html }));
    await p.goto('https://www.tiktok.com/foryou');
    await p.evaluate(JS);
    await p.waitForTimeout(2500);
    const r = await p.evaluate(() => ({ closed: !!window.jvClosed, opened: !!window.jvOpened, other: !!window.jvOther }));
    const ok = r.closed && !r.opened && !r.other;
    all = all && ok;
    console.log((ok ? 'OK   ' : 'FAIL ') + name + ' ' + JSON.stringify(r) + ' ' + logs.filter(l => l.includes('JARVIS-TT')).join(' / '));
    await p.close();
  }
  await b.close(); process.exit(all ? 0 : 1);
})();
