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
  'window.__JZP_LOGO="data:image/png;base64,' + fs.readFileSync(path.join(dir, '../main/assets/zapravka_logo.png')).toString('base64') + '";' +
  'window.__JZP_KLEVER="data:image/png;base64,' + fs.readFileSync(path.join(dir, '../main/assets/zapravka_klever.png')).toString('base64') + '";\n' +
  fs.readFileSync(path.join(dir, '../main/assets/zapravka_auto.js'), 'utf8');

// Как в настоящем веб-Telegram: при загрузке признак страницы входа стоит и у вошедшего, затем снимается (1,5 с)
const TG = (login) => `<!doctype html><body style="margin:0;background:#17212b" class="has-auth-pages">
<div id="auth-pages" style="${login ? '' : 'display:none'}">Вход: номер телефона</div>
${login ? '' : `<script>setTimeout(()=>{document.body.classList.remove('has-auth-pages');document.getElementById('auth-pages').remove();
document.body.insertAdjacentHTML('beforeend',document.getElementById('chatTpl').innerHTML);boot();},1500);</script><template id="chatTpl"><div class="chat-input">
<button class="chat-input-control-button" id="start" style="color:#fff">START</button>
<div class="new-message-bot-commands" role="button" id="menu" style="color:#fff">Открыть</div></div></template>`}
<script>window.startClicked=0;function boot(){if(window.start)start.addEventListener('mousedown',()=>{startClicked++;});
if(window.menu)menu.addEventListener('mousedown',()=>{const host=document.createElement('div');host.style.cssText='position:fixed;left:210px;top:10px';document.body.append(host);
const root=host.attachShadow({mode:'closed'});const f=document.createElement('iframe');f.src='https://tgminiapp.a-100.by/#tgWebAppData=x';
f.style.cssText='width:380px;height:380px;border:0;background:#fff';root.append(f);});}
// мини-приложение просит местоположение — веб-Telegram спрашивает человека (один раз)
window.addEventListener('message',e=>{if(e.data==='need-location'){const p=document.createElement('div');p.className='popup';
p.innerHTML='<div>Разрешить доступ к местоположению?</div><button id="allow">Разрешить</button>';document.body.append(p);
allow.onclick=()=>{p.remove();e.source.postMessage('location-ok','*');};}});</script></body>`;

