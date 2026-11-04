// Проверка вкладки «Заправка» на макете веб-Telegram и приложения А-100 (Playwright, Chromium), экраны — по скриншотам
// пользователя 04.11.2026. Запуск: node modules/web/src/test/zapravka_maket.js [папка для снимков].
// Что проверяется: путь до выбора колонки идёт сам; оболочка показывает АЗС и адрес с карточки станции и колонки из
// приложения; выбор колонки, литры/сумма своей клавиатурой и «Далее» доходят до приложения; «Перейти к оплате» нажата,
// предупреждение о геолокации НЕ нажато, оболочка убрана. Код выхода 1 — если что-то не так.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const dir = __dirname;
const shots = process.argv[2];
const js = 'window.__JZP_BG="data:image/jpeg;base64,' +
  fs.readFileSync(path.join(dir, '../main/assets/zapravka_fon.jpg')).toString('base64') + '";\n' +
  fs.readFileSync(path.join(dir, '../main/assets/zapravka_auto.js'), 'utf8');

const TG = (login) => `<!doctype html><body style="margin:0;background:#17212b" class="${login ? 'has-auth-pages' : ''}">
${login ? '<div id="auth-pages">Вход: номер телефона</div>' : `<div class="chat-input">
<button class="chat-input-control-button" id="start" style="color:#fff">START</button>
<div class="new-message-bot-commands" role="button" id="menu" style="color:#fff">Открыть</div></div>`}
<script>window.startClicked=0;if(window.start)start.onclick=()=>{startClicked++;};
if(window.menu)menu.onclick=()=>{const host=document.createElement('div');host.style.cssText='position:fixed;left:210px;top:10px';document.body.append(host);
const root=host.attachShadow({mode:'closed'});const f=document.createElement('iframe');f.src='https://tgminiapp.a-100.by/#tgWebAppData=x';
f.style.cssText='width:380px;height:380px;border:0;background:#fff';root.append(f);};
// мини-приложение просит местоположение — веб-Telegram спрашивает человека (один раз)
window.addEventListener('message',e=>{if(e.data==='need-location'){const p=document.createElement('div');p.className='popup';
p.innerHTML='<div>Разрешить доступ к местоположению?</div><button id="allow">Разрешить</button>';document.body.append(p);
allow.onclick=()=>{p.remove();e.source.postMessage('location-ok','*');};}});</script></body>`;

const APP = (delay) => `<!doctype html><body style="margin:0;font-family:sans-serif"><div id="page"></div>
<nav><span id="nav">Заправка</span></nav>
<script>
window.trail=[];const T=(x)=>trail.push(x);let center=1,qtyMode='l',qtyVal='',full=false;
const show=(h)=>{page.innerHTML=h;bind();};const go=(n,h)=>setTimeout(()=>{T(n);show(h());},${delay});
const HOME=()=>'<div data-k="tile"><span>Заправить авто</span></div><div>Помыть авто</div>';
const MAP=()=>'<button data-k="near">Ближайшая АЗС</button><button>Заправка по QR коду</button>';
const CARD=()=>'<div class="card"><div><div class="name">АЗС №14</div><div class="addr">ул. Тростенецкая, 16</div></div><div>❤</div>'+
  '<button>Открыть в Яндекс картах</button><button data-k="fuel">Заправиться</button></div>';
const PUMP=()=>{let t='';for(let i=1;i<=8;i++){t+='<div class="tile" data-n="'+i+'" style="display:inline-block;width:40px;height:50px;margin:2px;background:'+(i===center?'#f15a22':'#f8b394')+'">'+i+'</div>';}
  return '<h2>ВЫБОР НОМЕРА КОЛОНКИ</h2><div class="h">Номер колонки</div><div class="car" style="white-space:nowrap">'+t+'</div>'+
  '<div class="h">Вы выбрали</div><div>Номер колонки</div><div id="sel">'+center+'</div><button>Назад</button><button data-k="toqty">Выбрать сумму/литры</button>';};
const QTY=()=>'<h2>ВЫБЕРИТЕ КОЛИЧЕСТВО</h2><div>количество</div><input id="q" type="text"><div><button data-k="ml">Литры</button><button data-k="ms">Сумма</button></div>'+
  '<button data-k="full">До полного бака</button><div>Вы выбрали</div><div>Тип топлива АИ-95</div><button>Назад</button>'+
  '<button id="pay" data-k="pay" '+((full||parseFloat(qtyVal)>0)?'':'disabled')+'>Перейти к оплате</button>';
const WARN=()=>'<h2>ПРЕДУПРЕЖДЕНИЕ</h2><div>Судя по геолокации Вы находитесь далеко от заправки</div><button data-k="ok">Всё хорошо, заправлюсь тут</button><button>Выбрать другую</button>';
function bind(){
  page.querySelectorAll('[data-k]').forEach(e=>e.onclick=()=>{const k=e.dataset.k;T('click:'+k);
    if(k==='tile')go('map',MAP);if(k==='near'){if(window.locOk)go('card',CARD);else{T('ask-location');parent.postMessage('need-location','*');}}if(k==='fuel')go('pump',PUMP);if(k==='toqty')go('qty',QTY);
    if(k==='ml'){qtyMode='l';}if(k==='ms'){qtyMode='s';}if(k==='full'){full=true;show(QTY());}
    if(k==='pay'){T('pay:'+qtyMode+':'+(full?'full':qtyVal)+':pump'+center);go('warn',WARN);}if(k==='ok')T('GEO-OK-PRESSED');});
  page.querySelectorAll('.tile').forEach(e=>e.onclick=()=>{const n=+e.dataset.n;T('tile:'+n);center+=Math.sign(n-center);show(PUMP());});
  const q=document.getElementById('q');if(q)q.addEventListener('input',()=>{qtyVal=q.value;T('input:'+q.value);document.getElementById('pay').disabled=!(parseFloat(qtyVal)>0);});
}
addEventListener('message',e=>{if(e.data==='location-ok'){window.locOk=1;T('location-ok');go('card',CARD);}});
nav.onclick=()=>{T('click:nav');go('map',MAP);};show(HOME());T('home');
</script></body>`;

