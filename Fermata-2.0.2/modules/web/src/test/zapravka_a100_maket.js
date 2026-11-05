// Макет своей вкладки А-100 (05.11.2026): страница assets/zapravka_a100.html в Chromium, сервер А-100 — подмена
// (ответы по полям из кода приложения А-100). Запуск: node zapravka_a100_maket.js [папка для снимков]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const PAGE = fs.readFileSync(path.join(__dirname, '../main/assets/zapravka_a100.html'), 'utf8');
const ASSET = n => fs.readFileSync(path.join(__dirname, '../main/assets', n));
const HOME = [53.8455, 27.6342]; // рядом с АЗС №14
const STATIONS = [
  { id: 1, NUMBER: 14, NAME: 'ул. Тростенецкая, 16', POINT: '53.8456,27.6340', FUEL: [] },
  { id: 2, NUMBER: 7, NAME: 'пр-т Независимости, 100', POINT: '53.93,27.65', FUEL: [] },
  { id: 3, NUMBER: 99, NAME: 'Брест, ул. Дальняя, 1', POINT: '52.09,23.70', FUEL: [] }];
const shots = process.argv[2];

async function run(name, vp, opt, steps) {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: vp, geolocation: opt.geo ? { latitude: opt.geo[0], longitude: opt.geo[1] } : undefined, permissions: opt.geo ? ['geolocation'] : [] });
  const calls = [], logs = [];
  await ctx.route('https://appassets.androidplatform.net/zp/**', r => {
    const n = new URL(r.request().url()).pathname.replace('/zp/', '');
    if (n === 'zapravka_a100.html') return r.fulfill({ contentType: 'text/html', body: PAGE });
    return r.fulfill({ body: ASSET(n) });
  });
  await ctx.route('https://web.telegram.org/**', r => r.fulfill({ contentType: 'text/html', body: '<p>TG</p>' }));
  await ctx.route('https://tgminiappback.a-100.by/**', async r => {
    const q = r.request(), u = new URL(q.url()), p = u.pathname.replace('/api/v1', ''), auth = q.headers()['authorization'] || '';
    const body = q.postData() ? JSON.parse(q.postData()) : null;
    calls.push(q.method() + ' ' + p);
    const json = (s, o) => r.fulfill({ status: s, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(o) });
    await new Promise(res => setTimeout(res, opt.delay || 50));
    if (p === '/login') {
      if (body.phone === '375291234567' && body.password === 'Pass#1') return json(200, { data: { plainTextToken: '5|tok' } });
      return json(422, { data: null, message: 'Неверный номер телефона или пароль' });
    }
    if (auth !== 'Bearer 5|tok') return json(401, { data: null, message: 'Требуется авторизация пользователя' });
    if (p === '/azs') return json(200, { data: STATIONS });
    let m;
    if ((m = p.match(/^\/azs\/(\d+)\/pumps$/))) return json(200, { data: m[1] === '14' ? [3, 1, 2, 4, 5, 6, 7, 8] : [1, 2] });
    if ((m = p.match(/^\/azs\/(\d+)\/pumps\/(\d+)\/ready$/))) {
      if (m[2] === '5') return json(400, { data: null, message: 'Колонка не активна' });
      return json(200, { data: { nozzle_number: 2, fuel_name: 'АИ-95', price: 2.5 } });
    }
    if (p === '/fuel/calculate') { calls.push('calc:' + JSON.stringify(body)); return json(200, { data: { amount: body.liters * 2.5, liters: body.liters } }); }
    return json(404, { message: 'нет' });
  });
  const pg = await ctx.newPage();
  pg.on('console', m => { if (m.text().includes('[JARVIS-ZP]')) logs.push(m.text().replace('[JARVIS-ZP] fast: ', '')); });
  if (opt.token) await pg.addInitScript(t => { try { localStorage.setItem('a100_token', t); } catch (e) { } }, opt.token);
  const t0 = Date.now();
  await pg.goto('https://appassets.androidplatform.net/zp/zapravka_a100.html?tg=' + encodeURIComponent('https://web.telegram.org/k/#?tgaddr=x'));
  let ok = true, notes = [];
  const check = (c, what) => { if (!c) { ok = false; notes.push('НЕТ: ' + what); } };
  for (const s of steps) {
    try { await s(pg, check, calls, t0, notes); } catch (e) { ok = false; notes.push('ошибка: ' + e.message.split('\n')[0]); }
  }
  // ничего не выходит за край экрана
  const out = await pg.evaluate(() => [].slice.call(document.querySelectorAll('#app *')).filter(e => {
    const r = e.getBoundingClientRect(); return r.width && (r.right > innerWidth + 1 || r.bottom > innerHeight + 1 || r.left < -1);
  }).filter(e => !e.classList.contains('z-bg')).map(e => e.className).slice(0, 3));
  check(!out.length, 'всё в пределах экрана (' + out.join(';') + ')');
  check(!logs.some(l => /5\|tok|Pass#1|375291234567/.test(l)), 'вход и номер не попали в журнал');
  if (shots) await pg.screenshot({ path: path.join(shots, 'a100-' + name + '.png') });
  console.log((ok ? 'OK   ' : 'FAIL ') + name + (notes.length ? ' | ' + notes.join(' | ') : ''));
  if (!ok) console.log('     журнал: ' + logs.join(' / ') + '\n     запросы: ' + calls.join(' / '));
  await b.close();
  return ok;
}
const txt = pg => pg.evaluate(() => document.getElementById('app').innerText.replace(/\s+/g, ' '));
const tap = (pg, sel) => pg.click(sel);

(async () => {
  const CAR = { width: 784, height: 280 }, PHONE = { width: 393, height: 780 };
  const r = [];
  // 1. первый вход: неверный пароль → сообщение сервера; верный → станция рядом за <5 с, колонки, топливо, расчёт
  r.push(await run('vhod-i-raschet-telefon', PHONE, { geo: HOME }, [
    async (pg, check) => { await pg.waitForSelector('#ph'); check(true, 'форма входа'); },
    async (pg, check) => { await pg.fill('#ph', '+375 (29) 123 45 67'); await pg.fill('#pw', 'wrong'); await pg.click('.z-login button'); await pg.waitForFunction(() => document.querySelector('.z-err') && document.querySelector('.z-err').textContent.includes('Неверный')); check(true, 'ошибка входа'); },
    async (pg, check, calls, t0) => { await pg.fill('#pw', 'Pass#1'); const t = Date.now(); await pg.click('.z-login button'); await pg.waitForSelector('.z-p'); const s = await txt(pg); check(s.includes('АЗС №14') && s.includes('Тростенецкая'), 'АЗС №14 с адресом'); check(s.includes('рядом'), 'метка «рядом»'); check(Date.now() - t < 5000, 'станция за 5 с'); check(await pg.$$eval('.z-p', a => a.map(e => e.textContent).join(',')) === '1,2,3,4,5,6,7,8', 'колонки 1–8 по порядку'); },
    async (pg, check) => { await tap(pg, '[data-p="3"]'); await pg.waitForSelector('.z-fuel'); check((await txt(pg)).includes('АИ-95 · 2,50 руб/л'), 'топливо и цена'); },
    async (pg, check, calls) => { await tap(pg, '[data-q="40"]'); await tap(pg, '[data-a="go"]'); await pg.waitForFunction(() => document.querySelector('.z-go').textContent.includes('К ОПЛАТЕ')); check(calls.some(c => c === 'calc:{"liters":40,"azs_number":14,"nozzle_number":2,"pump_number":3}'), 'расчёт с колонкой 3, 40 л'); check((await txt(pg)).includes('100,00 руб'), 'сумма 100,00'); },
    async (pg, check) => { await tap(pg, '[data-a="go"]'); await pg.waitForSelector('.z-msg'); check((await txt(pg)).includes('через Telegram'), 'оплата — пока через Telegram'); await pg.click('[data-mb="0"]'); await pg.waitForURL(/web\.telegram\.org/); check(true, 'переход в Telegram'); }]));
  // 2. машина: вход уже есть, сумма своей клавиатурой, колонка не активна — сообщение
  r.push(await run('mashina-summa', CAR, { geo: HOME, token: '5|tok' }, [
    async (pg, check) => { await pg.waitForSelector('.z-p'); check(!(await pg.$('#ph')), 'без формы входа'); },
    async (pg, check) => { await tap(pg, '[data-p="5"]'); await pg.waitForSelector('.z-msg'); check((await txt(pg)).includes('не активна'), 'колонка не активна'); await pg.click('[data-mb="0"]'); },
    async (pg, check, calls) => { await tap(pg, '[data-p="2"]'); await pg.waitForSelector('.z-fuel'); await tap(pg, '[data-m="s"]'); await tap(pg, '[data-a="kb"]');
      for (const k of ['3', '7', ',', '5']) await tap(pg, '[data-k="' + k + '"]'); await tap(pg, '[data-a="kbok"]'); await tap(pg, '[data-a="go"]');
      await pg.waitForFunction(() => document.querySelector('.z-go').textContent.includes('К ОПЛАТЕ'));
      check(calls.some(c => c.startsWith('calc:{"liters":15,')), 'сумма 37,5 → 15 л по цене 2,5'); }]));
  // 3. далеко от станций: предупреждение перед расчётом, «Другая АЗС» — следующая
  r.push(await run('daleko', CAR, { geo: [53.20, 26.00], token: '5|tok' }, [
    async (pg, check) => { await pg.waitForSelector('.z-p'); check((await txt(pg)).includes('далеко'), 'метка «далеко»'); },
    async (pg, check, calls) => { await tap(pg, '[data-p="1"]'); await pg.waitForSelector('.z-fuel'); await tap(pg, '[data-q="20"]'); await tap(pg, '[data-a="go"]'); await pg.waitForSelector('.z-msg'); check((await txt(pg)).includes('Заправляться здесь?'), 'вопрос «заправляться здесь?»'); check(!calls.some(c => c.startsWith('calc:')), 'без расчёта до ответа'); await pg.click('[data-mb="1"]'); await pg.waitForFunction(() => !document.querySelector('.z-msg')); }]));
  // 4. вход устарел (401) — форма входа с объяснением; без местоположения — станции всё равно показаны
  r.push(await run('vhod-istyok', CAR, { token: '9|old' }, [
    async (pg, check) => { await pg.waitForSelector('#ph', { timeout: 15000 }); check((await txt(pg)).includes('истёк'), 'сообщение «вход истёк»'); }]));
  r.push(await run('bez-mesta', PHONE, { token: '5|tok' }, [
    async (pg, check) => { await pg.waitForSelector('.z-p', { timeout: 15000 }); check((await txt(pg)).includes('без местоположения'), 'метка «без местоположения»'); }]));
  console.log(r.filter(Boolean).length + ' из ' + r.length);
  process.exit(r.every(Boolean) ? 0 : 1);
})();
