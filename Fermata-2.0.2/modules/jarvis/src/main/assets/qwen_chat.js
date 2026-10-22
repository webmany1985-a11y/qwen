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
	// «Oops! There was an issue connecting to …» — английская строка сообщения об
	// ошибке сайта; русская следом остаётся (журнал 22.10.2026, 09:55)
	const NOISE = /^(Oops!.*|Пропустить|Skip|Поиск в Интернете.*|Ищу.*|Searching.*|Думаю.*|Размышля.*|Thinking.*|Копировать|Copy|Поделиться|Share)$/i;

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
			.filter(e => !/think/i.test(String(e.className)) && !e.closest('.smrm'));
	}

	/**
	 * Сравнение двух ответов (.smrm, «Какой ответ вы предпочитаете?»): берётся
	 * первый, и он же выбирается — иначе сайт дальше не пускает
	 * (журнал JARVIS 22.10.2026, 12:53).
	 */
	function compareCards() {
		const all = Array.from(document.querySelectorAll('.smrm')).filter(e => beforeCompare.indexOf(e) < 0);
		if (!all.length) return null;
		return Array.from(all[all.length - 1].querySelectorAll('.smrm-card'));
	}

	/** Модель, выбранная сейчас на сайте. */
	function currentModel() {
		const n = document.querySelector('.mms-trigger__name');
		return n ? (n.innerText || '').trim() : '';
	}

	/** Выбрать модель по названию; then — после выбора (или если не вышло). */
	function pickModel(want, then) {
		const cur = currentModel();
		if (!want || !cur || (cur === want)) {
			then();
			return;
		}
		const t = document.querySelector('.mms-trigger');
		if (!t) {
			then();
			return;
		}
		t.click();
		setTimeout(() => {
			const it = Array.from(document.querySelectorAll('.mms-list__item')).find(e => {
				const n = e.querySelector('.mms-list__name-text');
				return n && ((n.innerText || '').trim() === want);
			});
			if (it) it.click();
			log('model ' + cur + ' -> ' + want + (it ? '' : ': not in the list'));
			setTimeout(then, 800);
		}, 800);
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

	/**
	 * Режим мышления и поиск в сети: сайт в каждом новом разговоре ставит их
	 * по умолчанию (журнал JARVIS 22.10.2026, 19:13 — «нет доступа к погоде»:
	 * поиск был выключен). Выбор пользователя на странице запоминается
	 * ({@link settings}), перед вопросом выставляется снова ({@link applyModes}).
	 * Кнопка «Выбрать режим» — .mode-select-open; мышление — список
	 * «Авто / Мышление / Быстрый»; поиск включён — значок #appicon-online у поля.
	 */
	let chosenThinking = null;
	let chosenSearch = null;
	let thinkingApplied = false;
	document.addEventListener('click', function (e) {
		const it = e.target.closest && e.target.closest('.qwen-chat-v2-dropdown-menu-item');
		if (it && it.closest('.mode-selector-drawer-item, .ant-drawer, [class*="dropdown-menu"]')) {
			chosenThinking = (it.innerText || '').trim() || chosenThinking;
		}
		const li = e.target.closest && e.target.closest('.mode-selector-drawer-list-item');
		if (li && /Поиск в сети|Web search/i.test((li.innerText || '').trim())) chosenSearch = true;
		if (e.target.closest && e.target.closest('.mode-select-current-mode-close') && searchOn()) chosenSearch = false;
	}, true);

	function searchOn() {
		return !!document.querySelector('.mode-select-current-mode use[href="#appicon-online"], ' +
			'.mode-select-current-mode use[*|href="#appicon-online"]');
	}

	function closeDrawer() {
		const m = document.querySelector('.ant-drawer-open .ant-drawer-mask, .ant-drawer-mask');
		if (m) m.click();
	}

	function openModes(then) {
		const o = document.querySelector('.mode-select-open');
		if (!o) {
			then(false);
			return;
		}
		o.click();
		setTimeout(() => then(true), 700);
	}

	/** Выставить мышление и поиск, как запомнено; then — после. */
	function applyModes(want, then) {
		const wantThink = want && want.thinking;
		const wantSearch = want && (typeof want.search === 'boolean') ? want.search : null;
		const needThink = wantThink && !thinkingApplied;
		const needSearch = (wantSearch !== null) && (wantSearch !== searchOn());
		if (!needThink && !needSearch) {
			then();
			return;
		}
		openModes(ok => {
			if (!ok) {
				then();
				return;
			}
			const steps = [];
			if (needThink) steps.push(next => {
				const t = document.querySelector('.mode-selector-drawer-item .qwen-chat-v2-dropdown-menu-trigger');
				const cur = t ? (t.innerText || '').trim() : '';
				thinkingApplied = true;
				if (!t || (cur === wantThink)) {
					next();
					return;
				}
				t.click();
				setTimeout(() => {
					const it = Array.from(document.querySelectorAll('.qwen-chat-v2-dropdown-menu-item'))
						.find(e => (e.innerText || '').trim() === wantThink);
					if (it) it.click();
					log('thinking ' + cur + ' -> ' + wantThink + (it ? '' : ': not in the list'));
					setTimeout(next, 500);
				}, 500);
			});
			if (needSearch && wantSearch) steps.push(next => {
				const it = Array.from(document.querySelectorAll('.mode-selector-drawer-list-item'))
					.find(e => /Поиск в сети|Web search|Search/i.test((e.innerText || '').trim()));
				if (it) it.click();
				log('web search on' + (it ? '' : ': no item'));
				setTimeout(next, 600);
			});
			let i = 0;
			const run = () => {
				if (i < steps.length) steps[i++](run);
				else {
					if (document.querySelector('.ant-drawer-open')) closeDrawer();
					if (needSearch && !wantSearch) {
						const c = document.querySelector('.mode-select-current-mode-close');
						if (c) c.click();
						log('web search off');
					}
					setTimeout(then, 500);
				}
			};
			run();
		});
	}

	let before = [];
	let beforeCompare = [];
	let compareChosen = false;
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

		model: function () {
			return currentModel();
		},

		/** Что выбрано на странице: модель, мышление (если меняли), поиск. */
		settings: function () {
			// Мышление и поиск — только если их переключали: иначе открыть и закрыть
			// страницу значило бы затереть запомненное
			return JSON.stringify({ model: currentModel(), thinking: chosenThinking, search: chosenSearch });
		},

		send: function (text, wantArg) {
			// want — название модели (как раньше) или {model, thinking, search}
			const wantObj = (wantArg && typeof wantArg === 'object') ? wantArg : { model: wantArg };
			const want = wantObj.model;
			const ta = input();
			if (!ta) return JSON.stringify({ ok: false, why: 'no-input', login: needLogin() });
			before = replies();
			beforeCompare = Array.from(document.querySelectorAll('.smrm'));
			compareChosen = false;
			question = text;
			last = '';
			how = '';
			sentAt = lastChange = Date.now();
			pickModel(want, () => applyModes(wantObj, () => {
			sentAt = lastChange = Date.now();
			const ta2 = input() || ta;
			// Поле под React: значение — через родной сеттер, затем событие input
			const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
			set.call(ta2, text);
			ta2.dispatchEvent(new Event('input', { bubbles: true }));
			setTimeout(() => {
				const b = sendButton();
				if (b) {
					b.click();
					log('sent, button ' + path(b));
				} else {
					// Без кнопки — Enter (на телефоне сайт его может не принять)
					ta2.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
					log('sent by Enter: no send button');
				}
			}, 300);
			}));
			return JSON.stringify({ ok: true });
		},

		poll: function () {
			if (needLogin()) return JSON.stringify({ state: 'login' });
			const all = replies();
			const fresh = all.filter(e => before.indexOf(e) < 0);
			let text = '';
			const cards = compareCards();
			if (cards && cards.length) {
				const c = cards[0].querySelector('.smrm-card__content') || cards[0];
				const md = c.querySelector('.qwen-markdown');
				text = ((md || c).innerText || '').trim();
				how = 'compare: first of ' + cards.length;
			} else if (fresh.length) {
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
			const wait = (fresh.length || cards) ? 1500 : 3000;
			const done = !!text && ((!gen && (quiet >= wait)) || (quiet >= 8000));
			if (done && !fresh.length && !cards && how.indexOf(' at ') < 0) how += ' at ' + locate(text);
			if (done && cards && cards.length && !compareChosen) {
				// Выбрать первый ответ — сайт продолжит разговор
				compareChosen = true;
				const btn = cards[0].querySelector('.smrm-card__prefer-btn');
				if (btn) btn.click();
				log('compare: first answer chosen' + (btn ? '' : ' — no button'));
			}
			return JSON.stringify({
				state: done ? 'done' : 'wait', text: text, gen: gen, how: how, replies: all.length,
				dialog: dialogText(), input: ((input() || {}).value || '').slice(0, 40)
			});
		}
	};
	log('ready, ' + window.__jq.state());
})();