async function run(name, delay, actions, expect) {
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 800, height: 400 } }); const logs = [];
  await ctx.addInitScript(js);
  await ctx.route('https://web.telegram.org/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: TG(false) }));
  await ctx.route('https://tgminiapp.a-100.by/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: APP(delay) }));
  const p = await ctx.newPage();
  p.on('pageerror', e => logs.push('ERR ' + e.message));
  p.on('console', m => { if (m.text().startsWith('[JARVIS-ZP]')) logs.push(m.text().replace('[JARVIS-ZP] ', '')); });
  await p.goto('https://web.telegram.org/k/');
  await p.waitForSelector('#__jzp .z-load', { timeout: 3000 }); // заставка — сразу при открытии вкладки
  // окно разрешения местоположения: заставка должна уйти в сторону, человек нажимает «Разрешить»
  await p.waitForSelector('.popup #allow', { timeout: 20000 });
  await p.waitForTimeout(500);
  if (shots) await p.screenshot({ path: path.join(shots, name + '-0-mestopolozhenie.png') });
  await p.click('.popup #allow', { timeout: 3000 });
  if (shots) await p.screenshot({ path: path.join(shots, name + '-1-zagruzka.png') });
  await p.waitForSelector('#__jzp .z-pumps', { timeout: 20000 });
  await p.waitForTimeout(600);
  const hdr = await p.$eval('#__jzp .z-hdr', e => e.innerText.replace(/\s+/g, ' '));
  const pumps = await p.$$eval('#__jzp .z-p', a => a.map(e => e.textContent).join(','));
  for (const a of actions) { await p.click(a); await p.waitForTimeout(150); }
  if (shots) await p.screenshot({ path: path.join(shots, name + '-2-obolochka.png') });
  await p.click('#__jzp [data-a="go"]');
  await p.waitForFunction(() => !document.getElementById('__jzp'), null, { timeout: 30000 }).catch(() => { });
  const gone = await p.evaluate(() => !document.getElementById('__jzp'));
  const fr = p.frames().find(f => f.url().startsWith('https://tgminiapp.a-100.by'));
  const startClicked = await p.evaluate(() => window.startClicked);
  await fr.waitForFunction(() => window.trail[window.trail.length - 1] === 'warn', null, { timeout: 5000 }).catch(() => { });
  await p.waitForTimeout(500);
  const trail = await fr.evaluate(() => window.trail.join(' > '));
  if (shots) await p.screenshot({ path: path.join(shots, name + '-3-prilozhenie.png') });
  const ok = hdr.includes('АЗС №14') && hdr.includes('ул. Тростенецкая, 16') && pumps === '1,2,3,4,5,6,7,8' && gone &&
    trail.includes(expect) && trail.endsWith('warn') && !trail.includes('GEO-OK') && startClicked === 0 &&
    trail.includes('location-ok') && !logs.some(l => l.startsWith('ERR'));
  console.log((ok ? 'OK   ' : 'FAIL ') + name + ' | шапка: ' + hdr + ' | колонки: ' + pumps + ' | оболочка убрана: ' + gone + ' | «Старт» нажат: ' + startClicked);
  console.log('     приложение: ' + trail);
  if (!ok) console.log('     журнал: ' + logs.join(' / '));
  await b.close();
  return ok;
}

async function login() {
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 800, height: 400 } }); const logs = [];
  await ctx.addInitScript(js);
  await ctx.route('https://web.telegram.org/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: TG(true) }));
  const p = await ctx.newPage();
  p.on('console', m => { if (m.text().startsWith('[JARVIS-ZP]')) logs.push(m.text().replace('[JARVIS-ZP] ', '')); });
  await p.goto('https://web.telegram.org/k/');
  await p.waitForTimeout(2500);
  const gone = await p.evaluate(() => !document.getElementById('__jzp'));
  console.log((gone ? 'OK   ' : 'FAIL ') + 'vhod | страница входа видна, заставки нет: ' + gone + ' | ' + logs.join(' / '));
  await b.close();
  return gone;
}

(async () => {
  const r = [];
  r.push(await login());
  r.push(await run('litry', 300, ['#__jzp [data-p="7"]', '#__jzp [data-a="kb"]', '#__jzp [data-k="3"]', '#__jzp [data-k="5"]', '#__jzp [data-a="kbok"]'], 'pay:l:35:pump7'));
  r.push(await run('summa-medlenno', 1500, ['#__jzp [data-p="3"]', '#__jzp [data-m="s"]', '#__jzp [data-q="50"]'], 'pay:s:50:pump3'));
  r.push(await run('polnyj', 300, ['#__jzp [data-p="1"]', '#__jzp [data-a="full"]'], 'pay:l:full:pump1'));
  process.exit(r.every(Boolean) ? 0 : 1);
})();
