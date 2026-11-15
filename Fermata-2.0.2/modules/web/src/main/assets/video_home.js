// Главная вкладки «Видео» (пожелание 15.11.2026, «как Дом ТВ»): большая карточка выбранного фильма сверху,
// ряды афиш вбок, поиск, «Продолжить просмотр». Данные: D (страница), /ryady.json и /poisk.json (приложение
// берёт их у воркера афиш и хранит последний удачный ответ — без сети ряды остаются).
// Касание афиши — выбрать (карточка сверху), второе касание или «Смотреть» — открыть.
(function () {
	var $ = function (id) { return document.getElementById(id); };
	var rows = $('rows'), msg = $('msg'), sel = null, selCard = null;

	function el(tag, cls, txt) {
		var e = document.createElement(tag);
		if (cls) e.className = cls;
		if (txt != null) e.textContent = txt;
		return e;
	}
	function meta(f) {
		var m = $('hm');
		m.textContent = '';
		var parts = [];
		if (f.g) parts.push(String(f.g));
		m.appendChild(document.createTextNode(parts.join(' · ')));
		if (f.r > 0) {
			if (parts.length) m.appendChild(document.createTextNode(' · '));
			m.appendChild(el('span', 'st', '★ ' + Number(f.r).toFixed(1)));
		}
		if (f.s) m.appendChild(el('span', 'tag', 'сериал'));
		if (f.cont && f.p > 0) m.appendChild(document.createTextNode('  · просмотрено ' + f.p + '%'));
	}
	function select(f, card) {
		sel = f;
		if (selCard) selCard.classList.remove('sel');
		selCard = card || null;
		if (selCard) selCard.classList.add('sel');
		$('hn').textContent = f.n || '';
		var hp = $('hp');
		hp.classList.remove('bad');
		hp.onerror = function () { hp.classList.add('bad'); };
		hp.src = f.a || '';
		meta(f);
		var w = $('hw');
		w.classList.remove('off');
		w.textContent = f.cont ? '▶ Продолжить' : '▶ Смотреть';
	}
	function q(v) { return encodeURIComponent(v == null ? '' : String(v)); }
	function go(f) {
		location.href = D.act + '?do=' + (f.cont ? 'cont' : 'open') + '&u=' + q(f.z) + '&n=' + q(f.n) +
			'&a=' + q(f.a) + '&g=' + q(f.g || 0) + '&r=' + q(f.r || 0) + '&s=' + (f.s ? 1 : 0);
	}
	function card(f) {
		var c = el('a', 'card');
		c.href = '#';
		var ph = el('div', 'ph');
		if (f.a) {
			var i = el('img');
			i.loading = 'lazy';
			i.referrerPolicy = 'no-referrer';
			i.alt = '';
			i.onerror = function () { i.remove(); ph.appendChild(el('div', 'ni', f.n)); };
			i.src = f.a;
			ph.appendChild(i);
		} else ph.appendChild(el('div', 'ni', f.n));
		if (f.cont && f.p > 0) { var p = el('div', 'pr'); p.style.width = f.p + '%'; ph.appendChild(p); }
		c.appendChild(ph);
		c.appendChild(el('div', 'nm', f.n));
		c.addEventListener('click', function (e) {
			e.preventDefault();
			if (sel === f) go(f); else select(f, c);
		});
		return c;
	}
	function row(title, list, before) {
		if (!list || !list.length) return null;
		var s = el('section');
		s.appendChild(el('h2', null, title));
		var r = el('div', 'row');
		for (var i = 0; i < list.length; i++) if (list[i] && list[i].z) r.appendChild(card(list[i]));
		s.appendChild(r);
		if (before) rows.insertBefore(s, before); else rows.appendChild(s);
		return s;
	}
	function firstCard(s) { return s ? s.querySelector('.card') : null; }

	$('hw').addEventListener('click', function (e) { e.preventDefault(); if (sel) go(sel); });

	// «Продолжить просмотр»
	var cont = (D.cont || []).map(function (f) { f.cont = true; return f; });
	var contRow = row('Продолжить просмотр', cont);
	if (cont.length) select(cont[0], firstCard(contRow));

	// Ряды афиш
	var x = new XMLHttpRequest();
	x.open('GET', '/ryady.json');
	x.timeout = 20000;
	x.onload = function () {
		var d = null;
		try { d = JSON.parse(x.responseText); } catch (e) { }
		if (x.status !== 200 || !d || !d.ryady) { msg.textContent = 'Афиши не загрузились — нет связи. Сервисы и «Мои сайты» работают.'; return; }
		if (x.getResponseHeader('X-Jarvis-Cache') === 'old') msg.textContent = 'Нет связи — показаны сохранённые афиши.';
		var first = null;
		for (var i = 0; i < d.ryady.length; i++) {
			var s = row(d.ryady[i].nazvanie, d.ryady[i].filmy);
			if (!first) first = s;
		}
		if (!sel && first) {
			var c = firstCard(first);
			if (c) c.click();
		}
	};
	x.onerror = x.ontimeout = function () { msg.textContent = 'Афиши не загрузились — нет связи. Сервисы и «Мои сайты» работают.'; };
	x.send();

	// Поиск
	var found = null;
	$('sf').addEventListener('submit', function (e) {
		e.preventDefault();
		var t = $('q').value.trim();
		if (!t) return;
		$('q').blur();
		msg.textContent = 'Ищу «' + t + '»…';
		var y = new XMLHttpRequest();
		y.open('GET', '/poisk.json?q=' + q(t));
		y.timeout = 20000;
		y.onload = function () {
			var d = null;
			try { d = JSON.parse(y.responseText); } catch (e) { }
			if (found) { found.remove(); found = null; }
			if (y.status !== 200 || !d) { msg.textContent = 'Поиск не удался — нет связи.'; return; }
			if (!d.filmy || !d.filmy.length) { msg.textContent = 'По запросу «' + t + '» ничего не найдено.'; return; }
			msg.textContent = '';
			found = row('Найдено: «' + t + '»', d.filmy, rows.firstChild);
			var c = firstCard(found);
			if (c) { sel = null; c.click(); }
			window.scrollTo(0, 0);
		};
		y.onerror = y.ontimeout = function () { msg.textContent = 'Поиск не удался — нет связи.'; };
		y.send();
	});

	// Возврат кнопкой «Назад» со страницы фильма — обновить «Продолжить просмотр»
	window.addEventListener('pageshow', function (e) { if (e.persisted) location.reload(); });

	if (D.edit) { var f = $('edit'); if (f) f.scrollIntoView(); }
})();
