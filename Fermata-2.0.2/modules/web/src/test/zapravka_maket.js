// Проверка сценария вкладки «Заправка» на макете веб-Telegram и приложения А-100 (Playwright, Chromium).
// Запуск: node modules/web/src/test/zapravka_maket.js — по строке на вариант; leakedTicks должно быть 0 для «ok».
const { chromium } = require('playwright');
const fs = require('fs');
const js = fs.readFileSync(require('path').join(__dirname, '../main/assets/zapravka_auto.js'), 'utf8');
const TG = `<!doctype html><body><div class="chat-input"><div class="new-message-bot-commands" role="button" id="menu"><span>Открыть</span></div></div>
<script>menu.onclick=()=>{const p=document.createElement('div');p.className='popup popup-web-app';const f=document.createElement('iframe');f.src='https://app.a100.test/';f.style.width='400px';f.style.height='700px';p.append(f);document.body.append(p);};</script></body>`;
// Приложение: экраны по скриншотам; delay — задержка открытия следующего экрана, mode — где цепочка обрывается
const APP = (delay, mode) => `<!doctype html><body><div id="page"></div>
<nav><div class="nav" id="navHome"><span>Главная</span></div><div class="nav" id="nav"><span>Заправка</span></div></nav>
<script>
window.trail=[]; const go=(name,html)=>setTimeout(()=>{page.innerHTML=html;trail.push(name);bind();},${delay});
const HOME='<div class="tile" data-k="tile"><span>Заправить авто</span></div><div class="tile"><span>Помыть авто</span></div>';
const MAP='<div>карта</div><button data-k="near">Ближайшая АЗС</button><button data-k="qr">Заправка по QR коду</button>';
const CARD='<div>АЗС №14</div><button>Открыть в Яндекс картах</button><button data-k="fuel">Заправиться</button>';
const PUMP='<h2>ВЫБОР НОМЕРА КОЛОНКИ</h2><div>8</div><div>1</div><div>2</div><button data-k="back">Назад</button><button data-k="sum">Выбрать сумму/литры</button>';
function bind(){ page.querySelectorAll('[data-k]').forEach(e=>e.onclick=()=>{ const k=e.dataset.k; trail.push('click:'+k);
  if(k==='tile') go('map',MAP); if(k==='near') go('card', '${mode}'==='nocard' ? MAP : CARD); if(k==='fuel') go('pump',PUMP); if(k==='sum') go('sum','<div>сумма</div>'); }); }
nav.onclick=()=>{trail.push('click:nav'); go('map',MAP);};
page.innerHTML=HOME; trail.push('home'); bind();
</script></body>`;
async function run(delay, mode) {
  const b = await chromium.launch(); const ctx = await b.newContext(); const logs = [];
  await ctx.addInitScript(js);
  await ctx.route('https://web.telegram.org/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: TG }));
  await ctx.route('https://app.a100.test/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: APP(delay, mode) }));
  const p = await ctx.newPage();
  p.on('pageerror', e => logs.push('ERR ' + e.message));
  p.on('console', m => { if (m.text().startsWith('[JARVIS-ZP]')) logs.push(m.text().replace('[JARVIS-ZP] ', '')); });
  await p.goto('https://web.telegram.org/k/');
  // Скрыто ли приложение до экрана колонки: проверяем каждые 100 мс
  let leaked = [];
  for (let i = 0; i < 380; i++) {
    await p.waitForTimeout(100);
    const fr = p.frames().find(f => f.url().startsWith('https://app.a100.test'));
    if (!fr) continue;
    try {
      const st = await fr.evaluate(() => ({ vis: document.body ? getComputedStyle(document.body).visibility : 'nobody', pump: /ВЫБОР НОМЕРА/.test(document.body ? document.body.innerText : ''), trail: (window.trail||[]).join(',') }));
      if (st.vis === 'visible' && !st.pump) leaked.push(i);
      if (st.pump && st.vis === 'visible' && mode !== 'nocard') break;
    } catch (e) {}
  }
  const fr = p.frames().find(f => f.url().startsWith('https://app.a100.test'));
  const end = await fr.evaluate(() => ({ trail: window.trail.join(' > '), vis: getComputedStyle(document.body).visibility }));
  console.log(JSON.stringify({ delay, mode, leakedTicks: leaked.length, ...end, logs }));
  await b.close();
}
(async () => { await run(300, 'ok'); await run(2500, 'ok'); await run(300, 'nocard'); })();
