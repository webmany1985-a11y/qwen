// Макет своей вкладки А-100 (05.11.2026): страница assets/zapravka_a100.html в Chromium, сервер А-100 — подмена
// (ответы по полям из кода приложения А-100). Запуск: node zapravka_a100_maket.js [папка для снимков]
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const PAGE = fs.readFileSync(path.join(__dirname, '../main/assets/zapravka_a100.html'), 'utf8');
const ASSET = n => fs.readFileSync(path.join(__dirname, '../main/assets', n));
const HOME = [53.8455, 27.6342]; // рядом с АЗС №14
const STATIONS = [
  { id: 1, NUMBER: 14, NAME: 'ул. Тростенецкая, 16', POINT: '53.8456,27.6340', FUEL: [{ NAME: 'АИ-92', CODE: '92', COLOR: 'ff0' }, { NAME: 'АИ-95', CODE: '95', COLOR: 'f00' }] },
  { id: 2, NUMBER: 7, NAME: 'пр-т Независимости, 100', POINT: '53.93,27.65', FUEL: [] },
  { id: 4, NUMBER: 15, NAME: 'закрытая рядом', POINT: '53.8455,27.6342', ACTIVE: 'N', FUEL: [] },
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
  await ctx.route('https://payment.webpay.by/**', r => r.fulfill({ contentType: 'text/html', body: '<p>Банк</p>' }));
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
    if ((m = p.match(/^\/azs\/(\d+)\/pumps$/))) return json(200, { data: opt.many ? Array.from({ length: 24 }, (_, i) => 24 - i) : m[1] === '14' ? [3, 1, 2, 4, 5, 6, 7, 8] : [1, 2] });
    if ((m = p.match(/^\/azs\/(\d+)\/pumps\/(\d+)\/ready$/))) {
      if (m[2] === '5') return json(400, { data: null, message: 'Колонка не активна' });
      return json(200, { data: { nozzle_number: 2, fuel_name: 'АИ-95', price: 2.5 } });
    }
    if (p === '/users/me') return json(200, { data: { token: { abilities: [] }, user: { id: 7, phone: 'x', telegram_id: 7001, loyalty_customer_id: 55, bank_cards: opt.noCard ? [] : [{ id: 11, is_main: true, card: '425500xxxxxx1234', bran_card: 'VISA' }, { id: 12, is_main: false, card: '5100xxxxxxxx9999' }] } } });
    if (p === '/users/7/loyalty-cards') return json(200, { data: [{ number: '777', is_main: true }] });
    if (p === '/discount/calculate') { calls.push('disc:' + JSON.stringify(body)); if (opt.discFail) return json(500, { message: 'Server Error' }); return json(200, { data: { amount: body.liters * 2.5 - 1.5, discount: 1.5, promotions_discount: null, points_discount: null } }); }
    if (p === '/payment/recurring' && !(calls.some(c => c.startsWith('disc:')) || opt.discFail) && JSON.parse(q.postData()).loyalty_card_number) return json(500, { message: 'Server Error' }); // как у А-100 без расчёта скидки
    if (p.startsWith('/payment') && body.fuel_code !== body.fuel_name) return json(500, { message: 'Ошибка сервера. Попробуйте позже' }); // как у А-100 с кодом станции вместо названия (06.11.2026)
    if (p === '/payment/recurring') { calls.push('pay-rec:' + JSON.stringify(body)); return json(200, { data: { orderNumber: 'A-1' } }); }
    if (p === '/payment') { calls.push('pay-new:' + JSON.stringify(body)); return json(200, { data: { orderNumber: 'A-2', redirectUrl: 'https://payment.webpay.by/pay?x=1' } }); }
    if (p === '/fuel/calculate') { calls.push('calc:' + JSON.stringify(body)); return json(200, { data: { amount: body.liters * 2.5, liters: body.liters } }); }
    return json(404, { message: 'нет' });
  });
  const pg = await ctx.newPage();
  pg.on('console', m => { if (m.text().includes('[JARVIS-ZP]')) logs.push(m.text().replace('[JARVIS-ZP] fast: ', '')); });
  // канал в приложение (JzpApp, ZapravkaFragment.installAppBridge) — сообщения копятся в window.__app
  await pg.addInitScript(() => { window.JzpApp = { postMessage: m => (window.__app = window.__app || []).push(JSON.parse(m)) }; });
  if (opt.lastPos) await pg.addInitScript(p => { try { localStorage.setItem('a100_pos', JSON.stringify({ t: Date.now(), p: p })); } catch (e) { } }, opt.lastPos);
  if (opt.pumpsMem) await pg.addInitScript(() => { try { localStorage.setItem('a100_pumps', JSON.stringify({ 14: ['1', '2', '3', '4', '5', '6', '7', '8'] }));
    localStorage.setItem('a100_azs', JSON.stringify({ t: Date.now(), list: [{ NUMBER: 14, NAME: 'ул. Тростенецкая, 16', POINT: '53.8456,27.6340', FUEL: [] }] })); } catch (e) { } });
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
  // шапка не наезжает на метку расстояния и кнопки ‹ › Telegram
  const hdrOv = await pg.evaluate(() => { const h = document.querySelector('.z-hdr'); if (!h) return false; const hr = h.getBoundingClientRect();
    return [].slice.call(document.querySelectorAll('.z-tag,.z-nav')).some(e => { const r = e.getBoundingClientRect(); return r.top < hr.bottom - 1 && r.bottom > hr.top && r.left < hr.right && r.right > hr.left; }); });
  check(!hdrOv, 'шапка не наезжает на метку и кнопки');
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
    async (pg, check, calls) => { check(await pg.$$eval('[data-q]', a => a.map(e => e.textContent).join(',')) === '5,10,15,20', 'быстрые кнопки литров 5/10/15/20'); await tap(pg, '[data-q="20"]'); await tap(pg, '[data-a="go"]'); await pg.waitForFunction(() => document.querySelector('.z-go').textContent.includes('ОПЛАТИТЬ')); check(calls.some(c => c === 'calc:{"liters":20,"azs_number":14,"nozzle_number":2,"pump_number":3}'), 'расчёт с колонкой 3, 20 л'); check((await txt(pg)).includes('50,00 руб'), 'сумма 50,00'); },
    async (pg, check, calls) => { await tap(pg, '[data-a="go"]'); await pg.waitForSelector('.z-msg'); const m = await txt(pg);
      check(m.includes('Оплатить 48,50 руб (скидка 1,50 руб) картой •••• 1234?') && m.includes('колонка 3') && m.includes('20 л'), 'подтверждение: сумма, карта, колонка');
      await pg.click('[data-mb="1"]'); await pg.waitForTimeout(300); check(!calls.some(c => c.startsWith('pay-')), '«Отмена» — без платежа'); },
    async (pg, check, calls) => { await tap(pg, '[data-a="go"]'); await pg.waitForSelector('.z-msg'); await pg.click('[data-mb="0"]');
      await pg.waitForFunction(() => document.body.innerText.includes('Оплачено'));
      const b = JSON.parse((calls.find(c => c.startsWith('pay-rec:')) || 'pay-rec:{}').slice(8));
      check(b.bank_card_id === 11 && b.loyalty_card_number === '777' && b.fuel_code === 'АИ-95' && b.type === 'FUEL' && b.payment_method === 'card' && b.amount === 48.5 && b.discount === 1.5 && b.sale_point === 14 && b.liters === 20 && b.pump_number === 3 && b.nozzle_number === 2 && b.azs_number === 14 && b.price === 2.5,
        'тело оплаты: основная карта, лояльность, код топлива, сумма (' + JSON.stringify(b) + ')');
      check(!calls.some(c => c.startsWith('pay-new:')), 'один платёж');
      const dc = JSON.parse((calls.find(c => c.startsWith('disc:')) || 'disc:{}').slice(5));
      check(dc.card_number === '777' && dc.type === 'FUEL' && dc.sale_point === 14 && dc.liters === 20 && dc.pump_number === 3 && dc.client === '7001,55', 'расчёт скидки как в приложении (' + JSON.stringify(dc) + ')');
      const msgs = await pg.evaluate(() => window.__app || []);
      check(msgs.some(m => m.token === '5|tok'), 'вход передан приложению');
      check(msgs.some(m => m.paid === true), 'отметка оплаты передана приложению'); check((await txt(pg)).includes('колонка 3 — можно заправляться'), 'сообщение «можно заправляться»'); }]));
  // 2. машина: вход уже есть, сумма своей клавиатурой, колонка не активна — сообщение
  r.push(await run('mashina-summa', CAR, { geo: HOME, token: '5|tok' }, [
    async (pg, check) => { await pg.waitForSelector('.z-p'); check(!(await pg.$('#ph')), 'без формы входа'); },
    async (pg, check) => { await tap(pg, '[data-p="5"]'); await pg.waitForSelector('.z-msg'); check((await txt(pg)).includes('не активна'), 'колонка не активна'); await pg.click('[data-mb="0"]'); },
    async (pg, check, calls) => { await tap(pg, '[data-p="2"]'); await pg.waitForSelector('.z-fuel'); await tap(pg, '[data-m="s"]'); check(await pg.$$eval('[data-q]', a => a.map(e => e.textContent).join(',')) === '10,20,30,40', 'быстрые кнопки суммы 10/20/30/40'); await tap(pg, '[data-a="kb"]');
      for (const k of ['3', '7', ',', '5']) await tap(pg, '[data-k="' + k + '"]'); await tap(pg, '[data-a="kbok"]'); await tap(pg, '[data-a="go"]');
      await pg.waitForFunction(() => document.querySelector('.z-go').textContent.includes('ОПЛАТИТЬ'));
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
  // 5. телефон не отвечает о местоположении — станция по последнему месту сразу, без ожидания
  r.push(await run('proshloe-mesto', CAR, { token: '5|tok', lastPos: HOME }, [
    async (pg, check) => { const t = Date.now(); await pg.waitForSelector('.z-p', { timeout: 15000 }); const s = await txt(pg); check(s.includes('АЗС №14') && s.includes('по прошлому месту'), 'АЗС №14 по прошлому месту'); check(Date.now() - t < 4000, 'без ожидания местоположения'); }]));
  // 6. станция с 24 колонками — все на экране, без наложения на поле ввода
  for (const [nm, vp] of [['24-kolonki-mashina', CAR], ['24-kolonki-telefon', PHONE]]) r.push(await run(nm, vp, { geo: HOME, token: '5|tok', many: 1 }, [
    async (pg, check) => { await pg.waitForSelector('.z-p'); check(await pg.$$eval('.z-p', a => a.length) === 24, '24 колонки');
      const ov = await pg.evaluate(() => { const last = [].slice.call(document.querySelectorAll('.z-p')).reduce((m, e) => Math.max(m, e.getBoundingClientRect().bottom), 0); return last <= document.querySelector('.z-row').getBoundingClientRect().top + 1; });
      check(ov, 'колонки не заходят на поле ввода');
      await pg.click('[data-p="13"]'); await pg.waitForSelector('.z-fuel');
      const fo = await pg.evaluate(() => { const f = document.querySelector('.z-fuel').getBoundingClientRect(); return ![].slice.call(document.querySelectorAll('.z-p')).some(e => { const r = e.getBoundingClientRect(); return r.top < f.bottom && r.bottom > f.top && r.left < f.right && r.right > f.left; }); });
      check(fo, 'надпись топлива не закрывает колонки'); }]));
  // 7. нет сохранённой карты — страница банка, возврат ?paid=success — «Оплачено»
  r.push(await run('novaya-karta-bank', CAR, { geo: HOME, token: '5|tok', noCard: 1 }, [
    async (pg, check) => { await pg.waitForSelector('.z-p'); await tap(pg, '[data-p="1"]'); await pg.waitForSelector('.z-fuel'); await tap(pg, '[data-q="10"]'); await tap(pg, '[data-a="go"]');
      await pg.waitForFunction(() => document.querySelector('.z-go').textContent.includes('ОПЛАТИТЬ')); await tap(pg, '[data-a="go"]'); await pg.waitForSelector('.z-msg');
      check((await txt(pg)).includes('откроется страница банка'), 'без карты — страница банка'); },
    async (pg, check, calls) => { await pg.click('[data-mb="0"]'); await pg.waitForURL(/payment\.webpay\.by/); check(calls.some(c => c.startsWith('pay-new:') && !c.includes('bank_card_id')), 'POST /payment без карты');
      await pg.goto('https://appassets.androidplatform.net/zp/zapravka_a100.html?tg=x&paid=success'); await pg.waitForFunction(() => document.body.innerText.includes('Оплачено'));
      check((await txt(pg)).includes('колонка 1'), 'после банка — оплачено, колонка 1'); }]));
  // 8. колонки из памяти — сразу, даже когда сервер отвечает медленно (3 с)
  r.push(await run('kolonki-iz-pamyati', CAR, { geo: HOME, token: '5|tok', delay: 3000, pumpsMem: 1 }, [
    async (pg, check) => { const t = Date.now(); await pg.waitForSelector('.z-p', { timeout: 15000 }); check(Date.now() - t < 1500, 'колонки за 1,5 с при медленном сервере (' + (Date.now() - t) + ' мс)');
      check(await pg.$$eval('.z-p', a => a.map(e => e.textContent).join(',')) === '1,2,3,4,5,6,7,8', 'колонки из памяти'); }]));
  // 9. расчёт скидки не удался — оплата без карты лояльности, на полную сумму
  r.push(await run('skidka-ne-udalas', CAR, { geo: HOME, token: '5|tok', discFail: 1 }, [
    async (pg, check, calls) => { await pg.waitForSelector('.z-p'); await tap(pg, '[data-p="1"]'); await pg.waitForSelector('.z-fuel'); await tap(pg, '[data-q="10"]'); await tap(pg, '[data-a="go"]');
      await pg.waitForFunction(() => document.querySelector('.z-go').textContent.includes('ОПЛАТИТЬ')); await tap(pg, '[data-a="go"]'); await pg.waitForSelector('.z-msg');
      check((await txt(pg)).includes('Оплатить 25,00 руб картой'), 'полная сумма без скидки'); await pg.click('[data-mb="0"]'); await pg.waitForFunction(() => document.body.innerText.includes('Оплачено'));
      const b = JSON.parse((calls.find(c => c.startsWith('pay-rec:')) || 'pay-rec:{}').slice(8)); check(!b.loyalty_card_number && b.amount === 25, 'без карты лояльности'); }]));
  console.log(r.filter(Boolean).length + ' из ' + r.length);
  process.exit(r.every(Boolean) ? 0 : 1);
})();