const APP = (delay, mapDelay, locDelay, marker) => `<!doctype html><body style="margin:0;font-family:sans-serif"><div id="page"></div>
<nav><span id="nav">Заправка</span></nav>
<script>
window.trail=[];const T=(x)=>trail.push(x);let center=1,qtyMode='l',qtyVal='',full=false;
const show=(h)=>{page.innerHTML=h;bind();};
// Переход экрана ждёт кадров отрисовки (как анимации и карта на настоящем сайте): в спрятанном окне их нет
const frames=(k,cb)=>{let i=0;const f=()=>{if(++i>=k)cb();else requestAnimationFrame(f);};requestAnimationFrame(f);};
const go=(n,h)=>setTimeout(()=>frames(10,()=>{T(n);show(h());}),${delay});
const HOME=()=>'<div style="position:absolute;left:-2000px;top:40px;width:200px"><div class="slide" data-k="banner">Заправка</div></div><div data-k="tile"><span>Заправить<br>авто</span></div><div>Помыть авто</div>';
const MAP=()=>'<button data-k="near">Ближайшая АЗС</button><button>Заправка по QR коду</button>';
const FOCUS=()=>'<div class="ymaps-2-1-79-map" id="ymap" style="position:relative;width:380px;height:260px;background:#e8e8e8">'+
  ('${marker}'==='canvas' ? '' : '<div class="ymaps-2-1-79-placemark-overlay" data-k="pin" style="position:absolute;left:170px;top:70px;width:40px;height:56px"><img style="width:40px;height:40px;background:#7cc242;border-radius:50%"></div>')+
  '</div><button data-k="near">Ближайшая АЗС</button><button>Заправка по QR коду</button>';
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
    if(k==='tile')go('map',MAP);if(k==='near'){if(window.locOk)locate();else{T('ask-location');parent.postMessage('need-location','*');}}if(k==='fuel')go('pump',PUMP);if(k==='pin')go('card',CARD);if(k==='toqty')go('qty',QTY);
    if(k==='ml'){qtyMode='l';}if(k==='ms'){qtyMode='s';}if(k==='full'){full=true;show(QTY());}
    if(k==='pay'){T('pay:'+qtyMode+':'+(full?'full':qtyVal)+':pump'+center);go('warn',WARN);}if(k==='ok')T('GEO-OK-PRESSED');});
  page.querySelectorAll('.tile').forEach(e=>e.onclick=()=>{const n=+e.dataset.n;T('tile:'+n);center+=Math.sign(n-center);show(PUMP());});
  const q=document.getElementById('q');if(q)q.addEventListener('input',()=>{qtyVal=q.value;T('input:'+q.value);document.getElementById('pay').disabled=!(parseFloat(qtyVal)>0);});
}
let locTimer=null;const locate=()=>{clearTimeout(locTimer);locTimer=setTimeout(()=>{T('located');go('focus',FOCUS);},${locDelay || 0});};
// нарисованный значок: карта сама проверяет точку касания (значок 40×40 по центру, на 30 px выше центра карты)
addEventListener('mousedown',e=>{const m=document.getElementById('ymap');if(!m||'${marker}'!=='canvas')return;const r=m.getBoundingClientRect();
  const cx=r.left+r.width/2,cy=r.top+r.height/2-30;if(Math.abs(e.clientX-cx)<22&&Math.abs(e.clientY-cy)<22){T('click:pin');go('card',CARD);}else T('map-miss');},true);
addEventListener('message',e=>{if(e.data==='location-ok'){window.locOk=1;T('location-ok');locate();}});
nav.onclick=()=>{T('click:nav');setTimeout(()=>frames(10,()=>{T('map');show(MAP());}),${mapDelay || delay});};show(HOME());T('home');
</script></body>`;

// Контроль мелькания: в каждом кадре отрисовки — не виден ли чат веб-Telegram без заставки (кроме окна веб-Telegram)
const FLASH = `if (location.hostname === 'web.telegram.org') { window.__flash = 0; window.__frames = 0;
  (function f() { window.__frames++; const c = document.querySelector('.chat-input'), o = document.getElementById('__jzp');
    const pop = document.querySelector('.popup');
    if (c && !pop && getComputedStyle(c).visibility === 'visible' && (!o || o.style.display === 'none') && !window.__revealedOk) window.__flash++;
    requestAnimationFrame(f); })(); }`;

