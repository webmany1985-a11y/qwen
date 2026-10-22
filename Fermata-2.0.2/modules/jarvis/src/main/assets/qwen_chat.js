// Вкладка JARVIS: вопросы — на chat.qwen.ai, ответ читается со страницы.
// Страница открыта в невидимом WebView под кругом JARVIS.
// Имена классов — из кода сайта (21.10.2026): поле ввода
// textarea.message-input-textarea, кнопка button.send-button,
// ответ .response-message-content (текст в .qwen-markdown), пока Qwen пишет —
// есть .stop-button. Сайт меняется — поэтому запасные пути и запись в журнал.
(function () {
	if (window.__jq) return;
	const LOG = '[QWEN] ';
	const log = m => { try { console.log(LOG + m); } catch (e) { } };

	function visible(e) {
		if (!e) return false;
		// Страница без окна может быть не разложена — тогда видимым считается всё
		const b = document.body && document.body.getBoundingClientRect();
		if (!b || (b.width === 0)) return true;
		const r = e.getBoundingClientRect();
		return (r.width > 0) && (r.height > 0);
	}

	function input() {
		return document.querySelector('textarea.message-input-textarea') ||
			document.querySelector('textarea#chat-input') ||
			document.querySelector('textarea');
	}

	// Нажимать надо на саму кнопку: .message-input-right-button-send — только
	// рамка, клик по ней вопрос не отправлял (журнал 22.10.2026, 08:04–08:21)
	function sendButton() {
		return document.querySelector('.message-input-right-button-send button.send-button') ||
			document.querySelector('button.send-button') ||
			document.querySelector('button[aria-label="Отправить"], button[aria-label="Send"]') ||
			document.querySelector('.message-input-right-button-send button') ||
			document.querySelector('button[class*="send"]');
	}

	/** Гость: видна кнопка «Войти», либо окно «Войдите…» после отправки. */
	function needLogin() {
		const els = document.querySelectorAll('button, a, [role="button"]');
		for (const e of els) {
			const t = (e.innerText || '').trim();
			if (/^(Войти|Log in|Login|Sign in)$/i.test(t) && visible(e)) return true;
		}
		const modal = document.querySelector('[class*="modal"], [role="dialog"]');
		return !!(modal && visible(modal) && /Войдите|Log in|Sign in/i.test(modal.innerText || ''));
	}

	/** Видимое окно поверх чата (подтверждение возраста и т. п.) — в журнал. */
	function dialogText() {
		for (const e of document.querySelectorAll('[role="dialog"], [class*="modal"]')) {
			const r = e.getBoundingClientRect();
			if ((r.width > 0) && (r.height > 0)) {
				const t = (e.innerText || '').trim();
				if (t) return t.slice(0, 120);
			}
		}
		return '';
	}

	// Пока Qwen пишет, вместо «Отправить» — «Стоп». Одного .stop-button мало:
	// журнал 22.10.2026, 09:21 — на «Поиске в Интернете» ожидание кончилось раньше ответа
	function generating() {
		const s = document.querySelector('.stop-button, button[aria-label="Остановить"], button[aria-label="Стоп"], button[aria-label="Stop"]');
		if (s && visible(s) && !s.disabled) return true;
		const box = document.querySelector('.chat-prompt-send-button');
		return !!(box && !box.querySelector('button.send-button'));
	}

	/** Надписи сайта, а не ответ: «Поиск в Интернете», «Пропустить» и т. п. */
	const NOISE = /^(Пропустить|Skip|Поиск в Интернете.*|Ищу.*|Searching.*|Думаю.*|Размышля.*|Thinking.*|Копировать|Copy|Поделиться|Share)$/i;

	function clean(t) {
		return t.split('\n').map(l => l.trim()).filter(l => l && !NOISE.test(l)).join('\n').trim();
	}

	/** Где на странице лежит текст ответа — в журнал, чтобы читать его оттуда напрямую. */
	function locate(t) {
		const key = t.split('\n')[0].slice(0, 30);
		let best = null;
		for (const e of document.querySelectorAll('div, p, section, article')) {
			const it = e.innerText || '';
			if ((it.indexOf(key) >= 0) && (!best || it.length < best.innerText.length)) best = e;
		}
		if (!best) return '?';
		const p = [];
		for (let i = 0, e = best; e && (i < 7); i++, e = e.parentElement) {
			p.push(e.tagName.toLowerCase() + '.' + String(e.className).trim().split(/\s+/).slice(0, 3).join('.'));
		}
		return p.join(' < ');
	}

	function replies() {
		return Array.from(document.querySelectorAll('.response-message-content'))
			.filter(e => !/think/i.test(String(e.className)));
	}

	function replyText(e) {
		const md = e.querySelector('.qwen-markdown');
		return ((md || e).innerText || '').trim();
	}

	function path(e) {
		const p = [];
		for (let i = 0; e && (i < 4); i++, e = e.parentElement) {
			p.push(e.tagName.toLowerCase() + '.' + String(e.className).trim().split(/\s+/).slice(0, 3).join('.'));
		}
		return p.join(' < ');
	}

	let before = [];
	let question = '';
	let last = '';
	let lastChange = 0;
	let sentAt = 0;
	let how = '';

	/** Текст после вопроса — если блока ответа не нашлось. */
	function fallback() {
		const body = (document.body && document.body.innerText) || '';
		const i = body.lastIndexOf(question.slice(0, 40));
		if (i < 0) return '';
		return body.slice(i + question.length).trim();
	}

	window.__jq = {
		state: function () {
			return JSON.stringify({
				input: !!input(), send: !!sendButton(), login: needLogin(), url: location.href
			});
		},

		send: function (text) {
			const ta = input();
			if (!ta) return JSON.stringify({ ok: false, why: 'no-input', login: needLogin() });
			before = replies();
			question = text;
			last = '';
			how = '';
			sentAt = lastChange = Date.now();
			// Поле под React: значение — через родной сеттер, затем событие input
			const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
			set.call(ta, text);
			ta.dispatchEvent(new Event('input', { bubbles: true }));
			setTimeout(() => {
				const b = sendButton();
				if (b) {
					b.click();
					log('sent, button ' + path(b));
				} else {
					// Без кнопки — Enter (на телефоне сайт его может не принять)
					ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
					log('sent by Enter: no send button');
				}
			}, 300);
			return JSON.stringify({ ok: true });
		},

		poll: function () {
			if (needLogin()) return JSON.stringify({ state: 'login' });
			const all = replies();
			const fresh = all.filter(e => before.indexOf(e) < 0);
			let text = '';
			if (fresh.length) {
				const e = fresh[fresh.length - 1];
				text = replyText(e);
				if (!how) how = 'reply ' + path(e);
			} else if ((Date.now() - sentAt) > 8000) {
				text = clean(fallback());
				if (text && !how) how = 'fallback: after question text';
			}
			text = clean(text);
			const now = Date.now();
			if (text !== last) {
				last = text;
				lastChange = now;
			}
			const gen = generating();
			const quiet = now - lastChange;
			// Блок ответа найден — 1,5 с тишины; ответ «после вопроса» — 3 с: он ловит и надписи сайта
			const wait = fresh.length ? 1500 : 3000;
			const done = !!text && ((!gen && (quiet >= wait)) || (quiet >= 8000));
			if (done && !fresh.length && how.indexOf(' at ') < 0) how += ' at ' + locate(text);
			return JSON.stringify({
				state: done ? 'done' : 'wait', text: text, gen: gen, how: how, replies: all.length,
				dialog: dialogText(), input: ((input() || {}).value || '').slice(0, 40)
			});
		}
	};
	log('ready, ' + window.__jq.state());
})();
