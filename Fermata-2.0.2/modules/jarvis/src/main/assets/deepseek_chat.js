// Разговор с DeepSeek через сайт chat.deepseek.com (пожелание пользователя 30.10.2026). Встраивается в страницу
// WebView (DeepSeekChat): __jds.send(text, want) вписывает фразу и отправляет, __jds.poll() читает ответ.
// Разметку сайта из облака проверить нельзя (сайт просит подтвердить, что вы не робот), поэтому элементы ищутся по
// устойчивым признакам: видимое поле textarea, кнопки по подписи («DeepThink», «Поиск»), ответ — блок ds-markdown
// (с запасным путём — текст после вопроса). __jds.dump() пишет в журнал, что нашлось, — для разбора по журналу.
(function () {
	if (window.__jds) return;
	const LOG = '[DEEPSEEK] ';
	const log = m => { try { console.log(LOG + m); } catch (e) { } };

	function visible(e) {
		if (!e) return false;
		const r = e.getBoundingClientRect();
		return r.width > 0 && r.height > 0;
	}

	function input() {
		return Array.from(document.querySelectorAll('textarea')).filter(visible).pop() || null;
	}

	/** Кнопки-переключатели и кнопки вообще: button, role=button, div с курсором-рукой. */
	function buttons() {
		return Array.from(document.querySelectorAll('button, [role="button"], div[tabindex]')).filter(visible);
	}

	/**
	 * Элемент с подписью re: любой видимый (у DeepSeek переключатели — div без role, журнал 30.10.2026 13:31:
	 * «DeepThink: no button»), самый внутренний с такой подписью; нажимать его — щелчок всплывает к обработчику.
	 */
	function byText(re) {
		return Array.from(document.querySelectorAll('button, [role="button"], div, span'))
			.filter(b => visible(b) && re.test((b.innerText || '').trim()) && (b.innerText || '').trim().length < 40)
			.sort((a, b) => (a.innerText || '').length - (b.innerText || '').length
				|| b.querySelectorAll('*').length - a.querySelectorAll('*').length)[0] || null;
	}

	const THINK = /^(DeepThink|Глубок|深度思考|R1|Думать|Рассужд)/i;
	const SEARCH = /^(Search|Поиск|Интернет|联网搜索|Web)/i;

	/** Включён ли переключатель: aria-pressed / класс «selected/active/checked» / синий цвет текста. */
	function isOn(el) {
		if (!el) return null;
		// Признак включения бывает у обёртки: смотрим сам элемент и два уровня вверх
		for (let e = el, i = 0; e && i < 3; e = e.parentElement, i++) if (isOnOne(e)) return true;
		return false;
	}

	function isOnOne(el) {
		if (el.getAttribute('aria-pressed') === 'true' || el.getAttribute('aria-checked') === 'true') return true;
		const cls = (el.className && el.className.baseVal !== undefined) ? el.className.baseVal : (el.className || '');
		if (/selected|active|checked|--on\b|_on\b/i.test(cls)) return true;
		try {
			const c = getComputedStyle(el).color.match(/\d+/g).map(Number);
			if (c[2] > 150 && c[2] > c[0] + 40) return true;
		} catch (e) { }
		return false;
	}

	/** Кнопка отправки: иконка без подписи рядом с полем, правее всех; не переключатель. */
	function sendButton() {
		const ta = input();
		if (!ta) return null;
		let box = ta.parentElement;
		for (let i = 0; i < 5 && box; i++) {
			const cands = Array.from(box.querySelectorAll('button, [role="button"], div[aria-disabled]'))
				.filter(b => visible(b) && !(b.innerText || '').trim() && b.querySelector('svg'));
			if (cands.length) {
				cands.sort((a, b) => a.getBoundingClientRect().right - b.getBoundingClientRect().right);
				return cands[cands.length - 1];
			}
			box = box.parentElement;
		}
		return null;
	}

	function needLogin() {
		if (/sign_in|login/i.test(location.pathname)) return true;
		if (/confirm you are human|Human Verification/i.test(document.title + ' ' + ((document.body || {}).innerText || '').slice(0, 300))) return true;
		return !!Array.from(document.querySelectorAll('input[type=password]')).filter(visible)[0];
	}

	/** Блоки ответа: ds-markdown вне блока рассуждения (у DeepThink он отдельный — «Думал N секунд»). */
	function replies() {
		return Array.from(document.querySelectorAll('[class*="ds-markdown"]'))
			.filter(e => !e.parentElement.closest('[class*="ds-markdown"]'))
			.filter(e => !e.closest('[class*="think"], [class*="Think"]'));
	}

	const NOISE = /^(Думал.*|Thought for.*|Thinking.*|Рассужд.*|已深度思考.*|Copy|Копировать|Регенерировать|Regenerate)$/i;
	function clean(t) {
		return (t || '').split('\n').filter(l => !NOISE.test(l.trim())).join('\n').trim();
	}

	function generating() {
		// Пока идёт ответ, кнопка отправки становится «стоп» (квадрат) или поле заблокировано
		const ta = input();
		if (ta && ta.disabled) return true;
		const stop = buttons().find(b => /Stop|Стоп|Останов|停止/i.test(b.getAttribute('aria-label') || (b.innerText || '')));
		return !!stop;
	}

	let before = [];
	let question = '';
	let last = '';
	let lastChange = 0;
	let sentAt = 0;
	let how = '';
	let sending = false;
	let loadedAt = Date.now();
	let chosen = { thinking: null, search: null };
	/** С рассуждением: текст после вопроса — это ход мысли, не ответ; запасной путь — только поздно. */
	let thinkWanted = false;

	document.addEventListener('click', e => {
		if (!e.isTrusted) return;
		setTimeout(() => {
			const th = byText(THINK), se = byText(SEARCH);
			if (th && th.contains(e.target) || (th && e.target.contains && e.target.contains(th))) chosen.thinking = isOn(th);
			if (se && se.contains(e.target) || (se && e.target.contains && e.target.contains(se))) chosen.search = isOn(se);
		}, 300);
	}, true);

	function fallback() {
		const body = (document.body && document.body.innerText) || '';
		const i = body.lastIndexOf(question.slice(0, 40));
		if (i < 0) return '';
		return body.slice(i + question.length).trim();
	}

	/** Поставить переключатель в нужное положение. */
	function setToggle(re, want, name) {
		if (want === null || want === undefined) return;
		const b = byText(re);
		if (!b) { log(name + ': no button; ' + window.__jds.dump()); return; }
		const on = isOn(b);
		if (on !== want) {
			b.click();
			log(name + ' ' + on + ' -> ' + want);
		}
	}

	window.__jds = {
		state: function () {
			return JSON.stringify({ input: !!input(), send: !!sendButton(), login: needLogin(), url: location.href });
		},

		settings: function () {
			return JSON.stringify({ thinking: chosen.thinking, search: chosen.search });
		},

		/** Что нашлось на странице — для журнала. */
		dump: function () {
			return JSON.stringify({
				url: location.href, title: document.title, input: !!input(), send: !!sendButton(),
				think: !!byText(THINK), search: !!byText(SEARCH), replies: replies().length,
				buttons: buttons().map(b => (b.innerText || '').trim()).filter(x => x).slice(0, 15),
				labels: Array.from(document.querySelectorAll('div, span')).filter(e => visible(e) && !e.children.length)
					.map(e => (e.innerText || '').trim()).filter(x => x && x.length < 25).slice(-25),
				thinkOn: isOn(byText(THINK)), searchOn: isOn(byText(SEARCH))
			});
		},

		send: function (text, want) {
			const ta = input();
			if (!ta) return JSON.stringify({ ok: false, why: 'no-input',
				login: (Date.now() - loadedAt > 6000) && needLogin() });
			sending = true;
			before = replies();
			question = text;
			last = '';
			how = '';
			sentAt = lastChange = Date.now();
			want = want || {};
			thinkWanted = want.thinking === true;
			setToggle(THINK, (typeof want.thinking === 'boolean') ? want.thinking : null, 'DeepThink');
			setToggle(SEARCH, (typeof want.search === 'boolean') ? want.search : null, 'search');
			setTimeout(() => {
				const ta2 = input() || ta;
				const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
				set.call(ta2, text);
				ta2.dispatchEvent(new Event('input', { bubbles: true }));
				setTimeout(() => {
					sentAt = lastChange = Date.now();
					const b = sendButton();
					if (b && b.getAttribute('aria-disabled') !== 'true') {
						b.click();
						log('sent by button');
					} else {
						ta2.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
						log('sent by Enter' + (b ? ' (button disabled)' : ': no send button'));
					}
					sending = false;
				}, 400);
			}, 600);
			return JSON.stringify({ ok: true });
		},

		poll: function () {
			if (sending) return JSON.stringify({ state: 'wait', text: '', sending: true });
			if (needLogin()) return JSON.stringify({ state: 'login' });
			const fresh = replies().filter(e => before.indexOf(e) < 0);
			let text = '';
			if (fresh.length) {
				text = clean(fresh[fresh.length - 1].innerText);
				if (!how) how = 'ds-markdown';
			} else if ((Date.now() - sentAt) > (thinkWanted ? 45000 : 10000)) {
				text = clean(fallback());
				if (text && !how) how = 'fallback: after question text';
			}
			const now = Date.now();
			if (text !== last) { last = text; lastChange = now; }
			const gen = generating();
			const quiet = now - lastChange;
			const wait = fresh.length ? 2000 : 3500;
			const done = !!text && ((!gen && quiet >= wait) || quiet >= 8000);
			return JSON.stringify({ state: done ? 'done' : 'wait', text: text, gen: gen, how: how,
				replies: fresh.length });
		}
	};
	log('ready, ' + window.__jds.dump());
})();