async function run(name, delay, actions, expect, mapDelay, locDelay, marker) {
  const b = await chromium.launch(); const ctx = await b.newContext({ viewport: { width: 800, height: 400 } }); const logs = [];
  await ctx.addInitScript(FLASH);
  await ctx.addInitScript(js);
  await ctx.route('https://web.telegram.org/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: TG(false) }));
  await ctx.route('https://tgminiapp.a-100.by/**', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: APP(delay, mapDelay, locDelay, marker || 'dom') }));
  const p = await ctx.newPage();
  p.on('pageerror', e => logs.push('ERR ' + e.message));
  p.on('console', m => { if (m.text().startsWith('[JARVIS-ZP]')) logs.push(m.text().replace('[JARVIS-ZP] ', '')); });
  await p.goto('https://web.telegram.org/k/');
  await p.waitForSelector('#__jzp .z-load', { timeout: 1000 }); // заставка — сразу при открытии вкладки
  // окно разрешения местоположения: заставка должна уйти в сторону, человек нажимает «Разрешить»
  await p.waitForSelector('.popup #allow', { timeout: 20000 });
  await p.waitForTimeout(500);
  if (shots) await p.screenshot({ path: path.join(shots, name + '-0-mestopolozhenie.png') });
  await p.click('.popup #allow', { timeout: 3000 });
  await p.waitForTimeout(400);
  if (shots) await p.screenshot({ path: path.join(shots, name + '-1-zagruzka.png') });
  await p.waitForSelector('#__jzp .z-pumps', { timeout: 60000 });
  await p.waitForTimeout(600);
  const hdr = await p.$eval('#__jzp .z-hdr', e => e.innerText.replace(/\s+/g, ' '));
  const pumps = await p.$$eval('#__jzp .z-p', a => a.map(e => e.textContent).join(','));
  for (const a of actions) { await p.click(a); await p.waitForTimeout(150); }
  if (shots) await p.screenshot({ path: path.join(shots, name + '-2-obolochka.png') });
  await p.evaluate(() => { window.__flashBeforeGo = window.__flash; });
  await p.click('#__jzp [data-a="go"]');
  await p.waitForFunction(() => !document.getElementById('__jzp'), null, { timeout: 30000 }).catch(() => { });
  const gone = await p.evaluate(() => !document.getElementById('__jzp'));
  const fr = p.frames().find(f => f.url().startsWith('https://tgminiapp.a-100.by'));
  const startClicked = await p.evaluate(() => window.startClicked);
  const flash = await p.evaluate(() => [window.__flashBeforeGo, window.__frames]);
  await fr.waitForFunction(() => window.trail[window.trail.length - 1] === 'warn', null, { timeout: 5000 }).catch(() => { });
  await p.waitForTimeout(500);
  const trail = await fr.evaluate(() => window.trail.join(' > '));
  if (shots) await p.screenshot({ path: path.join(shots, name + '-3-prilozhenie.png') });
  const ok = hdr.includes('АЗС №14') && hdr.includes('ул. Тростенецкая, 16') && pumps === '1,2,3,4,5,6,7,8' && gone &&
    !trail.includes('click:banner') && trail.includes('click:tile') && trail.includes('click:pin') && !trail.includes('click:nav') && trail.includes(expect) && trail.endsWith('warn') && !trail.includes('GEO-OK') && startClicked === 0 && flash[0] === 0 &&
    trail.includes('location-ok') && !logs.some(l => l.startsWith('ERR'));
  console.log((ok ? 'OK   ' : 'FAIL ') + name + ' | шапка: ' + hdr + ' | колонки: ' + pumps + ' | оболочка убрана: ' + gone + ' | «Старт» нажат: ' + startClicked + ' | кадров с чатом без заставки: ' + flash[0] + ' из ' + flash[1]);
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
  await p.waitForTimeout(7000);
  const gone = await p.evaluate(() => !document.getElementById('__jzp'));
  console.log((gone ? 'OK   ' : 'FAIL ') + 'vhod | страница входа видна, заставки нет: ' + gone + ' | ' + logs.join(' / '));
  await b.close();
  return gone;
}

(async () => {
  const r = [];
  r.push(await login());
  r.push(await run('litry', 300, ['#__jzp [data-p="7"]', '#__jzp [data-a="kb"]', '#__jzp [data-k="3"]', '#__jzp [data-k="5"]', '#__jzp [data-a="kbok"]'], 'pay:l:35:pump7', 0, 25000)); // местоположение определяется 25 с
  r.push(await run('summa-medlenno', 1500, ['#__jzp [data-p="3"]', '#__jzp [data-m="s"]', '#__jzp [data-q="50"]'], 'pay:s:50:pump3', 6000, 0, 'canvas')); // карта открывается 6 с, значок нарисован
  r.push(await run('polnyj', 300, ['#__jzp [data-p="1"]', '#__jzp [data-a="full"]'], 'pay:l:full:pump1'));
  process.exit(r.every(Boolean) ? 0 : 1);
})();
